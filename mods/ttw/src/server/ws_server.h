#pragma once
// Loopback WebSocket server (Windows only). One network thread: blocking accept and
// select() with a 50 ms timeout, plain Winsock. The game thread only calls pollCommand,
// sendToAll and hasSpikeSubscriber, which take a mutex briefly and never wait on I/O.
#include <atomic>
#include <cstdint>
#include <deque>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "proto/dispatcher.h"

namespace ttwds::server {

inline constexpr const char* kDefaultBind = "127.0.0.1";
inline constexpr uint16_t kDefaultPort = 7960;

struct PendingCommand {
    std::string id, name;
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
    bool start(const std::string& bind, uint16_t port, proto::ServerInfo info);
    void stop();  // joins the network thread (within ~50 ms) and closes all sockets
    uint16_t port() const;  // bound port, 0 when not running

    bool pollCommand(PendingCommand& out);
    void sendToAll(std::string json);  // enqueue a text frame for every handshaken client
    bool hasSpikeSubscriber(int& rateHz) const;  // max rateHz among spike subscribers

    // Replaces what `welcome` reports (e.g. ttw is only known at DeferredInit). Any thread.
    void setInfo(proto::ServerInfo info);
    // Network-thread events ("client connected", ...). Set before start(); the callback runs
    // on the network thread and must not touch game memory.
    void setEventLog(std::function<void(const std::string&)> fn);

    struct Client;  // network-thread state + shared queue/subscription (see .cpp)

private:
    void run();
    void handleReadable(Client& c);
    void handleFrames(Client& c);
    void handleText(Client& c, const std::string& text);
    void beginClose(Client& c, uint16_t code);
    void flushQueue(Client& c);
    bool flushOut(Client& c);

    mutable std::mutex infoMx_;
    proto::ServerInfo info_;
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

}  // namespace ttwds::server
