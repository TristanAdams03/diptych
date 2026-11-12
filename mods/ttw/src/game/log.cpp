#include "game/log.h"
#include <chrono>
#include <cstdarg>
#include <ctime>
#if defined(_WIN32)
#include <share.h>
#endif

namespace ttwds::game {

namespace {
// Windows: deny writers but let the user read the log while the game runs (fopen_s would not).
std::FILE* open_log(const std::string& path, const char* mode) {
#if defined(_WIN32)
    return _fsopen(path.c_str(), mode, _SH_DENYWR);
#else
    return std::fopen(path.c_str(), mode);
#endif
}
}  // namespace

const char* log_level_name(LogLevel l) {
    switch (l) {
        case LogLevel::Off: return "off";
        case LogLevel::Info: return "info";
        case LogLevel::Debug: return "debug";
    }
    return "?";
}

Logger::~Logger() {
    std::lock_guard<std::mutex> lk(mx_);
    if (f_) std::fclose(f_);
    f_ = nullptr;
}

bool Logger::openFileLocked() {
    if (f_) return true;
    if (path_.empty()) return false;
    const char* mode = "a";
    if (!started_) {
        const std::string old = path_ + ".1";
        std::remove(old.c_str());               // rename does not replace on Windows
        std::rename(path_.c_str(), old.c_str());  // no previous log is fine
        started_ = true;
        mode = "w";
    }
    f_ = open_log(path_, mode);
    return f_ != nullptr;
}

bool Logger::open(const std::string& path, LogLevel level) {
    std::lock_guard<std::mutex> lk(mx_);
    if (f_) std::fclose(f_);
    f_ = nullptr;
    path_ = path;
    level_ = level;
    started_ = capped_ = false;
    written_ = 0;
    if (level == LogLevel::Off) return true;
    return openFileLocked();
}

void Logger::setMaxBytes(size_t n) {
    std::lock_guard<std::mutex> lk(mx_);
    maxBytes_ = n;
}

void Logger::setLevel(LogLevel level) {
    std::lock_guard<std::mutex> lk(mx_);
    level_ = level;
    if (level != LogLevel::Off) openFileLocked();
}

LogLevel Logger::level() const {
    std::lock_guard<std::mutex> lk(mx_);
    return level_;
}

void Logger::write(LogLevel l, bool forced, const char* fmt, std::va_list ap) {
    std::lock_guard<std::mutex> lk(mx_);
    if (capped_) return;
    if (!forced && static_cast<int>(l) > static_cast<int>(level_)) return;
    if (!openFileLocked()) return;
    const auto now = std::chrono::system_clock::now();
    const std::time_t t = std::chrono::system_clock::to_time_t(now);
    const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(now.time_since_epoch()).count() % 1000;
    std::tm tm{};
#if defined(_WIN32)
    localtime_s(&tm, &t);
#else
    localtime_r(&t, &tm);
#endif
    char ts[32];
    std::strftime(ts, sizeof ts, "%H:%M:%S", &tm);
    char line[2048];
    int n = std::snprintf(line, sizeof line, "%s.%03d [%s] ", ts, static_cast<int>(ms), log_level_name(l));
    if (n < 0) return;
    const int m = std::vsnprintf(line + n, sizeof line - static_cast<size_t>(n), fmt, ap);
    size_t len = static_cast<size_t>(n) + (m > 0 ? static_cast<size_t>(m) : 0);
    if (len > sizeof line - 1) len = sizeof line - 1;  // truncated
    if (written_ + len + 1 > maxBytes_) {
        std::fprintf(f_, "%s.%03d [info] log size cap reached; further lines dropped\n", ts, static_cast<int>(ms));
        std::fflush(f_);
        capped_ = true;
        return;
    }
    std::fwrite(line, 1, len, f_);
    std::fputc('\n', f_);
    std::fflush(f_);  // a crash must not eat the last lines
    written_ += len + 1;
}

void Logger::info(const char* fmt, ...) {
    std::va_list ap;
    va_start(ap, fmt);
    write(LogLevel::Info, false, fmt, ap);
    va_end(ap);
}

void Logger::debug(const char* fmt, ...) {
    std::va_list ap;
    va_start(ap, fmt);
    write(LogLevel::Debug, false, fmt, ap);
    va_end(ap);
}

void Logger::force(const char* fmt, ...) {
    std::va_list ap;
    va_start(ap, fmt);
    write(LogLevel::Info, true, fmt, ap);
    va_end(ap);
}

}  // namespace ttwds::game
