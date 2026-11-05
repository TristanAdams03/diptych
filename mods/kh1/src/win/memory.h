#pragma once
// Game memory at module base + offset, for the KH1 runtime. Every access is bounds-checked
// against the image size (SizeOfImage) and goes through the SEH guard; a fault returns
// nullopt/false. Per-reader disable-after-fault is layered on top by core::DisablingMemory.
#include <cstddef>
#include <cstdint>
#include <optional>
#include <vector>

#include "core/kh1_fields.h"

namespace diptych::win {

class WinMemory : public core::IMemory {
public:
    static constexpr size_t kMaxRead = 4096;

    // [base, base + size) is the readable range; size 0 makes every access fail.
    WinMemory(const void* base, size_t size) : base_(reinterpret_cast<uintptr_t>(base)), size_(size) {}
    // The mapped image of moduleBase, sized by its PE SizeOfImage (0 if the headers are invalid).
    static WinMemory for_module(const void* moduleBase);

    std::optional<uint8_t> u8(uintptr_t off) override;
    std::optional<uint32_t> u32(uintptr_t off) override;
    std::optional<std::vector<uint8_t>> bytes(uintptr_t off, size_t n) override;
    bool write_u8(uintptr_t off, uint8_t v) override;
    size_t image_size() override { return size_; }

private:
    bool in_range(uintptr_t off, size_t n) const { return size_ != 0 && off < size_ && n <= size_ - off; }
    uintptr_t base_;
    size_t size_;
};

}  // namespace diptych::win
