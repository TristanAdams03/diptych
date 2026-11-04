#pragma once
// kh1.probe / kh1.pressInput / kh1.log (protocol/kh1/PROTOCOL.md). Portable; runs on the runtime
// thread only, as does the PressScheduler.
#include <cstdint>
#include <optional>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>

#include "core/kh1_fields.h"
#include "core/log_ring.h"

namespace diptych::core {

// The kh1.pressInput experiment, the only game-memory write in M0. While a press is active each
// tick ORs `mask` into the byte at kInputOffset + byteIndex. The press window starts at the first
// tick after start() and lasts durationMs. A tick that finds the game unsafe (safe_now false) or
// fails to read the byte ends the press; a failed write also disables presses for the session.
// When a press ends for "done" or "not safe now", if the byte still holds the last value written,
// one more write clears `mask` from it, so the button is not left held if the game does not
// rewrite its input block every frame. If the game has changed the byte since, it is left alone.
class PressScheduler {
public:
    void start(int byteIndex, uint8_t mask, int durationMs);
    void tick(IMemory& mem, int64_t nowMs);
    bool active() const { return active_; }
    bool writes_disabled() const { return writesDisabled_; }
    // Log lines produced since the last call ("press start ...", "press end ...").
    std::vector<std::string> take_log();

private:
    void end_(const std::string& why);
    void release_(IMemory& mem);
    bool active_ = false;
    bool writesDisabled_ = false;
    int byteIndex_ = 0;
    uint8_t mask_ = 0;
    int durationMs_ = 0;
    std::optional<int64_t> startMs_;
    int writes_ = 0;
    std::optional<uint8_t> lastWritten_;
    std::vector<std::string> log_;
};

inline constexpr size_t kLogLines = 200;

// Runs one command and returns the complete `result` frame. Order of checks: unknown name ->
// "unknown command"; kh1.log always succeeds with the pinned lines of `log`, then its last 200 others (none if null);
// !supported -> "unsupported build"; missing/non-integer args -> "bad command"; out-of-range args
// or a faulted probe read -> "out of range"; a press while one runs -> "busy"; a press when
// !safe_now, after a write fault, or when a safety-gate reader (menu, cutscene, Gummi, the input
// byte) is disabled -> "not safe now". Exceptions become "internal error".
// `gate` is the memory the press ticks use (the runtime's DisablingMemory); the press safety
// check runs on it so a reader that faulted earlier refuses the press up front. Null -> `mem`.
// Probes always read `mem`, so a faulting probe never turns off a state reader.
std::string run_kh1_command(IMemory& mem, bool supported, const std::string& id, const std::string& name,
                            const nlohmann::json& args, PressScheduler& press, const LogRing* log = nullptr,
                            IMemory* gate = nullptr);

}  // namespace diptych::core
