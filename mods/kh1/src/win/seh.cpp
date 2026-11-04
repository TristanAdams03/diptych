#include "win/seh.h"

#include <windows.h>

namespace diptych::win {

// The one filter for every guarded access (seh_read_byte, seh_copy, seh_write_byte): reads of the
// version bytes, state readers, kh1.probe and the press write. Task 3 dropped EXCEPTION_GUARD_PAGE
// while this filter only guarded the fixed version-byte reads; kh1.probe now reads any offset in the
// image, and an uncaught STATUS_GUARD_PAGE_VIOLATION there would crash the game. By the time any
// filter runs the OS has already cleared the page's guard bit, so handling it costs nothing that
// passing it on would keep; it only turns a crash into "out of range". Our callers bounds-check
// against the module image, so thread-stack guard pages are never in reach.
static int seh_filter(unsigned long code) {
    switch (code) {
        case EXCEPTION_ACCESS_VIOLATION:
        case EXCEPTION_ARRAY_BOUNDS_EXCEEDED:
        case EXCEPTION_DATATYPE_MISALIGNMENT:
        case EXCEPTION_IN_PAGE_ERROR:
        case EXCEPTION_GUARD_PAGE:
            return EXCEPTION_EXECUTE_HANDLER;
        default:
            return EXCEPTION_CONTINUE_SEARCH;
    }
}

bool seh_read_byte(const void* addr, unsigned char* out) {
    __try {
        *out = *static_cast<const volatile unsigned char*>(addr);
        return true;
    } __except (seh_filter(GetExceptionCode())) {
        return false;
    }
}

bool seh_copy(const void* src, void* dst, size_t n) {
    __try {
        auto s = static_cast<const volatile unsigned char*>(src);
        auto d = static_cast<unsigned char*>(dst);
        for (size_t i = 0; i < n; ++i) d[i] = s[i];
        return true;
    } __except (seh_filter(GetExceptionCode())) {
        return false;
    }
}

bool seh_write_byte(void* addr, unsigned char v) {
    __try {
        *static_cast<volatile unsigned char*>(addr) = v;
        return true;
    } __except (seh_filter(GetExceptionCode())) {
        return false;
    }
}

}  // namespace diptych::win
