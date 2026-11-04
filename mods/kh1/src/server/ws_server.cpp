#include "server/ws_server.h"

#include <winsock2.h>
#include <ws2tcpip.h>

#include <algorithm>
#include <chrono>
#include <iterator>

#include "net/ws_frame.h"
#include "net/ws_handshake.h"

namespace diptych::server {

namespace {
using Clock = std::chrono::steady_clock;

constexpr size_t kMaxHandshakeBytes = 8 * 1024;
constexpr auto kHandshakeTimeout = std::chrono::seconds(5);
constexpr auto kLingerTimeout = std::chrono::seconds(2);
constexpr size_t kMaxMessage = 1 << 20;
constexpr long kSelectUsec = 50 * 1000;
constexpr size_t kOutLowWater = 64 * 1024;       // pull queued frames only below this
constexpr size_t kOutHardCap = 2 * 1024 * 1024;  // beyond this the client is closed (1008)
constexpr auto kWriteStallTimeout = std::chrono::seconds(5);

constexpr uint8_t kOpCont = 0x0, kOpText = 0x1, kOpBin = 0x2, kOpClose = 0x8, kOpPing = 0x9,
                  kOpPong = 0xA;

const char kForbidden[] =
    "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";

SOCKET sock(uintptr_t s) { return static_cast<SOCKET>(s); }

std::string closePayload(uint16_t code) {
    std::string p;
    p.push_back(static_cast<char>(code >> 8));
    p.push_back(static_cast<char>(code & 0xFF));
    return p;
}

bool setNonBlocking(SOCKET s) {
    u_long on = 1;
    return ioctlsocket(s, FIONBIO, &on) == 0;
}

// The game may spawn processes; never let them inherit our sockets.
void noInherit(SOCKET s) { SetHandleInformation(reinterpret_cast<HANDLE>(s), HANDLE_FLAG_INHERIT, 0); }

SOCKET makeListenSocket() {
    SOCKET s = WSASocketW(AF_INET, SOCK_STREAM, IPPROTO_TCP, nullptr, 0, WSA_FLAG_NO_HANDLE_INHERIT);
    if (s == INVALID_SOCKET)  // flag unsupported (pre-7 SP1 or an old Wine): fall back
        s = ::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (s != INVALID_SOCKET) noInherit(s);
    return s;
}
}  // namespace

struct WsServer::Client {
    // Network thread only.
    SOCKET s = INVALID_SOCKET;
    std::string in;        // unparsed inbound bytes
    std::string out;       // encoded bytes not yet written to the socket
    std::string fragment;  // reassembly of a fragmented text message
    bool inFragment = false;
    bool closing = false;  // close/403 queued: flush, then half-close and drain
    bool shutSent = false;
    bool dead = false;
    Clock::time_point since = Clock::now();  // accept time, or start of closing
    Clock::time_point lastWrite = Clock::now();  // last send progress, or last time out was empty

    // Guarded by WsServer::clientsMx_.
    bool open = false;  // handshaken and not closing: eligible for sendToAll/subscription
    proto::Subscription sub;
    std::deque<std::string> queue;
};

WsServer::WsServer() = default;
WsServer::~WsServer() { stop(); }

bool WsServer::start(const std::string& bind, uint16_t port, proto::Info info) {
    if (thread_.joinable()) return false;

    in_addr addr{};
    if (inet_pton(AF_INET, bind.c_str(), &addr) != 1) return false;
    if ((ntohl(addr.s_addr) >> 24) != 127) return false;  // loopback only

    WSADATA wsa;
    if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) return false;
    wsaStarted_ = true;

