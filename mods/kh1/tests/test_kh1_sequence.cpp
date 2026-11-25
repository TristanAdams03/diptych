#include <doctest/doctest.h>

#include <fstream>
#include <optional>
#include <sstream>
#include <string>

#include <nlohmann/json.hpp>

#include "core/command_args.h"
#include "core/kh1_commands.h"
#include "core/kh1_fields.h"
#include "core/kh1_sequence.h"
#include "fake_memory.h"

using namespace diptych::core;
using nlohmann::json;

#ifndef FIXTURES_DIR
#error FIXTURES_DIR must be defined
#endif

namespace {
constexpr size_t kImage = 0x3000000;
constexpr uintptr_t kSwap = 0x4D8632;       // fixture: require swap == 0
constexpr uintptr_t kShortcut0 = 0x2DE9BA4;  // fixture: require shortcut 0 == 3 (cure)
constexpr uintptr_t kMenuType = 0x2852790;   // fixture: until / onlyIf menu byte == 4

json fixture(const char* name) {
    std::ifstream f(std::string(FIXTURES_DIR) + "/" + name, std::ios::binary);
    REQUIRE_MESSAGE(f.good(), "missing fixture ", name);
    std::stringstream ss;
    ss << f.rdbuf();
    return json::parse(ss.str());
}

json step(int byteIndex, int mask, int holdMs, int gapMs) {
    return {{"byteIndex", byteIndex}, {"mask", mask}, {"holdMs", holdMs}, {"gapMs", gapMs}};
}
json cond(int64_t offset, int value) { return {{"offset", offset}, {"value", value}}; }
json seq_args(const json& steps) { return {{"steps", steps}}; }

// The error string parse_sequence throws, or "" when it accepts.
std::string seq_error(const json& args, size_t image = kImage) {
    try {
        parse_sequence(args, image);
        return "";
    } catch (const Fail& f) {
        return f.error;
    }
}

// A scheduler wired to an InjectState, as the runtime does once the XInput hook is installed.
struct Rig {
    FakeMemory m;
    InjectState inject;
    SequenceScheduler s;
    Rig() { s.attach(&inject); }
    // Runs a command; an accepted pressSequence returns "" (its result comes from take_result).
    std::string run(const std::string& name, const json& args, const std::string& id = "c5") {
        return run_kh1_command(m, true, id, name, args, s);
    }
};

// What the hook would report for controller 0 with nothing physically pressed.
PadInject seen(const InjectState& s) {
    uint32_t pk = 0;
    PadInject out;
    s.apply(pk, out.buttons, out.leftTrigger, out.rightTrigger);
    return out;
}

void check_err(const std::string& frame, const char* err) {
    REQUIRE_FALSE(frame.empty());
    const json r = json::parse(frame);
    CHECK(r["type"] == "result");
    CHECK(r["ok"] == false);
    CHECK(r["error"] == err);
    CHECK(r["data"].is_null());
}
}  // namespace

// ---- parse_sequence ---------------------------------------------------------------------------

TEST_CASE("parse_sequence reads steps, conditions and defaults") {
    const Sequence s = parse_sequence(fixture("command_sequence.json")["args"], kImage);
    REQUIRE(s.require.size() == 2);
    CHECK(s.require[0].offset == kSwap);
    CHECK(s.require[0].value == 0);
    CHECK(s.require[0].mask == 0xFF);  // mask defaults to 255
    CHECK(s.require[1].offset == kShortcut0);
    CHECK(s.require[1].value == 3);
    REQUIRE(s.steps.size() == 2);
    CHECK(s.steps[0].byteIndex == 1);
    CHECK(s.steps[0].mask == 0x14);
    CHECK(s.steps[0].pad == PadInject{static_cast<uint16_t>(kXiLeftShoulder | kXiY), 0, 0});
    CHECK(s.steps[0].holdMs == 100);
    CHECK(s.steps[0].gapMs == 50);
    REQUIRE(s.steps[0].until.has_value());
    CHECK(s.steps[0].until->offset == kMenuType);
    CHECK(s.steps[0].until->value == 4);
    CHECK(s.steps[0].untilTimeoutMs == 500);
    CHECK_FALSE(s.steps[0].onlyIf.has_value());
    CHECK(s.steps[1].pad == PadInject{kXiA, 0, 0});
    REQUIRE(s.steps[1].onlyIf.has_value());
    CHECK(s.steps[1].onlyIf->offset == kMenuType);
    CHECK_FALSE(s.steps[1].until.has_value());

    // require is optional; a mask-0 step is a plain wait (nothing pressed).
    const Sequence w = parse_sequence(seq_args(json::array({step(1, 0, 16, 16)})), kImage);
    CHECK(w.require.empty());
    CHECK(w.steps[0].pad == PadInject{});
}

