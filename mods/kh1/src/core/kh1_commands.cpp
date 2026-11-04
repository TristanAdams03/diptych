#include "core/kh1_commands.h"

#include <cstdio>
#include <limits>

#include "proto/envelope.h"

namespace diptych::core {

using nlohmann::json;

// ---- PressScheduler ---------------------------------------------------------------------------

void PressScheduler::start(int byteIndex, uint8_t mask, PadInject pad, int durationMs) {
    active_ = true;
    pad_ = pad;
    durationMs_ = durationMs;
    startMs_.reset();
    char buf[64];
    std::snprintf(buf, sizeof buf, " (xinput buttons 0x%04x lt %u rt %u)", static_cast<unsigned>(pad.buttons),
                  static_cast<unsigned>(pad.leftTrigger), static_cast<unsigned>(pad.rightTrigger));
    log_.push_back("press start byte " + std::to_string(byteIndex) + " mask " + std::to_string(mask) + " for " +
                   std::to_string(durationMs) + " ms" + buf);
}

void PressScheduler::end_(const std::string& why, int64_t nowMs) {
    if (inject_) inject_->clear();
    active_ = false;
    const int64_t held = startMs_ ? nowMs - *startMs_ : 0;
    startMs_.reset();
    log_.push_back("press end (" + why + ") after " + std::to_string(held) + " ms");
}

void PressScheduler::tick(IMemory& mem, int64_t nowMs) {
    if (!active_) return;
    if (!startMs_) startMs_ = nowMs;
    if (!inject_) return end_("aborted: xinput hook not installed", nowMs);
    if (nowMs - *startMs_ >= durationMs_) return end_("done", nowMs);
    if (!safe_now(mem)) return end_("aborted: not safe now", nowMs);
    inject_->set(pad_);  // a no-op after the first tick
}

std::vector<std::string> PressScheduler::take_log() {
    std::vector<std::string> out;
    out.swap(log_);
    return out;
}

// ---- commands ---------------------------------------------------------------------------------

namespace {
struct Fail {
    const char* error;
};

// An integer argument. Missing or not an integer -> "bad command"; outside [lo, hi] -> "out of range".
int64_t int_arg(const json& args, const char* key, int64_t lo, int64_t hi) {
    auto it = args.find(key);
    if (it == args.end() || !it->is_number_integer()) throw Fail{"bad command"};
    if (it->is_number_unsigned()) {
        const uint64_t u = it->get<uint64_t>();
        if (u > static_cast<uint64_t>(std::numeric_limits<int64_t>::max())) throw Fail{"out of range"};
    }
    const int64_t v = it->get<int64_t>();
    if (v < lo || v > hi) throw Fail{"out of range"};
    return v;
}

proto::CommandResult probe(IMemory& mem, const json& args) {
    const int64_t offset = int_arg(args, "offset", 0, std::numeric_limits<int64_t>::max());
    const int64_t length = int_arg(args, "length", 1, 256);
    const uint64_t size = mem.image_size();
    if (static_cast<uint64_t>(offset) >= size || static_cast<uint64_t>(length) > size - static_cast<uint64_t>(offset))
        throw Fail{"out of range"};
    std::optional<std::vector<uint8_t>> b;
    try {
        b = mem.bytes(static_cast<uintptr_t>(offset), static_cast<size_t>(length));
    } catch (...) {
    }
    if (!b || b->size() != static_cast<size_t>(length)) throw Fail{"out of range"};
    return {true, "", json{{"offset", offset}, {"hex", to_hex(*b)}}};
}

proto::CommandResult press_input(IMemory& gate, const json& args, PressScheduler& press) {
    const int64_t byteIndex = int_arg(args, "byteIndex", 0, 1);
    const int64_t mask = int_arg(args, "mask", 0, 255);
    const std::optional<PadInject> pad = ps2_to_xinput(static_cast<int>(byteIndex), static_cast<int>(mask));
    if (!pad) throw Fail{"out of range"};  // start (byte 0, 0x08) opens the pause menu
    const int64_t durationMs = int_arg(args, "durationMs", 16, 1000);
    if (press.active()) throw Fail{"busy"};
    if (!press.available() || !safe_now(gate)) throw Fail{"not safe now"};
    press.start(static_cast<int>(byteIndex), static_cast<uint8_t>(mask), *pad, static_cast<int>(durationMs));
    return {true, "", nullptr};
}

proto::CommandResult log_lines(const LogRing* log) {
    json lines = json::array();
    if (log) {
        for (auto& l : log->pinned()) lines.push_back(std::move(l));
        for (auto& l : log->last(kLogLines)) lines.push_back(std::move(l));
    }
    return {true, "", json{{"lines", std::move(lines)}}};
}
}  // namespace

std::string run_kh1_command(IMemory& mem, bool supported, const std::string& id, const std::string& name,
                            const json& args, PressScheduler& press, const LogRing* log, IMemory* gate) {
    proto::CommandResult r;
    try {
        const bool isProbe = name == "kh1.probe", isPress = name == "kh1.pressInput", isLog = name == "kh1.log";
        if (!isProbe && !isPress && !isLog) throw Fail{"unknown command"};
        if (!args.is_object()) throw Fail{"bad command"};
        if (isLog) {
            r = log_lines(log);
        } else {
            if (!supported) throw Fail{"unsupported build"};
            r = isProbe ? probe(mem, args) : press_input(gate ? *gate : mem, args, press);
        }
    } catch (const Fail& f) {
        r = {false, f.error, nullptr};
    } catch (...) {
        r = {false, "internal error", nullptr};
    }
    return proto::result_json(id, r);
}

}  // namespace diptych::core
