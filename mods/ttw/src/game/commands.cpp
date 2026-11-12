// Spike commands. Both run on the main thread from the MainGameLoop pump. Equip goes through
// the xNVSE console interface (X PluginAPI.h:96-110), never through engine calls or writes.
// Citation keys (X/J/NVWM) as in readers.cpp.
#include <nlohmann/json.hpp>

#include "game/engine.h"
#include "game/format.h"
#include "game/log.h"
#include "game/readers.h"
#include "game/seh.h"

#include "nvse/GameBSExtraData.h"
#include "nvse/GameExtraData.h"
#include "nvse/GameForms.h"
#include "nvse/GameObjects.h"
#include "nvse/GameRTTI.h"
#include "nvse/PluginAPI.h"

namespace ttwds::game {

namespace engine {

// Inventory walk, read-only:
// - container changes via extraDataList.GetByType(kExtraData_ContainerChanges)
//   (X GameBSExtraData.h:30-33; X GameExtraData.h:166). Not ExtraContainerChanges::GetForRef,
//   which creates and attaches one when missing (X GameExtraData.cpp:517-525): a write.
// - EntryData{extendData, countDelta, type} (X GameExtraData.h:333-337); true count =
//   base container count + countDelta (J functions_jip/jip_fn_inventory.h:261-280;
//   X GameForms.cpp:924 TESContainer::GetCountForForm). NVWM main.cpp:442 uses countDelta only.
// - equipped = any stack carries kExtraData_Worn/WornLeft (X GameExtraData.h:167-168;
//   NVWM main.cpp:463-464).
// - skip non-playable weapons (X GameForms.h:2902, weaponFlags1 0x80) and nameless ones.
void read_first_unequipped_weapon(void* ctx) {
    auto* r = static_cast<WeaponRead*>(ctx);
    PlayerCharacter* p = g_thePlayer ? *g_thePlayer : nullptr;
    if (!p) return;
    if (!p->extraDataList.HasType(kExtraData_ContainerChanges)) return;  // presence bit first (X GameBSExtraData.cpp:9-12)
    auto* xc = static_cast<ExtraContainerChanges*>(p->extraDataList.GetByType(kExtraData_ContainerChanges));
    ExtraContainerChanges::EntryDataList* list = xc ? xc->GetEntryDataList() : nullptr;
    if (!list) return;
    TESContainer* base = p->baseForm ? DYNAMIC_CAST(p->baseForm, TESForm, TESContainer) : nullptr;

    uint32_t n = 0;
    for (auto it = list->Begin(); !it.End() && n < 4096; ++it, ++n) {
        ExtraContainerChanges::EntryData* e = it.Get();
        ++r->entries;
        if (!e || !e->type || e->type->typeID != kFormType_TESObjectWEAP) continue;
        ++r->weapons;
        auto* weap = static_cast<TESObjectWEAP*>(e->type);
        if (weap->IsNonPlayable()) continue;
        const SInt32 count = e->countDelta + (base ? base->GetCountForForm(e->type) : 0);
        if (count <= 0) continue;
        bool equipped = false;
        if (e->extendData) {
            uint32_t m = 0;
            for (auto x = e->extendData->Begin(); !x.End() && m < 4096; ++x, ++m) {
                ExtraDataList* xl = x.Get();
                if (xl && (xl->HasType(kExtraData_Worn) || xl->HasType(kExtraData_WornLeft))) {
                    equipped = true;
                    break;
                }
            }
        }
        if (equipped) continue;
        if (!seh_copy_cstr(e->type->GetTheName(), r->name, sizeof r->name) || !r->name[0]) continue;
        r->formId = e->type->refID;
        r->found = true;
        return;
    }
}

// X PluginAPI.h:96-110: RunScriptLine2(buf, callingRefr, suppressConsoleOutput) since
// console kVersion 3; RunScriptLine(buf, object) before. Both return success.
void run_console_line(void* ctx) {
    auto* r = static_cast<ConsoleRun*>(ctx);
    NVSEConsoleInterface* ci = r->console;
    if (!ci || !r->line) return;
    if (ci->version >= 3 && ci->RunScriptLine2) {
        r->used2 = true;
        r->result = ci->RunScriptLine2(r->line, nullptr, true);
    } else if (ci->RunScriptLine) {
        r->result = ci->RunScriptLine(r->line, nullptr);
    }
}

}  // namespace engine

namespace {
proto::CommandResult fail(const char* error) {
    proto::CommandResult r;
    r.error = error;
    return r;
}
}  // namespace

proto::CommandResult NvseGame::equipFirstWeapon() {
    if (!onMainThread()) return fail("not on main thread");
    if (!console_) return fail("console interface unavailable");

    engine::WeaponRead w;
    if (!guarded(kInventory, "inventory", engine::read_first_unequipped_weapon, &w))
        return fail("inventory read failed");
    log_.debug("inventory: %u entries, %u weapons", w.entries, w.weapons);
    if (!w.found) return fail("no unequipped weapon");

    if (disabled_ & kConsole) return fail("console disabled after fault");
    const std::string line = equip_line(w.formId);
    engine::ConsoleRun run;
    run.console = console_;
    run.line = line.c_str();
    if (!guarded(kConsole, "console", engine::run_console_line, &run)) {
        log_.info("equip: console faulted on '%s'; console commands disabled", line.c_str());
        return fail("console command faulted");
    }
    log_.info("equip: '%s' (%s) via %s -> %s", line.c_str(), w.name, run.used2 ? "RunScriptLine2" : "RunScriptLine",
              run.result ? "ok" : "failed");
    if (!run.result) return fail("console command failed");

    proto::CommandResult r;
    r.ok = true;
    r.data = nlohmann::json{{"formId", hex_form_id(w.formId)}, {"name", std::string(w.name)}};
    return r;
}

proto::CommandResult NvseGame::dumpWorld() {
    if (!onMainThread()) return fail("not on main thread");
    engine::WorldRead w;
    if (!guarded(kWorld, "world", engine::read_world, &w) || !w.ok) return fail("world read failed");
    resolveInteriorWorldspace(w);  // own fault bit: a fault there keeps this command working

    auto str = [](const char* s) { return s[0] ? nlohmann::json(std::string(s)) : nlohmann::json(nullptr); };
    proto::CommandResult r;
    r.ok = true;
    r.data = nlohmann::json{{"worldspace", str(w.worldspace)},
                            {"parentWorldspace", str(w.parentWorldspace)},
                            {"cell", str(w.cell)},
                            {"interior", w.interior},
                            {"pos", nlohmann::json::array({w.pos[0], w.pos[1], w.pos[2]})}};
    log_.info("dumpWorld: %s", r.data.dump(-1, ' ', false, nlohmann::json::error_handler_t::replace).c_str());
    return r;
}

}  // namespace ttwds::game
