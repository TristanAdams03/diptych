// Windows-only: the runtime loop end to end, with the real WsServer on an ephemeral loopback port
// and FakeMemory standing in for the game image.
#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "ws_test_client.h"

#include <atomic>
#include <chrono>
#include <functional>
#include <memory>
#include <stdexcept>
#include <string>
#include <thread>

#include <nlohmann/json.hpp>

#include "core/kh1_fields.h"
#include "core/log_ring.h"
#include "fake_memory.h"
#include "win/runtime.h"

using namespace diptych;
using namespace testws;
using nlohmann::json;

namespace {

// The next text frame whose "type" is `type` (others, e.g. state frames, are skipped).
json next_of_type(TestClient& c, const char* type) {
    std::string text;
    while (c.expect(0x1, text)) {
        json j = json::parse(text);
        if (j.value("type", "") == type) return j;
    }
    return nullptr;
}

struct RunningRuntime {
    Wsa wsa;  // for the test client; the server starts its own
    std::atomic<bool> stop{false};
    std::atomic<int> classifyCalls{0};
    std::atomic<core::Build> build{core::Build::Unknown};
    core::LogRing ring;
    FakeMemory mem;
    core::InjectState inject;
    std::atomic<int> hookInstalls{0};
    std::atomic<bool> hookOk{true};
    std::atomic<bool> boom{false};      // makes onStep throw (Important 3)
    std::atomic<bool> runReturned{false};
    std::atomic<int> steps{0};
    std::unique_ptr<win::Runtime> rt;
    std::thread th;

    explicit RunningRuntime(int classifyForMs = 30000, std::string moduleId = {},
                            const std::function<void(win::RuntimeOptions&)>& tweak = {}) {
        mem.set(core::kWorldOffset, 3);
        mem.set(core::kRoomOffset, 2);
        mem.set(core::kWorldAltOffset, 3);
        mem.set(core::kSoraHpOffset, 52);
        mem.set(core::kSoraHpMaxOffset, 60);
        win::RuntimeOptions o;
        o.port = 0;
        o.mem = &mem;
        o.classify = [this] {
            ++classifyCalls;
            return build.load();
        };
        o.log = [this](const std::string& s) { ring.add(s); };
        o.logPinned = [this](const std::string& s) { ring.add(s, true); };
        o.ring = &ring;
        o.installInputHook = [this](std::string& line) {
            ++hookInstalls;
            line = hookOk.load() ? "xinput hook installed (slot +0x1608, fake)" : "xinput hook not installed: fake";
            return hookOk.load();
        };
        o.inject = &inject;
        o.classifyEveryMs = 20;
        o.classifyForMs = classifyForMs;
        o.moduleId = std::move(moduleId);
        o.onStep = [this](int64_t) {
            ++steps;
            if (boom.load()) throw std::runtime_error("injected");
        };
        if (tweak) tweak(o);
        rt = std::make_unique<win::Runtime>(std::move(o));
        th = std::thread([this] {
            rt->run(stop);
            runReturned = true;
        });
        const auto until = std::chrono::steady_clock::now() + std::chrono::seconds(5);
        while (rt->port() == 0 && std::chrono::steady_clock::now() < until)
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        REQUIRE(rt->port() != 0);
    }
    // Joins the loop; afterwards mem may be inspected safely.
    void halt() {
        stop = true;
        if (th.joinable()) th.join();
    }
    ~RunningRuntime() {
        halt();
        rt.reset();
    }
};

std::string command(const char* id, const char* name, const json& args) {
    return json{{"type", "command"}, {"id", id}, {"name", name}, {"args", args}}.dump();
}

}  // namespace

