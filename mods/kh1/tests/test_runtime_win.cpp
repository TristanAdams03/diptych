// Windows-only: the runtime loop end to end, with the real WsServer on an ephemeral loopback port
// and FakeMemory standing in for the game image.
#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "ws_test_client.h"

#include <atomic>
#include <chrono>
#include <memory>
#include <string>
#include <thread>

#include <nlohmann/json.hpp>

#include "core/kh1_fields.h"
#include "core/log_ring.h"
#include "fake_memory.h"
#include "win/runtime.h"

using namespace diptych;
using namespace testws;
using nlohmann::json;

namespace {

// The next text frame whose "type" is `type` (others, e.g. state frames, are skipped).
json next_of_type(TestClient& c, const char* type) {
    std::string text;
    while (c.expect(0x1, text)) {
        json j = json::parse(text);
        if (j.value("type", "") == type) return j;
    }
    return nullptr;
}

struct RunningRuntime {
    Wsa wsa;  // for the test client; the server starts its own
    std::atomic<bool> stop{false};
    std::atomic<int> classifyCalls{0};
    std::atomic<core::Build> build{core::Build::Unknown};
    core::LogRing ring;
    FakeMemory mem;
    std::unique_ptr<win::Runtime> rt;
    std::thread th;

    explicit RunningRuntime(int classifyForMs = 30000) {
        mem.set(core::kWorldOffset, 3);
        mem.set(core::kRoomOffset, 2);
        mem.set(core::kWorldAltOffset, 3);
        mem.set(core::kSoraHpOffset, 52);
        mem.set(core::kSoraHpMaxOffset, 60);
        win::RuntimeOptions o;
        o.port = 0;
        o.mem = &mem;
        o.classify = [this] {
            ++classifyCalls;
            return build.load();
        };
        o.log = [this](const std::string& s) { ring.add(s); };
        o.logPinned = [this](const std::string& s) { ring.add(s, true); };
        o.ring = &ring;
        o.classifyEveryMs = 20;
        o.classifyForMs = classifyForMs;
        rt = std::make_unique<win::Runtime>(std::move(o));
        th = std::thread([this] { rt->run(stop); });
        const auto until = std::chrono::steady_clock::now() + std::chrono::seconds(5);
        while (rt->port() == 0 && std::chrono::steady_clock::now() < until)
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        REQUIRE(rt->port() != 0);
    }
    // Joins the loop; afterwards mem may be inspected safely.
    void halt() {
        stop = true;
        if (th.joinable()) th.join();
    }
    ~RunningRuntime() {
        halt();
        rt.reset();
    }
};

std::string command(const char* id, const char* name, const json& args) {
    return json{{"type", "command"}, {"id", id}, {"name", name}, {"args", args}}.dump();
}

}  // namespace

TEST_CASE("runtime_end_to_end") {
    RunningRuntime r;
    TestClient c(r.rt->port());
    REQUIRE(c.handshake());
    REQUIRE(c.sendFrame(0x1, fixture("hello.json")));
    json w = next_of_type(c, "welcome");
    CHECK(w["plugin"] == "0.0.1-spike");
    CHECK(w["gameId"] == "kh1");
    CHECK(w["gameBuild"] == "unknown");
    CHECK(w["supported"] == false);

    // Unsupported: probe refused, kh1.log still works.
    REQUIRE(c.sendFrame(0x1, fixture("command_probe.json")));
    json res = next_of_type(c, "result");
    CHECK(res["id"] == "c1");
    CHECK(res["error"] == "unsupported build");
    REQUIRE(c.sendFrame(0x1, fixture("command_log.json")));
    res = next_of_type(c, "result");
    CHECK(res["ok"] == true);
    bool listening = false;
    for (auto& l : res["data"]["lines"]) listening |= l.get<std::string>().find("server listening on 127.0.0.1:") == 0;
    CHECK(listening);

    // Unsupported state: all null.
    REQUIRE(c.sendFrame(0x1, R"({"type":"subscribe","topics":["kh1.spike"],"rateHz":10})"));
    json st = next_of_type(c, "state");
    CHECK(st["topic"] == "kh1.spike");
    CHECK(st["data"]["world"].is_null());

    // Ruling O: the build becomes recognisable later; a fresh welcome is pushed.
    r.build = core::Build::Steam1002;
    w = next_of_type(c, "welcome");
    CHECK(w["gameBuild"] == "steam-1.0.0.2");
    CHECK(w["supported"] == true);
    st = next_of_type(c, "state");
    CHECK(st["data"]["world"] == 3);
    CHECK(st["data"]["soraHpMax"] == 60);
    CHECK(st["data"]["inputBytes"] == "00000000");
    const int64_t seq1 = st["data"]["seq"].get<int64_t>();
    st = next_of_type(c, "state");
    CHECK(st["data"]["seq"].get<int64_t>() == seq1 + 1);

    REQUIRE(c.sendFrame(0x1, fixture("command_probe.json")));
    res = next_of_type(c, "result");
    CHECK(res == json::parse(fixture("result_probe.json")));

    REQUIRE(c.sendFrame(0x1, command("p1", "kh1.pressInput", {{"byteIndex", 0}, {"mask", 16}, {"durationMs", 50}})));
    res = next_of_type(c, "result");
    CHECK(res["id"] == "p1");
    CHECK(res["ok"] == true);
    REQUIRE(c.sendFrame(0x1, command("p2", "kh1.pressInput", {{"byteIndex", 0}, {"mask", 16}, {"durationMs", 50}})));
    res = next_of_type(c, "result");
    CHECK(res["error"] == "busy");
    std::this_thread::sleep_for(std::chrono::milliseconds(300));

    REQUIRE(c.sendFrame(0x1, command("x", "kh1.nope", json::object())));
    res = next_of_type(c, "result");
    CHECK(res["error"] == "unknown command");

    r.halt();
    REQUIRE_FALSE(r.mem.writes.empty());
    for (auto& wr : r.mem.writes) {
        CHECK(wr.first == core::kInputOffset);
        CHECK(wr.second == 0x10);
    }
    bool started = false, ended = false;
    for (auto& l : r.ring.last(500)) {
        started |= l.find("press start") != std::string::npos;
        ended |= l.find("press end (done)") != std::string::npos;
    }
    CHECK(started);
    CHECK(ended);
}

TEST_CASE("runtime_stops_classifying_after_the_window") {
    RunningRuntime r(200);  // classify every 20 ms for 200 ms, build stays unknown
    std::this_thread::sleep_for(std::chrono::milliseconds(600));
    const int calls = r.classifyCalls.load();
    CHECK(calls >= 5);
    CHECK(calls <= 13);
    std::this_thread::sleep_for(std::chrono::milliseconds(200));
    CHECK(r.classifyCalls.load() == calls);
    bool final = false;
    for (auto& l : r.ring.pinned()) final |= l == "build classification final: unknown";
    CHECK(final);
}

TEST_CASE("runtime_stops_classifying_once_recognised") {
    RunningRuntime r;
    r.build = core::Build::Egs10010;
    std::this_thread::sleep_for(std::chrono::milliseconds(200));
    const int calls = r.classifyCalls.load();
    std::this_thread::sleep_for(std::chrono::milliseconds(200));
    CHECK(r.classifyCalls.load() == calls);
    TestClient c(r.rt->port());
    REQUIRE(c.handshake());
    REQUIRE(c.sendFrame(0x1, fixture("hello.json")));
    json w = next_of_type(c, "welcome");
    CHECK(w == json::parse(fixture("welcome_unsupported.json")));
}
