#pragma once
// ttw_dualscreen.ini: [Server] BindAddress, Port; [Debug] LogLevel. Portable, never throws.
#include <cstdint>
#include <string>
#include <string_view>
#include <vector>
#include "game/log.h"

namespace ttwds::game {

struct Config {
    std::string bind = "127.0.0.1";
    uint16_t port = 7960;
    LogLevel logLevel = LogLevel::Info;
    std::vector<std::string> warnings;  // one per rejected value; logged after the log opens
};

// Sections and keys are case-insensitive; ';' and '#' start comments; unknown keys ignored.
// BindAddress must be a 127.x.x.x literal; Port 1-65535 and not a reserved port.
Config parse_config(std::string_view text);

}  // namespace ttwds::game
