#include <doctest/doctest.h>

#include <fstream>
#include <sstream>
#include <string>

#include "proto/envelope.h"

using namespace diptych::proto;
using nlohmann::json;

#ifndef FIXTURES_DIR
#error FIXTURES_DIR must be defined
#endif

namespace {

std::string fixtureText(const char* name) {
    std::ifstream f(std::string(FIXTURES_DIR) + "/" + name, std::ios::binary);
    REQUIRE_MESSAGE(f.good(), "missing fixture ", name);
    std::stringstream ss;
    ss << f.rdbuf();
    return ss.str();
}
json fixture(const char* name) { return json::parse(fixtureText(name)); }

Info steam() { return {"0.0.1-spike", "kh1", "steam-1.0.0.2", true}; }
Info egs() { return {"0.0.1-spike", "kh1", "egs-1.0.0.10", false}; }

// A `result` frame carrying `error` for command id `id`, as PROTOCOL.md specifies for failures.
void checkFailedResult(const Inbound& in, const std::string& id, const std::string& error) {
    CHECK(in.route == Route::NetThread);
    json r = json::parse(in.reply);
    CHECK(r == json{{"type", "result"}, {"id", id}, {"ok", false}, {"error", error}, {"data", nullptr}});
}

}  // namespace

TEST_CASE("welcome_json matches welcome_steam.json") {
    CHECK(json::parse(welcome_json(steam())) == fixture("welcome_steam.json"));
    CHECK(json::parse(welcome_json(egs())) == fixture("welcome_unsupported.json"));
}

TEST_CASE("hello -> welcome, byte for byte the same as welcome_json") {
    Subscription sub;
    Inbound in = classify(fixtureText("hello.json"), steam(), sub);
    CHECK(in.route == Route::NetThread);
    CHECK(in.reply == welcome_json(steam()));
    json got = json::parse(in.reply);
    CHECK(got == fixture("welcome_steam.json"));
    CHECK(got["protocol"] == 0);
    CHECK(got["gameId"] == "kh1");
}

TEST_CASE("unsupported-build hello -> welcome with supported:false") {
    Subscription sub;
    Inbound in = classify(fixtureText("hello.json"), egs(), sub);
    CHECK(in.route == Route::NetThread);
    json got = json::parse(in.reply);
    CHECK(got == fixture("welcome_unsupported.json"));
    CHECK(got["supported"] == false);
    CHECK(got["gameBuild"] == "egs-1.0.0.10");

    Info unknown{"0.0.1-spike", "kh1", "unknown", false};
    got = json::parse(classify(R"({"type":"hello"})", unknown, sub).reply);
    CHECK(got["supported"] == false);
    CHECK(got["gameBuild"] == "unknown");
}

TEST_CASE("ping -> pong") {
    Subscription sub;
    Inbound in = classify(fixtureText("ping.json"), steam(), sub);
    CHECK(in.route == Route::NetThread);
    CHECK(json::parse(in.reply) == fixture("pong.json"));
    // A missing or non-integer t is answered with t:0.
    CHECK(json::parse(classify(R"({"type":"ping"})", steam(), sub).reply)["t"] == 0);
    CHECK(json::parse(classify(R"({"type":"ping","t":"x"})", steam(), sub).reply)["t"] == 0);
}

TEST_CASE("invalid json -> error frame 'invalid json'") {
    Subscription sub;
    for (const char* t : {"{not json", "", "{\"type\":\"hello\"", "\xff\xfe"}) {
        Inbound in = classify(t, steam(), sub);
        CHECK(in.route == Route::NetThread);
        CHECK(json::parse(in.reply) == fixture("error.json"));
    }
}

TEST_CASE("non-object / missing type / unknown type -> error frame 'unknown type'") {
    Subscription sub;
    for (const char* t : {R"({"type":"bogus"})", "[1,2]", "{}", R"({"type":5})", "42", "null",
                          R"({"type":"welcome"})", R"({"type":"state"})"}) {
        Inbound in = classify(t, steam(), sub);
        CHECK(in.route == Route::NetThread);
        CHECK(json::parse(in.reply) == json{{"type", "error"}, {"message", "unknown type"}});
    }
}

