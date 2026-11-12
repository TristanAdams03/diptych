// Portable tests for the main-thread pieces that do not touch the engine: the per-frame
// pump (command drain + rate-limited state push) driven by FakeGame, and the pure
// formatting helpers the xNVSE layer uses.
#include <doctest/doctest.h>
#include <deque>
#include <string>
#include <vector>
#include "fake_game.h"
#include "game/format.h"
#include "proto/dispatcher.h"
#include "proto/messages.h"
#include "proto/pump.h"

using namespace ttwds::proto;
using nlohmann::json;

namespace {
struct FakeTransport : ITransport {
    std::deque<std::pair<std::string, std::string>> cmds;
    std::vector<std::string> sent;
    bool subscribed = false;
    int rate = 5;
    bool pollCommand(std::string& id, std::string& name) override {
        if (cmds.empty()) return false;
        id = cmds.front().first;
        name = cmds.front().second;
        cmds.pop_front();
        return true;
    }
    void sendToAll(std::string text) override { sent.push_back(std::move(text)); }
    bool hasSpikeSubscriber(int& rateHz) override {
        if (subscribed) rateHz = rate;
        return subscribed;
    }
};
}  // namespace

TEST_CASE("equipFirstWeapon with a save returns ok with data.formId") {
    FakeGame g;
    json r = json::parse(run_command(g, "c1", "spike.equipFirstWeapon"));
    CHECK(r["ok"] == true);
    CHECK(r["id"] == "c1");
    CHECK(r["data"]["formId"] == "0001A6B9");
    CHECK(g.equipCalls == 1);
}

TEST_CASE("equipFirstWeapon without a save -> no save loaded") {
    FakeGame g;
    g.loaded = false;
    json r = json::parse(run_command(g, "c2", "spike.equipFirstWeapon"));
    CHECK(r["ok"] == false);
    CHECK(r["error"] == "no save loaded");
    CHECK(g.equipCalls == 0);
}

TEST_CASE("TTW-absent FakeGame snapshot reports null TTW globals") {
    FakeGame g;
    json s = json::parse(state_json(g.snapshot(1, 2)));
    CHECK(s["data"]["bInNVWasteland"].is_null());
    CHECK(s["data"]["bInDCWasteland"].is_null());
    CHECK(s["data"]["hp"] == 245.0);
    g.ttw = true;
    json t = json::parse(state_json(g.snapshot(1, 2)));
    CHECK(t["data"]["bInNVWasteland"] == 1);
}

TEST_CASE("pump counts frames and drains at most 8 commands per frame") {
    FakeGame g;
    FakeTransport t;
    for (int i = 0; i < 11; ++i) t.cmds.push_back({"id" + std::to_string(i), "spike.dumpWorld"});
    std::vector<std::string> seen;
    FramePump p;
    p.tick(g, t, 0, [&](const std::string& id, const std::string&, const std::string&) { seen.push_back(id); });
    CHECK(p.frame() == 1);
    CHECK(g.dumpCalls == 8);
    CHECK(t.sent.size() == 8);
    CHECK(seen.size() == 8);
    CHECK(json::parse(t.sent[0])["id"] == "id0");
    p.tick(g, t, 16, nullptr);
    CHECK(p.frame() == 2);
    CHECK(g.dumpCalls == 11);
    CHECK(t.sent.size() == 11);
}

TEST_CASE("pump pushes state only when subscribed and rate-limited") {
    FakeGame g;
    FakeTransport t;
    FramePump p;
    p.tick(g, t, 0, nullptr);
    CHECK(t.sent.empty());
    CHECK(g.snapshotCalls == 0);  // no subscriber: never build a snapshot

    t.subscribed = true;
    t.rate = 5;  // 200 ms
    p.tick(g, t, 1000, nullptr);
    REQUIRE(t.sent.size() == 1);  // first push is immediate
    json s = json::parse(t.sent[0]);
    CHECK(s["type"] == "state");
    CHECK(s["data"]["frame"] == 2);
    CHECK(s["data"]["ms"] == 1000);
    p.tick(g, t, 1100, nullptr);
    CHECK(t.sent.size() == 1);
    p.tick(g, t, 1199, nullptr);
    CHECK(t.sent.size() == 1);
    p.tick(g, t, 1200, nullptr);
    CHECK(t.sent.size() == 2);

    t.rate = 10;  // 100 ms
    p.tick(g, t, 1300, nullptr);
    CHECK(t.sent.size() == 3);
    t.rate = 0;  // nonsense rate is clamped to 1 Hz, never divides by zero
    p.tick(g, t, 1400, nullptr);
    CHECK(t.sent.size() == 3);
    p.tick(g, t, 2300, nullptr);
    CHECK(t.sent.size() == 4);
}

TEST_CASE("pump reset forgets the last push time") {
    FakeGame g;
    FakeTransport t;
    t.subscribed = true;
    FramePump p;
    p.tick(g, t, 5000, nullptr);
    p.resetPushClock();
    p.tick(g, t, 5001, nullptr);
    CHECK(t.sent.size() == 2);
}

TEST_CASE("format helpers") {
    using namespace ttwds::game;
    CHECK(xnvse_version_string(0x06040090) == "6.4.9");   // MAKE_NEW_VEGAS_VERSION(6,4,9)
    CHECK(xnvse_version_string(0x060500A0) == "6.5.10");
    CHECK(hex_form_id(0x0001A6B9) == "0001A6B9");
    CHECK(hex_form_id(0xFF000ABC) == "FF000ABC");
    CHECK(equip_line(0x0C01A6B9) == "player.equipitem 0C01A6B9 0 1");
    auto rgb = rgb_from_system_color(0xFFB642FFu);  // JG: GetColor(type) >> 8 == 0xRRGGBB
    CHECK(rgb[0] == 255);
    CHECK(rgb[1] == 182);
    CHECK(rgb[2] == 66);
}

TEST_CASE("equip refused while in a menu or loading; dumpWorld still runs") {
    for (const char* reason : {"in menu", "loading"}) {
        FakeGame g;
        g.blocked = reason;
        json r = json::parse(run_command(g, "e1", "spike.equipFirstWeapon"));
        CHECK(r["ok"] == false);
        CHECK(r["error"] == reason);
        CHECK(g.equipCalls == 0);
        json d = json::parse(run_command(g, "d1", "spike.dumpWorld"));
        CHECK(d["ok"] == true);
        CHECK(g.dumpCalls == 1);
    }
}

TEST_CASE("no save wins over blocked") {
    FakeGame g;
    g.loaded = false;
    g.blocked = "loading";
    json r = json::parse(run_command(g, "e2", "spike.equipFirstWeapon"));
    CHECK(r["error"] == "no save loaded");
}
