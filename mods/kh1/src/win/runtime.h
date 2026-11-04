#pragma once
// The KH1 runtime: owns the WebSocket server and, on one thread, re-classifies the build, ticks
// the press scheduler at ~1 kHz, runs queued commands (at most 8 per 100 ms) and sends kh1.spike
// state at the subscribed rate (<= 10 Hz). The thread never takes the loader lock and never waits
// on the network thread (WsServer calls only take a mutex briefly).
#include <atomic>
#include <cstdint>
#include <functional>
#include <string>

#include "core/kh1_commands.h"
#include "core/kh1_fields.h"
#include "core/log_ring.h"
#include "core/version_gate.h"
#include "server/ws_server.h"

namespace diptych::win {

inline constexpr const char* kPluginVersion = "0.0.1-spike";

struct RuntimeOptions {
    std::string bind = server::kDefaultBind;
    uint16_t port = server::kDefaultPort;
    core::IMemory* mem = nullptr;           // the game image; required
    std::function<core::Build()> classify;  // reads the version bytes; required
    std::function<void(const std::string&)> log;  // any thread (the server's event log uses it too)
    // Startup lines kh1.log must always return (build, server listening, classification final).
    // Falls back to `log` when empty.
    std::function<void(const std::string&)> logPinned;
    const core::LogRing* ring = nullptr;    // what kh1.log returns
    // Ruling O: classify every classifyEveryMs until the build is recognised or classifyForMs pass.
    int classifyEveryMs = 500;
    int classifyForMs = 30000;
    int serverRetryMs = 5000;  // if the port is taken, try again this often
    int tickReportMs = 60000;  // how often the measured loop rate is logged
    // Tests only: called at the start of every step(); a throw here exercises the run() guard.
    std::function<void(int64_t)> onStep;
};

class Runtime {
public:
    static constexpr int kCommandsPerWindow = 8;
    static constexpr int kCommandWindowMs = 100;
    static constexpr int kRawWatchMs = 100;

    explicit Runtime(RuntimeOptions o);
    ~Runtime();  // stops the server; call from the thread that ran run()
    Runtime(const Runtime&) = delete;
    Runtime& operator=(const Runtime&) = delete;

    // Loops step() with a 1 ms sleep until stop is set (forever in the game). Never throws: if
    // anything escapes step(), it logs one line, stops the server and returns, and the runtime
    // stays inert (no memory access, no serving) from then on.
    void run(const std::atomic<bool>& stop);
    bool inert() const { return inert_.load(); }  // any thread
    // One iteration at nowMs (ms since the runtime started). Runtime thread only.
    void step(int64_t nowMs);

    uint16_t port() const { return server_.port(); }  // any thread; 0 until listening

private:
    void classify_(int64_t now);
    void serve_(int64_t now);
    void commands_(int64_t now);
    void state_(int64_t now);
    void log_(const std::string& s);
    void log_pinned_(const std::string& s);
    void flush_press_log_();
    void go_inert_() noexcept;
    bool supported_() const { return build_ == core::Build::Steam1002; }
    proto::Info info_() const;

    RuntimeOptions o_;
    core::DisablingMemory guarded_;  // state, safe checks and press ticks
    core::PressScheduler press_;
    core::RawWatch raw_;
    server::WsServer server_;

    std::atomic<bool> inert_{false};
    core::Build build_ = core::Build::Unknown;
    bool classifying_ = true;
    bool classifiedOnce_ = false;
    int64_t nextClassify_ = 0;
    bool serverUp_ = false;
    int serverFailures_ = 0;
    int64_t nextServerTry_ = 0;
    int64_t windowStart_ = 0;
    int windowUsed_ = 0;
    int64_t nextState_ = 0;
    int64_t seq_ = 0;
    int64_t nextRaw_ = 0;
    int64_t tickWindowStart_ = 0;
    int64_t ticks_ = 0;
};

}  // namespace diptych::win
