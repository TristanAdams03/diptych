#include "core/log_file_cap.h"

namespace diptych::core {

std::string LogFileCap::next(std::string_view line) {
    if (capped_) return {};
    const std::string_view marker = kMarker;
    const size_t reserve = marker.size() + 2;
    const size_t cost = line.size() + 2;
    if (written_ + cost + reserve <= cap_) {
        written_ += cost;
        std::string out(line);
        out += "\r\n";
        return out;
    }
    capped_ = true;
    if (written_ + reserve > cap_) return {};  // cap smaller than the marker itself
    written_ += reserve;
    std::string out(marker);
    out += "\r\n";
    return out;
}

}  // namespace diptych::core