TEST_CASE("runtime_end_to_end") {
    RunningRuntime r;
    TestClient c(r.rt->port());
    REQUIRE(c.handshake());
    REQUIRE(c.sendFrame(0x1, fixture("hello.json")));
    json w = next_of_type(c, "welcome");
    CHECK(w["plugin"] == "0.0.1-spike");
    CHECK(w["gameId"] == "kh1");
    CHECK(w["gameBuild"] == "unknown");
    CHECK(w["supported"] == false);

    // Unsupported: probe refused, kh1.log still works.
    REQUIRE(c.sendFrame(0x1, fixture("command_probe.json")));
    json res = next_of_type(c, "result");
    CHECK(res["id"] == "c1");
    CHECK(res["error"] == "unsupported build");
    REQUIRE(c.sendFrame(0x1, fixture("command_log.json")));
    res = next_of_type(c, "result");
    CHECK(res["ok"] == true);
    bool listening = false;
    for (auto& l : res["data"]["lines"]) listening |= l.get<std::string>().find("server listening on 127.0.0.1:") == 0;
    CHECK(listening);

    // Unsupported state: all null.
    REQUIRE(c.sendFrame(0x1, R"({"type":"subscribe","topics":["kh1.spike"],"rateHz":10})"));
    json st = next_of_type(c, "state");
    CHECK(st["topic"] == "kh1.spike");
    CHECK(st["data"]["world"].is_null());

    // Ruling O: the build becomes recognisable later; a fresh welcome is pushed.
    r.build = core::Build::Steam1002;
    w = next_of_type(c, "welcome");
    CHECK(w["gameBuild"] == "steam-1.0.0.2");
    CHECK(w["supported"] == true);
    st = next_of_type(c, "state");
    CHECK(st["data"]["world"] == 3);
    CHECK(st["data"]["soraHpMax"] == 60);
    CHECK(st["data"]["inputBytes"] == "00000000");
    const int64_t seq1 = st["data"]["seq"].get<int64_t>();
    st = next_of_type(c, "state");
    CHECK(st["data"]["seq"].get<int64_t>() == seq1 + 1);

    REQUIRE(c.sendFrame(0x1, fixture("command_probe.json")));
    res = next_of_type(c, "result");
    CHECK(res == json::parse(fixture("result_probe.json")));

    // 1000 ms (the maximum) so the second press surely lands while the first runs, even on a slow loop.
    REQUIRE(c.sendFrame(0x1, command("p1", "kh1.pressInput", {{"byteIndex", 1}, {"mask", 0x40}, {"durationMs", 1000}})));
    res = next_of_type(c, "result");
    CHECK(res["id"] == "p1");
    CHECK(res["ok"] == true);
    REQUIRE(c.sendFrame(0x1, command("p2", "kh1.pressInput", {{"byteIndex", 1}, {"mask", 0x40}, {"durationMs", 50}})));
    res = next_of_type(c, "result");
    CHECK(res["error"] == "busy");
    std::this_thread::sleep_for(std::chrono::milliseconds(200));
    {
        uint32_t pk = 0;
        uint16_t b = 0;
        uint8_t lt = 0, rt = 0;
        r.inject.apply(pk, b, lt, rt);
        CHECK(b == core::kXiA);  // mid-press: the hook would report A held
        CHECK(pk == 1);
    }
    std::this_thread::sleep_for(std::chrono::milliseconds(1100));

    REQUIRE(c.sendFrame(0x1, command("x", "kh1.nope", json::object())));
    res = next_of_type(c, "result");
    CHECK(res["error"] == "unknown command");

    r.halt();
    CHECK(r.mem.writes.empty());  // presses never write game memory
    CHECK_FALSE(r.inject.active());
    CHECK(r.inject.generation() == 2);
    CHECK(r.hookInstalls.load() == 1);
    bool installed = false, started = false, ended = false;
    for (auto& l : r.ring.pinned()) installed |= l == "xinput hook installed (slot +0x1608, fake)";
    for (auto& l : r.ring.last(500)) {
        started |= l.find("press start") != std::string::npos;
        ended |= l.find("press end (done)") != std::string::npos;
    }
    CHECK(installed);
    CHECK(started);
    CHECK(ended);
}

TEST_CASE("runtime_refuses_presses_when_the_xinput_hook_fails") {
    RunningRuntime r(30000, {}, [](win::RuntimeOptions&) {});
    r.hookOk = false;
    r.build = core::Build::Steam1002;
    TestClient c(r.rt->port());
    REQUIRE(c.handshake());
    std::this_thread::sleep_for(std::chrono::milliseconds(100));
    REQUIRE(c.sendFrame(0x1, command("p", "kh1.pressInput", {{"byteIndex", 1}, {"mask", 0x40}, {"durationMs", 100}})));
    json res = next_of_type(c, "result");
    CHECK(res["error"] == "not safe now");
    r.halt();
    CHECK(r.hookInstalls.load() == 1);
    bool logged = false;
    for (auto& l : r.ring.pinned()) logged |= l == "xinput hook not installed: fake";
    CHECK(logged);
}

TEST_CASE("runtime_does_not_hook_an_unsupported_build") {
    RunningRuntime r;
    r.build = core::Build::Egs10010;
    std::this_thread::sleep_for(std::chrono::milliseconds(200));
    r.halt();
    CHECK(r.hookInstalls.load() == 0);
}

TEST_CASE("runtime_goes_inert_when_a_step_throws") {
    RunningRuntime r;
    r.build = core::Build::Steam1002;
    TestClient c(r.rt->port());
    REQUIRE(c.handshake());
    REQUIRE(c.sendFrame(0x1, R"({"type":"subscribe","topics":["kh1.spike"],"rateHz":10})"));
    // The first frames can predate classification (build is set after the runtime starts), so wait
    // for one that shows memory being read.
    json st;
    for (int i = 0; i < 50; ++i) {
        st = next_of_type(c, "state");
        if (st.is_null() || (st["data"].contains("world") && st["data"]["world"] == 3)) break;
    }
    REQUIRE(st["data"]["world"] == 3);  // memory is being read

    r.boom = true;  // the next step throws
    const auto t0 = std::chrono::steady_clock::now();
    while (!r.runReturned.load() && std::chrono::steady_clock::now() - t0 < std::chrono::seconds(3))
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    // run() returned on its own (stop was never set) instead of letting the exception escape.
    REQUIRE(r.runReturned.load());
    CHECK_FALSE(r.stop.load());
    CHECK(r.rt->inert());
    CHECK(r.rt->port() == 0);
    CHECK(c.closedByServer());
    const int reads = r.mem.reads;
    r.rt->step(999999);  // a stray step does nothing once inert
    CHECK(r.mem.reads == reads);
    int inertLines = 0;
    for (auto& l : r.ring.pinned()) inertLines += l.find("the mod is inert") != std::string::npos ? 1 : 0;
    CHECK(inertLines == 1);
}

