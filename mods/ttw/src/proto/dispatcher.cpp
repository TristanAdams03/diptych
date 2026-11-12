#include "proto/dispatcher.h"
#include <algorithm>
#include "proto/messages.h"

namespace ttwds::proto {

using nlohmann::json;

static Inbound netReply(std::string reply) {
    Inbound in;
    in.route = Route::NetThread;
    in.reply = std::move(reply);
    return in;
}

static Inbound classifyParsed(const json& j, const ServerInfo& info, Subscription& sub) {
    if (!j.is_object()) return netReply(error_json("unknown type"));
    auto it = j.find("type");
    if (it == j.end() || !it->is_string()) return netReply(error_json("unknown type"));
    const std::string type = it->get<std::string>();

    if (type == "hello") return netReply(welcome_json(info));
    if (type == "ping") {
        json t = j.value("t", json(0));
        if (!t.is_number_integer()) t = 0;
        return netReply(json{{"type", "pong"}, {"t", t}}.dump());
    }
    if (type == "subscribe") {
        bool spike = false;
        auto tp = j.find("topics");
        if (tp != j.end() && tp->is_array())
            for (const auto& e : *tp)
                if (e.is_string() && e.get<std::string>() == "spike") spike = true;
        sub.spike = spike;
        auto r = j.find("rateHz");
        if (r != j.end() && r->is_number()) {
            double v = r->get<double>();
            sub.rateHz = static_cast<int>(std::clamp(v, 1.0, 10.0));
        }
        return netReply("");
    }
    if (type == "command") {
        auto id = j.find("id");
        auto name = j.find("name");
        if (id == j.end() || !id->is_string() || name == j.end() || !name->is_string())
            return netReply(error_json("bad command"));
        Inbound in;
        in.route = Route::MainThread;
        in.commandId = id->get<std::string>();
        in.commandName = name->get<std::string>();
        return in;
    }
    return netReply(error_json("unknown type"));
}

Inbound classify(std::string_view text, const ServerInfo& info, Subscription& sub) {
    try {
        json j = json::parse(text.begin(), text.end());
        return classifyParsed(j, info, sub);
    } catch (const json::parse_error&) {
        return netReply(error_json("invalid json"));
    } catch (...) {
        return netReply(error_json("invalid json"));
    }
}

std::string run_command(IGame& game, const std::string& id, const std::string& name) {
    CommandResult r;
    try {
        if (name != "spike.equipFirstWeapon" && name != "spike.dumpWorld") {
            r.error = "unknown command";
        } else if (!game.saveLoaded()) {
            r.error = "no save loaded";
        } else if (name == "spike.equipFirstWeapon") {
            if (const char* blocked = game.blockedReason())
                r.error = blocked;
            else
                r = game.equipFirstWeapon();
        } else {
            r = game.dumpWorld();
        }
    } catch (...) {
        r = CommandResult{};
        r.error = "internal error";
    }
    if (!r.ok && r.error.empty()) r.error = "failed";
    return result_json(id, r);
}

}  // namespace ttwds::proto
