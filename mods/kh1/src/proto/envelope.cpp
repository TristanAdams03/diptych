#include "proto/envelope.h"

#include <algorithm>

namespace diptych::proto {

using nlohmann::json;

namespace {

std::string dump(const json& j) { return j.dump(-1, ' ', false, json::error_handler_t::replace); }

Inbound netReply(std::string reply) {
    Inbound in;
    in.route = Route::NetThread;
    in.reply = std::move(reply);
    return in;
}

Inbound classifyParsed(const json& j, const Info& info, Subscription& sub) {
    if (!j.is_object()) return netReply(error_json("unknown type"));
    auto it = j.find("type");
    if (it == j.end() || !it->is_string()) return netReply(error_json("unknown type"));
    const std::string& type = it->get_ref<const std::string&>();

    if (type == "hello") return netReply(welcome_json(info));
    if (type == "ping") {
        json t = j.value("t", json(0));
        if (!t.is_number_integer()) t = 0;
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
        if (!idOk || name == j.end() || !name->is_string() || args == j.end() || !args->is_object())
            return netReply(result_json(idOk ? id->get<std::string>() : std::string(),
                                        {false, "bad command", nullptr}));
        Inbound in;
        in.route = Route::MainThread;
        in.commandId = id->get<std::string>();
        in.commandName = name->get<std::string>();
        in.commandArgs = *args;
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
                     {"error", r.error.empty() ? json(nullptr) : json(r.error)},
                     {"data", (!r.ok || r.data.is_null()) ? json(nullptr) : r.data}});
}

std::string error_json(const std::string& message) { return dump(json{{"type", "error"}, {"message", message}}); }

Inbound classify(std::string_view text, const Info& info, Subscription& sub) {
    try {
        const json j = json::parse(text.begin(), text.end());
        return classifyParsed(j, info, sub);
    } catch (const json::parse_error&) {
        return netReply(error_json("invalid json"));
    } catch (...) {
        return netReply(error_json("invalid json"));
    }
}

}  // namespace diptych::proto
