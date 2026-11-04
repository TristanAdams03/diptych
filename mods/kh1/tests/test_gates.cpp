#include <doctest/doctest.h>

#include <cstdint>
#include <map>
#include <optional>
#include <string>

#include "core/log_ring.h"
#include "core/process_gate.h"
#include "core/version_gate.h"

using namespace diptych::core;

TEST_CASE("process_gate: only the KH1 executable, case-insensitive basename") {
    CHECK(is_kh1_process(L"C:\\x\\KINGDOM HEARTS FINAL MIX.exe"));
    CHECK(is_kh1_process(L"kingdom hearts final mix.EXE"));
    CHECK(is_kh1_process(L"Z:/games/KH/KINGDOM HEARTS FINAL MIX.exe"));
    CHECK_FALSE(is_kh1_process(L"C:\\x\\KINGDOM HEARTS II FINAL MIX.exe"));
    CHECK_FALSE(is_kh1_process(L"C:\\x\\KINGDOM HEARTS HD 1.5+2.5 Launcher.exe"));
    CHECK_FALSE(is_kh1_process(L"C:\\x\\KINGDOM HEARTS FINAL MIX.exe.bak"));
    CHECK_FALSE(is_kh1_process(L"C:\\KINGDOM HEARTS FINAL MIX.exe\\other.exe"));
    CHECK_FALSE(is_kh1_process(L""));
}

namespace {
ByteReader bytes_at(std::map<uintptr_t, uint8_t> m) {
    return [m](uintptr_t off) -> std::optional<uint8_t> {
        auto it = m.find(off);
        if (it == m.end()) return std::nullopt;
        return it->second;
    };
}
}  // namespace

TEST_CASE("version_gate: KH1-LUA-LIBRARY VersionCheck bytes") {
    CHECK(classify_build(bytes_at({{0x4698D2, 0x6A}})) == Build::Steam1002);
    CHECK(classify_build(bytes_at({{0x46A822, 0x6A}})) == Build::Egs10010);
    CHECK(classify_build(bytes_at({})) == Build::Unknown);
    CHECK(classify_build(bytes_at({{0x4698D2, 0x6B}})) == Build::Unknown);
    CHECK(classify_build(bytes_at({{0x4698D2, 0x6B}, {0x46A822, 0x6B}})) == Build::Unknown);
    CHECK(classify_build(nullptr) == Build::Unknown);
}

TEST_CASE("version_gate: build names") {
    CHECK(std::string(build_name(Build::Steam1002)) == "steam-1.0.0.2");
    CHECK(std::string(build_name(Build::Egs10010)) == "egs-1.0.0.10");
    CHECK(std::string(build_name(Build::Unknown)) == "unknown");
}

TEST_CASE("log_ring: keeps the last 500 lines in order") {
    LogRing r;
    CHECK(r.last(10).empty());
    for (int i = 0; i < 1234; ++i) r.add("line " + std::to_string(i));
    auto all = r.last(100000);
    REQUIRE(all.size() == LogRing::kCapacity);
    CHECK(LogRing::kCapacity == 500);
    CHECK(all.front() == "line 734");
    CHECK(all.back() == "line 1233");
    for (size_t i = 1; i < all.size(); ++i) CHECK(all[i] == "line " + std::to_string(734 + i));
    auto three = r.last(3);
    REQUIRE(three.size() == 3);
    CHECK(three[0] == "line 1231");
    CHECK(three[2] == "line 1233");
    CHECK(r.last(0).empty());
}

#include "core/log_file_cap.h"

TEST_CASE("log_file_cap: default cap is 1 MiB") {
    CHECK(LogFileCap::kDefaultCap == 1024u * 1024u);
}

TEST_CASE("log_file_cap: one final marker line at the cap, nothing after it") {
    const size_t cap = 200;
    LogFileCap c(cap);
    std::string file;
    int markers = 0;
    size_t writesAfterMarker = 0;
    for (int i = 0; i < 100; ++i) {
        std::string out = c.next("line number " + std::to_string(i));
        if (markers > 0 && !out.empty()) ++writesAfterMarker;
        if (out.find(LogFileCap::kMarker) != std::string::npos) ++markers;
        file += out;
    }
    CHECK(markers == 1);
    CHECK(writesAfterMarker == 0);
    CHECK(c.capped());
    CHECK(file.size() <= cap);
    CHECK(c.written() == file.size());
    // The marker is the last line of the file.
    const std::string tail = std::string(LogFileCap::kMarker) + "\r\n";
    REQUIRE(file.size() >= tail.size());
    CHECK(file.compare(file.size() - tail.size(), tail.size(), tail) == 0);
    // Lines before it are whole, CRLF-terminated.
    CHECK(file.rfind("line number 0\r\n", 0) == 0);
    CHECK(c.next("more").empty());
}

TEST_CASE("log_file_cap: lines that fit are written verbatim with CRLF") {
    LogFileCap c(1024);
    CHECK(c.next("hello") == "hello\r\n");
    CHECK(c.written() == 7);
    CHECK_FALSE(c.capped());
}

TEST_CASE("log_file_cap: a single oversized line trips the cap immediately") {
    LogFileCap c(64);
    std::string out = c.next(std::string(1000, 'x'));
    CHECK(out == std::string(LogFileCap::kMarker) + "\r\n");
    CHECK(c.capped());
    CHECK(c.next("y").empty());
}
