#include "win/memory.h"

#include "win/module_reader.h"
#include "win/seh.h"

namespace diptych::win {

WinMemory WinMemory::for_module(const void* moduleBase) { return WinMemory(moduleBase, module_image_size(moduleBase)); }

std::optional<std::vector<uint8_t>> WinMemory::bytes(uintptr_t off, size_t n) {
    if (n > kMaxRead || !in_range(off, n)) return std::nullopt;
    std::vector<uint8_t> out(n);
    if (n != 0 && !seh_copy(reinterpret_cast<const void*>(base_ + off), out.data(), n)) return std::nullopt;
    return out;
}

std::optional<uint8_t> WinMemory::u8(uintptr_t off) {
    if (!in_range(off, 1)) return std::nullopt;
    unsigned char b = 0;
    if (!seh_read_byte(reinterpret_cast<const void*>(base_ + off), &b)) return std::nullopt;
    return b;
}

std::optional<uint32_t> WinMemory::u32(uintptr_t off) {
    if (!in_range(off, 4)) return std::nullopt;
    unsigned char b[4] = {};
    if (!seh_copy(reinterpret_cast<const void*>(base_ + off), b, sizeof b)) return std::nullopt;
    return uint32_t(b[0]) | uint32_t(b[1]) << 8 | uint32_t(b[2]) << 16 | uint32_t(b[3]) << 24;
}

bool WinMemory::write_u8(uintptr_t off, uint8_t v) {
    if (!in_range(off, 1)) return false;
    return seh_write_byte(reinterpret_cast<void*>(base_ + off), v);
}

}  // namespace diptych::win
