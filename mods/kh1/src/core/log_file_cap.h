#pragma once
#include <cstddef>
#include <string>
#include <string_view>

namespace diptych::core {

// Size accounting for the on-disk log. Each line costs its length plus CRLF; room for the final
// marker line is always reserved, so the file never exceeds the cap. When the next line would not
// fit, next() returns the marker line once and everything after that returns "".
class LogFileCap {
public:
    static constexpr size_t kDefaultCap = 1024 * 1024;
    static constexpr const char* kMarker = "log size cap reached";

    explicit LogFileCap(size_t cap = kDefaultCap) : cap_(cap) {}

    // The bytes to write to the file for this line (possibly the marker, or empty).
    std::string next(std::string_view line);

    size_t written() const { return written_; }
    bool capped() const { return capped_; }

private:
    size_t cap_;
    size_t written_ = 0;
    bool capped_ = false;
};

}  // namespace diptych::core
