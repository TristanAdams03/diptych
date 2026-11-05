#include <doctest/doctest.h>

#include <cstdio>
#include <string>

#include "net/base64.h"
#include "net/sha1.h"
#include "net/ws_frame.h"
#include "net/ws_handshake.h"

using namespace diptych::net;

static std::string hex(const std::array<uint8_t, 20>& d) {
    std::string s;
    char b[3];
    for (uint8_t c : d) { std::snprintf(b, sizeof b, "%02x", c); s += b; }
    return s;
}
static std::string bytes(std::initializer_list<int> l) {
    std::string s;
    for (int c : l) s.push_back(static_cast<char>(c));
    return s;
}

TEST_CASE("sha1") {
    CHECK(hex(sha1("abc")) == "a9993e364706816aba3e25717850c26c9cd0d89d");
    CHECK(hex(sha1("")) == "da39a3ee5e6b4b0d3255bfef95601890afd80709");
    CHECK(hex(sha1("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq")) ==
          "84983e441c3bd26ebaae4aa1f95129e5e54670f1");
    CHECK(hex(sha1(std::string(1000000, 'a'))) == "34aa973cd4c4daa4f61eeb2bdbad27316534016f");
}

TEST_CASE("base64") {
    auto enc = [](std::string_view s) {
        return base64({reinterpret_cast<const uint8_t*>(s.data()), s.size()});
    };
    CHECK(enc("") == "");
    CHECK(enc("f") == "Zg==");
    CHECK(enc("fo") == "Zm8=");
    CHECK(enc("foo") == "Zm9v");
    CHECK(enc("foobar") == "Zm9vYmFy");
}

TEST_CASE("handshake") {
    std::string req =
        "GET /chat HTTP/1.1\r\nHost: 127.0.0.1:7970\r\nUpgrade: websocket\r\n"
        "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
        "Sec-WebSocket-Version: 13\r\n\r\n";
    auto r = ws_accept_response(req);
    REQUIRE(r.has_value());
    CHECK(r->rfind("HTTP/1.1 101", 0) == 0);
    CHECK(r->find("Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=") != std::string::npos);
    CHECK(r->substr(r->size() - 4) == "\r\n\r\n");

    // case-insensitive header names
    CHECK(ws_accept_response("GET / HTTP/1.1\r\nupgrade: WebSocket\r\nsec-websocket-key: dGhlIHNhbXBsZSBub25jZQ==\r\nhost: localhost:7970\r\n\r\n").has_value());
    // hardening: Origin, Host, completeness
    const std::string base =
        "GET / HTTP/1.1\r\nUpgrade: websocket\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n";
    CHECK(ws_accept_response(base + "Host: 127.0.0.1\r\n\r\n").has_value());
    CHECK_FALSE(ws_accept_response(base + "Host: 127.0.0.1:7970\r\nOrigin: https://evil.example\r\n\r\n"));
    CHECK_FALSE(ws_accept_response(base + "Host: 127.0.0.1:7970\r\norigin: null\r\n\r\n"));
    CHECK_FALSE(ws_accept_response(base + "Host: evil.example:7970\r\n\r\n"));
    CHECK_FALSE(ws_accept_response(base + "Host: 127.0.0.1.evil.example\r\n\r\n"));
    CHECK_FALSE(ws_accept_response(base + "\r\n"));
    CHECK_FALSE(ws_accept_response(base + "Host: localhost:7970\r\n"));  // no blank line
    // invalid
    CHECK_FALSE(ws_accept_response("GET / HTTP/1.1\r\nUpgrade: websocket\r\n\r\n"));
    CHECK_FALSE(ws_accept_response("POST / HTTP/1.1\r\nUpgrade: websocket\r\nSec-WebSocket-Key: abc\r\n\r\n"));
    CHECK_FALSE(ws_accept_response("GET / HTTP/1.1\r\nSec-WebSocket-Key: abc\r\n\r\n"));
}

