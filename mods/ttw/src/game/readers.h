#pragma once
// Engine-backed IGame for FNV 1.4.0.525 under xNVSE (Windows/MSVC only).
//
// Rules (global constraints):
// - every method runs on the game main thread, inside the kMessage_MainGameLoop handler
//   (or another xNVSE message handler, which xNVSE also dispatches on the main thread);
// - no TESForm*/TESObjectREFR* survives a call: we keep FormIDs and re-resolve each read;
// - every engine read runs under a hardware-exception guard and yields null on failure;
// - no writes to game memory, no hooks. The only "action" path is the xNVSE console.
#include <cstdint>
#include <string>
#include "proto/game_iface.h"

struct NVSEConsoleInterface;

namespace ttwds::game::engine {
struct WorldRead;
}

namespace ttwds::game {

class Logger;

class NvseGame : public proto::IGame {
public:
    explicit NvseGame(Logger& log) : log_(log) {}

    void setConsole(NVSEConsoleInterface* console) { console_ = console; }
    void setMainThread(uint32_t tid) { mainTid_ = tid; }

    // Lifecycle, from the xNVSE message handler (main thread).
    void onPostLoadGame(bool ok);
    void onNewGame();
    void onPreLoadGame();
    void onExitToMainMenu();
    // Load-order index of TaleOfTwoWastelands.esm, -1 if absent or unreadable.
    int ttwModIndex();

    bool saveLoaded() override { return loaded_; }
    const char* blockedReason() override;
    proto::SpikeSnapshot snapshot(int64_t frame, int64_t ms) override;
    proto::CommandResult equipFirstWeapon() override;
    proto::CommandResult dumpWorld() override;

    // Readers that faulted once are switched off for the rest of the session.
    static constexpr uint32_t kHealth = 1u << 0, kHardcore = 1u << 1, kGlobals = 1u << 2,
                              kWorld = 1u << 3, kColors = 1u << 4, kInventory = 1u << 5,
                              kScan = 1u << 6, kModIndex = 1u << 7,
                              kDoorWs = 1u << 8, kConsole = 1u << 9, kMenu = 1u << 10;

private:
    bool onMainThread() const;
    bool guarded(uint32_t bit, const char* what, void (*fn)(void*), void* ctx);
    void dropState(const char* why);
    void scanGlobals();
    // Interior only: worldspace via the last exterior door (low confidence, own fault bit).
    void resolveInteriorWorldspace(engine::WorldRead& w);

    Logger& log_;
    NVSEConsoleInterface* console_ = nullptr;
    uint32_t mainTid_ = 0;
    bool loaded_ = false;
    // FormIDs (never pointers) of the TTW globals; 0 = not present.
    uint32_t dcId_ = 0, nvId_ = 0;
    uint32_t disabled_ = 0;
    uint32_t lastColorsRaw_[5] = {};
    bool colorsLogged_ = false;
    bool firstSnapshotLogged_ = false;
};

}  // namespace ttwds::game
