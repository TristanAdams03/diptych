# Diptych: design

Date: 2026-11-03 · Status: awaiting user approval

> **Diptych** *(n.)* /ˈdɪp.tɪk/ (DIP-tick). A work of art made of two panels, hinged so it closes like a book.
> From the Greek *díptychos*, "folded in two".

---

## 1. What this is and why

Diptych is a **companion app for dual-screen Android handhelds**, starting with the AYN Thor. You keep launching
games the way you already do (GameNative, Cocoon, any launcher). Diptych lives on the bottom screen:

- **No supported game running:** it shows the **Shelf**, a list of supported games with each one's setup status.
- **A supported game starts:** it switches to that game's **companion screen**, built for that game.

This replaces two separate efforts with one app:

1. **Tale of Two Wastelands.** The TTW companion is already designed in
   `ttw-dualscreen/docs/superpowers/specs/2026-11-03-ttw-dualscreen-design.md`. That doc stays the authority for TTW's
   companion content and its xNVSE plugin. Its milestone-0 device test (v0.0.1-spike) is still pending. Diptych
   becomes the app side of TTW.
2. **Kingdom Hearts Final Mix (KH1)**, new in this doc, from the 1.5+2.5 collection on Steam. The user is playing it
   now.

More games come later, one at a time. The user plays "the popular, must-play games in the series", so KH2 and BBS
are the likely next ones.

### The user's words that shaped this
- KH1: "the map, and switching magic and items mid fight" are what they keep stopping for. They look up maps on their
  phone, to find **where to go next** and **where chests are**.
- "Add party HP to the map tab too."
- "I prefer the easiest and simplest install method… for dual screen android users."
- Failure would be "if it causes me to reinstall [the] game on the Thor".
- "I want it to be a dual screen device companion app." With no game running, show "a list of supported games and
  their setup status".
- Take inspiration from DualScreen Launcher, but "make sure it is unique in its own way so we don't get flagged by
  [its] creator". Use Steam artwork.

---

## 2. Goals, non-goals, anti-goals

**Goals**
1. One app, one install, that companions several games on the bottom screen.
2. A Shelf showing every supported game and an honest status: what it needs and what's left to do.
3. KH1: replace the phone. Where to go next, which rooms still hold chests, quick magic and items, party HP.
4. The easiest install a dual-screen Android user could ask for, and an uninstall that leaves the game exactly as
   it was.
5. A visual identity that is clearly Diptych's own.

**Non-goals (v1)**
- Being a game library or frontend: no store logins, no game installs, no Windows containers of our own.
- Emulator games (Switch, etc.) and single-screen phones.
- KH games other than KH1. KH2, BBS and the others come later.
- Replacing KH's own in-game menus on the bottom screen.

**Anti-goals (failure even if it works)**
- **Any change that could force a game reinstall.** No patched executables and no edited game archives. Every mod is
  removable in one step.
- **Looking or reading like a copy of DualScreen Launcher** (§6). This covers UI, names, wording, logo and code.
- Shipping copyrighted game assets: box art, Square Enix or Bethesda files.
- Stealing controller focus from the game, pausing GameNative's container, or crashing the game.
- Burning in the bottom OLED.
- Paywalls, accounts or telemetry.

---

## 3. Constraints (facts this rests on)

- **Device:** AYN Thor (Snapdragon 8 Gen 2, Android 13). Top display 1920×1080; bottom display 3.92" AMOLED
  1240×1080. Display ids are not stable.
- **Runtime:** the user runs KH 1.5+2.5 from **Steam through GameNative**.
  - GameNative stores Steam games in its **private app storage** (`/data/user/0/app.gamenative/Steam/steamapps/common/…`).
    Neither Diptych nor a file manager can reach those files (GameNative `SteamService.kt`).
  - **GameNative's own mod importer can.** It imports a local zip/7z/rar or folder from the game's page into the game
    folder (or Documents/My Games/AppData). It records every file it places, so a mod can be removed cleanly
    (since **v1.1.1**).
  - It **adds Wine DLL overrides automatically** for proxy DLLs it finds next to the game's executable: `dinput8`,
    `version`, `winmm`, `winhttp`, `dsound`, `dwmapi`, `dwrite` (since **v1.3.0**, `ModDllOverrides.kt`).
    **Minimum supported GameNative: 1.3.0.**
