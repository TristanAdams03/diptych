// Windows-only: drives WsServer with a raw Winsock client on an ephemeral loopback port.
#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest/doctest.h>

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <winsock2.h>
#include <ws2tcpip.h>

#include <chrono>
#include <fstream>
#include <sstream>
#include <string>
#include <mutex>
#include <thread>
#include <vector>

#include "proto/envelope.h"
#include "server/ws_server.h"

using namespace diptych;
using Clock = std::chrono::steady_clock;

namespace {

proto::Info testInfo() { return {"0.0.0-test", "kh1", "steam-1.0.0.2", true}; }

std::string fixture(const char* name) {
    std::ifstream f(std::string(FIXTURES_DIR) + "/" + name, std::ios::binary);
    std::stringstream ss;
    ss << f.rdbuf();
    std::string s = ss.str();
    while (!s.empty() && (s.back() == '\n' || s.back() == '\r')) s.pop_back();
    return s;
}

struct Wsa {
    Wsa() { WSADATA d; WSAStartup(MAKEWORD(2, 2), &d); }
    ~Wsa() { WSACleanup(); }
};

struct TestClient {
    SOCKET s = INVALID_SOCKET;
    std::string buf;

    // rcvbuf > 0 shrinks the receive window (set before connect) so server writes back up fast.
    explicit TestClient(uint16_t port, int rcvbuf = 0) {
        s = ::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
        if (rcvbuf > 0)
            setsockopt(s, SOL_SOCKET, SO_RCVBUF, reinterpret_cast<const char*>(&rcvbuf), sizeof rcvbuf);
        DWORD tmo = 3000;
        setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&tmo), sizeof tmo);
        sockaddr_in a{};
        a.sin_family = AF_INET;
        a.sin_port = htons(port);
        inet_pton(AF_INET, "127.0.0.1", &a.sin_addr);
        REQUIRE(::connect(s, reinterpret_cast<sockaddr*>(&a), sizeof a) == 0);
    }
    ~TestClient() { closeNow(); }
    void closeNow() {
        if (s != INVALID_SOCKET) closesocket(s);
        s = INVALID_SOCKET;
    }

    bool sendRaw(const std::string& d) {
        size_t off = 0;
        while (off < d.size()) {
            int n = ::send(s, d.data() + off, static_cast<int>(d.size() - off), 0);
            if (n <= 0) return false;
            off += static_cast<size_t>(n);
        }
        return true;
    }
    // Reads more bytes into buf; false on EOF/error/timeout.
    bool fill() {
        char tmp[4096];
        int n = ::recv(s, tmp, static_cast<int>(sizeof tmp), 0);
        if (n <= 0) return false;
        buf.append(tmp, static_cast<size_t>(n));
        return true;
    }
    std::string readHttp() {
        while (buf.find("\r\n\r\n") == std::string::npos)
            if (!fill()) return {};
        size_t e = buf.find("\r\n\r\n") + 4;
        std::string h = buf.substr(0, e);
        buf.erase(0, e);
        return h;
    }
    std::string request(const std::string& extra = "") const {
        return "GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
               "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n" +
               extra + "\r\n";
    }
    bool handshake() {
        if (!sendRaw(request())) return false;
        std::string h = readHttp();
        return h.rfind("HTTP/1.1 101", 0) == 0;
    }
    static std::string frame(uint8_t opcode, const std::string& p, bool fin = true) {
        std::string f;
        f.push_back(static_cast<char>((fin ? 0x80 : 0) | opcode));
        const uint64_t n = p.size();
        if (n < 126) {
            f.push_back(static_cast<char>(0x80 | n));
        } else if (n <= 0xFFFF) {
            f.push_back(static_cast<char>(0x80 | 126));
            f.push_back(static_cast<char>(n >> 8));
            f.push_back(static_cast<char>(n & 0xFF));
        } else {
            f.push_back(static_cast<char>(0x80 | 127));
            for (int i = 7; i >= 0; --i) f.push_back(static_cast<char>((n >> (8 * i)) & 0xFF));
        }
        const char mask[4] = {0x11, 0x22, 0x33, 0x44};
        f.append(mask, 4);
        for (size_t i = 0; i < p.size(); ++i) f.push_back(static_cast<char>(p[i] ^ mask[i & 3]));
        return f;
    }
    bool sendFrame(uint8_t opcode, const std::string& p) { return sendRaw(frame(opcode, p)); }
    // Reads one unmasked server frame.
    bool readFrame(uint8_t& opcode, std::string& payload) {
        for (;;) {
            if (buf.size() >= 2) {
                const auto b1 = static_cast<uint8_t>(buf[1]);
                uint64_t len = b1 & 0x7F;
                size_t pos = 2;
                bool ok = true;
                if (len == 126) {
                    ok = buf.size() >= 4;
                    if (ok) len = (uint64_t(uint8_t(buf[2])) << 8) | uint8_t(buf[3]);
                    pos = 4;
                } else if (len == 127) {
                    ok = buf.size() >= 10;
                    if (ok) {
                        len = 0;
                        for (int i = 0; i < 8; ++i) len = (len << 8) | uint8_t(buf[2 + i]);
                    }
                    pos = 10;
                }
                if (ok && buf.size() >= pos + len) {
                    opcode = static_cast<uint8_t>(buf[0] & 0x0F);
                    payload = buf.substr(pos, static_cast<size_t>(len));
                    buf.erase(0, pos + static_cast<size_t>(len));
                    return true;
                }
            }
            if (!fill()) return false;
        }
    }
    // Reads frames until one with the wanted opcode arrives.
    bool expect(uint8_t want, std::string& payload) {
        uint8_t op = 0;
        while (readFrame(op, payload))
            if (op == want) return true;
        return false;
    }
    bool pingPong(const std::string& p) {
        if (!sendFrame(0x9, p)) return false;
        std::string got;
        return expect(0xA, got) && got == p;
    }
    // True once the server has closed the connection (EOF or reset).
    bool closedByServer() {
        char tmp[4096];
        for (;;) {
            int n = ::recv(s, tmp, static_cast<int>(sizeof tmp), 0);
            if (n > 0) continue;
            if (n == 0) return true;
            return WSAGetLastError() != WSAETIMEDOUT;
        }
    }
};

