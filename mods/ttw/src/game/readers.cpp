// Spike readers for FNV 1.4.0.525. Source citations use the commits pinned in
// docs/superpowers/specs/research/nvse-plugin.md:
//   X  = xNVSE/NVSE tag 6.4.9, nvse/nvse/...
//   J  = jazzisparis/JIP-LN-NVSE 5a30ac43 (GPL-3.0) - offsets/addresses only, no code copied
//   JG = carxt/JohnnyGuitarNVSE c87905e6 (LGPL-2.1) - addresses only, no code copied
#include "game/readers.h"

#include <cmath>
#include <cstdio>
#include <cstring>

#include "game/engine.h"
#include "game/format.h"
#include "game/log.h"
#include "game/seh.h"

#include "nvse/GameAPI.h"
#include "nvse/GameData.h"
#include "nvse/GameExtraData.h"
#include "nvse/GameForms.h"
#include "nvse/GameObjects.h"
#include "nvse/GameRTTI.h"
#include "nvse/GameUI.h"
#include "nvse/PluginAPI.h"

namespace ttwds::game {

namespace {

constexpr const char* kDcGlobal = "bInDCWasteland";
constexpr const char* kNvGlobal = "bInNVWasteland";
constexpr uint32_t kMaxListNodes = 1u << 16;  // bound every engine list walk

// X GameObjects.cpp:52 - g_thePlayer = (PlayerCharacter**)0x011DEA3C.
PlayerCharacter* player() { return g_thePlayer ? *g_thePlayer : nullptr; }

bool is_type(const TESForm* f, UInt8 type) { return f && f->typeID == type; }

// ---- actor values ---------------------------------------------------------------------------
struct HealthRead {
    bool ok = false;
    float cur = 0, max = 0;
};
// X GameForms.h:1325 Fn_03 = GetActorValue (current), :1330 Fn_08 = GetPermanentActorValue
// (base + permanent modifiers = the Pip-Boy max); X GameAPI.h:311 eActorVal_Health = 16;
// same calls as NVWM main.cpp:670-672 (verified on device by its author).
void read_health(void* ctx) {
    auto* r = static_cast<HealthRead*>(ctx);
    PlayerCharacter* p = player();
    if (!p) return;
    r->cur = p->avOwner.Fn_03(eActorVal_Health);
    r->max = p->avOwner.Fn_08(eActorVal_Health);
    r->ok = true;
}

// ---- hardcore -------------------------------------------------------------------------------
struct HardcoreRead {
    bool ok = false;
    UInt8 flag = 0;
    UInt32 raw = 0;
};
// X GameObjects.h:621 `bool isHardcore; // 7BC` (J GameObjects.h:1024 types it UInt32).
// We take the byte and log the dword for on-device verification.
void read_hardcore(void* ctx) {
    auto* r = static_cast<HardcoreRead*>(ctx);
    PlayerCharacter* p = player();
    if (!p) return;
    r->flag = static_cast<UInt8>(p->isHardcore);
    r->raw = *reinterpret_cast<const UInt32*>(reinterpret_cast<const char*>(p) + 0x7BC);
    r->ok = true;
}

// ---- TTW globals ----------------------------------------------------------------------------
struct GlobalScan {
    UInt32 dc = 0, nv = 0, seen = 0;
};
// X GameData.h:239 DataHandler::globalList (tList<TESGlobal> @0xE8; J GameData.h:203);
// X GameForms.h:1782 TESGlobal::name keeps the EDID at runtime (String @0x18).
void scan_globals(void* ctx) {
    auto* r = static_cast<GlobalScan*>(ctx);
    DataHandler* dh = DataHandler::Get();
    if (!dh) return;
    uint32_t n = 0;
    for (auto it = dh->globalList.Begin(); !it.End() && n < kMaxListNodes; ++it, ++n) {
        TESGlobal* g = it.Get();
        if (!is_type(g, kFormType_TESGlobal) || !g->name.m_data) continue;
        ++r->seen;
        if (!r->dc && _stricmp(g->name.m_data, kDcGlobal) == 0) r->dc = g->refID;
        if (!r->nv && _stricmp(g->name.m_data, kNvGlobal) == 0) r->nv = g->refID;
    }
}

struct GlobalRead {
    UInt32 id = 0;
    const char* expect = nullptr;
    bool ok = false;
    float value = 0;
};
// Re-resolve by FormID every read (X GameAPI.cpp:198 LookupFormByID 0x4F9620), confirm it is
// still the same GLOB by type and EDID, then read TESGlobal::data (X GameForms.h:1785, @0x24).
void read_global(void* ctx) {
    auto* r = static_cast<GlobalRead*>(ctx);
    if (!r->id) return;
    TESForm* f = LookupFormByID(r->id);
    if (!is_type(f, kFormType_TESGlobal)) return;
    auto* g = static_cast<TESGlobal*>(f);
    if (!g->name.m_data || _stricmp(g->name.m_data, r->expect) != 0) return;
    r->value = g->data;
    r->ok = true;
}

// ---- system colors --------------------------------------------------------------------------
struct ColorRead {
    bool mgr = false, ok = false;
    UInt32 raw[5] = {};
};
// Singleton pointer: J GameUI.h:2128 `*(SystemColorManager**)0x11D8A88` (a plain read; JG's
// ThisCall(0x718B60) getter is not used so nothing gets constructed).
// Per-type color: JG internal/decoding.h:248 GetColor = thiscall 0x7190A0(mgr, type), used by
// JG GetSystemColor (functions/fn_ui.cpp:189-197) as `>> 8` -> 0xRRGGBB. J GameUI.cpp:224-237
// (SetColorRGB) stores 0xRRGGBBFF, so a plausible raw value has alpha 0xFF.
// Types 1..5 = HUD main, HUD alt, terminal, Pip-Boy, main menu (GECK wiki GetSystemColor).
void read_colors(void* ctx) {
    auto* r = static_cast<ColorRead*>(ctx);
    void* mgr = *reinterpret_cast<void**>(0x11D8A88);
    if (!mgr) return;
    r->mgr = true;
    for (UInt32 type = 1; type <= 5; ++type) r->raw[type - 1] = ThisStdCall<UInt32>(0x7190A0, mgr, type);
    r->ok = true;
}

// ---- TTW loaded -----------------------------------------------------------------------------
struct ModIndexRead {
    UInt32 index = 0xFF;
};
// X GameData.cpp:55-58 DataHandler::GetModIndex -> 0xFF when the file is not loaded.
void read_ttw_index(void* ctx) {
    auto* r = static_cast<ModIndexRead*>(ctx);
    DataHandler* dh = DataHandler::Get();
    if (!dh) return;
    r->index = dh->GetModIndex("TaleOfTwoWastelands.esm");
}

// ---- menu / loading state ------------------------------------------------------------------
struct MenuRead {
    bool loading = false, menuMode = false, dialog = false;
};
// Visibility array: X GameUI.cpp:3 `g_MenuVisibilityArray = (UInt8*)0x011F308F`, indexed by menu
// type in InterfaceManager::IsMenuVisible (X GameUI.cpp:16-22). GameUI.cpp is not in the example
// source set, so the same array is read directly. Types: X GameUI.h:96 kMenuType_Loading (0x3EF),
// :98 kMenuType_Dialog (0x3F1). Menu mode: X Hooks_Gameplay.cpp:962 `CdeclCall<bool>(0x702360)`,
// what xNVSE itself evaluates every frame (also research nvse-plugin.md section 7, game status).
void read_menu_state(void* ctx) {
    auto* r = static_cast<MenuRead*>(ctx);
    const UInt8* visible = reinterpret_cast<const UInt8*>(0x011F308F);
    r->loading = visible[kMenuType_Loading] != 0;
    r->dialog = visible[kMenuType_Dialog] != 0;
    r->menuMode = CdeclCall<bool>(0x702360);
}

void copy_name(const char* src, char* dst, size_t n) {
    if (!seh_copy_cstr(src, dst, n)) dst[0] = 0;
}

}  // namespace

// ---- shared with commands.cpp ---------------------------------------------------------------
namespace engine {

namespace {
void copy_worldspace(TESWorldSpace* ws, WorldRead* w) {
    if (!is_type(ws, kFormType_TESWorldSpace)) return;
    copy_name(ws->GetEditorID(), w->worldspace, sizeof w->worldspace);
    TESWorldSpace* parent = ws->parent;
    if (is_type(parent, kFormType_TESWorldSpace))
        copy_name(parent->GetEditorID(), w->parentWorldspace, sizeof w->parentWorldspace);
}
}  // namespace

// Cell and worldspace (high confidence): X GameObjects.h:145 TESObjectREFR::parentCell;
// X GameForms.h:3565 TESObjectCELL::worldSpace (@0xC0) and :3579 IsInterior() == (worldSpace == NULL);
// X GameForms.cpp:67-78 GetFullName: unnamed exterior cells inherit the worldspace name.
// EditorID: X GameForms.cpp:18-46 GetEditorID -> virtual GetName (worldspaces and cells keep
// their EDIDs at runtime: `cow`/`coc` resolve them; NVWM main.cpp:735 reads ws->GetName()).
// Parent worldspace: X GameForms.h:3678 TESWorldSpace `parent; // 070 confirmed`.
// Position: X GameObjects.h:142 posX/posY/posZ.
// Interiors leave worldspace empty here; see read_door_worldspace.
void read_world(void* ctx) {
    auto* w = static_cast<WorldRead*>(ctx);
    PlayerCharacter* p = player();
    if (!p) return;
    TESObjectCELL* cell = p->parentCell;
    if (!is_type(cell, kFormType_TESObjectCELL)) return;
    w->ok = true;
    w->interior = cell->IsInterior();
    w->pos[0] = p->posX;
    w->pos[1] = p->posY;
    w->pos[2] = p->posZ;

    copy_name(cell->GetTheName(), w->cell, sizeof w->cell);
    if (!w->cell[0]) copy_name(cell->GetEditorID(), w->cell, sizeof w->cell);
    copy_worldspace(cell->worldSpace, w);
}

// LOW confidence, separate fault bit: PlayerCharacter::lastExteriorDoor @0x604 (J GameObjects.h:945;
// xNVSE only has unk604 there) -> door->parentCell->worldSpace, every hop type-checked.
// Only called when the current cell has no worldspace (interior).
void read_door_worldspace(void* ctx) {
    auto* w = static_cast<WorldRead*>(ctx);
    PlayerCharacter* p = player();
    if (!p) return;
    auto* door = *reinterpret_cast<TESObjectREFR**>(reinterpret_cast<char*>(p) + 0x604);
    if (!is_type(door, kFormType_TESObjectREFR) || !is_type(door->parentCell, kFormType_TESObjectCELL)) return;
    copy_worldspace(door->parentCell->worldSpace, w);
    w->wsFromDoor = w->worldspace[0] != 0;
}

}  // namespace engine

// ---- NvseGame -------------------------------------------------------------------------------

bool NvseGame::onMainThread() const { return mainTid_ != 0 && GetCurrentThreadId() == mainTid_; }

bool NvseGame::guarded(uint32_t bit, const char* what, void (*fn)(void*), void* ctx) {
    if (disabled_ & bit) return false;
    unsigned long code = 0;
    if (seh_call(fn, ctx, &code)) return true;
    disabled_ |= bit;
    log_.info("reader '%s' faulted (exception 0x%08lX); disabled for this session", what, code);
    return false;
}

const char* NvseGame::blockedReason() {
    if (!onMainThread()) return "not on main thread";
    MenuRead m;
    if (!guarded(kMenu, "menu state", read_menu_state, &m)) return "menu state unreadable";
    if (m.loading) return "loading";
    if (m.menuMode || m.dialog) return "in menu";
    return nullptr;
}

void NvseGame::resolveInteriorWorldspace(engine::WorldRead& w) {
    if (!w.ok || !w.interior || w.worldspace[0]) return;
    engine::WorldRead d;  // separate buffer: a fault here cannot corrupt w
    if (guarded(kDoorWs, "last exterior door worldspace", engine::read_door_worldspace, &d) && d.wsFromDoor) {
        std::memcpy(w.worldspace, d.worldspace, sizeof w.worldspace);
        std::memcpy(w.parentWorldspace, d.parentWorldspace, sizeof w.parentWorldspace);
        w.wsFromDoor = true;
    }
}

void NvseGame::dropState(const char* why) {
    loaded_ = false;
    dcId_ = nvId_ = 0;
    log_.debug("state dropped (%s)", why);
}

void NvseGame::scanGlobals() {
    GlobalScan s;
    if (!guarded(kScan, "globals scan", scan_globals, &s)) return;
    dcId_ = s.dc;
    nvId_ = s.nv;
    log_.info("globals scan: %u globals, %s=%s, %s=%s", s.seen, kDcGlobal,
              dcId_ ? hex_form_id(dcId_).c_str() : "absent", kNvGlobal,
              nvId_ ? hex_form_id(nvId_).c_str() : "absent");
}

void NvseGame::onPostLoadGame(bool ok) {
    dropState("PostLoadGame");
    loaded_ = ok;
    firstSnapshotLogged_ = false;
    if (ok) scanGlobals();
}

void NvseGame::onNewGame() {
    dropState("NewGame");
    loaded_ = true;
    firstSnapshotLogged_ = false;
    scanGlobals();
}

void NvseGame::onPreLoadGame() { dropState("PreLoadGame"); }
void NvseGame::onExitToMainMenu() { dropState("ExitToMainMenu"); }

int NvseGame::ttwModIndex() {
    ModIndexRead r;
    if (!guarded(kModIndex, "mod index", read_ttw_index, &r)) return -1;
    return r.index == 0xFF ? -1 : static_cast<int>(r.index);
}

proto::SpikeSnapshot NvseGame::snapshot(int64_t frame, int64_t ms) {
    proto::SpikeSnapshot s;
    s.frame = frame;
    s.ms = ms;
    s.saveLoaded = loaded_;
    if (!loaded_ || !onMainThread()) return s;

    HealthRead h;
    if (guarded(kHealth, "health", read_health, &h) && h.ok && std::isfinite(h.cur) && std::isfinite(h.max) &&
        h.max > 0) {
        s.hp = h.cur;
        s.hpMax = h.max;
    }

    HardcoreRead hc;
    if (guarded(kHardcore, "hardcore", read_hardcore, &hc) && hc.ok) {
        s.hardcore = hc.flag != 0;
        if (!firstSnapshotLogged_) log_.debug("hardcore byte=%u dword@7BC=0x%08X", hc.flag, hc.raw);
    }

    auto global = [&](uint32_t id, const char* name) -> std::optional<int> {
        GlobalRead g;
        g.id = id;
        g.expect = name;
        if (id && guarded(kGlobals, "globals", read_global, &g) && g.ok && std::isfinite(g.value))
            return static_cast<int>(std::lround(g.value));
        return std::nullopt;
    };
    s.inDC = global(dcId_, kDcGlobal);
    s.inNV = global(nvId_, kNvGlobal);

    engine::WorldRead w;
    if (guarded(kWorld, "world", engine::read_world, &w) && w.ok) {
        resolveInteriorWorldspace(w);
        s.interior = w.interior;
        if (w.worldspace[0]) s.worldspace = std::string(w.worldspace);
        if (w.cell[0]) s.cell = std::string(w.cell);
    }

    ColorRead c;
    if (guarded(kColors, "colors", read_colors, &c) && c.ok) {
        const bool changed = !colorsLogged_ || std::memcmp(c.raw, lastColorsRaw_, sizeof c.raw) != 0;
        bool plausible = true;
        for (UInt32 v : c.raw) plausible = plausible && (v & 0xFF) == 0xFF;
        if (changed) {
            log_.info("system colors raw (0xRRGGBBAA): %08X %08X %08X %08X %08X -> %s", c.raw[0], c.raw[1],
                      c.raw[2], c.raw[3], c.raw[4], plausible ? "reported" : "implausible alpha, reporting null");
            std::memcpy(lastColorsRaw_, c.raw, sizeof c.raw);
            colorsLogged_ = true;
        }
        if (plausible) {
            std::array<std::array<int, 3>, 5> out{};
            for (size_t i = 0; i < 5; ++i) out[i] = rgb_from_system_color(c.raw[i]);
            s.colors = out;
        }
    } else if (!colorsLogged_ && !c.mgr) {
        log_.info("system colors: manager singleton at 0x11D8A88 is null; reporting null");
        colorsLogged_ = true;
    }

    if (!firstSnapshotLogged_) {
        firstSnapshotLogged_ = true;
        log_.info("first snapshot: hp=%s/%s hardcore=%s inDC=%s inNV=%s ws=%s%s cell=%s interior=%s",
                  s.hp ? std::to_string(*s.hp).c_str() : "null", s.hpMax ? std::to_string(*s.hpMax).c_str() : "null",
                  s.hardcore ? (*s.hardcore ? "1" : "0") : "null", s.inDC ? std::to_string(*s.inDC).c_str() : "null",
                  s.inNV ? std::to_string(*s.inNV).c_str() : "null", s.worldspace ? s.worldspace->c_str() : "null",
                  w.wsFromDoor ? " (via last exterior door)" : "", s.cell ? s.cell->c_str() : "null",
                  s.interior ? (*s.interior ? "1" : "0") : "null");
    }
    return s;
}

}  // namespace ttwds::game
