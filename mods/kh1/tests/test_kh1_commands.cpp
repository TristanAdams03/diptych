#include <doctest/doctest.h>

#include <string>

#include <nlohmann/json.hpp>

#include "core/kh1_commands.h"
#include "core/kh1_fields.h"
#include "core/log_ring.h"
#include "fake_memory.h"

using namespace diptych::core;
using nlohmann::json;

namespace {
json run(FakeMemory& m, bool supported, const std::string& name, const json& args, PressScheduler& p,
         const LogRing* log = nullptr) {
    return json::parse(run_kh1_command(m, supported, "c1", name, args, p, log));
}

void check_err(const json& r, const char* err) {
    CHECK(r["type"] == "result");
    CHECK(r["id"] == "c1");
    CHECK(r["ok"] == false);
    CHECK(r["error"] == err);
    REQUIRE(r.contains("data"));
    CHECK(r["data"].is_null());
}

json press_args(int byteIndex, int mask, int durationMs) {
    return {{"byteIndex", byteIndex}, {"mask", mask}, {"durationMs", durationMs}};
}
}  // namespace

TEST_CASE("unsupported_build_blocks_all: commands") {
    FakeMemory m;
    PressScheduler p;
    check_err(run(m, false, "kh1.probe", {{"offset", 0}, {"length", 4}}, p), "unsupported build");
    check_err(run(m, false, "kh1.pressInput", press_args(0, 16, 100), p), "unsupported build");
    check_err(run(m, false, "kh1.nope", json::object(), p), "unknown command");
    CHECK(m.reads == 0);
    CHECK(m.writes.empty());
    CHECK_FALSE(p.active());
}

TEST_CASE("kh1.log works on any build and returns the last 200 lines") {
    FakeMemory m;
    PressScheduler p;
    LogRing ring;
    for (int i = 0; i < 300; ++i) ring.add("line " + std::to_string(i));
    for (bool supported : {false, true}) {
        const json r = run(m, supported, "kh1.log", json::object(), p, &ring);
        CHECK(r["ok"] == true);
        CHECK(r["error"].is_null());
        REQUIRE(r["data"]["lines"].size() == 200);
        CHECK(r["data"]["lines"][0] == "line 100");
        CHECK(r["data"]["lines"][199] == "line 299");
    }
    const json empty = run(m, false, "kh1.log", json::object(), p);
    CHECK(empty["ok"] == true);
    CHECK(empty["data"]["lines"] == json::array());
}

TEST_CASE("probe_bounds") {
    FakeMemory m;
    m.set32(kWorldOffset, 0x03);
    PressScheduler p;
    SUBCASE("valid probe returns hex") {
        const json r = run(m, true, "kh1.probe", {{"offset", 36961940}, {"length", 4}}, p);
        CHECK(r["ok"] == true);
        CHECK(r["error"].is_null());
        CHECK(r["data"] == json{{"offset", 36961940}, {"hex", "03000000"}});
        CHECK(r["data"]["offset"] == kWorldOffset);
    }
    SUBCASE("lengths 1 and 256") {
        CHECK(run(m, true, "kh1.probe", {{"offset", 0}, {"length", 1}}, p)["data"]["hex"] == "00");
        CHECK(run(m, true, "kh1.probe", {{"offset", 0}, {"length", 256}}, p)["data"]["hex"].get<std::string>().size() == 512);
    }
    SUBCASE("offset == image_size") {
        check_err(run(m, true, "kh1.probe", {{"offset", m.size}, {"length", 1}}, p), "out of range");
    }
    SUBCASE("read crossing the image end") {
        check_err(run(m, true, "kh1.probe", {{"offset", m.size - 2}, {"length", 4}}, p), "out of range");
        CHECK(run(m, true, "kh1.probe", {{"offset", m.size - 4}, {"length", 4}}, p)["ok"] == true);
    }
    SUBCASE("length out of range") {
        check_err(run(m, true, "kh1.probe", {{"offset", 0}, {"length", 257}}, p), "out of range");
        check_err(run(m, true, "kh1.probe", {{"offset", 0}, {"length", 0}}, p), "out of range");
    }
    SUBCASE("negative and huge offsets") {
        check_err(run(m, true, "kh1.probe", {{"offset", -1}, {"length", 1}}, p), "out of range");
        check_err(run(m, true, "kh1.probe", {{"offset", UINT64_MAX}, {"length", 1}}, p), "out of range");
    }
    SUBCASE("faulted read") {
        m.faults.insert(0x100);
        check_err(run(m, true, "kh1.probe", {{"offset", 0xFE}, {"length", 4}}, p), "out of range");
        m.throws.insert(0x200);
        check_err(run(m, true, "kh1.probe", {{"offset", 0x200}, {"length", 1}}, p), "out of range");
    }
    SUBCASE("bad args") {
        check_err(run(m, true, "kh1.probe", {{"offset", 0}}, p), "bad command");
        check_err(run(m, true, "kh1.probe", {{"length", 4}}, p), "bad command");
        check_err(run(m, true, "kh1.probe", {{"offset", "0"}, {"length", 4}}, p), "bad command");
        check_err(run(m, true, "kh1.probe", {{"offset", 0.5}, {"length", 4}}, p), "bad command");
        check_err(run(m, true, "kh1.probe", {{"offset", true}, {"length", 4}}, p), "bad command");
    }
}

