#pragma once
// In-memory stand-in for the game image: unset bytes read as 0, offsets in `faults` read as
// nullopt (like an SEH-caught fault), offsets in `throws` throw, `writeFaults` refuse writes, and every write is recorded.
#include <cstdint>
#include <map>
#include <optional>
#include <set>
#include <stdexcept>
#include <utility>
#include <vector>

#include "core/kh1_fields.h"

struct FakeMemory : diptych::core::IMemory {
    size_t size = 0x3000000;
    std::map<uintptr_t, uint8_t> mem;
    std::set<uintptr_t> faults;
    std::set<uintptr_t> throws;
    std::set<uintptr_t> writeFaults;  // reads fine, writes fail
    std::vector<std::pair<uintptr_t, uint8_t>> writes;
    int reads = 0;

    void set(uintptr_t off, uint8_t v) { mem[off] = v; }
    void set32(uintptr_t off, uint32_t v) {
        for (int i = 0; i < 4; ++i) mem[off + i] = static_cast<uint8_t>(v >> (8 * i));
    }

    std::optional<std::vector<uint8_t>> bytes(uintptr_t off, size_t n) override {
        ++reads;
        if (off >= size || n > size - off) return std::nullopt;
        std::vector<uint8_t> out(n);
        for (size_t i = 0; i < n; ++i) {
            if (throws.count(off + i)) throw std::runtime_error("fake fault");
            if (faults.count(off + i)) return std::nullopt;
            auto it = mem.find(off + i);
            out[i] = it == mem.end() ? 0 : it->second;
        }
        return out;
    }
    std::optional<uint8_t> u8(uintptr_t off) override {
        auto b = bytes(off, 1);
        if (!b) return std::nullopt;
        return (*b)[0];
    }
    std::optional<uint32_t> u32(uintptr_t off) override {
        auto b = bytes(off, 4);
        if (!b) return std::nullopt;
        return uint32_t((*b)[0]) | uint32_t((*b)[1]) << 8 | uint32_t((*b)[2]) << 16 | uint32_t((*b)[3]) << 24;
    }
    bool write_u8(uintptr_t off, uint8_t v) override {
        if (off >= size || faults.count(off) || writeFaults.count(off)) return false;
        writes.emplace_back(off, v);
        mem[off] = v;
        return true;
    }
    size_t image_size() override { return size; }
};