uint16_t closeCode(const std::string& p) {
    if (p.size() < 2) return 0;
    return static_cast<uint16_t>((uint8_t(p[0]) << 8) | uint8_t(p[1]));
}

struct Running {
    Wsa wsa;
    server::WsServer srv;
    Running() { REQUIRE(srv.start("127.0.0.1", 0, testInfo())); REQUIRE(srv.port() != 0); }
    ~Running() { srv.stop(); }
};

}  // namespace

TEST_CASE("x64_build_and_defaults") {
    CHECK(sizeof(void*) == 8);
    CHECK(sizeof(SOCKET) == 8);  // UINT_PTR: the server must not truncate it to int
    CHECK(server::kDefaultPort == 7970);
    CHECK(std::string(server::kDefaultBind) == "127.0.0.1");
}

TEST_CASE("server_handshake_and_ping") {
    Running r;
    TestClient c(r.srv.port());
    REQUIRE(c.handshake());
    CHECK(c.pingPong("ping"));

    // Protocol-level ping/hello are answered on the network thread.
    REQUIRE(c.sendFrame(0x1, fixture("ping.json")));
    std::string text;
    REQUIRE(c.expect(0x1, text));
    CHECK(text.find("\"pong\"") != std::string::npos);
    REQUIRE(c.sendFrame(0x1, fixture("hello.json")));
    REQUIRE(c.expect(0x1, text));
    CHECK(text.find("\"welcome\"") != std::string::npos);
    CHECK(text.find("0.0.0-test") != std::string::npos);
}

