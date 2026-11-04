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

TEST_CASE("press_refused_when_unsafe") {
    PressScheduler p;
    SUBCASE("menu") {
        FakeMemory m;
        m.set(kMenuOffset, 1);
        check_err(run(m, true, "kh1.pressInput", press_args(0, 16, 100), p), "not safe now");
        CHECK(m.writes.empty());
        CHECK_FALSE(p.active());
        for (int t = 0; t < 10; ++t) p.tick(m, t);
        CHECK(m.writes.empty());
    }
    SUBCASE("cutscene") {
        FakeMemory m;
        m.set(kCutsceneOffset + 1, 2);
        check_err(run(m, true, "kh1.pressInput", press_args(0, 16, 100), p), "not safe now");
        CHECK(m.writes.empty());
    }
    SUBCASE("gummi") {
        FakeMemory m;
        m.set32(kGummiOffset, 1);
        check_err(run(m, true, "kh1.pressInput", press_args(0, 16, 100), p), "not safe now");
        CHECK(m.writes.empty());
    }
    SUBCASE("unreadable flag") {
        FakeMemory m;
        m.faults.insert(kMenuOffset);
        check_err(run(m, true, "kh1.pressInput", press_args(0, 16, 100), p), "not safe now");
        CHECK(m.writes.empty());
    }
    SUBCASE("all clear: ok, then writes stop after durationMs") {
        FakeMemory m;
        m.set(kInputOffset + 2, 0x01);
        const json r = run(m, true, "kh1.pressInput", press_args(2, 0x10, 30), p);
        CHECK(r["ok"] == true);
        CHECK(r["error"].is_null());
        CHECK(r["data"].is_null());
        CHECK(m.writes.empty());  // the command only schedules; ticks write
        CHECK(p.active());
        size_t lastWriteTick = 0;
        for (int t = 1000; t < 1050; ++t) {
            const size_t before = m.writes.size();
            p.tick(m, t);
            if (m.writes.size() != before) lastWriteTick = static_cast<size_t>(t - 1000);
        }
        // 30 press writes, then one release write on the tick that ends the press.
        REQUIRE(m.writes.size() == 31);
        CHECK(lastWriteTick == 30);
        for (size_t i = 0; i < 30; ++i) {
            CHECK(m.writes[i].first == kInputOffset + 2);
            CHECK(m.writes[i].second == 0x11);  // ORed into the existing byte
        }
        CHECK(m.writes[30].first == kInputOffset + 2);
        CHECK(m.writes[30].second == 0x01);  // the mask cleared, the game's own bit kept
        CHECK_FALSE(p.active());
        // The scheduler is free again.
        CHECK(run(m, true, "kh1.pressInput", press_args(0, 1, 16), p)["ok"] == true);
    }
    SUBCASE("busy while a press runs") {
        FakeMemory m;
        CHECK(run(m, true, "kh1.pressInput", press_args(0, 16, 100), p)["ok"] == true);
        p.tick(m, 0);
        check_err(run(m, true, "kh1.pressInput", press_args(1, 16, 100), p), "busy");
    }
    SUBCASE("a press aborts when the game leaves the safe state") {
        FakeMemory m;
        CHECK(run(m, true, "kh1.pressInput", press_args(0, 16, 100), p)["ok"] == true);
        p.tick(m, 0);
        p.tick(m, 1);
        CHECK(m.writes.size() == 2);
        m.set(kMenuOffset, 1);
        p.tick(m, 2);
        REQUIRE(m.writes.size() == 3);  // no more presses; one release write
        CHECK(m.writes[2].second == 0x00);
        CHECK_FALSE(p.active());
    }
    SUBCASE("no release write when the game changed the byte since the last press write") {
        FakeMemory m;
        CHECK(run(m, true, "kh1.pressInput", press_args(0, 16, 20), p)["ok"] == true);
        for (int t = 0; t < 20; ++t) p.tick(m, t);
        CHECK(m.writes.size() == 20);
        m.set(kInputOffset, 0x40);  // the game rewrote its input block
        p.tick(m, 20);
        CHECK(m.writes.size() == 20);
        CHECK(m.mem[kInputOffset] == 0x40);
        CHECK_FALSE(p.active());
    }
    SUBCASE("no release write when nothing was written") {
        FakeMemory m;
        CHECK(run(m, true, "kh1.pressInput", press_args(0, 16, 100), p)["ok"] == true);
        m.set(kMenuOffset, 1);
        p.tick(m, 0);
        CHECK(m.writes.empty());
        CHECK_FALSE(p.active());
    }
    SUBCASE("an unreadable input byte ends the press without the write-fault disable") {
        FakeMemory m;
        CHECK(run(m, true, "kh1.pressInput", press_args(1, 16, 100), p)["ok"] == true);
        m.faults.insert(kInputOffset + 1);
        p.tick(m, 0);
        CHECK(m.writes.empty());
        CHECK_FALSE(p.active());
        CHECK_FALSE(p.writes_disabled());
    }
    SUBCASE("a faulted write ends the press and disables further presses") {
        FakeMemory m;
        CHECK(run(m, true, "kh1.pressInput", press_args(0, 16, 100), p)["ok"] == true);
        m.writeFaults.insert(kInputOffset);
        p.tick(m, 0);
        CHECK(m.writes.empty());
        CHECK_FALSE(p.active());
        CHECK(p.writes_disabled());
        m.writeFaults.clear();
        check_err(run(m, true, "kh1.pressInput", press_args(0, 16, 100), p), "not safe now");
    }
}

