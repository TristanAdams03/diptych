#include "core/kh1_fields.h"

#include <cstdio>

namespace diptych::core {

using nlohmann::json;

namespace {
// Runs one read; a failed or throwing read yields null.
template <class F>
json field(F&& f) {
    try {
        return f();
    } catch (...) {
        return nullptr;
    }
}

json u8_field(IMemory& m, uintptr_t off) {
    return field([&]() -> json {
        auto v = m.u8(off);
        if (!v) return nullptr;
        return *v;
    });
}

// A 4-byte read reported as "any byte non-zero".
json flag32_field(IMemory& m, uintptr_t off) {
    return field([&]() -> json {
        auto v = m.u32(off);
        if (!v) return nullptr;
        return *v != 0;
    });
}

std::string hex32(uint32_t v) {
    char buf[16];
    std::snprintf(buf, sizeof buf, "0x%08x", static_cast<unsigned>(v));
    return buf;
}
}  // namespace

std::string to_hex(const std::vector<uint8_t>& b) {
    static const char* digits = "0123456789abcdef";
    std::string s;
    s.reserve(b.size() * 2);
    for (uint8_t x : b) {
        s.push_back(digits[x >> 4]);
        s.push_back(digits[x & 0xF]);
    }
    return s;
}

json kh1_spike_state(IMemory& m, int64_t seq, int64_t ms, bool supported) {
    json d = json::object();
    d["seq"] = seq;
    d["ms"] = ms;
    if (!supported) {
        for (const char* k : {"world", "room", "worldAlt", "soraHp", "soraHpMax", "inMenu", "inCutscene",
                              "inGummi", "inputBytes"})
            d[k] = nullptr;
        return d;
    }
    d["world"] = u8_field(m, kWorldOffset);
    d["room"] = u8_field(m, kRoomOffset);
    d["worldAlt"] = u8_field(m, kWorldAltOffset);
    d["soraHp"] = u8_field(m, kSoraHpOffset);
    d["soraHpMax"] = u8_field(m, kSoraHpMaxOffset);
    d["inMenu"] = field([&]() -> json {
        auto v = m.u8(kMenuOffset);
        if (!v) return nullptr;
        return *v != 0;
    });
    d["inCutscene"] = flag32_field(m, kCutsceneOffset);
    d["inGummi"] = flag32_field(m, kGummiOffset);
    d["inputBytes"] = field([&]() -> json {
        auto b = m.bytes(kInputOffset, 4);
        if (!b || b->size() != 4) return nullptr;
        return to_hex(*b);
    });
    return d;
}

std::string spike_state_frame(const json& data) {
    return json{{"type", "state"}, {"topic", "kh1.spike"}, {"data", data}}.dump();
}

bool safe_now(IMemory& m) {
    try {
        auto menu = m.u8(kMenuOffset);
        if (!menu || *menu != 0) return false;
        auto cut = m.u32(kCutsceneOffset);
        if (!cut || *cut != 0) return false;
        auto gummi = m.u32(kGummiOffset);
        if (!gummi || *gummi != 0) return false;
        return true;
    } catch (...) {
        return false;
    }
}

// ---- DisablingMemory --------------------------------------------------------------------------

void DisablingMemory::fault_(const Key& k) {
    if (!disabled_.insert(k).second) return;
    if (onFault_) {
        try {
            onFault_(k.off);
        } catch (...) {
        }
    }
}

std::optional<uint8_t> DisablingMemory::u8(uintptr_t off) {
    const Key k{off, 1};
    if (off_(k)) return std::nullopt;
    std::optional<uint8_t> v;
    try {
        v = inner_.u8(off);
    } catch (...) {
    }
    if (!v) fault_(k);
    return v;
}

std::optional<uint32_t> DisablingMemory::u32(uintptr_t off) {
    const Key k{off, 4};
    if (off_(k)) return std::nullopt;
    std::optional<uint32_t> v;
    try {
        v = inner_.u32(off);
    } catch (...) {
    }
    if (!v) fault_(k);
    return v;
}

std::optional<std::vector<uint8_t>> DisablingMemory::bytes(uintptr_t off, size_t n) {
    // Distinct from u8/u32 at the same offset: width n + 8 never collides with 1 or 4.
    const Key k{off, n + 8};
    if (off_(k)) return std::nullopt;
    std::optional<std::vector<uint8_t>> v;
    try {
        v = inner_.bytes(off, n);
    } catch (...) {
    }
    if (!v) fault_(k);
    return v;
}

// ---- RawWatch ---------------------------------------------------------------------------------

std::vector<std::string> RawWatch::poll(IMemory& m) {
    std::vector<std::string> out;
    std::optional<uint32_t> cut;
    std::optional<std::vector<uint8_t>> hp;
    try {
        cut = m.u32(kCutsceneOffset);
    } catch (...) {
    }
    try {
        hp = m.bytes(kSoraHpMaxOffset - 1, 5);
    } catch (...) {
    }
    if (!cutscene_ || *cutscene_ != cut) {
        cutscene_ = cut;
        if (cutsceneLines_ < kMaxLinesPerField)
            out.push_back("debug: inCutscene raw u32 " + (cut ? hex32(*cut) : std::string("unreadable")));
        else if (cutsceneLines_ == kMaxLinesPerField)
            out.push_back("debug: inCutscene raw changes suppressed from here on");
        ++cutsceneLines_;
    }
    if (!maxHp_ || *maxHp_ != hp) {
        maxHp_ = hp;
        if (maxHpLines_ < kMaxLinesPerField)
            out.push_back("debug: maxHP raw bytes [maxHP-1..maxHP+3] " + (hp ? to_hex(*hp) : std::string("unreadable")));
        else if (maxHpLines_ == kMaxLinesPerField)
            out.push_back("debug: maxHP raw changes suppressed from here on");
        ++maxHpLines_;
    }
    return out;
}

}  // namespace diptych::core
