#pragma once
// Loopback WebSocket server (Windows only, x64). One network thread: non-blocking accept and
// select() with a 50 ms timeout, plain Winsock. The runtime thread only calls pollCommand,
// sendToAll, sendToSubscribers and hasSubscriber, which take a mutex briefly and never wait on I/O.
#include <atomic>
#include <cstdint>
#include <deque>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <nlohmann/json.hpp>

#include "proto/envelope.h"

namespace diptych::server {

inline constexpr const char* kDefaultBind = "127.0.0.1";
inline constexpr uint16_t kDefaultPort = 7970;

struct PendingCommand {
    std::string id, name;
    nlohmann::json args;  // always an object (classify rejects anything else)
};

class WsServer {
public:
    static constexpr size_t kMaxClients = 4;
    static constexpr size_t kMaxQueuedFrames = 256;  // per client; oldest dropped
    static constexpr size_t kMaxQueuedCommands = 64;

    WsServer();
    ~WsServer();
    WsServer(const WsServer&) = delete;
    WsServer& operator=(const WsServer&) = delete;

    // bind must be an IPv4 loopback literal (127.x.x.x). port 0 picks an ephemeral port.
    bool start(const std::string& bind, uint16_t port, proto::Info info);
    void stop();  // joins the network thread (within ~50 ms) and closes all sockets
    // Bound port; 0 when not running, or once the network thread hit an unexpected exception.
    // In that case it logs one line, closes every socket and stops serving (it never rethrows);
    // the server stays inert until stop().
    uint16_t port() const;

    bool pollCommand(PendingCommand& out);
    void sendToAll(std::string json);  // enqueue a text frame for every handshaken client
    // Enqueue a text frame only for open clients subscribed to `topic` (state frames). Every
    // subscriber gets it at the caller's rate; per-client rates are not honoured yet.
    void sendToSubscribers(const std::string& topic, std::string json);
    // True if any open client subscribes to `topic`; rateHz is then their max rate (else untouched).
    bool hasSubscriber(const std::string& topic, int& rateHz) const;

    // Replaces what `welcome` reports from now on. Any thread.
    void setInfo(proto::Info info);
    // Network-thread events ("client connected", ...). Set before start(); the callback runs
    // on the network thread and must not touch game memory.
    void setEventLog(std::function<void(const std::string&)> fn);

    struct Client;  // network-thread state + shared queue/subscription (see .cpp)

private:
    void run();       // the network thread: runLoop_() inside a catch-all
    void runLoop_();
    void failInert_();  // network thread, after an exception escaped runLoop_()
    void handleReadable(Client& c);
    void handleFrames(Client& c);
    void handleText(Client& c, const std::string& text);
    void beginClose(Client& c, uint16_t code);
    void flushQueue(Client& c);
    bool flushOut(Client& c);

    mutable std::mutex infoMx_;
    proto::Info info_;
    std::function<void(const std::string&)> eventLog_;
    std::atomic<bool> stop_{false};
    std::atomic<uint16_t> port_{0};
    std::thread thread_;
    uintptr_t listen_ = ~uintptr_t(0);  // SOCKET, kept opaque so callers need no winsock2.h
    bool wsaStarted_ = false;

    mutable std::mutex clientsMx_;  // guards clients_ membership, each Client::queue and ::sub
    std::vector<std::unique_ptr<Client>> clients_;

    std::mutex cmdMx_;
    std::deque<PendingCommand> commands_;
};

}  // namespace diptych::server
