#include "core/kh1_sequence.h"

#include <cstdio>
#include <limits>
#include <utility>

#include "core/command_args.h"
#include "proto/envelope.h"

namespace diptych::core {

using nlohmann::json;

// ---- parsing ----------------------------------------------------------------------------------

namespace {
MemCondition parse_condition(const json& j, size_t imageSize) {
    if (!j.is_object()) throw Fail{"bad command"};
    const int64_t offset = int_arg(j, "offset", 0, std::numeric_limits<int64_t>::max());
    const int64_t value = int_arg(j, "value", 0, 255);
    const int64_t mask = j.contains("mask") ? int_arg(j, "mask", 1, 255) : 0xFF;
    if (static_cast<uint64_t>(offset) >= imageSize) throw Fail{"out of range"};
    if ((value & ~mask) != 0) throw Fail{"out of range"};  // could never match
    return {static_cast<uintptr_t>(offset), static_cast<uint8_t>(value), static_cast<uint8_t>(mask)};
}

// For log lines: client ids are arbitrary strings.
std::string short_id(const std::string& id) { return id.size() <= 32 ? id : id.substr(0, 32) + "..."; }
}  // namespace

Sequence parse_sequence(const json& args, size_t imageSize) {
    Sequence s;
    auto steps = args.find("steps");
    if (steps == args.end() || !steps->is_array()) throw Fail{"bad command"};
    if (steps->empty() || steps->size() > kSeqMaxSteps) throw Fail{"out of range"};
    auto require = args.find("require");
    if (require != args.end()) {
        if (!require->is_array()) throw Fail{"bad command"};
        if (require->size() > kSeqMaxRequire) throw Fail{"out of range"};
        for (const auto& c : *require) s.require.push_back(parse_condition(c, imageSize));
    }
    int64_t totalMs = 0;
    for (const auto& j : *steps) {
        if (!j.is_object()) throw Fail{"bad command"};
        SeqStep st;
        st.byteIndex = static_cast<int>(int_arg(j, "byteIndex", 0, 1));
        st.mask = static_cast<uint8_t>(int_arg(j, "mask", 0, 255));
        const std::optional<PadInject> pad = ps2_to_xinput(st.byteIndex, st.mask);
        if (!pad) throw Fail{"out of range"};  // start (byte 0, 0x08) opens the pause menu
        st.pad = *pad;
        st.holdMs = static_cast<int>(int_arg(j, "holdMs", kSeqMinMs, kSeqMaxStepMs));
        st.gapMs = static_cast<int>(int_arg(j, "gapMs", kSeqMinMs, kSeqMaxStepMs));
        totalMs += st.holdMs + st.gapMs;
        if (auto u = j.find("until"); u != j.end()) {
            st.until = parse_condition(*u, imageSize);
            st.untilTimeoutMs = static_cast<int>(int_arg(*u, "timeoutMs", kSeqMinMs, kSeqMaxTimeoutMs));
            totalMs += st.untilTimeoutMs;
        }
        if (auto o = j.find("onlyIf"); o != j.end()) st.onlyIf = parse_condition(*o, imageSize);
        s.steps.push_back(st);
    }
    if (totalMs > kSeqMaxTotalMs) throw Fail{"out of range"};
    return s;
}

std::optional<bool> condition_met(IMemory& mem, const MemCondition& c) {
    try {
        const std::optional<uint8_t> v = mem.u8(c.offset);
        if (!v) return std::nullopt;
        return (*v & c.mask) == c.value;
    } catch (...) {
        return std::nullopt;
    }
}

// ---- SequenceScheduler ------------------------------------------------------------------------

void SequenceScheduler::begin_(Sequence seq) {
    active_ = true;
    seq_ = std::move(seq);
    step_ = 0;
    phase_ = Phase::Begin;
    phaseMs_ = 0;
    firstMs_.reset();
    pressed_ = 0;
    skipped_ = 0;
}

void SequenceScheduler::start(int byteIndex, uint8_t mask, PadInject pad, int durationMs) {
    SeqStep st;
    st.byteIndex = byteIndex;
    st.mask = mask;
    st.pad = pad;
    st.holdMs = durationMs;
    st.gapMs = 0;  // the press ends at the release
    Sequence s;
    s.steps.push_back(st);
    id_.reset();
    begin_(std::move(s));
    char buf[64];
    std::snprintf(buf, sizeof buf, " (xinput buttons 0x%04x lt %u rt %u)", static_cast<unsigned>(pad.buttons),
                  static_cast<unsigned>(pad.leftTrigger), static_cast<unsigned>(pad.rightTrigger));
    log_.push_back("press start byte " + std::to_string(byteIndex) + " mask " + std::to_string(mask) + " for " +
                   std::to_string(durationMs) + " ms" + buf);
}

void SequenceScheduler::start_sequence(const std::string& id, Sequence seq) {
    const size_t n = seq.steps.size();
    id_ = id;
    begin_(std::move(seq));
    log_.push_back("sequence " + short_id(id) + " start: " + std::to_string(n) + " steps");
}

void SequenceScheduler::end_(End why, int64_t nowMs) {
    if (inject_) inject_->clear();
    active_ = false;
    const int64_t took = firstMs_ ? nowMs - *firstMs_ : 0;
    firstMs_.reset();
    if (!id_) {  // kh1.pressInput: its result went out when it started
        const char* w = why == End::Done     ? "done"
                        : why == End::NoHook ? "aborted: xinput hook not installed"
                                             : "aborted: not safe now";
        log_.push_back(std::string("press end (") + w + ") after " + std::to_string(took) + " ms");
        return;
    }
    const std::string at = " at step " + std::to_string(step_ + 1);
    std::string what;
    proto::CommandResult r{false, "not safe now", nullptr};
    switch (why) {
        case End::Done:
            what = "done: " + std::to_string(pressed_) + " pressed, " + std::to_string(skipped_) + " skipped";
            r = {true, "", json{{"steps", pressed_}, {"skipped", skipped_}}};
            break;
        case End::Timeout:
            what = "timeout" + at;
            r = {false, "timeout", nullptr};
            break;
        case End::NoHook:
            what = "aborted: xinput hook not installed";
            break;
        case End::Unsafe:
            what = "aborted: not safe now" + at;
            break;
        case End::Unreadable:
            what = "aborted: condition unreadable" + at;
            break;
    }
    log_.push_back("sequence " + short_id(*id_) + " end (" + what + ") after " + std::to_string(took) + " ms");
    result_ = proto::result_json(*id_, r);
    id_.reset();
}

void SequenceScheduler::tick(IMemory& gate, int64_t nowMs, IMemory* conditions) {
    if (!active_) return;
    IMemory& cm = conditions ? *conditions : gate;
    if (!firstMs_) firstMs_ = nowMs;
    if (!inject_) return end_(End::NoHook, nowMs);
    // Phases that take no time run back to back within one tick. Each pass moves to a later phase or
    // step, and a new hold always waits (holdMs >= 16), so the loop ends; the bound is a backstop.
    for (size_t pass = 0; pass < 4 * kSeqMaxSteps + 8; ++pass) {
        if (step_ >= seq_.steps.size()) return end_(End::Done, nowMs);
        const SeqStep& s = seq_.steps[step_];
        switch (phase_) {
            case Phase::Begin:
                if (s.onlyIf) {
                    const std::optional<bool> met = condition_met(cm, *s.onlyIf);
                    if (!met) return end_(End::Unreadable, nowMs);
                    if (!*met) {
                        ++skipped_;
                        ++step_;
                        continue;
                    }
                }
                phase_ = Phase::Hold;
                phaseMs_ = nowMs;
                continue;
            case Phase::Hold:
                if (nowMs - phaseMs_ >= s.holdMs) {
                    inject_->clear();
                    phase_ = s.until ? Phase::Until : Phase::Gap;
                    phaseMs_ = nowMs;
                    continue;
                }
                if (!safe_now(gate)) return end_(End::Unsafe, nowMs);
                inject_->set(s.pad);  // a no-op after the step's first tick
                return;
            case Phase::Until: {
                if (!safe_now(gate)) return end_(End::Unsafe, nowMs);
                const std::optional<bool> met = condition_met(cm, *s.until);
                if (!met) return end_(End::Unreadable, nowMs);
                if (*met) {
                    phase_ = Phase::Gap;
                    phaseMs_ = nowMs;
                    continue;
                }
                if (nowMs - phaseMs_ >= s.untilTimeoutMs) return end_(End::Timeout, nowMs);
                return;
            }
            case Phase::Gap:
                if (nowMs - phaseMs_ >= s.gapMs) {
                    ++pressed_;
                    ++step_;
                    phase_ = Phase::Begin;
                    continue;
                }
                if (!safe_now(gate)) return end_(End::Unsafe, nowMs);
                return;
        }
    }
}

std::vector<std::string> SequenceScheduler::take_log() {
    std::vector<std::string> out;
    out.swap(log_);
    return out;
}

std::optional<std::string> SequenceScheduler::take_result() {
    std::optional<std::string> out;
    out.swap(result_);
    return out;
}

}  // namespace diptych::core
