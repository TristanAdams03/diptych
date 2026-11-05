#include "win/runtime.h"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <optional>
#include <thread>
#include <unordered_map>
#include <vector>

#include <nlohmann/json.hpp>

#include "proto/envelope.h"

namespace diptych::win {

using nlohmann::json;

Runtime::Runtime(RuntimeOptions o)
    : o_(std::move(o)), guarded_(*o_.mem, [this](uintptr_t off) {
          char buf[96];
          std::snprintf(buf, sizeof buf, "reader at +0x%llx faulted; it stays off for this session",
                        static_cast<unsigned long long>(off));
          log_(buf);
      }) {
    if (o_.log) server_.setEventLog(o_.log);
}

Runtime::~Runtime() { server_.stop(); }

void Runtime::log_(const std::string& s) {
    if (o_.log) o_.log(s);
}

void Runtime::log_pinned_(const std::string& s) {
    if (o_.logPinned)
        o_.logPinned(s);
    else
        log_(s);
}

proto::Info Runtime::info_() const {
    return {kPluginVersion, "kh1", core::build_name(build_), supported_()};
}

void Runtime::run(const std::atomic<bool>& stop) {
    using Clock = std::chrono::steady_clock;
    try {
        const auto t0 = Clock::now();
        while (!stop.load() && !inert_.load()) {
            step(std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - t0).count());
            // A press accepted in this step is active already, so its first tick comes 1 ms later.
            const int ms = press_.active() ? o_.activeSleepMs : o_.idleSleepMs;
            std::this_thread::sleep_for(std::chrono::milliseconds(std::max(ms, 1)));
        }
    } catch (...) {
        go_inert_();
    }
}

// An exception escaping the runtime thread would terminate the game. Log once, stop serving and
// leave game memory alone for the rest of the session. Never rethrows.
void Runtime::go_inert_() noexcept {
    if (inert_.exchange(true)) return;
    try {
        log_pinned_("runtime stopped after an unexpected error; the mod is inert for this session");
    } catch (...) {
    }
    // Let go of any injected buttons: the hook stays installed, and nothing else would clear them.
    if (o_.inject) o_.inject->clear();
    watches_.clear();
    try {
        server_.stop();
    } catch (...) {
    }
    serverUp_ = false;
}

void Runtime::step(int64_t now) {
    if (inert_.load()) return;
    if (o_.onStep) o_.onStep(now);
    classify_(now);
    serve_(now);
    if (supported_()) {
        hook_();
        press_.tick(guarded_, now);
        flush_press_log_();
    }
    commands_(now);
    state_(now);
    if (supported_() && now >= nextRaw_) {
        nextRaw_ = now + kRawWatchMs;
        for (auto& l : raw_.poll(guarded_)) log_(l);
    }
    ++ticks_;
    if (now - tickWindowStart_ >= o_.tickReportMs) {
        const double hz = 1000.0 * static_cast<double>(ticks_) / static_cast<double>(now - tickWindowStart_);
        char buf[96];
        std::snprintf(buf, sizeof buf, "runtime loop rate %.0f Hz over the last %lld s", hz,
                      static_cast<long long>((now - tickWindowStart_) / 1000));
        log_(buf);
        tickWindowStart_ = now;
        ticks_ = 0;
    }
}

// Ruling O: the version bytes may not be final at attach, so look again every 500 ms until the
// build is recognised or 30 s pass. Every change is pushed to connected clients as a welcome.
void Runtime::classify_(int64_t now) {
    if (!classifying_ || now < nextClassify_) return;
    nextClassify_ = now + o_.classifyEveryMs;
    core::Build b = core::Build::Unknown;
    try {
        b = o_.classify ? o_.classify() : core::Build::Unknown;
    } catch (...) {
    }
    if (!classifiedOnce_ || b != build_) {
        classifiedOnce_ = true;
        build_ = b;
        log_pinned_(std::string("build ") + core::build_name(b) + (supported_() ? " supported" : " not supported"));
        if (serverUp_) {
            const proto::Info info = info_();
            server_.setInfo(info);
            server_.sendToAll(proto::welcome_json(info));
        }
    }
    if (b != core::Build::Unknown || now >= o_.classifyForMs) {
        classifying_ = false;
        log_pinned_(std::string("build classification final: ") + core::build_name(b) +
                    (o_.moduleId.empty() ? "" : " (" + o_.moduleId + ")"));
    }
}

