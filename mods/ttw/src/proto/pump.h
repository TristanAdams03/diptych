#pragma once
// The once-per-frame work of the kMessage_MainGameLoop handler, kept engine-free so it is
// unit-tested on Linux. Runs on the game main thread only.
#include <cstdint>
#include <functional>
#include <string>
#include "proto/game_iface.h"

namespace ttwds::proto {

// What the pump needs from the network side (WsServer adapts to this).
class ITransport {
public:
    virtual ~ITransport() = default;
    virtual bool pollCommand(std::string& id, std::string& name) = 0;
    virtual void sendToAll(std::string text) = 0;
    virtual bool hasSpikeSubscriber(int& rateHz) = 0;
};

// Called after each command with (id, name, result frame).
using CommandObserver = std::function<void(const std::string&, const std::string&, const std::string&)>;

class FramePump {
public:
    static constexpr int kMaxCommandsPerFrame = 8;

    // 1. frame++  2. drain up to 8 commands -> run_command -> sendToAll
    // 3. if a spike subscriber exists and ms - lastPush >= 1000/rateHz: push state_json(snapshot).
    void tick(IGame& game, ITransport& net, int64_t ms, const CommandObserver& onCommand);
    int64_t frame() const { return frame_; }
    // Next tick with a subscriber pushes immediately (used after a load).
    void resetPushClock() { pushed_ = false; }

private:
    int64_t frame_ = 0;
    int64_t lastPush_ = 0;
    bool pushed_ = false;
};

}  // namespace ttwds::proto
