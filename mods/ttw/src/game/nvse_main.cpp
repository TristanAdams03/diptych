// xNVSE entry points for TTW DualScreen (skeleton per xNVSE nvse_plugin_example/main.cpp:115-205).
//
// Threading: NVSEPlugin_Load starts the loopback WebSocket server (its own thread, never touches
// game memory). Everything that reads the game runs in the kMessage_MainGameLoop handler, which
// xNVSE dispatches on the game main thread (X Hooks_Gameplay.cpp:888-942).
//
// ServerInfo: the server starts at Load with ttw=false; whether TaleOfTwoWastelands.esm is
// loaded is only known once the data handler is populated, so it is decided at DeferredInit
// (first main-loop iteration) and pushed with WsServer::setInfo. A `hello` that arrives before
// that (main menu not reached yet) reports ttw:false; DeferredInit then pushes an unsolicited
// `welcome` with the final ServerInfo to every connected client.
//
// Shutdown: WsServer::stop() joins a thread, so it is never called from DllMain or a static
// destructor (loader lock). It runs on kMessage_ExitGame / kMessage_ExitGame_Console (main
// thread); otherwise process exit tears the socket down. The server object is leaked on
// purpose so no static destructor ever joins.
#include <windows.h>

#include <cstdint>
#include <string>

#include "game/config.h"
#include "game/format.h"
#include "game/log.h"
#include "game/readers.h"
#include "proto/messages.h"
#include "proto/pump.h"
#include "server/ws_server.h"

#include "nvse/PluginAPI.h"
#include "nvse/nvse_version.h"

// xNVSE's common/ sources reference gLog (common/IDebugLog.h:78). Never opened: our own
// Logger writes ttw_dualscreen.log.
IDebugLog gLog;

namespace {

using ttwds::game::Config;
using ttwds::game::Logger;
using ttwds::game::LogLevel;

constexpr const char* kPluginName = "TTW DualScreen";
constexpr UInt32 kPluginVersion = 1;
constexpr const char* kPluginVersionString = "0.0.1-spike";

// Leaked like g_server: no destructor (and its mutex) runs at process detach.
Logger& g_log = *new Logger();
Config g_cfg;
bool g_inited = false;
std::string g_dir;  // ...\Data\NVSE\Plugins\ (with trailing backslash)

PluginHandle g_handle = static_cast<PluginHandle>(kPluginHandle_Invalid);
NVSEMessagingInterface* g_messaging = nullptr;
ttwds::server::WsServer* g_server = nullptr;  // leaked by design (see header comment)
ttwds::game::NvseGame* g_game = nullptr;
ttwds::proto::FramePump g_pump;
ttwds::proto::ServerInfo g_info;
DWORD g_mainTid = 0;
ULONGLONG g_t0 = 0;
bool g_wrongThreadLogged = false;
bool g_pumpErrorLogged = false;

// Directory of this DLL, resolved from our own module handle (the game's working directory
// is the FNV root, but we do not rely on it).
std::string plugin_dir() {
    HMODULE self = nullptr;
    if (!GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                            reinterpret_cast<LPCSTR>(&plugin_dir), &self))
        return "Data\\NVSE\\Plugins\\";
    char buf[MAX_PATH * 2] = {};
    const DWORD n = GetModuleFileNameA(self, buf, static_cast<DWORD>(sizeof buf));
    if (n == 0 || n >= sizeof buf) return "Data\\NVSE\\Plugins\\";
    std::string path(buf, n);
    const size_t slash = path.find_last_of("\\/");
    return slash == std::string::npos ? std::string() : path.substr(0, slash + 1);
}

std::string read_file(const std::string& path) {
    std::string out;
    if (std::FILE* f = std::fopen(path.c_str(), "rb")) {
        char buf[4096];
        size_t n;
        while ((n = std::fread(buf, 1, sizeof buf, f)) > 0 && out.size() < (1u << 16)) out.append(buf, n);
        std::fclose(f);
    }
    return out;
}

void init_once() {
    if (g_inited) return;
    g_inited = true;
    g_t0 = GetTickCount64();
    g_dir = plugin_dir();
    const std::string ini = read_file(g_dir + "ttw_dualscreen.ini");
    g_cfg = ttwds::game::parse_config(ini);
    g_log.open(g_dir + "ttw_dualscreen.log", g_cfg.logLevel);
    g_log.info("%s %s (log level %s)", kPluginName, kPluginVersionString, ttwds::game::log_level_name(g_cfg.logLevel));
    g_log.info("ini: %s%s", (g_dir + "ttw_dualscreen.ini").c_str(), ini.empty() ? " (missing or empty; defaults)" : "");
    for (const auto& w : g_cfg.warnings) g_log.info("ini warning: %s", w.c_str());
}

struct ServerTransport final : ttwds::proto::ITransport {
    ttwds::server::WsServer& s;
    explicit ServerTransport(ttwds::server::WsServer& srv) : s(srv) {}
    bool pollCommand(std::string& id, std::string& name) override {
        ttwds::server::PendingCommand pc;
        if (!s.pollCommand(pc)) return false;
        id = std::move(pc.id);
        name = std::move(pc.name);
        return true;
    }
    void sendToAll(std::string text) override { s.sendToAll(std::move(text)); }
    bool hasSpikeSubscriber(int& rateHz) override { return s.hasSpikeSubscriber(rateHz); }
};

