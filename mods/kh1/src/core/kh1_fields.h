#pragma once
// The kh1.spike state snapshot, built from module-relative reads (protocol/kh1/PROTOCOL.md).
// Portable: the Windows reader (win/memory.h) and the tests' FakeMemory both implement IMemory.
#include <cstddef>
#include <cstdint>
#include <functional>
#include <optional>
#include <set>
#include <string>
#include <utility>
#include <vector>

#include <nlohmann/json.hpp>

namespace diptych::core {

// Offsets from the module base of KINGDOM HEARTS FINAL MIX.exe, Steam 1.0.0.2 (PROTOCOL.md table).
inline constexpr uintptr_t kWorldOffset = 0x233FE94;     // u8
inline constexpr uintptr_t kRoomOffset = 0x233FE8C;      // u8
inline constexpr uintptr_t kWorldAltOffset = 0x233FE84;  // u8 (Archipelago world_address)
inline constexpr uintptr_t kSoraHpOffset = 0x2D5CC4C;    // u8
inline constexpr uintptr_t kSoraHpMaxOffset = 0x2D5CC50; // confirmed on PC 2026-11-04: soraHP+4 in the battle block (HP, maxHP, MP, maxMP as u32); read low byte
inline constexpr uintptr_t kMenuOffset = 0x232DFA0;      // u8, 0 = no menu
inline constexpr uintptr_t kCutsceneOffset = 0x23AB2D0;  // read 4 bytes, any non-zero = cutscene
inline constexpr uintptr_t kGummiOffset = 0x5075A8;      // u32, non-zero = Gummi
inline constexpr uintptr_t kInputOffset = 0x23407B4;     // 4 input bytes

// Reads and writes at moduleBase + off. Every call returns nullopt/false (and may throw, in tests)
// when the range is outside the image or the access faults.
struct IMemory {
    virtual ~IMemory() = default;
    virtual std::optional<uint8_t> u8(uintptr_t off) = 0;
    virtual std::optional<uint32_t> u32(uintptr_t off) = 0;  // little-endian
    virtual std::optional<std::vector<uint8_t>> bytes(uintptr_t off, size_t n) = 0;
    virtual bool write_u8(uintptr_t off, uint8_t v) = 0;
    virtual size_t image_size() = 0;
};

// The `data` object of a kh1.spike state frame. When !supported every game field is null and
// memory is not touched. Otherwise each field is read on its own: a read that fails or throws
// makes only that field null.
nlohmann::json kh1_spike_state(IMemory& mem, int64_t seq, int64_t ms, bool supported);

// {"type":"state","topic":"kh1.spike","data":data}
std::string spike_state_frame(const nlohmann::json& data);

// True only when the menu, cutscene and Gummi reads all succeed and are all zero. Never throws.
bool safe_now(IMemory& mem);

// Lowercase hex of the bytes, two digits each.
std::string to_hex(const std::vector<uint8_t>& b);

// Wraps an IMemory: the first read at an offset that fails (nullopt or exception) turns that
// reader (keyed by offset and kind) off for the rest of the session and calls onFault once.
// Never throws. Writes and image_size pass straight through. Not thread-safe.
class DisablingMemory : public IMemory {
public:
    DisablingMemory(IMemory& inner, std::function<void(uintptr_t)> onFault = {})
        : inner_(inner), onFault_(std::move(onFault)) {}
    std::optional<uint8_t> u8(uintptr_t off) override;
    std::optional<uint32_t> u32(uintptr_t off) override;
    std::optional<std::vector<uint8_t>> bytes(uintptr_t off, size_t n) override;
    bool write_u8(uintptr_t off, uint8_t v) override { return inner_.write_u8(off, v); }
    size_t image_size() override { return inner_.image_size(); }

private:
    struct Key {
        uintptr_t off;
        size_t width;
        auto operator<=>(const Key&) const = default;
    };
    bool off_(const Key& k) const { return disabled_.count(k) != 0; }
    void fault_(const Key& k);
    IMemory& inner_;
    std::function<void(uintptr_t)> onFault_;
    std::set<Key> disabled_;
};

// Debug aid for the PC session: logs the raw 4-byte inCutscene value and the bytes around maxHP
// (maxHP-1 .. maxHP+3) whenever they change, so the real widths can be read off the log.
// At most kMaxLinesPerField lines per field per session, then one "suppressed" line.
class RawWatch {
public:
    static constexpr int kMaxLinesPerField = 200;
    std::vector<std::string> poll(IMemory& mem);

private:
    std::optional<std::optional<uint32_t>> cutscene_;
    std::optional<std::optional<std::vector<uint8_t>>> maxHp_;
    int cutsceneLines_ = 0, maxHpLines_ = 0;
};

}  // namespace diptych::core
