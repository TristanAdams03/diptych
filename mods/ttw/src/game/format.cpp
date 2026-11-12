#include "game/format.h"
#include <cstdio>

namespace ttwds::game {

std::string xnvse_version_string(uint32_t v) {
    char b[32];
    std::snprintf(b, sizeof b, "%u.%u.%u", (v >> 24) & 0xFFu, (v >> 16) & 0xFFu, (v >> 4) & 0xFFFu);
    return b;
}

std::string hex_form_id(uint32_t formId) {
    char b[16];
    std::snprintf(b, sizeof b, "%08X", formId);
    return b;
}

std::string equip_line(uint32_t formId) { return "player.equipitem " + hex_form_id(formId) + " 0 1"; }

std::array<int, 3> rgb_from_system_color(uint32_t raw) {
    const uint32_t c = raw >> 8;
    return {static_cast<int>((c >> 16) & 0xFF), static_cast<int>((c >> 8) & 0xFF), static_cast<int>(c & 0xFF)};
}

}  // namespace ttwds::game
