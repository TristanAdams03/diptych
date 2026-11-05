#include "net/sha1.h"

#include <string>

namespace diptych::net {
namespace {
constexpr uint32_t rol(uint32_t v, int n) { return (v << n) | (v >> (32 - n)); }

void block(uint32_t h[5], const uint8_t* p) {
    uint32_t w[80];
    for (int i = 0; i < 16; ++i)
        w[i] = (uint32_t(p[i * 4]) << 24) | (uint32_t(p[i * 4 + 1]) << 16) |
               (uint32_t(p[i * 4 + 2]) << 8) | uint32_t(p[i * 4 + 3]);
    for (int i = 16; i < 80; ++i) w[i] = rol(w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16], 1);
    uint32_t a = h[0], b = h[1], c = h[2], d = h[3], e = h[4];
    for (int i = 0; i < 80; ++i) {
        uint32_t f, k;
        if (i < 20) { f = (b & c) | (~b & d); k = 0x5A827999u; }
        else if (i < 40) { f = b ^ c ^ d; k = 0x6ED9EBA1u; }
        else if (i < 60) { f = (b & c) | (b & d) | (c & d); k = 0x8F1BBCDCu; }
        else { f = b ^ c ^ d; k = 0xCA62C1D6u; }
        uint32_t t = rol(a, 5) + f + e + k + w[i];
        e = d; d = c; c = rol(b, 30); b = a; a = t;
    }
    h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e;
}
}  // namespace

std::array<uint8_t, 20> sha1(std::string_view data) {
    uint32_t h[5] = {0x67452301u, 0xEFCDAB89u, 0x98BADCFEu, 0x10325476u, 0xC3D2E1F0u};
    const auto* p = reinterpret_cast<const uint8_t*>(data.data());
    size_t n = data.size();
    size_t full = n / 64;
    for (size_t i = 0; i < full; ++i) block(h, p + i * 64);

    uint8_t tail[128] = {};
    size_t rem = n - full * 64;
    for (size_t i = 0; i < rem; ++i) tail[i] = p[full * 64 + i];
    tail[rem] = 0x80;
    size_t tailLen = (rem < 56) ? 64 : 128;
    uint64_t bits = uint64_t(n) * 8;
    for (int i = 0; i < 8; ++i) tail[tailLen - 1 - i] = uint8_t(bits >> (8 * i));
    for (size_t off = 0; off < tailLen; off += 64) block(h, tail + off);

    std::array<uint8_t, 20> out{};
    for (int i = 0; i < 5; ++i)
        for (int j = 0; j < 4; ++j) out[i * 4 + j] = uint8_t(h[i] >> (24 - 8 * j));
    return out;
}
}  // namespace diptych::net
