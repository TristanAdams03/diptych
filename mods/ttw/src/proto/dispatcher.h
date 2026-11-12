#pragma once
#include <string>
#include <string_view>
#include "proto/game_iface.h"
#include "proto/messages.h"

namespace ttwds::proto {

enum class Route { NetThread, MainThread };

struct Inbound {
    Route route = Route::NetThread;
    std::string reply;        // fully built reply (NetThread); empty if none
    std::string commandId;    // MainThread only
    std::string commandName;  // MainThread only
};

struct Subscription {
    bool spike = false;
    int rateHz = 5;
};

// Never throws. Parse failure -> error "invalid json"; unknown type -> error "unknown type".
Inbound classify(std::string_view text, const ServerInfo& info, Subscription& sub);

// Runs on the game main thread; returns the `result` JSON frame.
std::string run_command(IGame& game, const std::string& id, const std::string& name);

}  // namespace ttwds::proto
