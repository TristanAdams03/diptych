#include "core/pe_imports.h"

namespace diptych::core {

namespace {
constexpr int kMaxDescriptors = 1024;
constexpr int kMaxThunks = 65536;
constexpr size_t kMaxName = 256;

char lower(char c) { return c >= 'A' && c <= 'Z' ? static_cast<char>(c - 'A' + 'a') : c; }

template <class T>
bool rd(const ImageReader& read, uint64_t rva, T& out) {
    if (rva > 0xFFFFFFFFull) return false;
    return read(static_cast<uint32_t>(rva), &out, sizeof out);
}

// The NUL-terminated name at rva equals want, ignoring ASCII case.
bool name_is(const ImageReader& read, uint32_t rva, std::string_view want, bool& readOk) {
    readOk = true;
    for (size_t i = 0; i <= want.size() && i < kMaxName; ++i) {
        char c = 0;
        if (!rd(read, uint64_t{rva} + i, c)) {
            readOk = false;
            return false;
        }
        if (i == want.size()) return c == 0;
        if (c == 0 || lower(c) != lower(want[i])) return false;
    }
    return false;
}

ImportSlot fail(std::string why) {
    ImportSlot s;
    s.why = std::move(why);
    return s;
}
}  // namespace

ImportSlot find_import_by_ordinal(const ImageReader& read, std::string_view dll, uint16_t ordinal) {
    uint16_t mz = 0;
    if (!rd(read, 0, mz) || mz != 0x5A4D) return fail("no MZ header");
    uint32_t lfanew = 0;
    if (!rd(read, 0x3C, lfanew) || lfanew == 0 || lfanew > 0x10000) return fail("bad e_lfanew");
    uint32_t sig = 0;
    if (!rd(read, lfanew, sig) || sig != 0x00004550) return fail("no PE signature");
    const uint64_t opt = uint64_t{lfanew} + 4 + 20;  // past the signature and IMAGE_FILE_HEADER
    uint16_t magic = 0;
    if (!rd(read, opt, magic) || magic != 0x20B) return fail("not a PE32+ image");
    uint32_t numDirs = 0;
    if (!rd(read, opt + 108, numDirs) || numDirs < 2) return fail("no import directory");
    uint32_t impRva = 0, impSize = 0;
    if (!rd(read, opt + 112 + 8, impRva) || !rd(read, opt + 112 + 12, impSize)) return fail("import directory unreadable");
    if (impRva == 0) return fail("no import directory");

    bool sawDll = false;
    for (int d = 0; d < kMaxDescriptors; ++d) {
        const uint64_t desc = uint64_t{impRva} + 20ull * static_cast<uint64_t>(d);
        uint32_t oft = 0, nameRva = 0, ft = 0;
        if (!rd(read, desc, oft) || !rd(read, desc + 12, nameRva) || !rd(read, desc + 16, ft))
            return fail("import descriptor unreadable");
        if (oft == 0 && nameRva == 0 && ft == 0) break;  // the null terminator
        if (nameRva == 0) continue;
        bool ok = true;
        if (!name_is(read, nameRva, dll, ok)) {
            if (!ok) return fail("import name unreadable");
            continue;
        }
        if (oft == 0 || ft == 0) return fail(std::string(dll) + " has no OriginalFirstThunk");
        for (int t = 0; t < kMaxThunks; ++t) {
            uint64_t thunk = 0;
            if (!rd(read, uint64_t{oft} + 8ull * static_cast<uint64_t>(t), thunk)) return fail("thunk unreadable");
            if (thunk == 0) break;
            const bool byOrdinal = (thunk >> 63) != 0;
            if (byOrdinal && static_cast<uint16_t>(thunk & 0xFFFF) == ordinal) {
                const uint64_t slot = uint64_t{ft} + 8ull * static_cast<uint64_t>(t);
                uint64_t probe = 0;
                if (!rd(read, slot, probe)) return fail("IAT slot unreadable");
                ImportSlot s;
                s.found = true;
                s.iatRva = static_cast<uint32_t>(slot);
                return s;
            }
        }
        sawDll = true;  // another descriptor may name the same DLL; keep looking
    }
    if (sawDll) return fail(std::string(dll) + " ordinal " + std::to_string(ordinal) + " not imported");
    return fail(std::string(dll) + " not imported");
}

}  // namespace diptych::core