    SOCKET ls = makeListenSocket();
    if (ls == INVALID_SOCKET) {
        stop();
        return false;
    }
    BOOL excl = TRUE;  // nobody else may bind this port while we hold it
    setsockopt(ls, SOL_SOCKET, SO_EXCLUSIVEADDRUSE, reinterpret_cast<const char*>(&excl), sizeof excl);
    sockaddr_in sa{};
    sa.sin_family = AF_INET;
    sa.sin_addr = addr;
    sa.sin_port = htons(port);
    int salen = sizeof sa;
    if (::bind(ls, reinterpret_cast<sockaddr*>(&sa), sizeof sa) != 0 || ::listen(ls, 8) != 0 ||
        getsockname(ls, reinterpret_cast<sockaddr*>(&sa), &salen) != 0 || !setNonBlocking(ls)) {
        closesocket(ls);
        stop();
        return false;
    }
    listen_ = static_cast<uintptr_t>(ls);
    setInfo(std::move(info));
    stop_ = false;
    port_ = ntohs(sa.sin_port);
    thread_ = std::thread([this] { run(); });
    return true;
}

void WsServer::stop() {
    stop_ = true;
    if (thread_.joinable()) thread_.join();
    std::vector<std::unique_ptr<Client>> gone;
    {
        std::lock_guard<std::mutex> lk(clientsMx_);
        gone.swap(clients_);
    }
    for (auto& c : gone)
        if (c->s != INVALID_SOCKET) closesocket(c->s);
    if (listen_ != ~uintptr_t(0)) {
        closesocket(sock(listen_));
        listen_ = ~uintptr_t(0);
    }
    {
        std::lock_guard<std::mutex> lk(cmdMx_);
        commands_.clear();
    }
    port_ = 0;
    if (wsaStarted_) {
        WSACleanup();
        wsaStarted_ = false;
    }
}

uint16_t WsServer::port() const { return port_; }

bool WsServer::pollCommand(PendingCommand& out) {
    std::lock_guard<std::mutex> lk(cmdMx_);
    if (commands_.empty()) return false;
    out = std::move(commands_.front());
    commands_.pop_front();
    return true;
}

void WsServer::sendToAll(std::string json) {
    const std::string frame = net::ws_encode(kOpText, json);
    std::lock_guard<std::mutex> lk(clientsMx_);
    for (auto& c : clients_) {
        if (!c->open) continue;
        c->queue.push_back(frame);
        while (c->queue.size() > kMaxQueuedFrames) c->queue.pop_front();
    }
}

void WsServer::setInfo(proto::Info info) {
    std::lock_guard<std::mutex> lk(infoMx_);
    info_ = std::move(info);
}

void WsServer::setEventLog(std::function<void(const std::string&)> fn) { eventLog_ = std::move(fn); }

bool WsServer::hasSubscriber(const std::string& topic, int& rateHz) const {
    std::lock_guard<std::mutex> lk(clientsMx_);
    bool any = false;
    int best = 0;
    for (const auto& c : clients_) {
        if (!c->open || !proto::subscribed(c->sub, topic)) continue;
        any = true;
        best = std::max(best, c->sub.rateHz);
    }
    if (any) rateHz = best;
    return any;
}

// ---- network thread -------------------------------------------------------------------------

// Moves queued frames into the write buffer while it is below the low-water mark, so the
// bounded drop-oldest deque (not c.out) absorbs a slow reader.
void WsServer::flushQueue(Client& c) {
    std::lock_guard<std::mutex> lk(clientsMx_);
    while (!c.queue.empty() && c.out.size() < kOutLowWater) {
        c.out += c.queue.front();
        c.queue.pop_front();
    }
}

// Writes as much of c.out as the socket takes. False if the connection is broken.
bool WsServer::flushOut(Client& c) {
    while (!c.out.empty()) {
        const int n = ::send(c.s, c.out.data(), static_cast<int>(std::min<size_t>(c.out.size(), 1 << 16)), 0);
        if (n > 0) {
            c.out.erase(0, static_cast<size_t>(n));
            c.lastWrite = Clock::now();
            continue;
        }
        return n < 0 && WSAGetLastError() == WSAEWOULDBLOCK;
    }
    return true;
}

void WsServer::beginClose(Client& c, uint16_t code) {
    if (c.closing) return;
    c.out += net::ws_encode(kOpClose, closePayload(code));
    c.closing = true;
    c.since = Clock::now();
    std::lock_guard<std::mutex> lk(clientsMx_);
    c.open = false;
    c.queue.clear();
}