TEST_CASE("parse_sequence accepts its limits") {
    json sixteen = json::array();
    for (int i = 0; i < 16; ++i) sixteen.push_back(step(1, 0x40, 156, 156));  // 16 * 312 = 4992 ms
    CHECK(parse_sequence(seq_args(sixteen), kImage).steps.size() == 16);
    // 1000 + 1000 + 3000 = 5000 ms exactly.
    json s = step(1, 0x40, 1000, 1000);
    s["until"] = {{"offset", static_cast<int64_t>(kImage) - 1}, {"value", 0x80}, {"mask", 0x80}, {"timeoutMs", 3000}};
    CHECK(parse_sequence(seq_args(json::array({s})), kImage).steps[0].until->mask == 0x80);
    json req = json::array();
    for (int i = 0; i < 16; ++i) req.push_back(cond(i, 0));
    CHECK(parse_sequence({{"require", req}, {"steps", json::array({step(0, 0x10, 16, 16)})}}, kImage).require.size() == 16);
}

TEST_CASE("parse_sequence rejects out-of-range values") {
    auto with = [](json st) { return seq_args(json::array({std::move(st)})); };
    CHECK(seq_error(seq_args(json::array())) == "out of range");  // no steps
    json seventeen = json::array();
    for (int i = 0; i < 17; ++i) seventeen.push_back(step(1, 0x40, 16, 16));
    CHECK(seq_error(seq_args(seventeen)) == "out of range");
    CHECK(seq_error(with(step(0, 0x08, 100, 100))) == "out of range");  // start
    CHECK(seq_error(with(step(0, 0x18, 100, 100))) == "out of range");  // start + up
    CHECK(seq_error(with(step(2, 0x40, 100, 100))) == "out of range");
    CHECK(seq_error(with(step(1, 256, 100, 100))) == "out of range");
    CHECK(seq_error(with(step(1, 0x40, 15, 100))) == "out of range");
    CHECK(seq_error(with(step(1, 0x40, 1001, 100))) == "out of range");
    CHECK(seq_error(with(step(1, 0x40, 100, 15))) == "out of range");
    CHECK(seq_error(with(step(1, 0x40, 100, 1001))) == "out of range");
    // Over 5 s in total, counting each until's timeout.
    json slow = json::array();
    for (int i = 0; i < 3; ++i) slow.push_back(step(1, 0x40, 1000, 1000));  // 6000
    CHECK(seq_error(seq_args(slow)) == "out of range");
    json waits = step(1, 0x40, 1000, 1000);
    waits["until"] = {{"offset", 0}, {"value", 1}, {"timeoutMs", 3001}};  // 5001
    CHECK(seq_error(with(waits)) == "out of range");
    // until.timeoutMs below 16.
    json quick = step(1, 0x40, 100, 100);
    quick["until"] = {{"offset", 0}, {"value", 1}, {"timeoutMs", 15}};
    CHECK(seq_error(with(quick)) == "out of range");
    // Condition offsets outside the image, values over 255, value bits outside the mask, mask 0.
    json far = step(1, 0x40, 100, 100);
    far["onlyIf"] = cond(static_cast<int64_t>(kImage), 0);
    CHECK(seq_error(with(far)) == "out of range");
    far["onlyIf"] = cond(-1, 0);
    CHECK(seq_error(with(far)) == "out of range");
    far["onlyIf"] = cond(0, 256);
    CHECK(seq_error(with(far)) == "out of range");
    far["onlyIf"] = {{"offset", 0}, {"value", 0x03}, {"mask", 0x01}};
    CHECK(seq_error(with(far)) == "out of range");
    far["onlyIf"] = {{"offset", 0}, {"value", 0}, {"mask", 0}};
    CHECK(seq_error(with(far)) == "out of range");
    json req = json::array();
    for (int i = 0; i < 17; ++i) req.push_back(cond(i, 0));
    CHECK(seq_error({{"require", req}, {"steps", json::array({step(1, 0x40, 16, 16)})}}) == "out of range");
    CHECK(seq_error({{"require", json::array({cond(static_cast<int64_t>(kImage), 0)})},
                     {"steps", json::array({step(1, 0x40, 16, 16)})}}) == "out of range");
}

