// DllMain for the dinput8.dll proxy. Nothing here blocks or waits: in KINGDOM HEARTS FINAL MIX.exe
// it starts the runtime thread and returns; in every other process it does nothing at all.
#include <windows.h>

#include <atomic>
#include <cstdio>
#include <string>
#include <string_view>

#include "core/process_gate.h"
#include "core/version_gate.h"
#include "win/log.h"
#include "win/memory.h"
#include "win/module_reader.h"
#include "win/runtime.h"

namespace {

// The runtime thread: everything here runs after DllMain has returned. It never exits.
DWORD WINAPI runtime_start(LPVOID) {
    using namespace diptych;
    win::enable_file_log();
    win::log_line(std::string("diptych_kh1 ") + win::kPluginVersion + " loaded");

    // Ask for 1 ms timer resolution so the 1 kHz press tick is not stuck at the default 15.6 ms.
    // winmm is resolved here, never in DllMain; the real loop rate is logged once a minute.
    if (HMODULE winmm = LoadLibraryW(L"winmm.dll")) {
        using TimePeriodFn = UINT(WINAPI*)(UINT);
        auto begin = reinterpret_cast<TimePeriodFn>(GetProcAddress(winmm, "timeBeginPeriod"));
        win::log_line(begin && begin(1) == 0 ? "timer resolution 1 ms requested" : "timer resolution request failed");
    }

    const void* base = GetModuleHandleW(nullptr);
    // Leaked on purpose: the runtime lives as long as the process; nothing runs at detach.
    auto* mem = new win::WinMemory(win::WinMemory::for_module(base));
    char size[64];
    std::snprintf(size, sizeof size, "module image size 0x%llx", static_cast<unsigned long long>(mem->image_size()));
    win::log_line(size);

    win::RuntimeOptions o;
    o.mem = mem;
    // A fresh guarded reader per attempt: a fault in one attempt must not poison the next.
    o.classify = [base] { return core::classify_build(win::module_byte_reader(base)); };
    o.log = [](const std::string& s) { win::log_line(s); };
    o.ring = &win::log_ring();
    auto* rt = new win::Runtime(std::move(o));
    static std::atomic<bool> never{false};
    rt->run(never);
    return 0;
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
            if (HANDLE t = CreateThread(nullptr, 0, runtime_start, nullptr, 0, nullptr)) CloseHandle(t);
        }
    }
    // DLL_PROCESS_DETACH: deliberately nothing (no joins, no frees).
    return TRUE;
}
