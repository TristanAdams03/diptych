#pragma once
// Finds an import-by-ordinal IAT slot in a mapped PE32+ image. Portable: the Windows side reads the
// live module through the SEH guard, the tests a byte buffer holding a fake image.
#include <cstddef>
#include <cstdint>
#include <functional>
#include <string>
#include <string_view>

namespace diptych::core {

// Copies n bytes at image RVA rva into out. False when out of the image or the read faults.
using ImageReader = std::function<bool(uint32_t rva, void* out, size_t n)>;

struct ImportSlot {
    bool found = false;
    uint32_t iatRva = 0;   // RVA of the 8-byte FirstThunk (IAT) entry, when found
    std::string why;       // when not found: a short reason for the log
};

// Walks the import descriptors of a mapped PE32+ image for the module named dll (ASCII,
// case-insensitive) and the thunk whose OriginalFirstThunk entry is an import by ordinal `ordinal`.
// Never throws on bad input: every read failure or malformed header ends the walk with a reason.
ImportSlot find_import_by_ordinal(const ImageReader& read, std::string_view dll, uint16_t ordinal);

}  // namespace diptych::core
