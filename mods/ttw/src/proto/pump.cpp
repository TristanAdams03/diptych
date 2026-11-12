#include "proto/pump.h"
#include <algorithm>
#include "proto/dispatcher.h"
#include "proto/messages.h"

namespace ttwds::proto {

void FramePump::tick(IGame& game, ITransport& net, int64_t ms, const CommandObserver& onCommand) {
    ++frame_;
    std::string id, name;
    for (int i = 0; i < kMaxCommandsPerFrame && net.pollCommand(id, name); ++i) {
        std::string reply = run_command(game, id, name);
        if (onCommand) onCommand(id, name, reply);
        net.sendToAll(std::move(reply));
    }
    int rateHz = 0;
    if (!net.hasSpikeSubscriber(rateHz)) return;
    const int64_t period = 1000 / std::clamp(rateHz, 1, 10);
    if (pushed_ && ms - lastPush_ < period) return;
    pushed_ = true;
    lastPush_ = ms;
    net.sendToAll(state_json(game.snapshot(frame_, ms)));
}

}  // namespace ttwds::proto
