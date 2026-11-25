#pragma once
// kh1.pressSequence and kh1.pressInput (protocol/kh1/PROTOCOL.md): controller-0 presses injected
// through the XInputGetState hook (win/xinput_hook.cpp), which reads the InjectState this scheduler
// sets. No game memory is written. A sequence is a short, app-authored list of steps with u8 memory
// conditions checked between them; kh1.pressInput is a one-step sequence with no gap whose result
// goes out when it starts. Portable; runtime thread only.
#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>

#include "core/kh1_fields.h"
#include "core/xinput_inject.h"

namespace diptych::core {

inline constexpr size_t kSeqMaxSteps = 16;
inline constexpr size_t kSeqMaxRequire = 16;
inline constexpr int kSeqMinMs = 16;           // holdMs, gapMs and until.timeoutMs
inline constexpr int kSeqMaxStepMs = 1000;     // holdMs and gapMs
inline constexpr int kSeqMaxTimeoutMs = 5000;  // until.timeoutMs
inline constexpr int kSeqMaxTotalMs = 5000;    // every step's holdMs + gapMs + until.timeoutMs

// Met when (u8 at moduleBase + offset) & mask == value. value never has bits outside mask.
struct MemCondition {
    uintptr_t offset = 0;
    uint8_t value = 0;
    uint8_t mask = 0xFF;
};

struct SeqStep {
    int byteIndex = 0;
    uint8_t mask = 0;  // the PS2 bits asked for (log only); 0 presses nothing (a plain wait)
    PadInject pad;
    int holdMs = 0;
    int gapMs = 0;
    std::optional<MemCondition> until;  // after the release, wait for it (up to untilTimeoutMs)
    int untilTimeoutMs = 0;
    std::optional<MemCondition> onlyIf;  // checked when the step begins; false skips the step
};

struct Sequence {
    std::vector<MemCondition> require;  // checked once, when the command arrives
    std::vector<SeqStep> steps;
};

// Validates kh1.pressSequence args. `steps` missing or not an array, an element or condition that
// is not an object, or a missing/non-integer field -> Fail{"bad command"}; `require` present but not
// an array -> Fail{"bad command"}. No steps or more than 16, more than 16 require conditions, a
// byteIndex/mask with no XInput mapping (or Start), holdMs/gapMs outside 16..1000, a timeoutMs
// outside 16..5000, a total over 5000 ms, a condition offset outside [0, imageSize), a value or mask
// outside 0..255 (mask 1..255), or value bits outside mask -> Fail{"out of range"}.
Sequence parse_sequence(const nlohmann::json& args, size_t imageSize);

// Whether the condition holds; nullopt when its byte cannot be read (or the read throws).
std::optional<bool> condition_met(IMemory& mem, const MemCondition& c);

// Runs one press or one sequence at a time. The first tick after a start begins step 1. A step
// whose onlyIf is false is skipped; otherwise its pad is injected for holdMs, released, then (with
// `until`) the scheduler waits for the condition, then stays released for gapMs. Every tick that
// would inject or wait first checks safe_now on the gate memory; an unsafe tick ends the sequence
// ("not safe now"), as does a condition byte that cannot be read. An until that is not met within
// its timeoutMs ends it with "timeout". Every end clears the injection. Without an attached
// InjectState (the hook is not installed) nothing can start: callers refuse with "not safe now".
class SequenceScheduler {
public:
    void attach(InjectState* s) { inject_ = s; }
    bool available() const { return inject_ != nullptr; }
    // kh1.pressInput: hold `pad` for durationMs. The caller answers the command itself.
    void start(int byteIndex, uint8_t mask, PadInject pad, int durationMs);
    // kh1.pressSequence: its `result` frame (for command `id`) is ready from take_result once it ends.
    void start_sequence(const std::string& id, Sequence seq);
    // `gate` is read by safe_now (the runtime's DisablingMemory). Conditions read `conditions`
    // (raw memory, so an app-chosen offset never turns off a safety reader); null -> `gate`.
    void tick(IMemory& gate, int64_t nowMs, IMemory* conditions = nullptr);
    bool active() const { return active_; }
    // Log lines produced since the last call ("press start ...", "sequence c5 end ...").
    std::vector<std::string> take_log();
    // The complete `result` frame of a sequence that ended since the last call, else nullopt.
    // Ok: data {steps: steps pressed, skipped: steps skipped by onlyIf}.
    std::optional<std::string> take_result();

private:
    enum class Phase { Begin, Hold, Until, Gap };
    enum class End { Done, NoHook, Unsafe, Unreadable, Timeout };
    void begin_(Sequence seq);
    void end_(End why, int64_t nowMs);

    InjectState* inject_ = nullptr;
    bool active_ = false;
    Sequence seq_;
    std::optional<std::string> id_;  // set for a pressSequence (its result is deferred)
    size_t step_ = 0;
    Phase phase_ = Phase::Begin;
    int64_t phaseMs_ = 0;
    std::optional<int64_t> firstMs_;  // the first tick after the start
    int pressed_ = 0, skipped_ = 0;
    std::vector<std::string> log_;
    std::optional<std::string> result_;
};

}  // namespace diptych::core
