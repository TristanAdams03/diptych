#pragma once
// Windows-only test helpers: a raw Winsock WebSocket client for driving WsServer on loopback.
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <winsock2.h>
#include <ws2tcpip.h>

#include <doctest/doctest.h>

#include <cstdint>
#include <fstream>
#include <sstream>
#include <string>

namespace testws {

inline std::string fixture(const char* name) {
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

inline uint16_t closeCode(const std::string& p) {
    if (p.size() < 2) return 0;
    return static_cast<uint16_t>((uint8_t(p[0]) << 8) | uint8_t(p[1]));
}

}  // namespace testws