void WsServer::handleText(Client& c, const std::string& text) {
    proto::Subscription sub;
    {
        std::lock_guard<std::mutex> lk(clientsMx_);
        sub = c.sub;
    }
    proto::Info info;
    {
        std::lock_guard<std::mutex> lk(infoMx_);
        info = info_;
    }
    proto::Inbound in = proto::classify(text, info, sub);
    {
        std::lock_guard<std::mutex> lk(clientsMx_);
        c.sub = sub;
    }
    if (in.route == proto::Route::MainThread) {
        bool full = false;
        {
            std::lock_guard<std::mutex> lk(cmdMx_);
            full = commands_.size() >= kMaxQueuedCommands;
            if (!full) commands_.push_back({in.commandId, in.commandName, std::move(in.commandArgs)});
        }
        if (!full) return;
        in.reply = proto::result_json(in.commandId, {false, "busy", nullptr});
    }
    if (in.reply.empty()) return;  // e.g. subscribe: no ack, so send nothing
    c.out += net::ws_encode(kOpText, in.reply);
}

void WsServer::handleFrames(Client& c) {
    net::WsFrame f;
    while (!c.closing) {
        const net::DecodeStatus st = net::ws_decode(c.in, f, kMaxMessage);
        if (st == net::DecodeStatus::NeedMore) return;
        if (st == net::DecodeStatus::ProtocolError) return beginClose(c, 1002);
        if (st == net::DecodeStatus::TooBig) return beginClose(c, 1009);

        switch (f.opcode) {
        case kOpPing:
            c.out += net::ws_encode(kOpPong, f.payload);
            break;
        case kOpPong:
            break;
        case kOpClose: {
            uint16_t code = 1000;
            if (f.payload.size() >= 2)
                code = static_cast<uint16_t>((static_cast<uint8_t>(f.payload[0]) << 8) |
                                             static_cast<uint8_t>(f.payload[1]));
            if (f.payload.size() == 1 || code < 1000 || code == 1005 || code == 1006 || code == 1015)
                code = 1002;
            beginClose(c, code);
            return;
        }
        case kOpText:
            if (c.inFragment) return beginClose(c, 1002);
            if (f.fin) {
                handleText(c, f.payload);
            } else {
                c.inFragment = true;
                c.fragment = std::move(f.payload);
            }
            break;
        case kOpCont:
            if (!c.inFragment) return beginClose(c, 1002);
            if (c.fragment.size() + f.payload.size() > kMaxMessage) return beginClose(c, 1009);
            c.fragment += f.payload;
            if (f.fin) {
                c.inFragment = false;
                std::string msg = std::move(c.fragment);
                c.fragment.clear();
                handleText(c, msg);
            }
            break;
        case kOpBin:
        default:
            return beginClose(c, 1002);  // protocol messages are text only
        }
    }
}

void WsServer::handleReadable(Client& c) {
    char buf[16 * 1024];
    const int n = ::recv(c.s, buf, static_cast<int>(sizeof buf), 0);
    if (n == 0 || (n < 0 && WSAGetLastError() != WSAEWOULDBLOCK)) {
        c.dead = true;
        return;
    }
    if (n < 0) return;
    if (c.closing) return;  // draining after close/403: discard
    c.in.append(buf, static_cast<size_t>(n));

    bool handshaken = false;
    {
        std::lock_guard<std::mutex> lk(clientsMx_);
        handshaken = c.open;
    }
    if (!handshaken) {
        const size_t end = c.in.find("\r\n\r\n");
        if (end == std::string::npos) {
            if (c.in.size() > kMaxHandshakeBytes) c.dead = true;
            return;
        }
        if (end + 4 > kMaxHandshakeBytes) {
            c.dead = true;
            return;
        }
        auto resp = net::ws_accept_response(std::string_view(c.in).substr(0, end + 4));
        if (!resp) {
            c.out += kForbidden;
            c.closing = true;
            c.since = Clock::now();
            c.in.clear();
            return;
        }
        c.in.erase(0, end + 4);
        c.out += *resp;
        size_t open = 0;
        {
            std::lock_guard<std::mutex> lk(clientsMx_);
            c.open = true;
            for (const auto& o : clients_) open += o->open ? 1 : 0;
        }
        if (eventLog_) eventLog_("client connected (" + std::to_string(open) + " open)");
    }
    handleFrames(c);
}