- **The user's KH install is clean:** no OpenKH, LuaBackend or other mods.
- **KH1 PC:** `KINGDOM HEARTS FINAL MIX.exe` (x64). It sits in the same folder as the 1.5+2.5 launcher and the other
  collection games. Supported build: **Steam 1.0.0.2**. Epic 1.0.0.10 can come later from the same address tables.
- **Known memory addresses (Steam 1.0.0.2)** come from the community:
  - `gaithern/KH1-LUA-LIBRARY` (MIT, maintained), files `SteamGlobal_1_0_0_2.lua` / `EGSGlobal_1_0_0_10.lua`: world,
    room, Sora HP / max HP, input, command menu, Donald/Goofy pointers, cutscene/menu/Gummi states.
  - `HydroSulphide/KH1FM-Memory-Map` (GPL-3): about 1,800 labelled values, including all party HP/MP and item slots.
  - `gaithern/KH-1FM-AP-LUA` (MIT): chests-opened flags, world progress, inventory.
- **KH1 has no in-game area map or minimap** (players confirm). Every map Diptych shows, we build ourselves.
- **KH1 shortcuts are magic-only** (L1 + face button; one page of three by default). A LuaBackend mod (Nexus 224)
  proves the slots can be rewritten live. Items are usable only from Sora's 8 equipped item slots.
- **Lua route rejected for v1.** LuaBackend Hook reads its scripts from the container's Documents folder, and GameNative
  gives the Steam folder a varying drive letter, so it would mean a two-part install. The native-module pattern
  (`culk/KH1-RANDOMIZER` KH1Overlay) proves in-process DLLs work, so we ship a single native DLL instead.
- **Loopback:** a local socket inside Wine is reachable at `127.0.0.1` from Android, as already proven for TTW-style
  companions.
- **Build machine:** Arch on WSL2 with Docker. Native Windows DLLs build on GitHub Actions `windows-latest`. A public
  GitHub repo for the TTW project already exists, with working CI patterns to copy.

---

## 4. System overview

```
 GameNative container (top screen)                 Android (bottom screen)
 ┌──────────────────────────────────┐             ┌──────────────────────────────┐
 │ KINGDOM HEARTS FINAL MIX.exe     │             │ Diptych (Kotlin + Compose)    │
 │  └ dinput8.dll  (Diptych KH1 mod)│◄── WS ────►│  • Shelf + fold-out details   │
 │      127.0.0.1:<kh1 port>        │  loopback   │  • KH1 companion screen       │
 └──────────────────────────────────┘             │  • TTW companion screen       │
 ┌──────────────────────────────────┐             │  • device, focus, OLED care   │
 │ FalloutNV.exe + xNVSE            │◄── WS ────►│  • mod downloads, artwork     │
 │  └ ttw_dualscreen.dll            │             └──────────────────────────────┘
 └──────────────────────────────────┘
```

**Repo:** a new public repo, `diptych`. Layout: `app/` (Android), `mods/kh1/` (DLL), `protocol/` (shared spec and
fixtures), `tools/mockserver/` (per-game mocks), `docs/`. The TTW plugin stays in `ttw-dualscreen` until its device
spike passes, then moves to `mods/ttw/`. The builder may choose the exact layout.

---

## 5. Diptych app

### 5.1 Screens
- **Top bar (all screens):** the hinge mark, the "Diptych" wordmark, text tabs **Shelf · Device · Guide**, and a
  settings gear.