TEST_CASE("server_two_clients_and_abrupt_close") {
    Running r;
    TestClient a(r.srv.port());
    REQUIRE(a.handshake());
    TestClient b(r.srv.port());
    REQUIRE(b.handshake());
    CHECK(b.pingPong("b1"));

    REQUIRE(a.sendRaw(TestClient::frame(0x1, "{\"type\":\"ping\"}").substr(0, 3)));
    a.closeNow();
    std::this_thread::sleep_for(std::chrono::milliseconds(150));
    CHECK(b.pingPong("b2"));
    CHECK(b.pingPong("b3"));

    auto t0 = Clock::now();
    r.srv.stop();
    CHECK(Clock::now() - t0 < std::chrono::milliseconds(500));
    CHECK(r.srv.port() == 0);
}

TEST_CASE("server_oversized_frame") {
    Running r;
    {
        TestClient c(r.srv.port());
        REQUIRE(c.handshake());
        c.sendRaw(TestClient::frame(0x1, std::string(2 * 1024 * 1024, 'x')));  // may fail midway
        std::string p;
        REQUIRE(c.expect(0x8, p));
        CHECK(closeCode(p) == 1009);
        CHECK(c.closedByServer());
    }
    TestClient d(r.srv.port());
    REQUIRE(d.handshake());
    CHECK(d.pingPong("again"));
}

