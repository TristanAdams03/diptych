#include "win/log.h"

#include <windows.h>

#include <cstdio>
#include <mutex>

#include "core/log_file_cap.h"

namespace diptych::win {

namespace {
struct Logger {
    core::LogRing ring;
    std::mutex fileMu;
    HANDLE file = INVALID_HANDLE_VALUE;
    bool fileTried = false;
    core::LogFileCap cap;  // 1 MiB per session, then one marker line
};

// Leaked, as in ttw-dualscreen: DLL_PROCESS_DETACH runs no destructor and frees nothing.
Logger& logger() {
    static Logger* g = new Logger;
    return *g;
}
}  // namespace

core::LogRing& log_ring() { return logger().ring; }

std::string narrow(std::wstring_view w) {
    if (w.empty()) return {};
    int n = WideCharToMultiByte(CP_UTF8, 0, w.data(), static_cast<int>(w.size()), nullptr, 0, nullptr, nullptr);
    if (n <= 0) return {};
    std::string s(static_cast<size_t>(n), '\0');
    WideCharToMultiByte(CP_UTF8, 0, w.data(), static_cast<int>(w.size()), s.data(), n, nullptr, nullptr);
    return s;
}

void log_line(std::string_view msg, bool pin) {
    SYSTEMTIME t;
    GetLocalTime(&t);
    char prefix[48];
    std::snprintf(prefix, sizeof prefix, "%02u:%02u:%02u.%03u [%lu] ", t.wHour, t.wMinute, t.wSecond,
                  t.wMilliseconds, GetCurrentThreadId());
    std::string line = prefix;
    line.append(msg);

    Logger& L = logger();
    {
        std::lock_guard<std::mutex> lk(L.fileMu);
        if (L.file != INVALID_HANDLE_VALUE) {
            const std::string out = L.cap.next(line);
            if (!out.empty()) {
                DWORD written = 0;
                WriteFile(L.file, out.data(), static_cast<DWORD>(out.size()), &written, nullptr);
            }
        }
    }
    L.ring.add(std::move(line), pin);
}

void enable_file_log() {
    Logger& L = logger();
    std::lock_guard<std::mutex> lk(L.fileMu);
    if (L.fileTried) return;
    L.fileTried = true;

    HMODULE self = nullptr;
    if (!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                            reinterpret_cast<LPCWSTR>(&enable_file_log), &self))
        return;
    std::wstring path(32768, L'\0');
    DWORD n = GetModuleFileNameW(self, path.data(), static_cast<DWORD>(path.size()));
    if (n == 0 || n >= path.size()) return;
    path.resize(n);
    auto slash = path.find_last_of(L"\\/");
    path.resize(slash == std::wstring::npos ? 0 : slash + 1);
    path += L"diptych_kh1.log";

    // One previous session is kept as diptych_kh1.log.1; this session starts a fresh file.
    // Failures (no old log, read-only folder) are ignored: the ring still records everything.
    const std::wstring old = path + L".1";
    MoveFileExW(path.c_str(), old.c_str(), MOVEFILE_REPLACE_EXISTING);
    HANDLE h = CreateFileW(path.c_str(), GENERIC_WRITE, FILE_SHARE_READ, nullptr, CREATE_ALWAYS,
                           FILE_ATTRIBUTE_NORMAL, nullptr);
    if (h != INVALID_HANDLE_VALUE) L.file = h;  // never closed: the logger is leaked
}

}  // namespace diptych::win