- **Shelf** (home, no game running):
  - A status line, e.g. "1 of 3 ready · GameNative 1.3.0 · Focus Lock off".
  - A horizontal **shelf of tall covers**. Each cover has a status ribbon: **READY**, **SET UP**, **UPDATE**,
    **SOON**.
  - The selected cover is enlarged with a brass outline. Under it, a line with the game's name, what its companion
    does, and a main button (**Play**, **Set up** or **Update**).
- **Fold-out** (a game's details), drawn as two leaves with a brass hinge between them:
  - **Left leaf:** the game's Steam hero art and logo, a status tag, and the required build (e.g. "Steam 1.0.0.2 ·
    GameNative 1.3.0+").
  - **Right leaf:**
    - **Before you play:** a checklist, ticked off as Diptych sees each step.
    - **What goes where:** top shows the game, bottom shows that game's companion.
    - **Mod check.**
    - **Show me how:** step-by-step pictures of the GameNative import.
    - **Changelog.**
- **Device:** the Thor settings that matter for every game, each with a plain "how to fix":
  - GameNative version;
  - AYN Focus Lock;
  - battery exemption for GameNative;
  - the container suspend policy;
  - which screen is which, with an **Identify screens** override.
- **Guide:** per-game setup guides, starting with the TTW-on-Thor guide that already exists, plus a KH1 guide.
- **Companion screens:** each game's own screen takes over **full-bleed** when that game connects (§7 for KH1),
  without Diptych's top bar. A small hinge button in one corner opens a pull-down to Shelf, Device and Settings
  mid-game. The TTW screen follows the TTW design.

### 5.2 Status: what Diptych can and can't know
Diptych can't see inside GameNative's storage, so status is based on what Diptych can observe:

| Status | Meaning |
|---|---|
| **SOON** | Not supported yet. Shown greyed out with what's planned. |
| **SET UP** | The mod has never connected. Shows the steps. "Mod downloaded" is ticked when Diptych has the zip. |
| **READY** | The mod has connected at least once, with a compatible version and a supported game build. |
| **UPDATE** | A newer mod is available, or the connected mod or game build doesn't match. Explains which. |

**TTW before its companion lands (M6):** TTW shows **SOON**, with a note that it's being tested in the TTW
DualScreen app. While that separate test app is running on the bottom screen, Diptych stays in the background and
doesn't compete for the display.

The GameNative version is read from the installed package. Focus Lock and battery exemption are read where Android
allows it; otherwise the Device tab shows them as "check this", not as a status we pretend to know.

### 5.3 Shared behavior
All of this carries over from the TTW design (§5.4–5.5 and §7 there). It is written once in Diptych and used by every
game:
- **Display handling:** pick the bottom screen with a heuristic, device profiles and a manual override; never put an
  activity on the game's display.
- **Focus:** a non-focusable window with a focus anchor, an optional accessibility hand-back, and a Focus Lock
  recommendation.
- **Survival:** a foreground service, reconnect forever, revive after a kill.
- **OLED care:** idle blank, pixel shift, a brightness ceiling.
- **Connection-state screens.**

Diptych listens for every supported game's mod at once, each on its own loopback port, and switches to whichever
connects.

### 5.4 Mods, updates and network use
- Diptych downloads each game's mod zip from this repo's **GitHub Releases** and keeps it where the user can pick it
  in GameNative's importer (e.g. `Download/Diptych/`).
- It checks for app and mod updates on launch, and notifies when something is newer.
- **The only internet use:** Steam artwork and GitHub release checks. Both can be switched off.
- No accounts, no telemetry, no sign-in.

### 5.5 Steam artwork
- Fetched at runtime from Steam's public CDN and cached on the device. **Never bundled in the APK or the repo.**
- URLs (verified 2026-11-03), with `shared.akamai.steamstatic.com/store_item_assets/steam/apps/<appid>/…` as the
  fallback host:
  - `https://cdn.cloudflare.steamstatic.com/steam/apps/<appid>/library_600x900.jpg` (cover; `_2x` for high DPI)
  - `…/library_hero.jpg` (fold-out left leaf)
  - `…/logo.png` (fold-out logo)
  - `…/header.jpg` (spare)
- App ids:
  - KH 1.5+2.5: **2552430**. One Steam app holds all the collection's games, so KH1 and KH2 share its art. The
    ribbon names the game.
  - TTW has no Steam page. Its cover is **Fallout 3 (22370) and New Vegas (22380) side by side**, split by the brass
    hinge.
- Offline or failed fetch: a plain tile with the game's initials and its accent color.

---

## 6. Identity: unique to Diptych (rules)

Diptych is inspired by what users liked about DualScreen Launcher: honest statuses, the required game build shown up
front, per-game details, and a preview of what goes on which screen. It must not look like it, read like it, or reuse
anything of it.

- **Look:**
  - Warm **charcoal and brass** gallery palette, not navy or indigo.
  - **Serif display type** (Fraunces) with a clean sans (Manrope). Both are OFL and bundled.
  - The **two-panel hinge mark**. No cat or controller icon.
- **Layout:**
  - A top bar with text tabs and a **horizontal cover shelf**. No left navigation rail and no vertical list of
    library rows.
  - Details as a **two-leaf fold-out**.
- **Words:** our own, e.g. "Shelf", "Before you play", "What goes where", "Mod check", READY / SET UP / UPDATE /
  SOON. Avoid DualScreen Launcher's phrases ("Your library", "Ready to play", "Managed game configuration",
  "Dual-screen preview", "Companion profile").
