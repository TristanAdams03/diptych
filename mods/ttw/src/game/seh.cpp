// Deliberately tiny and object-free: __try/__except cannot share a function with C++
// objects that need unwinding (C2712), so the guarded bodies live in plain callbacks.
#include "game/seh.h"
#include <windows.h>

namespace ttwds::game {

static int seh_filter(unsigned long code, unsigned long* out) {
    if (out) *out = code;
    switch (code) {
        case EXCEPTION_ACCESS_VIOLATION:
        case EXCEPTION_ARRAY_BOUNDS_EXCEEDED:
        case EXCEPTION_DATATYPE_MISALIGNMENT:
        case EXCEPTION_IN_PAGE_ERROR:
        case EXCEPTION_ILLEGAL_INSTRUCTION:
        case EXCEPTION_PRIV_INSTRUCTION:
        case EXCEPTION_INT_DIVIDE_BY_ZERO:
        case EXCEPTION_FLT_INVALID_OPERATION:
        case EXCEPTION_FLT_DIVIDE_BY_ZERO:
            return EXCEPTION_EXECUTE_HANDLER;
        default:
            return EXCEPTION_CONTINUE_SEARCH;
    }
}

bool seh_call(void (*fn)(void*), void* ctx, unsigned long* code) {
    if (code) *code = 0;
    __try {
        fn(ctx);
        return true;
    } __except (seh_filter(GetExceptionCode(), code)) {
        return false;
    }
}

bool seh_copy_cstr(const char* src, char* dst, size_t n) {
    if (!dst || n == 0) return false;
    dst[0] = 0;
    if (!src) return false;
    __try {
        size_t i = 0;
        for (; i + 1 < n && src[i]; ++i) dst[i] = src[i];
        dst[i] = 0;
        return true;
    } __except (seh_filter(GetExceptionCode(), nullptr)) {
        dst[0] = 0;
        return false;
    }
}

}  // namespace ttwds::game
