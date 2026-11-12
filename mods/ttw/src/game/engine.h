#pragma once
// POD results of guarded engine reads, shared by readers.cpp and commands.cpp.
// Each read_* callback takes a pointer to its struct, is run through seh_call(), and touches
// only POD data, so a fault leaves the struct partially filled but never crashes.
#include <cstdint>

struct NVSEConsoleInterface;

namespace ttwds::game::engine {

struct WorldRead {
    bool ok = false;           // player and cell resolved
    bool interior = false;
    bool wsFromDoor = false;   // interior: worldspace taken from the last exterior door
    char worldspace[96] = {};  // EditorID
    char parentWorldspace[96] = {};
    char cell[160] = {};       // display name, else EditorID
    float pos[3] = {};
};
void read_world(void* ctx);  // WorldRead*; interiors leave worldspace empty
void read_door_worldspace(void* ctx);  // WorldRead*; low confidence, own fault bit

struct WeaponRead {
    bool found = false;
    uint32_t formId = 0;
    char name[128] = {};
    uint32_t entries = 0, weapons = 0;  // diagnostics
};
void read_first_unequipped_weapon(void* ctx);  // WeaponRead*

struct ConsoleRun {
    NVSEConsoleInterface* console = nullptr;
    const char* line = nullptr;
    bool used2 = false;   // RunScriptLine2 (console kVersion >= 3) vs RunScriptLine
    bool result = false;
};
void run_console_line(void* ctx);  // ConsoleRun*

}  // namespace ttwds::game::engine
