#pragma once
#include <cstddef>
#include <deque>
#include <mutex>
#include <string>
#include <vector>

namespace diptych::core {

// Thread-safe in-memory ring of the most recent log lines.
class LogRing {
public:
    static constexpr size_t kCapacity = 500;

    void add(std::string line);
    // The newest min(n, size) lines, oldest first.
    std::vector<std::string> last(size_t n) const;

private:
    mutable std::mutex mu_;
    std::deque<std::string> lines_;
};

}  // namespace diptych::core
