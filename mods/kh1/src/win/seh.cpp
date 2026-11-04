#include "win/seh.h"

#include <windows.h>

namespace diptych::win {

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

}  // namespace diptych::win
