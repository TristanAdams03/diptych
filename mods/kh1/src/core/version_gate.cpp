#include "core/version_gate.h"

namespace diptych::core {

Build classify_build(const ByteReader& readByte) {
    if (!readByte) return Build::Unknown;
    if (readByte(kSteam1002Offset) == kVersionByte) return Build::Steam1002;
    if (readByte(kEgs10010Offset) == kVersionByte) return Build::Egs10010;
    return Build::Unknown;
}

const char* build_name(Build b) {
    switch (b) {
        case Build::Steam1002: return "steam-1.0.0.2";
        case Build::Egs10010: return "egs-1.0.0.10";
        case Build::Unknown: break;
    }
    return "unknown";
}

}  // namespace diptych::core
