#include "core/log_ring.h"

#include <algorithm>

namespace diptych::core {

void LogRing::add(std::string line, bool pin) {
    std::lock_guard<std::mutex> lk(mu_);
    if (pin && pinned_.size() < kMaxPinned) {
        pinned_.push_back(std::move(line));
        return;
    }
    if (lines_.size() == kCapacity) lines_.pop_front();
    lines_.push_back(std::move(line));
}

std::vector<std::string> LogRing::last(size_t n) const {
    std::lock_guard<std::mutex> lk(mu_);
    n = std::min(n, lines_.size());
    return std::vector<std::string>(lines_.end() - static_cast<std::ptrdiff_t>(n), lines_.end());
}

std::vector<std::string> LogRing::pinned() const {
    std::lock_guard<std::mutex> lk(mu_);
    return pinned_;
}

}  // namespace diptych::core