TEST_CASE("pressInput refuses once a safety-gate reader is disabled") {
    // Minor 4: the press ticks read through a DisablingMemory. After one transient fault the reader
    // stays off, so every later press would abort on its first tick; the command must say so.
    PressScheduler p;
    SUBCASE("menu reader") {
        FakeMemory m;
        DisablingMemory g(m);
        m.faults.insert(kMenuOffset);
        CHECK_FALSE(safe_now(g));  // the transient fault turns the reader off
        m.faults.clear();
        check_err(json::parse(run_kh1_command(m, true, "c1", "kh1.pressInput", press_args(0, 16, 100), p, nullptr, &g)),
                  "not safe now");
        CHECK_FALSE(p.active());
        // Raw memory alone has recovered; without the gate the press would be accepted.
        CHECK(run(m, true, "kh1.pressInput", press_args(0, 16, 100), p)["ok"] == true);
    }
    SUBCASE("input byte reader") {
        FakeMemory m;
        DisablingMemory g(m);
        m.faults.insert(kInputOffset + 1);
        CHECK_FALSE(g.u8(kInputOffset + 1).has_value());
        m.faults.clear();
        check_err(json::parse(run_kh1_command(m, true, "c1", "kh1.pressInput", press_args(1, 16, 100), p, nullptr, &g)),
                  "not safe now");
        CHECK_FALSE(p.active());
        CHECK(json::parse(run_kh1_command(m, true, "c1", "kh1.pressInput", press_args(0, 16, 100), p, nullptr, &g))["ok"] ==
              true);
    }
}

TEST_CASE("pressInput args") {
    FakeMemory m;
    PressScheduler p;
    check_err(run(m, true, "kh1.pressInput", press_args(32, 16, 100), p), "out of range");
    check_err(run(m, true, "kh1.pressInput", press_args(-1, 16, 100), p), "out of range");
    check_err(run(m, true, "kh1.pressInput", press_args(0, 256, 100), p), "out of range");
    check_err(run(m, true, "kh1.pressInput", press_args(0, 16, 15), p), "out of range");
    check_err(run(m, true, "kh1.pressInput", press_args(0, 16, 1001), p), "out of range");
    check_err(run(m, true, "kh1.pressInput", {{"byteIndex", 0}, {"mask", 16}}, p), "bad command");
    check_err(run(m, true, "kh1.pressInput", {{"byteIndex", "0"}, {"mask", 16}, {"durationMs", 100}}, p),
              "bad command");
    CHECK(m.writes.empty());
    CHECK_FALSE(p.active());
    CHECK(run(m, true, "kh1.pressInput", press_args(3, 255, 1000), p)["ok"] == true);
}

TEST_CASE("pressInput reaches the last byte of the 32-byte input block") {
    FakeMemory m;
    PressScheduler p;
    CHECK(run(m, true, "kh1.pressInput", press_args(31, 64, 100), p)["ok"] == true);
    p.tick(m, 0);
    REQUIRE_FALSE(m.writes.empty());
    CHECK(m.writes.front().first == diptych::core::kInputOffset + 31);
}

TEST_CASE("press log lines") {
    FakeMemory m;
    PressScheduler p;
    p.start(1, 0x20, 16);
    for (int t = 0; t < 20; ++t) p.tick(m, t);
    auto lines = p.take_log();
    REQUIRE(lines.size() == 3);
    CHECK(lines[0].find("press start") != std::string::npos);
    CHECK(lines[1].find("press release") != std::string::npos);
    CHECK(lines[2].find("press end") != std::string::npos);
    CHECK(p.take_log().empty());
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