- **Scope:** a companion over the user's existing GameNative install, not a launcher with its own containers. This
  keeps it different by design.
- **Code:** DualScreen Launcher is closed source. We use nothing from its APK, its payloads or its DLLs. Facts we
  learned (e.g. its use of port 7788) only tell us what to avoid.
- **Ports:** never 7786–7788 (ProfMags), 8765 (Skyrim companion), 27000/28000 (FO4 Pip-Boy), or 7960 (TTW plugin,
  ours). The KH1 mod gets its own port in a Diptych range chosen by the builder.

Approved look: `mockups/diptych-v4.html` (`diptych-v4.png`).

---

## 7. KH1 companion screen

Approved layout: `mockups/kh1-first-look-v2.html` (`kh1-v2.png`).
**Approved style (user, 2026-11-04):** `mockups/kh1-style/` (Map tab and Magic & Items with the Cura target
picker). Night-sky navy `#0b1433`, panels `#15235a` with a `#9fc0ff` rim, gold `#ffd166` for next/chests, "you"
`#3f8cff`; Fredoka headings + Nunito body (OFL); touch targets ≥44 dp. Live at
https://claude.ai/artifact/NbEdcugnxr11CzczfcZwUC. The final styling is a KH-inspired design of our
own, drawn by us; no Square Enix assets.

- **Header:** world and room ("TRAVERSE TOWN · SECOND DISTRICT").
- **Next:** one line with where to go next ("head to the Hotel and talk to Leon"), with a tap to hide spoilers. It
  is shown by default.
- **Map (main area):**
  - A **room diagram** of the current world: rooms as boxes, connections as lines.
  - The current room is lit up, the next destination glows gold, and rooms you can't reach yet are dimmed.
  - Each room shows **chests opened / total**, e.g. "1 / 3", with a ✓ when cleared.
- **Pinned beside the map:** party HP and MP (Sora, Donald, Goofy). This is the user's request: it stays on the map
  tab.
- **Magic:** tap to cast the magic Sora has learned. **Items:** tap to use from Sora's 8 equipped item slots, showing
  counts. In the first layout both sit next to the map, and may move to their own tab later with party HP staying
  pinned.
- **Tabs:** MAP · MAGIC & ITEMS · PARTY · JOURNAL. Journal can wait for a later version.

### 7.1 Data the companion needs
**Live from the mod:** world id, room id, Sora/Donald/Goofy HP/MP and max values, item slots and stock counts, learned
magic and levels, chest-opened flags, world-progress and story flags, and the cutscene, menu and Gummi states.