namespace {
// A scheduler wired to an InjectState, as the runtime does once the XInput hook is installed.
struct Press {
    InjectState inject;
    PressScheduler p;
    Press() { p.attach(&inject); }
};

// What the hook would report for controller 0 with nothing physically pressed.
PadInject seen(const InjectState& s, uint32_t* packet = nullptr) {
    uint32_t pk = 0;
    PadInject out;
    s.apply(pk, out.buttons, out.leftTrigger, out.rightTrigger);
    if (packet) *packet = pk;
    return out;
}
}  // namespace

TEST_CASE("press_refused_when_unsafe") {
    Press x;
    PressScheduler& p = x.p;
    SUBCASE("menu") {
        FakeMemory m;
        m.set(kMenuOffset, 1);
        check_err(run(m, true, "kh1.pressInput", press_args(1, 0x40, 100), p), "not safe now");
        CHECK_FALSE(p.active());
        for (int t = 0; t < 10; ++t) p.tick(m, t);
        CHECK_FALSE(x.inject.active());
    }
    SUBCASE("cutscene") {
        FakeMemory m;
        m.set(kCutsceneOffset + 1, 2);
        check_err(run(m, true, "kh1.pressInput", press_args(1, 0x40, 100), p), "not safe now");
    }
    SUBCASE("gummi") {
        FakeMemory m;
        m.set32(kGummiOffset, 1);
        check_err(run(m, true, "kh1.pressInput", press_args(1, 0x40, 100), p), "not safe now");
    }
    SUBCASE("unreadable flag") {
        FakeMemory m;
        m.faults.insert(kMenuOffset);
        check_err(run(m, true, "kh1.pressInput", press_args(1, 0x40, 100), p), "not safe now");
    }
    SUBCASE("xinput hook not installed") {
        FakeMemory m;
        PressScheduler bare;
        CHECK_FALSE(bare.available());
        check_err(run(m, true, "kh1.pressInput", press_args(1, 0x40, 100), bare), "not safe now");
        CHECK_FALSE(bare.active());
    }
    SUBCASE("all clear: ok, injects A for durationMs, then releases") {
        FakeMemory m;
        const json r = run(m, true, "kh1.pressInput", press_args(1, 0x40, 30), p);
        CHECK(r["ok"] == true);
        CHECK(r["error"].is_null());
        CHECK(r["data"].is_null());
        CHECK_FALSE(x.inject.active());  // the command only schedules; ticks inject
        CHECK(p.active());
        uint32_t pk0 = 0, pk = 0;
        seen(x.inject, &pk0);
        int injectedTicks = 0;
        for (int t = 1000; t < 1050; ++t) {
            p.tick(m, t);
            if (x.inject.active()) {
                ++injectedTicks;
                CHECK(seen(x.inject, &pk) == PadInject{kXiA, 0, 0});
                CHECK(pk != pk0);
            }
        }
        CHECK(injectedTicks == 30);
        CHECK_FALSE(p.active());
        CHECK_FALSE(x.inject.active());
        CHECK(seen(x.inject, &pk) == PadInject{});
        CHECK(x.inject.generation() == 2);  // one bump on press, one on release
        CHECK(m.writes.empty());             // no game memory is written
        // The scheduler is free again.
        CHECK(run(m, true, "kh1.pressInput", press_args(0, 0x10, 16), p)["ok"] == true);
    }
    SUBCASE("busy while a press runs") {
        FakeMemory m;
        CHECK(run(m, true, "kh1.pressInput", press_args(1, 0x40, 100), p)["ok"] == true);
        p.tick(m, 0);
        check_err(run(m, true, "kh1.pressInput", press_args(1, 0x20, 100), p), "busy");
        CHECK(seen(x.inject) == PadInject{kXiA, 0, 0});
    }
    SUBCASE("a press ends early when the game leaves the safe state") {
        FakeMemory m;
        CHECK(run(m, true, "kh1.pressInput", press_args(1, 0x01, 100), p)["ok"] == true);
        p.tick(m, 0);
        p.tick(m, 1);
        CHECK(seen(x.inject) == PadInject{0, 255, 0});
        m.set(kMenuOffset, 1);
        p.tick(m, 2);
        CHECK_FALSE(p.active());
        CHECK_FALSE(x.inject.active());
        auto lines = p.take_log();
        REQUIRE(lines.size() == 2);
        CHECK(lines[1].find("press end (aborted: not safe now)") != std::string::npos);
    }
    SUBCASE("unsafe on the first tick: never injects") {
        FakeMemory m;
        CHECK(run(m, true, "kh1.pressInput", press_args(1, 0x40, 100), p)["ok"] == true);
        m.set32(kGummiOffset, 1);
        p.tick(m, 0);
        CHECK_FALSE(p.active());
        CHECK(x.inject.generation() == 0);
    }
}

