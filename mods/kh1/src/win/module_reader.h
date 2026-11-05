#pragma once
#include <cstdint>

#include "core/version_gate.h"

namespace diptych::win {

// SizeOfImage from the PE optional header of a mapped module, or 0 if the headers are not a
// valid PE image (or cannot be read).
uint32_t module_image_size(const void* moduleBase);

// A reader of moduleBase + offset that refuses offsets >= SizeOfImage and reads through the SEH
// guard. After the first fault the reader is disabled and returns nullopt for the rest of its life.
core::ByteReader module_byte_reader(const void* moduleBase);

}  // namespace diptych::win
