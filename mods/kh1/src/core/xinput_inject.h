#pragma once
// kh1.pressInput injection through XInputGetState (protocol/kh1/PROTOCOL.md). Portable: the
// Windows hook (win/xinput_hook.cpp) reads an InjectState; the runtime thread sets it.
#include <atomic>
#include <cstdint>
#include <optional>

namespace diptych::core {

// XINPUT_GAMEPAD wButtons bits (xinput.h), repeated so core builds without the Windows SDK.
inline constexpr uint16_t kXiDpadUp = 0x0001;
inline constexpr uint16_t kXiDpadDown = 0x0002;
inline constexpr uint16_t kXiDpadLeft = 0x0004;
inline constexpr uint16_t kXiDpadRight = 0x0008;
inline constexpr uint16_t kXiStart = 0x0010;
inline constexpr uint16_t kXiBack = 0x0020;
inline constexpr uint16_t kXiLeftThumb = 0x0040;
inline constexpr uint16_t kXiRightThumb = 0x0080;
inline constexpr uint16_t kXiLeftShoulder = 0x0100;
inline constexpr uint16_t kXiRightShoulder = 0x0200;
inline constexpr uint16_t kXiA = 0x1000;
inline constexpr uint16_t kXiB = 0x2000;
inline constexpr uint16_t kXiX = 0x4000;
inline constexpr uint16_t kXiY = 0x8000;

// What one press adds to controller 0's XINPUT_GAMEPAD: buttons ORed into wButtons, and a
// trigger value (0 or 255) that the reported trigger is raised to.
struct PadInject {
    uint16_t buttons = 0;
    uint8_t leftTrigger = 0;
    uint8_t rightTrigger = 0;
    bool operator==(const PadInject&) const = default;
};

// PS2-style pad mask -> XInput. byteIndex 1: LT 01, RT 02, LB 04, RB 08, Y 10, B 20, A 40, X 80.
// byteIndex 0: select 01, L3 02, R3 04, start 08, up 10, right 20, down 40, left 80.
// nullopt (-> "out of range") for any other byteIndex, a mask over 0xFF, or start (0x08 in byte 0:
// it opens the pause menu).
std::optional<PadInject> ps2_to_xinput(int byteIndex, int mask);

// The injection the hook applies. One 64-bit atomic: the low 32 bits are the PadInject, the high
// 32 a generation that bumps on every change, so the hook reads both in one lock-free load.
// set/clear: the runtime thread. apply/active/note_no_pad: any thread (the game's). Never throws,
// never allocates.
class InjectState {
public:
    void set(PadInject p) noexcept;  // a no-op if p is already the injection
    void clear() noexcept { set(PadInject{}); }
    bool active() const noexcept;
    uint32_t generation() const noexcept;
    // For dwUserIndex 0 after a successful XInputGetState: ORs the buttons in, raises the
    // triggers, and adds the generation to dwPacketNumber, so the packet number changes whenever
    // the injection does (and stays strictly increasing, since both parts only grow).
    void apply(uint32_t& packet, uint16_t& buttons, uint8_t& leftTrigger, uint8_t& rightTrigger) const noexcept;
    // The hook calls this when controller 0 is not connected while a press is active.
    void note_no_pad() noexcept { noPad_.store(true, std::memory_order_relaxed); }
    // True once after the first note_no_pad (the runtime logs it once per session).
    bool take_no_pad_once() noexcept;

private:
    std::atomic<uint64_t> word_{0};
    std::atomic<bool> noPad_{false};
    bool noPadTaken_ = false;  // runtime thread only
};

static_assert(std::atomic<uint64_t>::is_always_lock_free, "the hook must not take a lock");

}  // namespace diptych::core
