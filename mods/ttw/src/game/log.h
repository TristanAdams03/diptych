#pragma once
// Tiny thread-safe file logger (portable). The network thread and the game thread both log.
#include <cstdarg>
#include <cstdio>
#include <mutex>
#include <string>

namespace ttwds::game {

enum class LogLevel { Off = 0, Info = 1, Debug = 2 };
const char* log_level_name(LogLevel l);

class Logger {
public:
    Logger() = default;
    ~Logger();
    Logger(const Logger&) = delete;
    Logger& operator=(const Logger&) = delete;

    static constexpr size_t kDefaultMaxBytes = 4u << 20;

    // Renames an existing log to <path>.1 (replacing it), then starts a fresh file.
    // With LogLevel::Off no file is created (until force() is used).
    bool open(const std::string& path, LogLevel level);
    // Past this many bytes one final "log size cap reached" line is written, then nothing.
    void setMaxBytes(size_t n);
    void setLevel(LogLevel level);
    LogLevel level() const;

#if defined(__GNUC__)
#define TTWDS_PRINTF(a, b) __attribute__((format(printf, a, b)))
#else
#define TTWDS_PRINTF(a, b)
#endif
    void info(const char* fmt, ...) TTWDS_PRINTF(2, 3);
    void debug(const char* fmt, ...) TTWDS_PRINTF(2, 3);
    // Written whatever the level (e.g. why the plugin refused to load).
    void force(const char* fmt, ...) TTWDS_PRINTF(2, 3);
#undef TTWDS_PRINTF

private:
    void write(LogLevel l, bool forced, const char* fmt, std::va_list ap);
    bool openFileLocked();  // rotates once per Logger, then opens for writing
    mutable std::mutex mx_;
    std::FILE* f_ = nullptr;
    std::string path_;
    LogLevel level_ = LogLevel::Off;
    bool started_ = false;  // file rotated/created in this run
    bool capped_ = false;
    size_t written_ = 0;
    size_t maxBytes_ = kDefaultMaxBytes;
};

}  // namespace ttwds::game