TEST_CASE("pressInput refuses once a safety-gate reader is disabled") {
    // The press ticks read through a DisablingMemory. After one transient fault the reader
    // stays off, so every later press would abort on its first tick; the command must say so.
    Press x;
    PressScheduler& p = x.p;
    FakeMemory m;
    DisablingMemory g(m);
    m.faults.insert(kMenuOffset);
    CHECK_FALSE(safe_now(g));  // the transient fault turns the reader off
    m.faults.clear();
    check_err(json::parse(run_kh1_command(m, true, "c1", "kh1.pressInput", press_args(1, 0x40, 100), p, nullptr, &g)),
              "not safe now");
    CHECK_FALSE(p.active());
    // Raw memory alone has recovered; without the gate the press would be accepted.
    CHECK(run(m, true, "kh1.pressInput", press_args(1, 0x40, 100), p)["ok"] == true);
}

TEST_CASE("pressInput args") {
    FakeMemory m;
    Press x;
    PressScheduler& p = x.p;
    for (int bi : {-1, 2, 3, 13, 17, 31, 32}) check_err(run(m, true, "kh1.pressInput", press_args(bi, 0x40, 100), p), "out of range");
    check_err(run(m, true, "kh1.pressInput", press_args(1, 256, 100), p), "out of range");
    check_err(run(m, true, "kh1.pressInput", press_args(1, -1, 100), p), "out of range");
    check_err(run(m, true, "kh1.pressInput", press_args(0, 0x08, 100), p), "out of range");  // start
    check_err(run(m, true, "kh1.pressInput", press_args(0, 0x18, 100), p), "out of range");  // start + up
    check_err(run(m, true, "kh1.pressInput", press_args(1, 0x40, 15), p), "out of range");
    check_err(run(m, true, "kh1.pressInput", press_args(1, 0x40, 1001), p), "out of range");
    check_err(run(m, true, "kh1.pressInput", {{"byteIndex", 1}, {"mask", 0x40}}, p), "bad command");
    check_err(run(m, true, "kh1.pressInput", {{"byteIndex", "1"}, {"mask", 0x40}, {"durationMs", 100}}, p),
              "bad command");
    CHECK_FALSE(p.active());
    CHECK(run(m, true, "kh1.pressInput", press_args(1, 255, 1000), p)["ok"] == true);
    p.tick(m, 0);
    CHECK(seen(x.inject) ==
          PadInject{static_cast<uint16_t>(kXiA | kXiB | kXiX | kXiY | kXiLeftShoulder | kXiRightShoulder), 255, 255});
}

TEST_CASE("pressInput byte 0 drives the d-pad") {
    FakeMemory m;
    Press x;
    CHECK(run(m, true, "kh1.pressInput", press_args(0, 0x10 | 0x80, 100), x.p)["ok"] == true);
    x.p.tick(m, 0);
    CHECK(seen(x.inject) == PadInject{static_cast<uint16_t>(kXiDpadUp | kXiDpadLeft), 0, 0});
}

TEST_CASE("press log lines") {
    FakeMemory m;
    Press x;
    x.p.start(1, 0x20, PadInject{kXiB, 0, 0}, 16);
    for (int t = 0; t < 20; ++t) x.p.tick(m, t);
    auto lines = x.p.take_log();
    REQUIRE(lines.size() == 2);
    CHECK(lines[0] == "press start byte 1 mask 32 for 16 ms (xinput buttons 0x2000 lt 0 rt 0)");
    CHECK(lines[1] == "press end (done) after 16 ms");
    CHECK(x.p.take_log().empty());
}

TEST_CASE("kh1.log keeps the pinned startup lines first, then the last 200 others") {
    FakeMemory m;
    PressScheduler p;
    LogRing ring;
    ring.add("diptych_kh1 0.0.1-spike loaded", true);
    ring.add("build unknown not supported", true);
    ring.add("server listening on 127.0.0.1:7970", true);
    for (int i = 0; i < 1000; ++i) ring.add("debug " + std::to_string(i));
    ring.add("build classification final: unknown", true);
    for (int i = 1000; i < 1100; ++i) ring.add("debug " + std::to_string(i));
    for (bool supported : {false, true}) {
        const json r = run(m, supported, "kh1.log", json::object(), p, &ring);
        REQUIRE(r["ok"] == true);
        const json& lines = r["data"]["lines"];
        REQUIRE(lines.size() == 204);
        CHECK(lines[0] == "diptych_kh1 0.0.1-spike loaded");
        CHECK(lines[1] == "build unknown not supported");
        CHECK(lines[2] == "server listening on 127.0.0.1:7970");
        CHECK(lines[3] == "build classification final: unknown");
        CHECK(lines[4] == "debug 900");
        CHECK(lines[203] == "debug 1099");
    }
}
