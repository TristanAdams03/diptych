// DllMain for the dinput8.dll proxy. Nothing here blocks or waits: in KINGDOM HEARTS FINAL MIX.exe
// it starts the runtime thread and returns; in every other process it does nothing at all.
#include <windows.h>

#include <string>
#include <string_view>

#include "core/process_gate.h"
#include "core/version_gate.h"
#include "win/log.h"
#include "win/module_reader.h"

namespace {

// Task 3 stub; Task 4 replaces the body with the server + reader.
DWORD WINAPI runtime_start(LPVOID) {
    using namespace diptych;
    win::enable_file_log();
    win::log_line("KH1 detected");
    const core::Build b = core::classify_build(win::module_byte_reader(GetModuleHandleW(nullptr)));
    win::log_line(std::string("build: ") + core::build_name(b));
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
