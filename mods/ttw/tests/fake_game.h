#pragma once
#include "proto/game_iface.h"

struct FakeGame : ttwds::proto::IGame {
    bool loaded = true;
    bool ttw = false;  // TTW absent by default: the TTW globals read as null
    const char* blocked = nullptr;  // "in menu" / "loading" to simulate a blocked game
    int equipCalls = 0, dumpCalls = 0, snapshotCalls = 0;
    bool saveLoaded() override { return loaded; }
    const char* blockedReason() override { return blocked; }
    ttwds::proto::SpikeSnapshot snapshot(int64_t frame, int64_t ms) override {
        ++snapshotCalls;
        ttwds::proto::SpikeSnapshot s;
        s.frame = frame; s.ms = ms; s.saveLoaded = loaded;
        if (loaded) {
            s.hp = 245.0f; s.hpMax = 280.0f; s.hardcore = false; s.interior = false;
            s.worldspace = "WastelandNV"; s.cell = "Goodsprings";
            if (ttw) { s.inDC = 0; s.inNV = 1; }
        }
        return s;
    }
    ttwds::proto::CommandResult equipFirstWeapon() override {
        ++equipCalls;
        return {true, "", nlohmann::json{{"formId", "0001A6B9"}, {"name", "Lincoln's Repeater"}}};
    }
    ttwds::proto::CommandResult dumpWorld() override {
        ++dumpCalls;
        return {true, "", nlohmann::json{{"worldspace", "Wasteland"}}};
    }
};
