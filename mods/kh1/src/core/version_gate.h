#pragma once
#include <cstdint>
#include <functional>
#include <optional>

namespace diptych::core {

// Builds of KINGDOM HEARTS FINAL MIX.exe, identified as in KH1-LUA-LIBRARY's VersionCheck.lua.
enum class Build { Steam1002, Egs10010, Unknown };

// Returns the byte at moduleBase + offset, or nullopt if it is out of range or unreadable.
using ByteReader = std::function<std::optional<uint8_t>(uintptr_t offset)>;

inline constexpr uintptr_t kSteam1002Offset = 0x4698D2;
inline constexpr uintptr_t kEgs10010Offset = 0x46A822;
inline constexpr uint8_t kVersionByte = 0x6A;

// Steam 1.0.0.2 if the byte at 0x4698D2 is 0x6A, else EGS 1.0.0.10 if the byte at 0x46A822 is
// 0x6A, else Unknown (also when readByte is empty).
Build classify_build(const ByteReader& readByte);

// "steam-1.0.0.2" / "egs-1.0.0.10" / "unknown".
const char* build_name(Build b);

}  // namespace diptych::core