**Built once by us, shipped as data files in the app:**
1. **Rooms per world:** room id → name, which rooms connect, and a diagram layout. Sourced from community room
   lists and the address libraries, then hand-checked.
2. **Chests per room:** chest flag → room and contents. Seeded from the Archipelago KH1 location list (MIT) and
   checked.

**Checking the data.** Room ids, chest flags and story flags are checked against the Steam 1.0.0.2 build on the user's
Windows PC, by reading live values while playing. The KH1 screen also has a **"this looks wrong"** tap, which saves the
current world/room/flags to the problem report, so mistakes found on the Thor can be fixed.
3. **Story steps:** progress-flag conditions → a "next" hint and the destination room. This is the biggest
   hand-authored piece, written world by world. It can ship one world at a time; a world without steps yet shows
   the map and chests with no hint.

### 7.2 Actions (writes)
- **Cast magic** and **use item** only when it's safe: not in a cutscene, menu, Gummi Ship, dialogue or a loading
  screen. Otherwise the button is greyed out with the reason.
- The mechanism was decided in the KH1 spike (`docs/spike/RESULTS.md`): writing the input address does nothing;
  presses go through an in-memory hook of `XInputGetState` (works on PC and Thor). Shortcut spells open a target
  picker for some spells (Cura), so a cast includes picking the target. It must not leave the player's shortcuts changed afterwards, unless
  that is the intended effect.
- Never write anything else. No stat, item-count or flag edits.

---

## 8. KH1 mod (`dinput8.dll`)

- **One file**, in a zip that GameNative imports into the game folder. GameNative ≥1.3.0 is meant to set the
  override itself, but on the Thor test (2026-11-04) it did not for KH 1.5+2.5: the user adds
  `WINEDLLOVERRIDES=dinput8=n,b` in the game's Environment settings (see `docs/spike/RESULTS.md`).
- **Proxy:**
  - It forwards DirectInput (`DirectInput8Create` and the other exports) to the real system `dinput8.dll`, so
    controls work exactly as before.
  - It does nothing inside the 1.5+2.5 launcher or any other collection game. It turns on only when the process is
    `KINGDOM HEARTS FINAL MIX.exe`.
- **Version gate:** confirm the game is Steam 1.0.0.2 before doing anything. Check the exe version or a byte
  signature at a known address. If it doesn't match, stay inert and tell Diptych "unsupported build" over the socket
  so the Shelf shows UPDATE.
- **No disk changes.** Reads and writes happen in memory only. The game folder is private on Android, so logs can't
  be read there: the log is kept in memory and sent to Diptych on request, and Diptych's **Report a problem**
  bundles it.
- **Server:** loopback-only WebSocket, reusing the TTW plugin's portable WebSocket core and hardening (Origin/Host
  checks, bounded queues), rebuilt for x64.
- **Timing:** reads at a bounded rate (≤10 Hz) with no effect on the game thread. Writes must be timed the way the
  game expects; the spike decides whether that needs a frame hook or not. No code patches on disk; any in-memory hook
  is removed cleanly on exit.
- **Safety:** every read is guarded, so a fault blanks that field and never crashes the game. Uninstall is
  GameNative → Mods → remove.
- **Coexisting with LuaBackend** (users who also want KH Lua mods): out of scope for v1, recorded as a later option.
- **Never overwrite another proxy.** The setup guide and "Show me how" say to stop if GameNative's importer reports
  that `dinput8.dll` already exists. If the DLL finds it was renamed or chain-loaded oddly, it stays inert and
  reports why.
- **On a Windows PC** (development, and PC players), the same DLL loads natively next to the exe. There it also writes
  its log to its own folder.

---

## 9. Protocol
- A shared envelope with TTW's protocol v0: hello / welcome / subscribe / state / command / result / ping / pong /
  error.
- `welcome` adds `gameId` (`kh1` | `ttw`), `gameBuild` and `supported`. These are new field names, so they don't
  clash with TTW v0's existing `game` build string; TTW v0 stays compatible.
