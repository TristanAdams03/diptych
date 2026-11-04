#include "proto/envelope.h"

#include <algorithm>
#include <utility>

namespace diptych::proto {

using nlohmann::json;

namespace {

// Client JSON is bounded before anything recursive (copy, dump, ==) touches it: nlohmann parses
// iteratively but copies and serializes recursively, so a ~20 KB frame nested 10,000 deep would
// overflow the network thread's 1 MB stack.
constexpr int kMaxDepth = 32;                // nested objects/arrays per frame
constexpr size_t kMaxArgsBytes = 64 * 1024;  // serialized command args

struct TooDeep {};

// nlohmann reports a container start at the number of containers around it, so a start at
// kMaxDepth would open level kMaxDepth + 1.
bool depthGuard(int depth, json::parse_event_t event, json&) {
    if ((event == json::parse_event_t::object_start || event == json::parse_event_t::array_start) &&
        depth >= kMaxDepth)
        throw TooDeep{};
    return true;
}

std::string dump(const json& j) { return j.dump(-1, ' ', false, json::error_handler_t::replace); }

Inbound netReply(std::string reply) {
    Inbound in;
    in.route = Route::NetThread;
    in.reply = std::move(reply);
    return in;
}

// Takes j by non-const reference so command args can be moved out, never deep-copied.
Inbound classifyParsed(json& j, const Info& info, Subscription& sub) {
    if (!j.is_object()) return netReply(error_json("unknown type"));
    auto it = j.find("type");
    if (it == j.end() || !it->is_string()) return netReply(error_json("unknown type"));
    const std::string& type = it->get_ref<const std::string&>();

    if (type == "hello") return netReply(welcome_json(info));
    if (type == "ping") {
        json t = 0;
        auto tv = j.find("t");
        if (tv != j.end() && tv->is_number_integer()) t = *tv;  // a scalar: the copy is flat
        return netReply(dump(json{{"type", "pong"}, {"t", t}}));
    }
    if (type == "subscribe") {
        bool spike = false;
        auto tp = j.find("topics");
        if (tp != j.end() && tp->is_array())
            for (const auto& e : *tp)
                if (e.is_string() && e.get_ref<const std::string&>() == kSpikeTopic) spike = true;
        sub.spike = spike;
        auto r = j.find("rateHz");
        if (r != j.end() && r->is_number()) {
            const double v = r->get<double>();
            sub.rateHz = static_cast<int>(std::clamp(v, 1.0, 10.0));
        }
        return netReply("");  // no ack
    }
    if (type == "command") {
        auto id = j.find("id");
        auto name = j.find("name");
        auto args = j.find("args");
        const bool idOk = id != j.end() && id->is_string();
        auto bad = [&] {
            return netReply(result_json(idOk ? id->get<std::string>() : std::string(),
                                        {false, "bad command", nullptr}));
        };
        if (!idOk || name == j.end() || !name->is_string() || args == j.end() || !args->is_object())
            return bad();
        if (dump(*args).size() > kMaxArgsBytes) return bad();
        Inbound in;
        in.route = Route::MainThread;
        in.commandId = id->get<std::string>();
        in.commandName = name->get<std::string>();
        in.commandArgs = std::move(*args);
        return in;
    }
    return netReply(error_json("unknown type"));
}

}  // namespace

bool subscribed(const Subscription& sub, std::string_view topic) { return topic == kSpikeTopic && sub.spike; }

std::string welcome_json(const Info& info) {
    return dump(json{{"type", "welcome"},
                     {"plugin", info.plugin},
                     {"protocol", 0},
                     {"gameId", info.gameId},
                     {"gameBuild", info.gameBuild},
                     {"supported", info.supported},
                     {"features", json::array({"spike"})}});
}

std::string result_json(const std::string& id, const CommandResult& r) {
    return dump(json{{"type", "result"},
                     {"id", id},
                     {"ok", r.ok},
                     {"error", !r.error.empty() ? json(r.error) : r.ok ? json(nullptr) : json("internal error")},
                     {"data", (!r.ok || r.data.is_null()) ? json(nullptr) : r.data}});
}

std::string error_json(const std::string& message) { return dump(json{{"type", "error"}, {"message", message}}); }

Inbound classify(std::string_view text, const Info& info, Subscription& sub) {
    try {
        json j = json::parse(text.begin(), text.end(), depthGuard);
        return classifyParsed(j, info, sub);
    } catch (const TooDeep&) {
        return netReply(error_json("invalid json"));
    } catch (const json::parse_error&) {
        return netReply(error_json("invalid json"));
    } catch (...) {
        return netReply(error_json("invalid json"));
    }
}

}  // namespace diptych::proto
