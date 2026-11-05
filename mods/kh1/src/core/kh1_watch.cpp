#include "core/kh1_watch.h"

#include <limits>
#include <optional>

#include "core/command_args.h"

namespace diptych::core {

using nlohmann::json;

WatchSet parse_watch(const json& args, size_t imageSize) {
    auto it = args.find("regions");
    if (it == args.end() || !it->is_array()) throw Fail{"bad command"};
    if (it->size() > kWatchMaxRegions) throw Fail{"out of range"};
    WatchSet w;
    size_t total = 0;
    for (const auto& r : *it) {
        if (!r.is_object()) throw Fail{"bad command"};
        const int64_t offset = int_arg(r, "offset", 0, std::numeric_limits<int64_t>::max());
        const int64_t length = int_arg(r, "length", 1, static_cast<int64_t>(kWatchMaxLength));
        const uint64_t off = static_cast<uint64_t>(offset), len = static_cast<uint64_t>(length);
        if (off >= imageSize || len > imageSize - off) throw Fail{"out of range"};
        total += static_cast<size_t>(len);
        if (total > kWatchMaxTotal) throw Fail{"out of range"};
        w.regions.push_back({static_cast<uintptr_t>(off), static_cast<size_t>(len)});
    }
    return w;
}

json read_watch(IMemory& mem, const WatchSet& watch) {
    json out = json::array();
    for (const auto& r : watch.regions) {
        std::optional<std::vector<uint8_t>> b;
        try {
            b = mem.bytes(r.offset, r.length);
        } catch (...) {
            b.reset();
        }
        json hex = (b && b->size() == r.length) ? json(to_hex(*b)) : json(nullptr);
        out.push_back(json{{"offset", static_cast<uint64_t>(r.offset)}, {"hex", std::move(hex)}});
    }
    return out;
}

}  // namespace diptych::core
