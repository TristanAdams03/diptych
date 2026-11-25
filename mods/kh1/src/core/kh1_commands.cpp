#include "core/kh1_commands.h"

#include <limits>
#include <utility>

#include "core/command_args.h"
#include "proto/envelope.h"

namespace diptych::core {

using nlohmann::json;

// ---- commands ---------------------------------------------------------------------------------

namespace {
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

proto::CommandResult press_input(IMemory& gate, const json& args, SequenceScheduler& press) {
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

// Starts the sequence; its result is sent when it ends (press.take_result()).
void press_sequence(IMemory& mem, IMemory& gate, const std::string& id, const json& args, SequenceScheduler& press) {
    Sequence seq = parse_sequence(args, mem.image_size());
    if (press.active()) throw Fail{"busy"};
    if (!press.available() || !safe_now(gate)) throw Fail{"not safe now"};
    for (const MemCondition& c : seq.require) {
        const std::optional<bool> met = condition_met(mem, c);
        if (!met || !*met) throw Fail{"not safe now"};
    }
    press.start_sequence(id, std::move(seq));
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
                            const json& args, SequenceScheduler& press, const LogRing* log, IMemory* gate,
                            std::optional<WatchSet>* watch) {
    proto::CommandResult r;
    if (watch) watch->reset();
    try {
        const bool isProbe = name == "kh1.probe", isPress = name == "kh1.pressInput", isLog = name == "kh1.log",
                   isWatch = name == "kh1.watch", isSequence = name == "kh1.pressSequence";
        if (!isProbe && !isPress && !isLog && !isWatch && !isSequence) throw Fail{"unknown command"};
        if (!args.is_object()) throw Fail{"bad command"};
        if (isLog) {
            r = log_lines(log);
        } else {
            if (!supported) throw Fail{"unsupported build"};
            if (isSequence) {
                press_sequence(mem, gate ? *gate : mem, id, args, press);
                return "";  // deferred: the runtime sends press.take_result() when the sequence ends
            }
            if (isWatch) {
                if (!watch) throw Fail{"internal error"};  // the caller has nowhere to keep it
                WatchSet w = parse_watch(args, mem.image_size());
                const size_t count = w.regions.size();
                *watch = std::move(w);
                r = {true, "", json{{"count", count}}};
            } else {
                r = isProbe ? probe(mem, args) : press_input(gate ? *gate : mem, args, press);
            }
        }
    } catch (const Fail& f) {
        if (watch) watch->reset();
        r = {false, f.error, nullptr};
    } catch (...) {
        if (watch) watch->reset();
        r = {false, "internal error", nullptr};
    }
    return proto::result_json(id, r);
}

}  // namespace diptych::core
