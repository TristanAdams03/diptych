#include "win/module_reader.h"

#include <windows.h>

#include <atomic>
#include <memory>

#include "win/seh.h"

namespace diptych::win {

uint32_t module_image_size(const void* moduleBase) {
    if (!moduleBase) return 0;
    auto base = static_cast<const unsigned char*>(moduleBase);
    IMAGE_DOS_HEADER dos{};
    if (!seh_copy(base, &dos, sizeof dos) || dos.e_magic != IMAGE_DOS_SIGNATURE) return 0;
    if (dos.e_lfanew <= 0 || dos.e_lfanew > 0x10000) return 0;
    IMAGE_NT_HEADERS64 nt{};
    if (!seh_copy(base + dos.e_lfanew, &nt, sizeof nt)) return 0;
    if (nt.Signature != IMAGE_NT_SIGNATURE || nt.OptionalHeader.Magic != IMAGE_NT_OPTIONAL_HDR64_MAGIC)
        return 0;
    return nt.OptionalHeader.SizeOfImage;
}

core::ByteReader module_byte_reader(const void* moduleBase) {
    const auto base = reinterpret_cast<uintptr_t>(moduleBase);
    const uint32_t size = module_image_size(moduleBase);
    auto faulted = std::make_shared<std::atomic<bool>>(false);
    return [base, size, faulted](uintptr_t offset) -> std::optional<uint8_t> {
        if (size == 0 || offset >= size || faulted->load()) return std::nullopt;
        unsigned char b = 0;
        if (!seh_read_byte(reinterpret_cast<const void*>(base + offset), &b)) {
            faulted->store(true);
            return std::nullopt;
        }
        return b;
    };
}

}  // namespace diptych::win