TEST_CASE("decode") {
    WsFrame f;
    SUBCASE("masked Hello") {
        std::string buf = bytes({0x81, 0x85, 0x37, 0xfa, 0x21, 0x3d, 0x7f, 0x9f, 0x4d, 0x51, 0x58});
        CHECK(ws_decode(buf, f) == DecodeStatus::Ok);
        CHECK(f.opcode == 1);
        CHECK(f.fin);
        CHECK(f.payload == "Hello");
        CHECK(buf.empty());
    }
    SUBCASE("trailing bytes kept") {
        std::string buf = bytes({0x81, 0x85, 0x37, 0xfa, 0x21, 0x3d, 0x7f, 0x9f, 0x4d, 0x51, 0x58, 0x88});
        CHECK(ws_decode(buf, f) == DecodeStatus::Ok);
        CHECK(buf == bytes({0x88}));
    }
    SUBCASE("unmasked is protocol error") {
        std::string buf = bytes({0x81, 0x05, 'H', 'e', 'l', 'l', 'o'});
        CHECK(ws_decode(buf, f) == DecodeStatus::ProtocolError);
    }
    SUBCASE("126 header split") {
        std::string buf = bytes({0x82, 0xfe, 0x00});
        CHECK(ws_decode(buf, f) == DecodeStatus::NeedMore);
        buf += bytes({0xc8, 1, 2, 3, 4});  // len 200, mask 01020304
        CHECK(ws_decode(buf, f) == DecodeStatus::NeedMore);
        buf += std::string(200, '\0');
        // payload = 200 bytes; byte i unmasked = 0 ^ mask[i%4]
        CHECK(ws_decode(buf, f) == DecodeStatus::Ok);
        CHECK(f.opcode == 2);
        REQUIRE(f.payload.size() == 200);
        CHECK(f.payload[0] == 1);
        CHECK(f.payload[5] == 2);
    }
    SUBCASE("too big") {
        // 126 form, length 11, maxPayload 10
        std::string buf = bytes({0x81, 0xfe, 0x00, 0x0b, 0, 0, 0, 0});
        CHECK(ws_decode(buf, f, 10) == DecodeStatus::TooBig);
    }
    SUBCASE("max+1 default") {
        std::string buf = bytes({0x81, 0xff, 0, 0, 0, 0, 0, 0x10, 0x00, 0x01, 0, 0, 0, 0});
        CHECK(ws_decode(buf, f) == DecodeStatus::TooBig);
    }
    SUBCASE("64-bit length with MSB set is error") {
        std::string buf = bytes({0x81, 0xff, 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        CHECK(ws_decode(buf, f) != DecodeStatus::Ok);
    }
    SUBCASE("reserved bits are error") {
        std::string buf = bytes({0xc1, 0x80, 0, 0, 0, 0});
        CHECK(ws_decode(buf, f) == DecodeStatus::ProtocolError);
    }
    SUBCASE("reserved opcodes are errors") {
        for (int op : {3, 4, 5, 6, 7, 0xB, 0xC, 0xD, 0xE, 0xF}) {
            std::string buf = bytes({0x80 | op, 0x80, 0, 0, 0, 0});
            CHECK(ws_decode(buf, f) == DecodeStatus::ProtocolError);
        }
    }
    SUBCASE("fragmented control frame is error") {
        std::string buf = bytes({0x09, 0x80, 0, 0, 0, 0});
        CHECK(ws_decode(buf, f) == DecodeStatus::ProtocolError);
    }
}

TEST_CASE("encode") {
    CHECK(ws_encode(1, "Hi") == bytes({0x81, 0x02, 'H', 'i'}));
    CHECK(ws_encode(8, "") == bytes({0x88, 0x00}));
    auto c = ws_encode(9, std::string(200, 'p'));  // control frames capped at 125
    CHECK(c.size() == 127);
    CHECK(c.substr(0, 2) == bytes({0x89, 125}));
    auto m = ws_encode(1, std::string(200, 'x'));
    CHECK(m.substr(0, 4) == bytes({0x81, 0x7e, 0x00, 0xc8}));
    CHECK(m.size() == 204);
    auto b = ws_encode(1, std::string(70000, 'a'));
    CHECK(b.substr(0, 10) == bytes({0x81, 0x7f, 0, 0, 0, 0, 0, 0x01, 0x11, 0x70}));
    CHECK(b.size() == 70010);
}
