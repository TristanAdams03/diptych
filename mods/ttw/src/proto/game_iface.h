#pragma once
#include <array>
#include <cstdint>
#include <optional>
#include <string>
#include <nlohmann/json.hpp>

namespace ttwds::proto {

struct SpikeSnapshot {
    int64_t frame = 0;
    int64_t ms = 0;
    bool saveLoaded = false;
    std::optional<float> hp, hpMax;
    std::optional<bool> hardcore, interior;
    std::optional<int> inDC, inNV;
    std::optional<std::string> worldspace, cell;
    std::optional<std::array<std::array<int, 3>, 5>> colors;  // hudMain,hudAlt,terminal,pipboy,menu
};

struct CommandResult {
    bool ok = false;
    std::string error;
    nlohmann::json data;  // null when absent
};

class IGame {
public:
    virtual ~IGame() = default;
    virtual bool saveLoaded() = 0;
    // Why actions must not run right now ("in menu", "loading"), or nullptr when clear.
    // Read-only commands (dumpWorld) ignore it.
    virtual const char* blockedReason() = 0;
    virtual SpikeSnapshot snapshot(int64_t frame, int64_t ms) = 0;
    virtual CommandResult equipFirstWeapon() = 0;
    virtual CommandResult dumpWorld() = 0;
};

}  // namespace ttwds::proto
