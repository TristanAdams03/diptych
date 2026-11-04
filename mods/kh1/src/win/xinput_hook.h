#pragma once
// Ruling W: kh1.pressInput injects through an IAT hook of XINPUT1_4.dll ordinal 2 (XInputGetState)
// in the main module. The hook calls the real function and, while a press is active, ORs the
// injection into controller 0's state (core::InjectState::apply). It is never removed (this DLL is
// pinned); with no press active it is a plain passthrough.
#include <string>

#include "core/xinput_inject.h"

namespace diptych::win {

// What the hook reads. The runtime's press scheduler sets it.
core::InjectState& xinput_inject_state();

// Runtime thread only, never DllMain. Finds the slot through the SEH-guarded image reader,
// VirtualProtects it, swaps the pointer atomically and restores the protection. Returns false (with
// the reason in `line`) when the import is missing, the walk faults or a protection change fails;
// the slot is then left untouched. At most one install per process; a second call returns false.
bool install_xinput_hook(const void* moduleBase, std::string& line);

}  // namespace diptych::win
