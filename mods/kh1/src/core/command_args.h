#pragma once
// Shared by the command handlers (kh1_commands.cpp, kh1_watch.cpp): the error a handler throws and
// the integer-argument check every command uses.
#include <cstdint>
#include <limits>

#include <nlohmann/json.hpp>

namespace diptych::core {

// Thrown by a command handler; `error` becomes the result's error string.
struct Fail {
    const char* error;
};

// An integer field of `obj`. Missing or not an integer -> "bad command"; outside [lo, hi] -> "out of range".
inline int64_t int_arg(const nlohmann::json& obj, const char* key, int64_t lo, int64_t hi) {
    auto it = obj.find(key);
    if (it == obj.end() || !it->is_number_integer()) throw Fail{"bad command"};
    if (it->is_number_unsigned()) {
        const uint64_t u = it->get<uint64_t>();
        if (u > static_cast<uint64_t>(std::numeric_limits<int64_t>::max())) throw Fail{"out of range"};
    }
    const int64_t v = it->get<int64_t>();
    if (v < lo || v > hi) throw Fail{"out of range"};
    return v;
}

}  // namespace diptych::core
