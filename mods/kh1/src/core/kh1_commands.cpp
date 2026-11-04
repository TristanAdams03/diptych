#include "core/kh1_commands.h"

#include <limits>

#include "proto/envelope.h"

namespace diptych::core {

using nlohmann::json;

// ---- PressScheduler ---------------------------------------------------------------------------

void PressScheduler::start(int byteIndex, uint8_t mask, int durationMs) {
    active_ = true;
    byteIndex_ = byteIndex;
    mask_ = mask;
    durationMs_ = durationMs;
    startMs_.reset();
    writes_ = 0;
    lastWritten_.reset();
    log_.push_back("press start byte " + std::to_string(byteIndex) + " mask " + std::to_string(mask) + " for " +
                   std::to_string(durationMs) + " ms");
}

void PressScheduler::end_(const std::string& why) {
    active_ = false;
    startMs_.reset();
    log_.push_back("press end (" + why + ") after " + std::to_string(writes_) + " writes");
}

void PressScheduler::release_(IMemory& mem) {
    if (!lastWritten_) return;
    const uint8_t last = *lastWritten_;
    lastWritten_.reset();
    const uintptr_t off = kInputOffset + static_cast<uintptr_t>(byteIndex_);
    std::optional<uint8_t> cur;
    try {
        cur = mem.u8(off);
    } catch (...) {
    }
    if (!cur || *cur != last) return;  // unreadable, or the game rewrote it: leave it alone
    bool ok = false;
    try {
        ok = mem.write_u8(off, static_cast<uint8_t>(*cur & ~mask_));
    } catch (...) {
    }
    if (!ok) {
        writesDisabled_ = true;
        log_.push_back("press release write faulted, presses disabled for this session");
        return;
    }
    log_.push_back("press release: cleared mask from the input byte");
}

void PressScheduler::tick(IMemory& mem, int64_t nowMs) {
    if (!active_) return;
    if (!startMs_) startMs_ = nowMs;
    if (nowMs - *startMs_ >= durationMs_) {
        release_(mem);
        return end_("done");
    }
    if (!safe_now(mem)) {
        release_(mem);
        return end_("aborted: not safe now");
    }
    const uintptr_t off = kInputOffset + static_cast<uintptr_t>(byteIndex_);
    std::optional<uint8_t> cur;
    try {
        cur = mem.u8(off);
    } catch (...) {
    }
    if (!cur) return end_("aborted: input byte unreadable");
    const uint8_t v = static_cast<uint8_t>(*cur | mask_);
    bool ok = false;
    try {
        ok = mem.write_u8(off, v);
    } catch (...) {
    }
    if (!ok) {
        writesDisabled_ = true;
        lastWritten_.reset();
        return end_("aborted: write faulted, presses disabled for this session");
    }
    lastWritten_ = v;
    ++writes_;
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
    const int64_t byteIndex = int_arg(args, "byteIndex", 0, 3);
    const int64_t mask = int_arg(args, "mask", 0, 255);
    const int64_t durationMs = int_arg(args, "durationMs", 16, 1000);
    if (press.active()) throw Fail{"busy"};
    if (press.writes_disabled() || !safe_now(gate)) throw Fail{"not safe now"};
    // The first tick reads this byte through the same gate; if that reader is off, say so now.
    std::optional<uint8_t> in;
    try {
        in = gate.u8(kInputOffset + static_cast<uintptr_t>(byteIndex));
    } catch (...) {
    }
    if (!in) throw Fail{"not safe now"};
    press.start(static_cast<int>(byteIndex), static_cast<uint8_t>(mask), static_cast<int>(durationMs));
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
