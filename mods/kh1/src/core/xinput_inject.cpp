#include "core/xinput_inject.h"

namespace diptych::core {

namespace {
constexpr uint32_t pack(PadInject p) {
    return static_cast<uint32_t>(p.buttons) | (static_cast<uint32_t>(p.leftTrigger) << 16) |
           (static_cast<uint32_t>(p.rightTrigger) << 24);
}
}  // namespace

std::optional<PadInject> ps2_to_xinput(int byteIndex, int mask) {
    if (mask < 0 || mask > 0xFF) return std::nullopt;
    PadInject p;
    if (byteIndex == 1) {
        if (mask & 0x01) p.leftTrigger = 255;
        if (mask & 0x02) p.rightTrigger = 255;
        if (mask & 0x04) p.buttons |= kXiLeftShoulder;
        if (mask & 0x08) p.buttons |= kXiRightShoulder;
        if (mask & 0x10) p.buttons |= kXiY;
        if (mask & 0x20) p.buttons |= kXiB;
        if (mask & 0x40) p.buttons |= kXiA;
        if (mask & 0x80) p.buttons |= kXiX;
        return p;
    }
    if (byteIndex == 0) {
        if (mask & 0x08) return std::nullopt;  // start opens the pause menu
        if (mask & 0x01) p.buttons |= kXiBack;
        if (mask & 0x02) p.buttons |= kXiLeftThumb;
        if (mask & 0x04) p.buttons |= kXiRightThumb;
        if (mask & 0x10) p.buttons |= kXiDpadUp;
        if (mask & 0x20) p.buttons |= kXiDpadRight;
        if (mask & 0x40) p.buttons |= kXiDpadDown;
        if (mask & 0x80) p.buttons |= kXiDpadLeft;
        return p;
    }
    return std::nullopt;
}

void InjectState::set(PadInject p) noexcept {
    const uint32_t lo = pack(p);
    uint64_t cur = word_.load(std::memory_order_relaxed);
    for (;;) {
        if (static_cast<uint32_t>(cur) == lo) return;
        const uint64_t gen = (cur >> 32) + 1;
        const uint64_t next = ((gen & 0xFFFFFFFFull) << 32) | lo;
        if (word_.compare_exchange_weak(cur, next, std::memory_order_release, std::memory_order_relaxed)) return;
    }
}

bool InjectState::active() const noexcept {
    return static_cast<uint32_t>(word_.load(std::memory_order_acquire)) != 0;
}

uint32_t InjectState::generation() const noexcept {
    return static_cast<uint32_t>(word_.load(std::memory_order_acquire) >> 32);
}

void InjectState::apply(uint32_t& packet, uint16_t& buttons, uint8_t& leftTrigger,
                        uint8_t& rightTrigger) const noexcept {
    const uint64_t w = word_.load(std::memory_order_acquire);
    const uint32_t lo = static_cast<uint32_t>(w);
    packet += static_cast<uint32_t>(w >> 32);
    buttons = static_cast<uint16_t>(buttons | (lo & 0xFFFF));
    const uint8_t lt = static_cast<uint8_t>(lo >> 16), rt = static_cast<uint8_t>(lo >> 24);
    if (lt > leftTrigger) leftTrigger = lt;
    if (rt > rightTrigger) rightTrigger = rt;
}

bool InjectState::take_no_pad_once() noexcept {
    if (noPadTaken_ || !noPad_.load(std::memory_order_relaxed)) return false;
    noPadTaken_ = true;
    return true;
}

}  // namespace diptych::core
