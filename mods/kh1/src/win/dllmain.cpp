// DllMain for the dinput8.dll proxy. Nothing here blocks or waits: in KINGDOM HEARTS FINAL MIX.exe
// it starts the runtime thread and returns; in every other process it does nothing at all.
#include <windows.h>

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <optional>
#include <string>
#include <string_view>

#include "core/process_gate.h"
#include "core/version_gate.h"
#include "win/log.h"
#include "win/memory.h"
#include "win/module_reader.h"
#include "win/runtime.h"
#include "win/xinput_hook.h"

namespace {

void runtime_body();

// The runtime thread: everything here runs after DllMain has returned. An exception escaping a
// thread proc would terminate the game, so nothing escapes: on one, log a line and end the thread
// (the mod is then inert; the forwarder keeps working).
DWORD WINAPI runtime_start(LPVOID) {
    try {
        runtime_body();
    } catch (...) {
        try {
            diptych::win::log_line("runtime failed with an unexpected error; the mod is inert for this session", true);
        } catch (...) {
        }
    }
    return 0;
}

// Runs forever unless the runtime goes inert (Runtime::run returns instead of throwing).
void runtime_body() {
    using namespace diptych;
    win::enable_file_log();
    win::log_line(std::string("diptych_kh1 ") + win::kPluginVersion + " loaded", true);

    // Ask for 1 ms timer resolution so the 1 kHz press tick (and the 10 ms idle sleep) is not
    // stuck at the default 15.6 ms.
    // winmm is resolved here, never in DllMain; the real loop rate is logged once a minute. Only the
    // copy already loaded or the one in System32: a winmm.dll proxy in the game folder is never loaded.
    HMODULE winmm = GetModuleHandleW(L"winmm.dll");
    if (!winmm) winmm = LoadLibraryExW(L"winmm.dll", nullptr, LOAD_LIBRARY_SEARCH_SYSTEM32);
    if (!winmm) win::log_line("winmm.dll not available; timer resolution left at the default");
    if (winmm) {
        using TimePeriodFn = UINT(WINAPI*)(UINT);
        auto begin = reinterpret_cast<TimePeriodFn>(GetProcAddress(winmm, "timeBeginPeriod"));
        win::log_line(begin && begin(1) == 0 ? "timer resolution 1 ms requested" : "timer resolution request failed",
                      true);
    }

    const void* base = GetModuleHandleW(nullptr);
    // Leaked on purpose: the runtime lives as long as the process; nothing runs at detach.
    auto* mem = new win::WinMemory(win::WinMemory::for_module(base));
    // Log only (the gate is unchanged): the PE header's TimeDateStamp and SizeOfImage identify the
    // exact build, so the PC session can record them for a stronger gate before M1 ships writes.
    // Read through the guarded reader: e_lfanew at 0x3C, then the NT headers.
    std::optional<uint32_t> ts, img;
    if (auto lfanew = mem->u32(0x3C)) {
        ts = mem->u32(*lfanew + 8);    // IMAGE_FILE_HEADER::TimeDateStamp
        img = mem->u32(*lfanew + 80);  // IMAGE_OPTIONAL_HEADER64::SizeOfImage
    }
    const auto hex = [](const std::optional<uint32_t>& v) {
        if (!v) return std::string("?");
        char b[16];
        std::snprintf(b, sizeof b, "0x%08lx", static_cast<unsigned long>(*v));
        return std::string(b);
    };
    const std::string peId = "pe TimeDateStamp " + hex(ts) + " SizeOfImage " + hex(img);
    char size[64];
    std::snprintf(size, sizeof size, "module image size 0x%llx", static_cast<unsigned long long>(mem->image_size()));
    win::log_line(std::string(size) + "; main module " + peId, true);

    win::RuntimeOptions o;
    o.mem = mem;
    // A fresh guarded reader per attempt: a fault in one attempt must not poison the next.
    o.classify = [base] { return core::classify_build(win::module_byte_reader(base)); };
    o.log = [](const std::string& s) { win::log_line(s); };
    o.logPinned = [](const std::string& s) { win::log_line(s, true); };
    o.ring = &win::log_ring();
    o.moduleId = peId;
    // Ruling W: installed by the runtime (this thread) once the build is supported; never in DllMain.
    o.installInputHook = [base](std::string& line) { return win::install_xinput_hook(base, line); };
    o.inject = &win::xinput_inject_state();
    auto* rt = new win::Runtime(std::move(o));
    static std::atomic<bool> never{false};
    rt->run(never);
}

// Static so DllMain does not put 64 KiB on the loader's stack; DllMain is serialised by the loader.
wchar_t g_exePath[32768];

}  // namespace

BOOL WINAPI DllMain(HINSTANCE hinst, DWORD reason, LPVOID) {
    if (reason == DLL_PROCESS_ATTACH) {
        DisableThreadLibraryCalls(hinst);
        const DWORD cap = static_cast<DWORD>(sizeof g_exePath / sizeof g_exePath[0]);
        const DWORD n = GetModuleFileNameW(nullptr, g_exePath, cap);
        if (n > 0 && n < cap && diptych::core::is_kh1_process(std::wstring_view(g_exePath, n))) {
            // Pin this DLL so a stray FreeLibrary can never unmap code our threads are running.
            // Takes no new lock beyond the loader lock we already hold; it does not load anything.
            HMODULE self = nullptr;
            GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_PIN | GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS,
                               reinterpret_cast<LPCWSTR>(&g_exePath), &self);
            if (HANDLE t = CreateThread(nullptr, 0, runtime_start, nullptr, 0, nullptr)) CloseHandle(t);
        }
    }
    // DLL_PROCESS_DETACH: deliberately nothing (no joins, no frees).
    return TRUE;
}