void on_main_loop() {
    const DWORD tid = GetCurrentThreadId();
    if (!g_mainTid) {
        g_mainTid = tid;
        if (g_game) g_game->setMainThread(tid);
        g_log.info("first MainGameLoop (main thread %lu)", tid);
    } else if (tid != g_mainTid) {
        if (!g_wrongThreadLogged) g_log.info("MainGameLoop on unexpected thread %lu; skipped", tid);
        g_wrongThreadLogged = true;
        return;
    }
    if (!g_server || !g_game) return;
    ServerTransport t(*g_server);
    try {
        g_pump.tick(*g_game, t, static_cast<int64_t>(GetTickCount64() - g_t0),
                    [](const std::string& id, const std::string& name, const std::string& reply) {
                        g_log.info("command %s id=%s -> %s", name.c_str(), id.c_str(), reply.c_str());
                    });
    } catch (...) {
        if (!g_pumpErrorLogged) g_log.info("frame pump threw a C++ exception; continuing");
        g_pumpErrorLogged = true;
    }
}

void on_deferred_init() {
    if (!g_game) return;
    const int idx = g_game->ttwModIndex();
    g_info.ttw = idx >= 0;
    if (g_server) {
        g_server->setInfo(g_info);
        // Clients that said hello before DeferredInit got ttw:false; re-announce.
        g_server->sendToAll(ttwds::proto::welcome_json(g_info));
    }
    if (idx >= 0)
        g_log.info("DeferredInit: TaleOfTwoWastelands.esm loaded at index %02X", static_cast<unsigned>(idx));
    else
        g_log.info("DeferredInit: TaleOfTwoWastelands.esm not loaded");
}

void message_handler(NVSEMessagingInterface::Message* msg) {
    if (!msg) return;
    switch (msg->type) {
        case NVSEMessagingInterface::kMessage_MainGameLoop:
            on_main_loop();
            break;
        case NVSEMessagingInterface::kMessage_DeferredInit:
            on_deferred_init();
            break;
        case NVSEMessagingInterface::kMessage_PostLoadGame: {
            // data = (void*)bLoadSucceeded (X Serialization.cpp:763)
            const bool ok = msg->data != nullptr;
            g_log.info("PostLoadGame (success=%d)", ok ? 1 : 0);
            if (g_game) g_game->onPostLoadGame(ok);
            g_pump.resetPushClock();
            break;
        }
        case NVSEMessagingInterface::kMessage_NewGame:
            g_log.info("NewGame");
            if (g_game) g_game->onNewGame();
            g_pump.resetPushClock();
            break;
        case NVSEMessagingInterface::kMessage_PreLoadGame:
            g_log.info("PreLoadGame");
            if (g_game) g_game->onPreLoadGame();
            break;
        case NVSEMessagingInterface::kMessage_ExitToMainMenu:
            g_log.info("ExitToMainMenu");
            if (g_game) g_game->onExitToMainMenu();
            break;
        case NVSEMessagingInterface::kMessage_ExitGame:
        case NVSEMessagingInterface::kMessage_ExitGame_Console:
            g_log.info("exit game: stopping server");
            if (g_server) g_server->stop();
            break;
        default:
            break;
    }
}

}  // namespace

extern "C" {

bool NVSEPlugin_Query(const NVSEInterface* nvse, PluginInfo* info) {
    init_once();
    info->infoVersion = PluginInfo::kInfoVersion;
    info->name = kPluginName;
    info->version = kPluginVersion;
    if (nvse->isEditor) {
        g_log.force("query: refusing to load in the editor");
        return false;
    }
    if (nvse->runtimeVersion != RUNTIME_VERSION_1_4_0_525) {
        g_log.force("query: refusing: runtime %08X is not FNV 1.4.0.525 (%08X)", nvse->runtimeVersion,
                   static_cast<UInt32>(RUNTIME_VERSION_1_4_0_525));
        return false;
    }
    g_log.info("query: ok (xNVSE %s, runtime %08X)", ttwds::game::xnvse_version_string(nvse->nvseVersion).c_str(),
               nvse->runtimeVersion);
    return true;
}

bool NVSEPlugin_Load(NVSEInterface* nvse) {
    init_once();
    g_handle = nvse->GetPluginHandle();
    g_messaging = static_cast<NVSEMessagingInterface*>(nvse->QueryInterface(kInterface_Messaging));
    if (!g_messaging) {
        g_log.info("load: failed: no messaging interface");
        return false;
    }
    if (g_messaging->version < 4)
        g_log.info("load: messaging interface v%u < 4: no MainGameLoop, the plugin will stay idle",
                   g_messaging->version);
    auto* console = static_cast<NVSEConsoleInterface*>(nvse->QueryInterface(kInterface_Console));
    g_log.info("load: console interface %s (v%u)", console ? "available" : "unavailable", console ? console->version : 0u);

    g_info.pluginVersion = kPluginVersionString;
    g_info.game = "FalloutNV 1.4.0.525";
    g_info.xnvse = ttwds::game::xnvse_version_string(nvse->nvseVersion);
    g_info.ttw = false;  // decided at DeferredInit

    g_game = new ttwds::game::NvseGame(g_log);
    g_game->setConsole(console);
    // Register before starting the server so a failed Load never leaves a socket open.
    if (!g_messaging->RegisterListener(g_handle, "NVSE", message_handler)) {
        g_log.info("load: RegisterListener failed");
        return false;
    }

    g_server = new ttwds::server::WsServer();
    g_server->setEventLog([](const std::string& e) { g_log.info("server: %s", e.c_str()); });
    if (g_server->start(g_cfg.bind, g_cfg.port, g_info))
        g_log.info("load: server listening on %s:%u", g_cfg.bind.c_str(), static_cast<unsigned>(g_server->port()));
    else
        g_log.info("load: server failed to start on %s:%u (port in use?); plugin stays loaded, idle",
                   g_cfg.bind.c_str(), static_cast<unsigned>(g_cfg.port));

    g_log.info("load: ok");
    return true;
}

}  // extern "C"