TEST_CASE("command routes to the main queue with id, name and args") {
    Subscription sub;
    Inbound in = classify(fixtureText("command_probe.json"), steam(), sub);
    CHECK(in.route == Route::MainThread);
    CHECK(in.reply.empty());
    CHECK(in.commandId == "c1");
    CHECK(in.commandName == "kh1.probe");
    CHECK(in.commandArgs == json{{"offset", 36961940}, {"length", 4}});

    in = classify(fixtureText("command_press.json"), steam(), sub);
    CHECK(in.route == Route::MainThread);
    CHECK(in.commandId == "c2");
    CHECK(in.commandName == "kh1.pressInput");
    CHECK(in.commandArgs == json{{"byteIndex", 0}, {"mask", 16}, {"durationMs", 100}});

    // kh1.log is routed on an unsupported build too; refusing commands is Task 4's job.
    in = classify(fixtureText("command_log.json"), egs(), sub);
    CHECK(in.route == Route::MainThread);
    CHECK(in.commandId == "c3");
    CHECK(in.commandName == "kh1.log");
    CHECK(in.commandArgs == json::object());

    // Unknown names are routed as well; `unknown command` comes from command execution.
    in = classify(R"({"type":"command","id":"c9","name":"nope","args":{}})", steam(), sub);
    CHECK(in.route == Route::MainThread);
    CHECK(in.commandName == "nope");
}

TEST_CASE("malformed command -> result 'bad command'") {
    Subscription sub;
    // id is a string: the result echoes it.
    checkFailedResult(classify(R"({"type":"command","id":"c1","args":{}})", steam(), sub), "c1", "bad command");
    checkFailedResult(classify(R"({"type":"command","id":"c1","name":7,"args":{}})", steam(), sub), "c1",
                      "bad command");
    checkFailedResult(classify(R"({"type":"command","id":"c1","name":"kh1.log"})", steam(), sub), "c1",
                      "bad command");
    checkFailedResult(classify(R"({"type":"command","id":"c1","name":"kh1.log","args":[]})", steam(), sub),
                      "c1", "bad command");
    checkFailedResult(classify(R"({"type":"command","id":"c1","name":"kh1.log","args":null})", steam(), sub),
                      "c1", "bad command");
    // No string id to echo: id is "" so the frame still has PROTOCOL.md's `id:string`.
    checkFailedResult(classify(R"({"type":"command","name":"kh1.log","args":{}})", steam(), sub), "",
                      "bad command");
    checkFailedResult(classify(R"({"type":"command","id":5,"name":"kh1.log","args":{}})", steam(), sub), "",
                      "bad command");
}

TEST_CASE("subscribe to kh1.spike, rate clamped to 1..10, no reply") {
    Subscription sub;
    Inbound in = classify(fixtureText("subscribe.json"), steam(), sub);
    CHECK(in.route == Route::NetThread);
    CHECK(in.reply.empty());  // subscribe has no ack
    CHECK(sub.spike);
    CHECK(sub.rateHz == 5);
    CHECK(subscribed(sub, "kh1.spike"));
    CHECK_FALSE(subscribed(sub, "spike"));

    classify(R"({"type":"subscribe","topics":["kh1.spike"],"rateHz":50})", steam(), sub);
    CHECK(sub.rateHz == 10);
    classify(R"({"type":"subscribe","topics":["kh1.spike"],"rateHz":0})", steam(), sub);
    CHECK(sub.rateHz == 1);
    classify(R"({"type":"subscribe","topics":["kh1.spike"]})", steam(), sub);
    CHECK(sub.rateHz == 1);  // no rateHz: unchanged

    // TTW's topic name and unknown topics do not subscribe; a new subscribe replaces the old one.
    Subscription s2;
    in = classify(R"({"type":"subscribe","topics":["spike"],"rateHz":5})", steam(), s2);
    CHECK(in.reply.empty());
    CHECK_FALSE(s2.spike);
    CHECK_FALSE(subscribed(s2, "kh1.spike"));
    classify(R"({"type":"subscribe","topics":["other",3,"kh1.spike"]})", steam(), s2);
    CHECK(s2.spike);
    classify(R"({"type":"subscribe","topics":[]})", steam(), s2);
    CHECK_FALSE(s2.spike);
}

TEST_CASE("result_json and error_json match the fixtures") {
    CHECK(json::parse(result_json("c2", {false, "not safe now", nullptr})) ==
          fixture("result_press_refused.json"));
    CHECK(json::parse(result_json("c1", {true, "", json{{"offset", 36961940}, {"hex", "03000000"}}})) ==
          fixture("result_probe.json"));
    CHECK(json::parse(error_json("invalid json")) == fixture("error.json"));
    // Every failed result has data:null, even if the caller passed some.
    CHECK(json::parse(result_json("c4", {false, "busy", json{{"x", 1}}}))["data"].is_null());
}