TEST_CASE("runtime_releases_injected_buttons_when_it_goes_inert_mid_press") {
    RunningRuntime r;
    r.build = core::Build::Steam1002;
    TestClient c(r.rt->port());
    REQUIRE(c.handshake());
    const auto tHook = std::chrono::steady_clock::now();
    while (r.hookInstalls.load() == 0 && std::chrono::steady_clock::now() - tHook < std::chrono::seconds(2))
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    REQUIRE(r.hookInstalls.load() == 1);
    REQUIRE(c.sendFrame(0x1, command("p", "kh1.pressInput", {{"byteIndex", 1}, {"mask", 64}, {"durationMs", 1000}})));
    json res = next_of_type(c, "result");
    REQUIRE(res["ok"] == true);
    const auto t0 = std::chrono::steady_clock::now();
    while (!r.inject.active() && std::chrono::steady_clock::now() - t0 < std::chrono::seconds(2))
        std::this_thread::sleep_for(std::chrono::milliseconds(2));
    REQUIRE(r.inject.active());  // the press is being injected

    r.boom = true;  // the next step throws mid-press
    while (!r.runReturned.load() && std::chrono::steady_clock::now() - t0 < std::chrono::seconds(4))
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    REQUIRE(r.runReturned.load());
    CHECK(r.rt->inert());
    CHECK_FALSE(r.inject.active());  // no buttons left held for the rest of the session
}

TEST_CASE("runtime_stops_classifying_after_the_window") {
    RunningRuntime r(200);  // classify every 20 ms for 200 ms, build stays unknown
    std::this_thread::sleep_for(std::chrono::milliseconds(600));
    const int calls = r.classifyCalls.load();
    CHECK(calls >= 5);
    CHECK(calls <= 13);
    std::this_thread::sleep_for(std::chrono::milliseconds(200));
    CHECK(r.classifyCalls.load() == calls);
    bool final = false;
    for (auto& l : r.ring.pinned()) final |= l == "build classification final: unknown";
    CHECK(final);
}

TEST_CASE("runtime_logs_the_module_id_next_to_the_final_classification") {
    RunningRuntime r(100, "pe TimeDateStamp 0x12345678 SizeOfImage 0x3000000");
    std::this_thread::sleep_for(std::chrono::milliseconds(400));
    bool found = false;
    for (auto& l : r.ring.pinned())
        found |= l == "build classification final: unknown (pe TimeDateStamp 0x12345678 SizeOfImage 0x3000000)";
    CHECK(found);
}

TEST_CASE("runtime_idles_slower_without_a_press") {
    // A 50 ms idle sleep: over 500 ms at most ~10 steps. (1 ms sleeps would give 30+ even at the
    // default 15.6 ms timer resolution.) Then a press switches to the 1 ms active sleep.
    RunningRuntime r(30000, {}, [](win::RuntimeOptions& o) { o.idleSleepMs = 50; });
    r.build = core::Build::Steam1002;
    std::this_thread::sleep_for(std::chrono::milliseconds(100));
    int s0 = r.steps.load();
    std::this_thread::sleep_for(std::chrono::milliseconds(500));
    const int idle = r.steps.load() - s0;
    CHECK(idle >= 3);
    CHECK(idle <= 14);

    TestClient c(r.rt->port());
    REQUIRE(c.handshake());
    REQUIRE(c.sendFrame(0x1, command("p", "kh1.pressInput", {{"byteIndex", 1}, {"mask", 0x40}, {"durationMs", 1000}})));
    json res = next_of_type(c, "result");
    REQUIRE(res["ok"] == true);
    s0 = r.steps.load();
    std::this_thread::sleep_for(std::chrono::milliseconds(500));
    CHECK(r.steps.load() - s0 > 15);  // the active rate during the press (~30 even at 15.6 ms)
}

TEST_CASE("runtime_stops_classifying_once_recognised") {
    RunningRuntime r;
    r.build = core::Build::Egs10010;
    std::this_thread::sleep_for(std::chrono::milliseconds(200));
    const int calls = r.classifyCalls.load();
    std::this_thread::sleep_for(std::chrono::milliseconds(200));
    CHECK(r.classifyCalls.load() == calls);
    TestClient c(r.rt->port());
    REQUIRE(c.handshake());
    REQUIRE(c.sendFrame(0x1, fixture("hello.json")));
    json w = next_of_type(c, "welcome");
    CHECK(w == json::parse(fixture("welcome_unsupported.json")));
}
