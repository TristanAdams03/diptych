#pragma once
// Hardware-exception guard for game-memory reads (MSVC SEH), after ttw-dualscreen's
// plugin/src/game/seh.cpp. The guarded functions are tiny and object-free: __try/__except
// cannot share a function with C++ objects that need unwinding (C2712).
#include <cstddef>

namespace diptych::win {

// All three return false on an access violation, in-page error, misalignment, bounds fault or
// guard-page hit (PAGE_GUARD); any other exception propagates.

// Copies one byte from addr into *out. Returns false (leaving *out unchanged) if the read
// raises an access violation or another hardware fault.
bool seh_read_byte(const void* addr, unsigned char* out);

// Copies n bytes from src to dst. Returns false if any read faults (dst is then partial).
bool seh_copy(const void* src, void* dst, size_t n);

// Stores one byte at addr. Returns false if the write raises an access violation or another
// hardware fault (e.g. a read-only page).
bool seh_write_byte(void* addr, unsigned char v);

}  // namespace diptych::win
