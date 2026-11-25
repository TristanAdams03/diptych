#pragma once
// kh1.probe / kh1.pressInput / kh1.pressSequence / kh1.log / kh1.watch (protocol/kh1/PROTOCOL.md). Portable;
// runs on the runtime thread only, as does the SequenceScheduler (core/kh1_sequence.h).
#include <cstdint>
#include <optional>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>

#include "core/kh1_fields.h"
#include "core/kh1_sequence.h"
#include "core/kh1_watch.h"
#include "core/log_ring.h"

namespace diptych::core {

inline constexpr size_t kLogLines = 200;

// Runs one command and returns the complete `result` frame, or "" for an accepted kh1.pressSequence
// (its result comes later, from press.take_result(), when the sequence ends). Order of checks:
// unknown name -> "unknown command"; kh1.log always succeeds with the pinned lines of `log`, then its
// last 200 others (none if null); !supported -> "unsupported build"; missing/non-integer args ->
// "bad command"; out-of-range args or a faulted probe read (or a press byteIndex/mask with no XInput
// mapping, or start) -> "out of range"; a press or sequence while one runs -> "busy"; a press or
// sequence when !safe_now, without the XInput hook, or when a safety-gate reader (menu, cutscene,
// Gummi) is disabled -> "not safe now"; a sequence whose `require` does not hold (or cannot be read)
// -> "not safe now". Exceptions become "internal error".
// `gate` is the memory the press ticks use (the runtime's DisablingMemory); the press safety
// check runs on it so a reader that faulted earlier refuses the press up front. Null -> `mem`.
// Probes and a sequence's `require` always read `mem`, so they never turn off a state reader.
// kh1.watch validates its regions with parse_watch against mem.image_size() and, when ok, stores
// the new set in *watch (data {count}); the caller keeps it for the sending client. *watch is left
// empty for every other command and every failure; a null `watch` makes kh1.watch "internal error".
std::string run_kh1_command(IMemory& mem, bool supported, const std::string& id, const std::string& name,
                            const nlohmann::json& args, SequenceScheduler& press, const LogRing* log = nullptr,
                            IMemory* gate = nullptr, std::optional<WatchSet>* watch = nullptr);

}  // namespace diptych::core