TEST_CASE("parse_sequence rejects missing or ill-typed fields") {
    CHECK(seq_error(json::object()) == "bad command");
    CHECK(seq_error(json{{"steps", 5}}) == "bad command");
    CHECK(seq_error(json{{"steps", json::array({1})}}) == "bad command");
    CHECK(seq_error(seq_args(json::array({{{"byteIndex", 1}, {"mask", 0x40}, {"holdMs", 100}}}))) == "bad command");
    CHECK(seq_error(seq_args(json::array({{{"byteIndex", "1"}, {"mask", 0x40}, {"holdMs", 100}, {"gapMs", 100}}}))) ==
          "bad command");
    json st = step(1, 0x40, 100, 100);
    st["until"] = 4;
    CHECK(seq_error(seq_args(json::array({st}))) == "bad command");
    st["until"] = {{"offset", 0}, {"value", 1}};  // no timeoutMs
    CHECK(seq_error(seq_args(json::array({st}))) == "bad command");
    st.erase("until");
    st["onlyIf"] = json::array();
    CHECK(seq_error(seq_args(json::array({st}))) == "bad command");
    st["onlyIf"] = {{"offset", 0}};  // no value
    CHECK(seq_error(seq_args(json::array({st}))) == "bad command");
    st["onlyIf"] = {{"offset", 0}, {"value", 1}, {"mask", "ff"}};
    CHECK(seq_error(seq_args(json::array({st}))) == "bad command");
    CHECK(seq_error({{"require", 1}, {"steps", json::array({step(1, 0x40, 16, 16)})}}) == "bad command");
    CHECK(seq_error({{"require", json::array({1})}, {"steps", json::array({step(1, 0x40, 16, 16)})}}) == "bad command");
}

// ---- the scheduler ----------------------------------------------------------------------------

TEST_CASE("a sequence presses each step for holdMs, releases for gapMs, then reports done") {
    Rig r;
    CHECK(r.run("kh1.pressSequence", seq_args(json::array({step(1, 0x40, 20, 30), step(0, 0x10, 20, 30)}))) == "");
    CHECK(r.s.active());
    CHECK_FALSE(r.inject.active());  // the command only schedules; ticks inject
    CHECK_FALSE(r.s.take_result().has_value());
    // Step 1: A for 20 ms (t 0..19), released for 30 (t 20..49). Step 2: up for 20 (t 50..69), released for 30.
    for (int t = 0; t < 100; ++t) {
        r.s.tick(r.m, t);
        CAPTURE(t);
        if (t < 20) CHECK(seen(r.inject) == PadInject{kXiA, 0, 0});
        else if (t < 50) CHECK_FALSE(r.inject.active());
        else if (t < 70) CHECK(seen(r.inject) == PadInject{kXiDpadUp, 0, 0});
        else if (t < 100) CHECK_FALSE(r.inject.active());
        if (t < 99) CHECK(r.s.active());
    }
    r.s.tick(r.m, 100);
    CHECK_FALSE(r.s.active());
    CHECK(r.inject.generation() == 4);  // set A, clear, set up, clear
    const std::optional<std::string> res = r.s.take_result();
    REQUIRE(res.has_value());
    CHECK(json::parse(*res) == json{{"type", "result"}, {"id", "c5"}, {"ok", true}, {"error", nullptr},
                                    {"data", {{"steps", 2}, {"skipped", 0}}}});
    CHECK_FALSE(r.s.take_result().has_value());  // taken once
    CHECK(r.m.writes.empty());  // no game memory is written
    auto lines = r.s.take_log();
    REQUIRE(lines.size() == 2);
    CHECK(lines[0] == "sequence c5 start: 2 steps");
    CHECK(lines[1] == "sequence c5 end (done: 2 pressed, 0 skipped) after 100 ms");
}

