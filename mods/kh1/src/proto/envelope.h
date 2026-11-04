#pragma once
// KH1 protocol v0 envelope (protocol/kh1/PROTOCOL.md): what the network thread answers itself
// (hello, ping, subscribe, malformed frames) and what it hands to the runtime (commands).
#include <string>
#include <string_view>

#include <nlohmann/json.hpp>

namespace diptych::proto {

inline constexpr std::string_view kSpikeTopic = "kh1.spike";

// What `welcome` reports.
struct Info {
    std::string plugin;     // "0.0.1-spike"
    std::string gameId;     // "kh1"
    std::string gameBuild;  // "steam-1.0.0.2" | "egs-1.0.0.10" | "unknown"
    bool supported = false;
};

enum class Route { NetThread, MainThread };

struct Inbound {
    Route route = Route::NetThread;
    std::string reply;         // NetThread: the full reply frame; empty means send nothing
    std::string commandId;     // MainThread only
    std::string commandName;   // MainThread only
    nlohmann::json commandArgs;  // MainThread only; always an object
};

// One client's topics. v0 has a single topic, kh1.spike.
struct Subscription {
    bool spike = false;
    int rateHz = 5;
};

bool subscribed(const Subscription& sub, std::string_view topic);

struct CommandResult {
    bool ok = false;
    std::string error;    // empty -> null
    nlohmann::json data;  // null when absent; always null when !ok
};

std::string welcome_json(const Info& info);
std::string result_json(const std::string& id, const CommandResult& r);
std::string error_json(const std::string& message);

// Never throws. Unparseable -> error "invalid json"; not an object, no string type, or an unknown
// type -> error "unknown type"; a command without string id/name or an object args -> result
// "bad command". Commands go to the main queue unvalidated beyond that (execution is Task 4).
Inbound classify(std::string_view text, const Info& info, Subscription& sub);

}  // namespace diptych::proto
