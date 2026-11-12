#include <doctest/doctest.h>
#include <fstream>
#include <sstream>
#include "fake_game.h"
#include "proto/dispatcher.h"
#include "proto/messages.h"

using namespace ttwds::proto;
using nlohmann::json;

#ifndef FIXTURES_DIR
#error FIXTURES_DIR must be defined
#endif

static json fixture(const char* name) {
    std::ifstream f(std::string(FIXTURES_DIR) + "/" + name);
    REQUIRE_MESSAGE(f.good(), "missing fixture ", name);
    std::stringstream ss; ss << f.rdbuf();
    return json::parse(ss.str());
}
static std::string fixtureText(const char* name) {
    std::ifstream f(std::string(FIXTURES_DIR) + "/" + name);
    std::stringstream ss; ss << f.rdbuf();
    return ss.str();
}
static ServerInfo info() { return {"0.0.1-spike", "FalloutNV 1.4.0.525", "6.4.9", true}; }

TEST_CASE("hello -> welcome matching fixture") {
    Subscription sub;
    auto in = classify(fixtureText("hello.json"), info(), sub);
    CHECK(in.route == Route::NetThread);
    json got = json::parse(in.reply);
    json want = fixture("welcome.json");
    CHECK(got == want);
    CHECK(got["protocol"] == 0);
}

TEST_CASE("welcome_json matches fixture and reflects ttw") {
    CHECK(json::parse(welcome_json(info())) == fixture("welcome.json"));
    ServerInfo early = info();
    early.ttw = false;
    json got = json::parse(welcome_json(early));
    CHECK(got["type"] == "welcome");
    CHECK(got["ttw"] == false);
    CHECK(got["plugin"] == "0.0.1-spike");
    // classify(hello) and welcome_json must agree byte for byte.
    Subscription sub;
    CHECK(classify(R"({"type":"hello"})", early, sub).reply == welcome_json(early));
}

TEST_CASE("ping -> pong") {
    Subscription sub;
    auto in = classify(R"({"type":"ping","t":5})", info(), sub);
    CHECK(in.route == Route::NetThread);
    CHECK(json::parse(in.reply) == fixture("pong.json"));
}

TEST_CASE("invalid json -> error") {
    Subscription sub;
    auto in = classify("{not json", info(), sub);
    CHECK(in.route == Route::NetThread);
    CHECK(json::parse(in.reply) == fixture("error.json"));
}

TEST_CASE("unknown type / non-object / missing type -> error unknown type") {
    Subscription sub;
    for (const char* t : {R"({"type":"bogus"})", "[1,2]", "{}", R"({"type":5})", "42"}) {
        auto in = classify(t, info(), sub);
        CHECK(in.route == Route::NetThread);
        CHECK(json::parse(in.reply)["message"] == "unknown type");
    }
}

TEST_CASE("command routes to main thread") {
    Subscription sub;
    auto in = classify(fixtureText("command_equip.json"), info(), sub);
    CHECK(in.route == Route::MainThread);
    CHECK(in.commandId == "c1");
    CHECK(in.commandName == "spike.equipFirstWeapon");
}

TEST_CASE("malformed command -> error") {
    Subscription sub;
    auto in = classify(R"({"type":"command","name":"x"})", info(), sub);
    CHECK(in.route == Route::NetThread);
    CHECK(json::parse(in.reply)["type"] == "error");
}

TEST_CASE("run_command with no save") {
    FakeGame g; g.loaded = false;
    CHECK(json::parse(run_command(g, "c1", "spike.equipFirstWeapon")) == fixture("result_no_save.json"));
    CHECK(g.equipCalls == 0);
}

TEST_CASE("run_command ok matches fixture") {
    FakeGame g;
    CHECK(json::parse(run_command(g, "c1", "spike.equipFirstWeapon")) == fixture("result_ok.json"));
    run_command(g, "c2", "spike.dumpWorld");
    CHECK(g.dumpCalls == 1);
}

TEST_CASE("run_command unknown name") {
    FakeGame g;
    json r = json::parse(run_command(g, "c9", "nope"));
    CHECK(r["ok"] == false);
    CHECK(r["error"] == "unknown command");
    CHECK(r["id"] == "c9");
}

TEST_CASE("globals_absent_reports_null") {
    SpikeSnapshot s; s.saveLoaded = true;
    std::string out = state_json(s);
    CHECK(out.find("\"bInDCWasteland\":null") != std::string::npos);
    CHECK(out.find("\"bInNVWasteland\":null") != std::string::npos);
    CHECK(json::parse(out)["data"]["colors"].is_null());
}

TEST_CASE("state_json matches fixture") {
    SpikeSnapshot s;
    s.frame = 12345; s.ms = 205750; s.saveLoaded = true;
    s.hp = 245.0f; s.hpMax = 280.0f; s.hardcore = false;
    s.inDC = 1; s.inNV = 0; s.worldspace = "Wasteland"; s.cell = "Megaton"; s.interior = false;
    s.colors = std::array<std::array<int, 3>, 5>{{{255,182,66},{255,67,42},{33,231,121},{255,182,66},{255,182,66}}};
    CHECK(json::parse(state_json(s)) == fixture("state_spike.json"));
}

TEST_CASE("subscribe clamps rate") {
    Subscription sub;
    classify(R"({"type":"subscribe","topics":["spike"],"rateHz":50})", info(), sub);
    CHECK(sub.spike); CHECK(sub.rateHz == 10);
    classify(R"({"type":"subscribe","topics":["spike"],"rateHz":0})", info(), sub);
    CHECK(sub.rateHz == 1);
    classify(fixtureText("subscribe.json").c_str(), info(), sub);
    CHECK(sub.rateHz == 5);
    Subscription s2;
    auto in = classify(R"({"type":"subscribe","topics":["other"]})", info(), s2);
    CHECK(in.route == Route::NetThread);
    CHECK_FALSE(s2.spike);
}
