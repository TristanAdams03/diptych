#pragma once
// Pure formatting helpers for the xNVSE layer (portable, unit-tested).
#include <array>
#include <cstdint>
#include <string>

namespace ttwds::game {

// NVSEInterface::nvseVersion is MAKE_NEW_VEGAS_VERSION(major, minor, beta)
// = major<<24 | minor<<16 | beta<<4 (xNVSE nvse_version.h:13-14). -> "6.4.9"
std::string xnvse_version_string(uint32_t packed);
// "0001A6B9": 8 uppercase hex digits.
std::string hex_form_id(uint32_t formId);
// Console line for EquipItem: NoUnequip 0 (may replace the current weapon), HideMessage 1.
std::string equip_line(uint32_t formId);
// SystemColorManager::GetColor returns 0xRRGGBBxx; JG GetSystemColor shifts >> 8
// (JG functions/fn_ui.cpp:193). -> {r, g, b}
std::array<int, 3> rgb_from_system_color(uint32_t raw);

}  // namespace ttwds::game