TEST_CASE("until waits for the byte after the release; the gap starts once it matches") {
    Rig r;
    json s = step(1, 0x14, 20, 30);
    s["until"] = {{"offset", kMenuType}, {"value", 0x04}, {"mask", 0x0F}, {"timeoutMs", 500}};
    REQUIRE(r.run("kh1.pressSequence", seq_args(json::array({s, step(1, 0x40, 20, 20)}))) == "");
    for (int t = 0; t < 120; ++t) r.s.tick(r.m, t);  // released at 20, waiting since
    CHECK(r.s.active());
    CHECK_FALSE(r.inject.active());
    r.m.set(kMenuType, 0xF4);  // low nibble 4: matches under mask 0x0F
    for (int t = 120; t < 150; ++t) {
        r.s.tick(r.m, t);
        CHECK_FALSE(r.inject.active());  // the 30 ms gap, t 120..149
    }
    r.s.tick(r.m, 150);
    CHECK(seen(r.inject) == PadInject{kXiA, 0, 0});  // step 2 begins
    for (int t = 151; t <= 190; ++t) r.s.tick(r.m, t);
    CHECK_FALSE(r.s.active());
    REQUIRE(r.s.take_result().has_value());
}

TEST_CASE("until times out: the sequence ends with timeout and releases everything") {
    Rig r;
    json s = step(1, 0x14, 20, 30);
    s["until"] = {{"offset", kMenuType}, {"value", 4}, {"timeoutMs", 100}};
    REQUIRE(r.run("kh1.pressSequence", seq_args(json::array({s, step(1, 0x40, 20, 20)}))) == "");
    for (int t = 0; t < 120; ++t) r.s.tick(r.m, t);  // released at 20; the timeout ends at 120
    CHECK(r.s.active());
    r.s.tick(r.m, 120);
    CHECK_FALSE(r.s.active());
    CHECK_FALSE(r.inject.active());
    const auto res = r.s.take_result();
    REQUIRE(res.has_value());
    check_err(*res, "timeout");
    auto lines = r.s.take_log();
    REQUIRE(lines.size() == 2);
    CHECK(lines[1] == "sequence c5 end (timeout at step 1) after 120 ms");
}

TEST_CASE("onlyIf false skips the step: no press, no gap") {
    Rig r;
    json skip = step(1, 0x20, 20, 30);
    skip["onlyIf"] = cond(kMenuType, 2);
    REQUIRE(r.run("kh1.pressSequence", seq_args(json::array({skip, step(1, 0x40, 20, 20)}))) == "");
    r.s.tick(r.m, 0);
    CHECK(seen(r.inject) == PadInject{kXiA, 0, 0});  // went straight to step 2
    for (int t = 1; t <= 40; ++t) r.s.tick(r.m, t);
    CHECK_FALSE(r.s.active());
    const auto res = r.s.take_result();
    REQUIRE(res.has_value());
    CHECK(json::parse(*res)["data"] == json{{"steps", 1}, {"skipped", 1}});
}