TEST_CASE("command_routed") {
    Running r;
    TestClient c(r.srv.port());
    REQUIRE(c.handshake());
    REQUIRE(c.sendFrame(0x1, fixture("command_probe.json")));
    server::PendingCommand pc;
    bool got = false;
    auto t0 = Clock::now();
    while (!got && Clock::now() - t0 < std::chrono::milliseconds(200)) {
        got = r.srv.pollCommand(pc);
        if (!got) std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
    REQUIRE(got);
    CHECK(pc.id == "c1");
    CHECK(pc.name == "kh1.probe");
    CHECK(pc.args == nlohmann::json{{"offset", 36961940}, {"length", 4}});
    CHECK_FALSE(r.srv.pollCommand(pc));

    // A malformed command is answered on the network thread and never queued.
    REQUIRE(c.sendFrame(0x1, "{\"type\":\"command\",\"id\":\"c5\",\"name\":\"kh1.log\"}"));
    std::string text;
    REQUIRE(c.expect(0x1, text));
    auto j = nlohmann::json::parse(text);
    CHECK(j["type"] == "result");
    CHECK(j["id"] == "c5");
    CHECK(j["error"] == "bad command");
    CHECK_FALSE(r.srv.pollCommand(pc));
}

TEST_CASE("subscribe_and_send_to_all") {
    Running r;
    int rate = 0;
    CHECK_FALSE(r.srv.hasSubscriber("kh1.spike", rate));
    TestClient c(r.srv.port());
    REQUIRE(c.handshake());
    REQUIRE(c.sendFrame(0x1, "{\"type\":\"subscribe\",\"topics\":[\"kh1.spike\"],\"rateHz\":7}"));
    CHECK(c.pingPong("sync"));  // subscribe has no ack; the pong orders after it
    REQUIRE(r.srv.hasSubscriber("kh1.spike", rate));
    CHECK(rate == 7);
    rate = 0;
    CHECK_FALSE(r.srv.hasSubscriber("spike", rate));  // TTW's topic name is not ours
    CHECK(rate == 0);

    r.srv.sendToAll("{\"type\":\"state\"}");
    std::string text;
    REQUIRE(c.expect(0x1, text));
    CHECK(text == "{\"type\":\"state\"}");

    c.closeNow();
    bool gone = false;
    auto t0 = Clock::now();
    while (!gone && Clock::now() - t0 < std::chrono::milliseconds(1000)) {
        gone = !r.srv.hasSubscriber("kh1.spike", rate);
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
    CHECK(gone);
}

TEST_CASE("server_close_and_binary") {
    Running r;
    {
        TestClient c(r.srv.port());
        REQUIRE(c.handshake());
        REQUIRE(c.sendFrame(0x8, std::string("\x03\xE8", 2)));
        std::string p;
        REQUIRE(c.expect(0x8, p));
        CHECK(closeCode(p) == 1000);
    }
    {
        TestClient c(r.srv.port());
        REQUIRE(c.handshake());
        REQUIRE(c.sendFrame(0x2, "bin"));
        std::string p;
        REQUIRE(c.expect(0x8, p));
        CHECK(closeCode(p) == 1002);
    }
    {
        TestClient c(r.srv.port());
        REQUIRE(c.handshake());
        REQUIRE(c.sendRaw(std::string("\x81\x05hello", 7)));  // unmasked -> protocol error
        std::string p;
        REQUIRE(c.expect(0x8, p));
        CHECK(closeCode(p) == 1002);
    }
}

TEST_CASE("server_rejects_bad_handshakes") {
    Running r;
    {
        TestClient c(r.srv.port());
        REQUIRE(c.sendRaw(c.request("Origin: http://evil.example\r\n")));
        CHECK(c.readHttp().rfind("HTTP/1.1 403", 0) == 0);
        CHECK(c.closedByServer());
    }
    {
        TestClient c(r.srv.port());
        c.sendRaw(std::string(9000, 'A'));
        CHECK(c.closedByServer());
    }
    CHECK_FALSE(r.srv.start("0.0.0.0", 0, testInfo()));  // already running
}

TEST_CASE("server_bind_must_be_loopback") {
    Wsa wsa;
    server::WsServer s;
    CHECK_FALSE(s.start("0.0.0.0", 0, testInfo()));
    CHECK_FALSE(s.start("localhost", 0, testInfo()));
    CHECK(s.port() == 0);
    s.stop();  // no-op
}

TEST_CASE("server_max_four_clients") {
    Running r;
    TestClient c1(r.srv.port()), c2(r.srv.port()), c3(r.srv.port()), c4(r.srv.port());
    REQUIRE(c1.handshake());
    REQUIRE(c2.handshake());
    REQUIRE(c3.handshake());
    REQUIRE(c4.handshake());
    TestClient c5(r.srv.port());
    CHECK(c5.closedByServer());
    CHECK(c4.pingPong("still"));
    c1.closeNow();
    std::this_thread::sleep_for(std::chrono::milliseconds(150));
    TestClient c6(r.srv.port());
    REQUIRE(c6.handshake());
    CHECK(c6.pingPong("room"));
}

TEST_CASE("server_drops_idle_unhandshaken") {
    Running r;
    TestClient c(r.srv.port());
    DWORD tmo = 7000;
    setsockopt(c.s, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&tmo), sizeof tmo);
    auto t0 = Clock::now();
    CHECK(c.closedByServer());
    auto dt = Clock::now() - t0;
    CHECK(dt > std::chrono::milliseconds(4500));
    CHECK(dt < std::chrono::milliseconds(6500));
}

TEST_CASE("server_drops_stalled_reader") {
    Running r;
    TestClient slow(r.srv.port(), 4096);
    REQUIRE(slow.handshake());
    REQUIRE(slow.sendFrame(0x1, fixture("subscribe.json")));
    REQUIRE(slow.pingPong("sync"));
    int rate = 0;
    REQUIRE(r.srv.hasSubscriber("kh1.spike", rate));

    // The slow client never reads again; the game keeps broadcasting ~60 KB states.
    const std::string big = "{\"type\":\"state\",\"pad\":\"" + std::string(60 * 1024, 'x') + "\"}";
    bool dropped = false;
    auto t0 = Clock::now();
    while (!dropped && Clock::now() - t0 < std::chrono::seconds(12)) {
        r.srv.sendToAll(big);
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
        dropped = !r.srv.hasSubscriber("kh1.spike", rate);
    }
    CHECK(dropped);
    CHECK(Clock::now() - t0 < std::chrono::seconds(9));  // 5 s stall timeout + slack

    TestClient fresh(r.srv.port());
    REQUIRE(fresh.handshake());
    CHECK(fresh.pingPong("alive"));
}

TEST_CASE("server_output_hard_cap") {
    Running r;
    TestClient c(r.srv.port(), 4096);
    REQUIRE(c.handshake());
    // Flood pings without reading: ~5 MB of pongs pile up server side and must trip the 2 MB cap.
    const std::string ping = TestClient::frame(0x9, std::string(125, 'p'));
    std::string burst;
    for (int i = 0; i < 1000; ++i) burst += ping;
    for (int i = 0; i < 40 && c.sendRaw(burst); ++i) {
    }
    std::string p;
    REQUIRE(c.expect(0x8, p));
    CHECK(closeCode(p) == 1008);
    CHECK(c.closedByServer());

    TestClient fresh(r.srv.port());
    REQUIRE(fresh.handshake());
    CHECK(fresh.pingPong("alive"));
}

TEST_CASE("server_set_info_and_event_log") {
    Wsa wsa;
    server::WsServer srv;
    std::mutex mx;
    std::vector<std::string> events;
    srv.setEventLog([&](const std::string& e) {
        std::lock_guard<std::mutex> lk(mx);
        events.push_back(e);
    });
    REQUIRE(srv.start("127.0.0.1", 0, testInfo()));
    auto info = testInfo();
    info.plugin = "9.9.9-updated";
    info.gameBuild = "egs-1.0.0.10";
    info.supported = false;
    srv.setInfo(info);
    {
        TestClient c(srv.port());
        REQUIRE(c.handshake());
        REQUIRE(c.sendFrame(0x1, fixture("hello.json")));
        std::string text;
        REQUIRE(c.expect(0x1, text));
        auto j = nlohmann::json::parse(text);
        CHECK(j["plugin"] == "9.9.9-updated");
        CHECK(j["gameBuild"] == "egs-1.0.0.10");
        CHECK(j["supported"] == false);
    }
    srv.stop();
    std::lock_guard<std::mutex> lk(mx);
    REQUIRE(!events.empty());
    CHECK(events[0] == "client connected (1 open)");
}

TEST_CASE("server_busy_reply_has_null_data") {
    Running r;
    TestClient c(r.srv.port());
    REQUIRE(c.handshake());
    // Nobody polls, so the queue fills; the next command gets an immediate busy result.
    for (size_t i = 0; i <= server::WsServer::kMaxQueuedCommands; ++i)
        REQUIRE(c.sendFrame(0x1, "{\"type\":\"command\",\"id\":\"b" + std::to_string(i) +
                                     "\",\"name\":\"kh1.log\",\"args\":{}}"));
    std::string text;
    REQUIRE(c.expect(0x1, text));
    auto j = nlohmann::json::parse(text);
    CHECK(j["type"] == "result");
    CHECK(j["id"] == "b" + std::to_string(server::WsServer::kMaxQueuedCommands));
    CHECK(j["ok"] == false);
    CHECK(j["error"] == "busy");
    REQUIRE(j.contains("data"));
    CHECK(j["data"].is_null());
}

TEST_CASE("server_unsolicited_welcome_after_set_info") {
    Wsa wsa;
    server::WsServer srv;
    proto::Info info{"0.0.0-test", "kh1", "unknown", false};
    REQUIRE(srv.start("127.0.0.1", 0, info));
    TestClient c(srv.port());
    REQUIRE(c.handshake());
    REQUIRE(c.sendFrame(0x1, fixture("hello.json")));
    std::string text;
    REQUIRE(c.expect(0x1, text));
    CHECK(nlohmann::json::parse(text)["supported"] == false);
    // setInfo + sendToAll(welcome_json) pushes a changed Info to clients already connected.
    info.gameBuild = "steam-1.0.0.2";
    info.supported = true;
    srv.setInfo(info);
    srv.sendToAll(proto::welcome_json(info));
    REQUIRE(c.expect(0x1, text));
    auto j = nlohmann::json::parse(text);
    CHECK(j["type"] == "welcome");
    CHECK(j["gameId"] == "kh1");
    CHECK(j["supported"] == true);
    srv.stop();
}
