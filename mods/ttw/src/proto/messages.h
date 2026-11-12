#pragma once
#include <string>
#include "proto/game_iface.h"

namespace ttwds::proto {

struct ServerInfo {
    std::string pluginVersion;
    std::string game;
    std::string xnvse;
    bool ttw = false;
};

// Reply to `hello`; also pushed unsolicited to all clients when ServerInfo changes (DeferredInit).
std::string welcome_json(const ServerInfo& info);

std::string state_json(const SpikeSnapshot& s);
std::string result_json(const std::string& id, const CommandResult& r);
std::string error_json(const std::string& message);

}  // namespace ttwds::proto