TEST_CASE("the game leaving the safe state ends a sequence with not safe now") {
    for (int when : {5, 25}) {  // mid-hold, mid-gap
        CAPTURE(when);
        Rig r;
        REQUIRE(r.run("kh1.pressSequence", seq_args(json::array({step(1, 0x40, 20, 30), step(1, 0x40, 20, 30)}))) ==
                "");
        for (int t = 0; t < when; ++t) r.s.tick(r.m, t);
        r.m.set(kMenuOffset, 1);
        r.s.tick(r.m, when);
        CHECK_FALSE(r.s.active());
        CHECK_FALSE(r.inject.active());
        const auto res = r.s.take_result();
        REQUIRE(res.has_value());
        check_err(*res, "not safe now");
        r.s.take_log();
    }
    // Unsafe while waiting on an until.
    Rig r;
    json s = step(1, 0x14, 20, 30);
    s["until"] = {{"offset", kMenuType}, {"value", 4}, {"timeoutMs", 500}};
    REQUIRE(r.run("kh1.pressSequence", seq_args(json::array({s}))) == "");
    for (int t = 0; t < 40; ++t) r.s.tick(r.m, t);
    r.m.set32(kGummiOffset, 1);
    r.s.tick(r.m, 40);
    CHECK_FALSE(r.s.active());
    check_err(*r.s.take_result(), "not safe now");
}

TEST_CASE("an unreadable condition ends the sequence with not safe now") {
    Rig r;
    json s = step(1, 0x40, 20, 20);
    s["until"] = {{"offset", kMenuType}, {"value", 4}, {"timeoutMs", 500}};
    REQUIRE(r.run("kh1.pressSequence", seq_args(json::array({s}))) == "");
    for (int t = 0; t < 25; ++t) r.s.tick(r.m, t);
    r.m.faults.insert(kMenuType);
    r.s.tick(r.m, 25);
    CHECK_FALSE(r.s.active());
    check_err(*r.s.take_result(), "not safe now");

    Rig q;
    json o = step(1, 0x40, 20, 20);
    o["onlyIf"] = cond(kMenuType, 0);
    q.m.throws.insert(kMenuType);
    REQUIRE(q.run("kh1.pressSequence", seq_args(json::array({o}))) == "");
    q.s.tick(q.m, 0);
    CHECK_FALSE(q.s.active());
    CHECK(q.inject.generation() == 0);  // nothing was ever injected
    check_err(*q.s.take_result(), "not safe now");
}

TEST_CASE("conditions read the raw memory passed to tick; the gate stays the safety reader") {
    Rig r;
    FakeMemory raw;
    json s = step(1, 0x40, 20, 20);
    s["until"] = {{"offset", kMenuType}, {"value", 4}, {"timeoutMs", 500}};
    REQUIRE(r.run("kh1.pressSequence", seq_args(json::array({s}))) == "");
    raw.set(kMenuType, 4);  // only raw has the byte
    for (int t = 0; t <= 40; ++t) r.s.tick(r.m, t, &raw);
    CHECK_FALSE(r.s.active());
    CHECK(json::parse(*r.s.take_result())["ok"] == true);
}

// ---- kh1.pressSequence through run_kh1_command ------------------------------------------------

