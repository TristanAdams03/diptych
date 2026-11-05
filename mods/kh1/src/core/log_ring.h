#pragma once
#include <cstddef>
#include <deque>
#include <mutex>
#include <string>
#include <vector>

namespace diptych::core {

// Thread-safe in-memory ring of the most recent log lines, plus a small set of pinned lines
// (startup: loaded, build, server listening, classification final) that debug chatter can never
// push out. Pinned lines are kept apart from the ring and are not returned by last().
class LogRing {
public:
    static constexpr size_t kCapacity = 500;
    static constexpr size_t kMaxPinned = 32;

    // pin: keep the line in pinned() for the whole session. Once kMaxPinned lines are pinned,
    // further pinned lines go to the ring like any other.
    void add(std::string line, bool pin = false);
    // The newest min(n, size) unpinned lines, oldest first.
    std::vector<std::string> last(size_t n) const;
    // Pinned lines, oldest first.
    std::vector<std::string> pinned() const;

private:
    mutable std::mutex mu_;
    std::deque<std::string> lines_;
    std::vector<std::string> pinned_;
};

}  // namespace diptych::core
