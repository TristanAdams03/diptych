#pragma once
// kh1.watch (protocol/kh1/PROTOCOL.md): memory regions a client asks to see, as hex, in each of
// its kh1.spike state frames. Read-only. Portable; the runtime keeps one WatchSet per connection.
#include <cstddef>
#include <cstdint>
#include <vector>

#include <nlohmann/json.hpp>

#include "core/kh1_fields.h"

namespace diptych::core {

inline constexpr size_t kWatchMaxRegions = 8;
inline constexpr size_t kWatchMaxLength = 256;   // bytes per region
inline constexpr size_t kWatchMaxTotal = 1024;   // bytes over all regions

struct WatchRegion {
    uintptr_t offset = 0;  // from the module base
    size_t length = 0;     // 1..kWatchMaxLength
};

struct WatchSet {
    std::vector<WatchRegion> regions;  // in the client's order; empty = no watch
};

// Validates kh1.watch args {regions:[{offset:int, length:int}]}. `regions` missing or not an array,
// an element that is not an object, or a missing/non-integer offset or length -> Fail{"bad command"}.
// More than 8 regions, a length outside 1..256, a total over 1024 bytes, or a region not wholly
// inside [0, imageSize) -> Fail{"out of range"}. An empty array is valid (it clears the watch).
WatchSet parse_watch(const nlohmann::json& args, size_t imageSize);

// The state frame's `regions` array: [{offset:int, hex:string|null}] in watch order. Each region is
// read on its own; one that fails, throws or comes back short has hex null. Never throws for a read.
nlohmann::json read_watch(IMemory& mem, const WatchSet& watch);

}  // namespace diptych::core