TEST_CASE("pressSequence refusals answer at once") {
    SUBCASE("unsupported build") {
        FakeMemory m;
        SequenceScheduler s;
        check_err(run_kh1_command(m, false, "c5", "kh1.pressSequence", fixture("command_sequence.json")["args"], s),
                  "unsupported build");
        CHECK(m.reads == 0);
    }
    SUBCASE("bad args") {
        Rig r;
        check_err(r.run("kh1.pressSequence", json::object()), "bad command");
        check_err(r.run("kh1.pressSequence", seq_args(json::array({step(0, 0x08, 100, 100)}))), "out of range");
        CHECK_FALSE(r.s.active());
    }
    SUBCASE("xinput hook not installed") {
        FakeMemory m;
        SequenceScheduler bare;
        check_err(run_kh1_command(m, true, "c5", "kh1.pressSequence", seq_args(json::array({step(1, 0x40, 20, 20)})),
                                  bare),
                  "not safe now");
        CHECK_FALSE(bare.active());
    }
    SUBCASE("unsafe now") {
        Rig r;
        r.m.set(kMenuOffset, 1);
        check_err(r.run("kh1.pressSequence", seq_args(json::array({step(1, 0x40, 20, 20)}))), "not safe now");
        CHECK_FALSE(r.s.active());
    }
    SUBCASE("a require that does not match, or cannot be read") {
        Rig r;
        const json args = fixture("command_sequence.json")["args"];
        check_err(r.run("kh1.pressSequence", args), "not safe now");  // shortcut 0 reads 0, not 3
        r.m.set(kShortcut0, 3);
        r.m.set(kSwap, 1);
        check_err(r.run("kh1.pressSequence", args), "not safe now");  // swapped
        r.m.set(kSwap, 0);
        r.m.faults.insert(kSwap);
        check_err(r.run("kh1.pressSequence", args), "not safe now");
        CHECK_FALSE(r.s.active());
        r.m.faults.clear();
        CHECK(r.run("kh1.pressSequence", args) == "");
        CHECK(r.s.active());
    }
    SUBCASE("busy while a sequence or a press runs") {
        Rig r;
        REQUIRE(r.run("kh1.pressSequence", seq_args(json::array({step(1, 0x40, 100, 100)}))) == "");
        check_err(r.run("kh1.pressSequence", seq_args(json::array({step(1, 0x40, 100, 100)})), "c6"), "busy");
        check_err(r.run("kh1.pressInput", {{"byteIndex", 1}, {"mask", 0x40}, {"durationMs", 100}}, "c7"), "busy");
        Rig q;
        REQUIRE(json::parse(q.run("kh1.pressInput", {{"byteIndex", 1}, {"mask", 0x40}, {"durationMs", 100}}))["ok"] ==
                true);
        check_err(q.run("kh1.pressSequence", seq_args(json::array({step(1, 0x40, 100, 100)}))), "busy");
    }
    SUBCASE("a disabled safety-gate reader refuses up front") {
        Rig r;
        DisablingMemory g(r.m);
        r.m.faults.insert(kMenuOffset);
        CHECK_FALSE(safe_now(g));
        r.m.faults.clear();
        check_err(run_kh1_command(r.m, true, "c5", "kh1.pressSequence", seq_args(json::array({step(1, 0x40, 20, 20)})),
                                  r.s, nullptr, &g),
                  "not safe now");
    }
}

TEST_CASE("the fixture sequence ends with the fixture results") {
    SUBCASE("the picker opens: result_sequence.json") {
        Rig r;
        r.m.set(kShortcut0, 3);
        REQUIRE(r.run("kh1.pressSequence", fixture("command_sequence.json")["args"]) == "");
        std::optional<std::string> res;
        for (int t = 0; t < 2000 && !res; ++t) {
            if (t == 130) r.m.set(kMenuType, 4);  // the target picker opens after the LB+Y press
            r.s.tick(r.m, t);
            res = r.s.take_result();
        }
        REQUIRE(res.has_value());
        CHECK(json::parse(*res) == fixture("result_sequence.json"));
    }
    SUBCASE("it never opens: result_sequence_timeout.json") {
        Rig r;
        r.m.set(kShortcut0, 3);
        REQUIRE(r.run("kh1.pressSequence", fixture("command_sequence.json")["args"]) == "");
        std::optional<std::string> res;
        int t = 0;
        for (; t < 2000 && !res; ++t) {
            r.s.tick(r.m, t);
            res = r.s.take_result();
        }
        REQUIRE(res.has_value());
        CHECK(t - 1 == 600);  // held 0..99, released at 100, timed out 500 ms later
        CHECK(json::parse(*res) == fixture("result_sequence_timeout.json"));
    }
}

TEST_CASE("pressInput through the scheduler never makes a deferred result") {
    Rig r;
    const json res = json::parse(r.run("kh1.pressInput", {{"byteIndex", 1}, {"mask", 0x40}, {"durationMs", 16}}));
    CHECK(res["ok"] == true);
    for (int t = 0; t < 20; ++t) r.s.tick(r.m, t);
    CHECK_FALSE(r.s.active());
    CHECK_FALSE(r.s.take_result().has_value());
}
