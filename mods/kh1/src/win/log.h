#pragma once
#include <string>
#include <string_view>

#include "core/log_ring.h"

namespace diptych::win {

// The process-wide ring (leaked on purpose: no destructor runs at DLL detach).
core::LogRing& log_ring();

// Adds a timestamped line to the ring and, once enabled, appends it to diptych_kh1.log.
// pin: a startup line that kh1.log must always return (see core::LogRing::add).
void log_line(std::string_view msg, bool pin = false);

// Rotates diptych_kh1.log beside this DLL to diptych_kh1.log.1 and opens a fresh one, capped at
// 1 MiB per session (then one "log size cap reached" line; the ring continues). Only called in the KH1 process, so other
// processes never touch the disk. If the folder is not writable the log stays in memory only.
void enable_file_log();

// UTF-16 -> UTF-8 for log messages.
std::string narrow(std::wstring_view w);

}  // namespace diptych::win
