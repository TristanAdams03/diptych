#pragma once
// kh1.probe / kh1.pressInput / kh1.log / kh1.watch (protocol/kh1/PROTOCOL.md). Portable; runs on the runtime
// thread only, as does the PressScheduler.
#include <cstdint>
#include <optional>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>

#include "core/kh1_fields.h"
#include "core/kh1_watch.h"
#include "core/log_ring.h"
#include "core/xinput_inject.h"

namespace diptych::core {

// kh1.pressInput: a synthetic controller-0 press injected through the XInputGetState hook
// (win/xinput_hook.cpp), which reads the InjectState this scheduler sets. No game memory is
// written. The press window starts at the first tick after start() and lasts durationMs; the first
// tick that finds the game safe sets the injection, and the tick that ends the press clears it.
// A tick that finds the game unsafe (safe_now false) ends the press early. Without an attached
// InjectState (the hook is not installed) presses are refused with "not safe now".
class PressScheduler {
public:
    void attach(InjectState* s) { inject_ = s; }
    bool available() const { return inject_ != nullptr; }
    void start(int byteIndex, uint8_t mask, PadInject pad, int durationMs);
    void tick(IMemory& mem, int64_t nowMs);
    bool active() const { return active_; }
    // Log lines produced since the last call ("press start ...", "press end ...").
    std::vector<std::string> take_log();

private:
    void end_(const std::string& why, int64_t nowMs);
    InjectState* inject_ = nullptr;
    bool active_ = false;
    PadInject pad_;
    int durationMs_ = 0;
    std::optional<int64_t> startMs_;
    std::vector<std::string> log_;
};

inline constexpr size_t kLogLines = 200;

// Runs one command and returns the complete `result` frame. Order of checks: unknown name ->
// "unknown command"; kh1.log always succeeds with the pinned lines of `log`, then its last 200 others (none if null);
// !supported -> "unsupported build"; missing/non-integer args -> "bad command"; out-of-range args
// or a faulted probe read (or a pressInput byteIndex/mask with no XInput mapping, or start) ->
// "out of range"; a press while one runs -> "busy"; a press when !safe_now, without the XInput hook,
// or when a safety-gate reader (menu, cutscene, Gummi) is disabled -> "not safe now". Exceptions become "internal error".
// `gate` is the memory the press ticks use (the runtime's DisablingMemory); the press safety
// check runs on it so a reader that faulted earlier refuses the press up front. Null -> `mem`.
// Probes always read `mem`, so a faulting probe never turns off a state reader.
// kh1.watch validates its regions with parse_watch against mem.image_size() and, when ok, stores
// the new set in *watch (data {count}); the caller keeps it for the sending client. *watch is left
// empty for every other command and every failure; a null `watch` makes kh1.watch "internal error".
std::string run_kh1_command(IMemory& mem, bool supported, const std::string& id, const std::string& name,
                            const nlohmann::json& args, PressScheduler& press, const LogRing* log = nullptr,
                            IMemory* gate = nullptr, std::optional<WatchSet>* watch = nullptr);

}  // namespace diptych::core