- Each game defines its own topics and commands in `protocol/<game>/`, with fixtures shared by the mod, the mock
  server and the app tests.

---

## 10. Testing
- **Mock servers per game** (KH1 states in several worlds, mid-fight, in a cutscene, unsupported build), so the app
  is built and screenshot-tested without the device.
- **App:**
  - unit tests (status logic, data-table loading, "next" step evaluation, display picker);
  - screenshot tests of the Shelf, the fold-out, and the KH1 screens in several worlds.
- **Mod:**
  - portable unit tests (protocol, framing, flag decoding against captured memory snapshots);
  - Windows CI build and export checks.
- **PC-first loop:** the user installs Steam KH 1.5+2.5 (the same 1.0.0.2 build) on their Windows PC.
  - Mod work, address checks and data checks happen there first. Memory snapshots captured on the PC become test
    fixtures.
  - The Thor is used only for what only it can prove: loading under GameNative/FEX, loopback, the bottom screen and
    focus.
- **Device checklists** the user runs on the Thor, as with the TTW spike.

## 11. Milestones
The user is **late in KH1** (Neverland / Hollow Bastion / End of the World), so the order puts this playthrough first.

0. **KH1 spike, PC first, then Thor:**
   - the proxy loads;
   - controls still work;
   - Diptych connects over loopback;
   - world, room and HP read correctly;
   - one safe write (e.g. cast Cure) works.

   (The TTW spike is still pending and runs separately.)
1. **Thin Diptych shell:**
   - the Shelf with Steam covers and statuses;
   - the connection service, display/focus/OLED care;
   - the full-bleed companion frame with the hinge button.

   Before M2, a **KH1 style mockup for the user to approve**.
2. **KH1 read-only, late worlds first:**
   - room diagrams, chests per room and pinned party HP for **Neverland, Hollow Bastion and End of the World**;
   - then the remaining worlds.
3. **KH1 actions:** tap-to-cast magic and tap-to-use items, with safe-state gating.
4. **KH1 "next" hints:** late worlds first, then the rest, with the spoiler toggle.
5. **Shell polish:** the full fold-out, Device and Guide tabs, mod download and update checks, "Show me how".
6. **TTW companion inside Diptych**, built from the TTW design once its spike passes.
7. **Guides:** KH1 on the Thor with Diptych, and TTW updated for Diptych.

### Milestone order change (user, 2026-11-04)
After the spike passed, the user chose **KH1 map first**: the KH1 style mockup and M2 (read-only map, chests, pinned
party HP for the late worlds) come next, inside a bare-bones app. The M1 shell (Shelf, covers, service polish) moves
after M2.

## 12. Answers from the user (2026-11-03)
1. **PC testing:** yes. The user will install Steam KH 1.5+2.5 on their Windows PC, so most mod and data work is
   tested there before the Thor.
2. **KH1 progress:** late game. Build Neverland, Hollow Bastion and End of the World first, so it helps on this
   playthrough.
3. **KH folder on the Thor:** clean install, no mods (said earlier).

## 13. Calls made by Claude
- **Repo:** new public repo `diptych` under the same GitHub account, GPL-3.0. The TTW plugin moves in after its
  spike.
- **App stack:** Kotlin + Compose, as in the TTW design.
- **KH1 mod:** a native `dinput8.dll` proxy, not LuaBackend. Lua coexistence comes later.
- **Fonts:** Fraunces + Manrope (OFL), bundled.
- **"Next" hints** are on by default, with a one-tap spoiler hide.
- **Journal** is deferred.
- **Mod zips** land in `Download/Diptych/` for the GameNative importer.
- **Companion screens are full-bleed** with a corner hinge button, not under Diptych's top bar.
- **TTW shows SOON** until its spike passes. Diptych stays out of the way of the separate TTW test app.
- **Map data** is checked on the user's PC, plus a "this looks wrong" tap that feeds the problem report.
- **The KH1 look gets its own mockup approval** before M2.
- **Protocol field `gameId`**, not `game`, so the TTW v0 plugin stays compatible.
