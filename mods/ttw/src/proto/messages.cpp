#include "proto/messages.h"

namespace ttwds::proto {

using nlohmann::json;

static std::string dump(const json& j) {
    return j.dump(-1, ' ', false, json::error_handler_t::replace);
}

template <typename T>
static json opt(const std::optional<T>& v) {
    return v ? json(*v) : json(nullptr);
}

std::string welcome_json(const ServerInfo& info) {
    return dump(json{{"type", "welcome"},
                     {"plugin", info.pluginVersion},
                     {"protocol", 0},
                     {"game", info.game},
                     {"xnvse", info.xnvse},
                     {"ttw", info.ttw},
                     {"features", json::array({"spike"})}});
}

std::string state_json(const SpikeSnapshot& s) {
    json colors = nullptr;
    if (s.colors) {
        static const char* names[5] = {"hudMain", "hudAlt", "terminal", "pipboy", "menu"};
        colors = json::object();
        for (size_t i = 0; i < 5; ++i)
            colors[names[i]] = json::array({(*s.colors)[i][0], (*s.colors)[i][1], (*s.colors)[i][2]});
    }
    json data = {{"frame", s.frame},
                 {"ms", s.ms},
                 {"saveLoaded", s.saveLoaded},
                 {"hp", opt(s.hp)},
                 {"hpMax", opt(s.hpMax)},
                 {"hardcore", opt(s.hardcore)},
                 {"bInDCWasteland", opt(s.inDC)},
                 {"bInNVWasteland", opt(s.inNV)},
                 {"worldspace", opt(s.worldspace)},
                 {"cell", opt(s.cell)},
                 {"interior", opt(s.interior)},
                 {"colors", colors}};
    return dump(json{{"type", "state"}, {"topic", "spike"}, {"data", data}});
}

std::string result_json(const std::string& id, const CommandResult& r) {
    json j = {{"type", "result"},
              {"id", id},
              {"ok", r.ok},
              {"error", r.error.empty() ? json(nullptr) : json(r.error)},
              {"data", r.data.is_null() ? json(nullptr) : r.data}};
    return dump(j);
}

std::string error_json(const std::string& message) {
    return dump(json{{"type", "error"}, {"message", message}});
}

}  // namespace ttwds::proto