void Runtime::serve_(int64_t now) {
    if (serverUp_ || now < nextServerTry_) return;
    nextServerTry_ = now + o_.serverRetryMs;
    bool started = false;
    try {
        started = server_.start(o_.bind, o_.port, info_());
    } catch (...) {  // e.g. bad_alloc building the info; start() cleans up its own sockets
        try {
            server_.stop();
        } catch (...) {
        }
    }
    if (started) {
        serverUp_ = true;
        log_pinned_("server listening on " + o_.bind + ":" + std::to_string(server_.port()));
        return;
    }
    // Log the first failure and then about once a minute.
    if (serverFailures_ % 12 == 0) {
        const auto line = "server could not listen on " + o_.bind + ":" + std::to_string(o_.port) + "; retrying every " +
             std::to_string(o_.serverRetryMs / 1000) + " s";
        if (serverFailures_ == 0)
            log_pinned_(line);  // the first failure is a startup line too
        else
            log_(line);
    }
    ++serverFailures_;
}

void Runtime::commands_(int64_t now) {
    if (!serverUp_) return;
    if (now - windowStart_ >= kCommandWindowMs) {
        windowStart_ = now;
        windowUsed_ = 0;
    }
    server::PendingCommand cmd;
    while (windowUsed_ < kCommandsPerWindow && server_.pollCommand(cmd)) {
        ++windowUsed_;
        // Probes read raw memory: a faulting probe must not turn off a state reader. The press
        // safety check reads guarded_, like the press ticks, so a disabled gate reader refuses it.
        // Watch reads, like probes, go to raw memory (SEH-guarded, image-bounded) at state time.
        std::optional<core::WatchSet> watch;
        std::string reply = core::run_kh1_command(*o_.mem, supported_(), cmd.id, cmd.name, cmd.args, press_, o_.ring,
                                                  &guarded_, &watch);
        if (watch) {
            if (watch->regions.empty())
                watches_.erase(cmd.client);
            else
                watches_[cmd.client] = std::move(*watch);
            prune_watches_();  // bounds the map even when nobody subscribes
        }
        flush_press_log_();
        if (cmd.name != "kh1.log") {
            const bool ok = reply.find("\"ok\":true") != std::string::npos;
            log_("command " + cmd.name.substr(0, 64) + (ok ? " ok" : " failed: " + reply.substr(0, 160)));
        }
        server_.sendToAll(std::move(reply));
    }
}

void Runtime::state_(int64_t now) {
    if (!serverUp_) return;
    int rate = 0;
    if (!server_.hasSubscriber(std::string(proto::kSpikeTopic), rate)) {
        nextState_ = now;  // the first frame goes out as soon as someone subscribes
        return;
    }
    if (now < nextState_) return;
    rate = std::clamp(rate, 1, 10);
    nextState_ = now + 1000 / rate;
    const std::string topic(proto::kSpikeTopic);
    json data = core::kh1_spike_state(guarded_, seq_++, now, supported_());
    std::unordered_map<server::ClientId, std::string> perClient;
    if (!watches_.empty()) {
        prune_watches_();
        if (supported_()) {
            for (const auto& [id, w] : watches_) {
                json d = data;
                // Raw memory, not guarded_: a faulting watch region must never disable a safety-gate reader.
                d["regions"] = core::read_watch(*o_.mem, w);
                perClient.emplace(id, core::spike_state_frame(d));
            }
        }
    }
    server_.sendToSubscribers(topic, core::spike_state_frame(data), perClient);
}

// Forgets the watches of clients that disconnected or are closing (ids are never reused).
void Runtime::prune_watches_() {
    if (watches_.empty()) return;
    const std::vector<server::ClientId> open = server_.openClients();
    std::erase_if(watches_, [&](const auto& kv) { return std::find(open.begin(), open.end(), kv.first) == open.end(); });
}

void Runtime::hook_() {
    if (!hookTried_) {
        hookTried_ = true;
        std::string line;
        bool ok = false;
        try {
            if (o_.installInputHook) ok = o_.installInputHook(line);
            else line = "xinput hook not available; presses disabled";
        } catch (...) {
            ok = false;
            line = "xinput hook install threw; presses disabled";
        }
        log_pinned_(line);
        if (ok && o_.inject) press_.attach(o_.inject);
    }
    if (o_.inject && o_.inject->take_no_pad_once())
        log_("xinput controller 0 not connected during a press; nothing injected (logged once)");
}

void Runtime::flush_press_log_() {
    for (auto& l : press_.take_log()) log_(l);
}

}  // namespace diptych::win
