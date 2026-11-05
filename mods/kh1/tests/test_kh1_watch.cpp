#include <doctest/doctest.h>

#include <optional>
#include <string>

#include <nlohmann/json.hpp>

#include "core/command_args.h"
#include "core/kh1_commands.h"
#include "core/kh1_watch.h"
#include "fake_memory.h"

using namespace diptych::core;
using nlohmann::json;

namespace {
constexpr size_t kImage = 0x3000000;
constexpr int64_t kBattle = 0x2D5CC4C;

json regions(std::initializer_list<std::pair<int64_t, int64_t>> rs) {
    json a = json::array();
    for (auto [o, l] : rs) a.push_back({{"offset", o}, {"length", l}});
    return json{{"regions", a}};
}

// The error string parse_watch throws, or "" when it accepts.
std::string watch_error(const json& args, size_t image = kImage) {
    try {
        parse_watch(args, image);
        return "";
    } catch (const Fail& f) {
        return f.error;
    }
}
}  // namespace

TEST_CASE("parse_watch accepts one battle-block region") {
    const WatchSet w = parse_watch(regions({{kBattle, 16}}), kImage);
    REQUIRE(w.regions.size() == 1);
    CHECK(w.regions[0].offset == 0x2D5CC4C);
    CHECK(w.regions[0].length == 16);
}

TEST_CASE("parse_watch accepts the limits and an empty list") {
    CHECK(parse_watch(json{{"regions", json::array()}}, kImage).regions.empty());
    json eight = json::array();
    for (int i = 0; i < 8; ++i) eight.push_back({{"offset", i * 256}, {"length", 128}});
    CHECK(parse_watch(json{{"regions", eight}}, kImage).regions.size() == 8);  // 1024 bytes total
    CHECK(parse_watch(regions({{static_cast<int64_t>(kImage) - 256, 256}}), kImage).regions.size() == 1);
}

TEST_CASE("parse_watch rejects out-of-range regions") {
    json nine = json::array();
    for (int i = 0; i < 9; ++i) nine.push_back({{"offset", i}, {"length", 1}});
    CHECK(watch_error(json{{"regions", nine}}) == "out of range");
    CHECK(watch_error(regions({{kBattle, 0}})) == "out of range");
    CHECK(watch_error(regions({{kBattle, 257}})) == "out of range");
    CHECK(watch_error(regions({{0, 256}, {256, 256}, {512, 256}, {768, 256}, {1024, 1}})) == "out of range");  // 1025
    CHECK(watch_error(regions({{static_cast<int64_t>(kImage) - 15, 16}})) == "out of range");
    CHECK(watch_error(regions({{static_cast<int64_t>(kImage), 1}})) == "out of range");
    CHECK(watch_error(regions({{-1, 4}})) == "out of range");
}

TEST_CASE("parse_watch rejects missing or ill-typed fields") {
    CHECK(watch_error(json::object()) == "bad command");
    CHECK(watch_error(json{{"regions", 5}}) == "bad command");
    CHECK(watch_error(json{{"regions", json::array({1})}}) == "bad command");
    CHECK(watch_error(json{{"regions", json::array({{{"offset", "0x2D5CC4C"}, {"length", 16}}})}}) == "bad command");
    CHECK(watch_error(json{{"regions", json::array({{{"offset", 1.5}, {"length", 16}}})}}) == "bad command");
    CHECK(watch_error(json{{"regions", json::array({{{"offset", kBattle}}})}}) == "bad command");
}

TEST_CASE("read_watch returns hex per region") {
    FakeMemory m;
    m.set32(kBattle, 55);
    m.set32(kBattle + 4, 57);
    m.set32(kBattle + 8, 5);
    m.set32(kBattle + 12, 5);
    const json r = read_watch(m, parse_watch(regions({{kBattle, 16}}), kImage));
    REQUIRE(r.size() == 1);
    CHECK(r[0]["offset"] == kBattle);
    CHECK(r[0]["hex"] == "37000000390000000500000005000000");
}

TEST_CASE("read_watch nulls only a faulted region") {
    FakeMemory m;
    m.set(0x100, 0xab);
    m.faults.insert(0x2001);
    m.throws.insert(0x3000);
    const json r = read_watch(m, parse_watch(regions({{0x2000, 4}, {0x100, 1}, {0x3000, 2}}), kImage));
    REQUIRE(r.size() == 3);
    CHECK(r[0]["offset"] == 0x2000);
    CHECK(r[0]["hex"].is_null());
    CHECK(r[1]["hex"] == "ab");
    CHECK(r[2]["hex"].is_null());
}

TEST_CASE("kh1.watch command") {
    FakeMemory m;
    PressScheduler p;
    std::optional<WatchSet> w;
    auto run = [&](bool supported, const json& args) {
        return json::parse(run_kh1_command(m, supported, "w1", "kh1.watch", args, p, nullptr, nullptr, &w));
    };
    json r = run(true, regions({{kBattle, 16}, {0x2DE992C, 256}}));
    CHECK(r["ok"] == true);
    CHECK(r["data"] == json{{"count", 2}});
    REQUIRE(w.has_value());
    CHECK(w->regions.size() == 2);

    r = run(true, json{{"regions", json::array()}});
    CHECK(r["data"]["count"] == 0);
    REQUIRE(w.has_value());
    CHECK(w->regions.empty());

    r = run(false, regions({{kBattle, 16}}));
    CHECK(r["error"] == "unsupported build");
    CHECK_FALSE(w.has_value());
    r = run(true, regions({{kBattle, 300}}));
    CHECK(r["error"] == "out of range");
    CHECK_FALSE(w.has_value());
    r = run(true, json::object());
    CHECK(r["error"] == "bad command");

    // Without somewhere to keep the watch, the command cannot succeed.
    r = json::parse(run_kh1_command(m, true, "w1", "kh1.watch", regions({{kBattle, 16}}), p));
    CHECK(r["error"] == "internal error");
    CHECK(m.writes.empty());
}