void WsServer::run() {
    const SOCKET ls = sock(listen_);
    while (!stop_) {
        // Move queued outbound frames and drop finished clients.
        const auto now = Clock::now();
        for (auto& cp : clients_) {
            Client& c = *cp;
            if (c.dead) continue;
            bool open = false;
            {
                std::lock_guard<std::mutex> lk(clientsMx_);
                open = c.open;
            }
            if (c.out.empty()) c.lastWrite = now;  // stall clock runs only while bytes are pending
            if (open) flushQueue(c);
            if (!open && !c.closing && now - c.since > kHandshakeTimeout) c.dead = true;
            if (c.closing && now - c.since > kLingerTimeout) c.dead = true;
            // Slow or stalled reader: close with 1008; if even that can't drain, the linger
            // timeout (or a second trip) kills it.
            const bool stalled = !c.out.empty() && now - c.lastWrite > kWriteStallTimeout;
            if (!c.closing && (c.out.size() > kOutHardCap || stalled))
                beginClose(c, 1008);
            else if (c.closing && stalled)
                c.dead = true;
        }
        std::vector<std::unique_ptr<Client>> gone;
        {
            std::lock_guard<std::mutex> lk(clientsMx_);
            auto it = std::stable_partition(clients_.begin(), clients_.end(),
                                            [](const std::unique_ptr<Client>& c) { return !c->dead; });
            gone.assign(std::make_move_iterator(it), std::make_move_iterator(clients_.end()));
            clients_.erase(it, clients_.end());
        }
        for (auto& c : gone) closesocket(c->s);
        gone.clear();

        fd_set rd, wr;
        FD_ZERO(&rd);
        FD_ZERO(&wr);
        FD_SET(ls, &rd);
        for (auto& c : clients_) {
            FD_SET(c->s, &rd);
            if (!c->out.empty()) FD_SET(c->s, &wr);
        }
        timeval tv{0, kSelectUsec};
        const int ready = ::select(0, &rd, &wr, nullptr, &tv);
        if (ready == SOCKET_ERROR) {
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
            continue;
        }
        if (ready == 0) continue;

        if (FD_ISSET(ls, &rd)) {
            const SOCKET s = ::accept(ls, nullptr, nullptr);  // non-blocking; WSAEWOULDBLOCK -> skip
            if (s != INVALID_SOCKET) {
                noInherit(s);
                if (clients_.size() >= kMaxClients || !setNonBlocking(s)) {
                    closesocket(s);
                } else {
                    BOOL nodelay = TRUE;
                    setsockopt(s, IPPROTO_TCP, TCP_NODELAY, reinterpret_cast<const char*>(&nodelay),
                               sizeof nodelay);
                    auto c = std::make_unique<Client>();
                    c->s = s;
                    std::lock_guard<std::mutex> lk(clientsMx_);
                    clients_.push_back(std::move(c));
                }
            }
        }

        for (auto& cp : clients_) {
            Client& c = *cp;
            if (c.s == INVALID_SOCKET || c.dead) continue;
            if (FD_ISSET(c.s, &rd)) handleReadable(c);
            if (c.dead) continue;
            if (!flushOut(c)) {
                c.dead = true;
                continue;
            }
            if (c.closing && c.out.empty() && !c.shutSent) {
                shutdown(c.s, SD_SEND);  // peer sees our close, then EOF; we drain until it hangs up
                c.shutSent = true;
            }
        }
    }
}

}  // namespace diptych::server
