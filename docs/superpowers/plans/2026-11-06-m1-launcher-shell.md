# Diptych Launcher Shell (M1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** With no game running, the bottom screen shows Diptych itself: a shelf of Steam covers with honest setup statuses, a two-leaf fold-out per game, and basic Device and Guide tabs. When KH1's mod connects, the KH1 companion takes over full-bleed, with a corner hinge button to get back.

**Architecture:**
- A foreground service owns the one `SpikeClient` and `Kh1Session` and reconnects forever. The activity only observes it.
- A small persisted store remembers which mods have connected, so statuses survive restarts.
- Shell screens are new composables under `com.diptych.app.shell`, separate from `kh1/ui`.
- Covers come from Steam's public CDN at runtime and are cached on the device, never bundled.

**Tech Stack:** Kotlin + Compose (existing app). Coil 2.7.0 (`io.coil-kt:coil-compose`) for images over the existing OkHttp. Android `Service` + notification. Paparazzi screenshots (existing). JUnit.

**Spec:** `docs/superpowers/specs/2026-11-03-diptych-design.md` §5.1–5.5 (screens, status, shared behavior, artwork), §6 (identity rules), §11 M1 and "Milestone order change". Approved look: `docs/superpowers/specs/mockups/diptych-v4.html` (`diptych-v4.png`).

## Global Constraints

- **Identity (spec §6):**
  - Warm charcoal and brass: ground `#151412`, surface `#1e1c19`, raised `#26231f`, ink `#f2ece1`, muted `#a39a8b`, brass `#c9a45c`, line `#3a352e`. Never navy or indigo in the shell. KH1's navy stays inside its own companion screen.
  - Fraunces (display) + Manrope (body), both OFL and bundled with their license files.
  - The two-panel hinge mark. No left rail, no vertical library list.
- **Words:** "Shelf", "Device", "Guide", "Before you play", "What goes where", "Mod check", READY / SET UP / UPDATE / SOON. Never "Your library", "Ready to play", "Managed game configuration", "Dual-screen preview" or "Companion profile".
- **The only internet use:** Steam artwork (`https://cdn.cloudflare.steamstatic.com/steam/apps/<appid>/…`, fallback `https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/<appid>/…`) and GitHub release links opened in the browser. Artwork can be switched off in Settings. No accounts, no telemetry.
- **App ids:** KH 1.5+2.5 `2552430` (KH1 and KH2 share it; the ribbon names the game). TTW cover = Fallout 3 `22370` + New Vegas `22380` side by side, split by a brass hinge.
- **Never put an activity on display 0** (the game display). Keep the existing `pickCompanionDisplay` / `relocationTarget` logic.
- **Touch targets** ≥ 48 dp. Text ≥ 12 sp in the shell.
- **Port** 7970 for KH1. The app listens for one game in M1; the design allows more later.
- **Commits:** author `ghost <t.j.adams06@gmail.com>`. End each message with a blank line, then:
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`
  `Claude-Session: https://claude.ai/code/session_019oN1T9KYsPh716Ayphvqnu`

## Review Focus

1. **The service and the activity disagree after Android kills one of them.** Expected: reopening the app shows the true connection state within 2 s, with no duplicate sockets. Owner: Task 4 (service binding test).
2. **No network, or a Steam CDN 404.** Expected: the initials tile in the game's accent color, no spinner forever, and no crash. Owner: Task 3.
3. **KH1 connects while the user is on the fold-out or the Device tab.** Expected: the companion takes over. The hinge button returns to where they were. Owner: Task 5.
4. **The mod disconnects mid-game** (game closed). Expected: the companion shows "Waiting" for 10 s, then the Shelf comes back. Reconnecting within 10 s keeps the companion. Owner: Task 5.
5. **An unsupported build connects.** Expected: KH1's ribbon says UPDATE with the reason ("needs Steam 1.0.0.2"), never READY. Owner: Task 2.

---

### Task 1: Shell theme, fonts, hinge mark and top bar

**Files:**
- Create: `app/app/src/main/java/com/diptych/app/shell/ShellTheme.kt`, `shell/HingeMark.kt`, `shell/TopBar.kt`
- Create: `app/app/src/main/res/font/fraunces.ttf`, `manrope.ttf` (variable TTFs from github.com/google/fonts `ofl/fraunces`, `ofl/manrope`), `app/app/src/main/assets/licenses/OFL-Fraunces.txt`, `OFL-Manrope.txt`
- Test: `app/app/src/test/java/com/diptych/app/shell/ShellScreenshotTest.kt` (`topBar`)

**Interfaces:**
- `object ShellColors` holds the tokens above.
- `ShellType` gives display = Fraunces 700, body = Manrope.
- `@Composable fun HingeMark(modifier)`: two 9×16 dp outlined leaves with a 2×8 dp brass pin between, as in diptych-v4.
- `enum class ShellTab { Shelf, Device, Guide }`
- `@Composable fun TopBar(active: ShellTab, onSelect: (ShellTab) -> Unit, onSettings: () -> Unit)`
  - It holds the hinge mark, the "Diptych" wordmark, a small "DIP-tick" under it, the tabs and a gear.

- [ ] **Step 1:** Write the screenshot test `topBar` with Shelf active. Record it.
- [ ] **Step 2:** Implement until it matches diptych-v4's bar. Verify the screenshot, and look at the PNG.
- [ ] **Step 3:** Commit: `feat(shell): charcoal-and-brass theme, fonts, hinge mark, top bar`.

### Task 2: Game catalog and status

**Files:**
- Create: `shell/Catalog.kt`, `shell/Status.kt`, `shell/SeenStore.kt`
- Test: `app/app/src/test/java/com/diptych/app/shell/StatusTest.kt`

**Interfaces:**
- `data class GameEntry(val id: String, val title: String, val ribbonName: String, val tagline: String, val requiredBuild: String, val coverAppIds: List<Int>, val heroAppId: Int, val accent: Long, val port: Int?, val modAsset: String?, val guideUrl: String?, val changelogUrl: String?)`
- `val CATALOG: List<GameEntry>`, in order:
  1. **TTW:** id "ttw"; title "Tale of Two Wastelands"; ribbon "TTW"; covers [22370, 22380]; hero 22380; port null (SOON). Tagline: "Pip-Boy dashboard · map · items · radio".
  2. **KH1:** id "kh1"; title "Kingdom Hearts Final Mix"; ribbon "KH1"; covers [2552430]; hero 2552430; required "Steam 1.0.0.2 · GameNative 1.3.0+"; port 7970. Tagline: "Map with chests · story hints · party HP".
  3. **KH2:** id "kh2"; ribbon "KH2"; covers [2552430]; port null (SOON). Tagline: "Minimap · party HP · drive gauge".
- `enum class GameStatus { Soon, SetUp, Ready, Update }`
- `data class StatusInfo(val status: GameStatus, val reason: String)`
- `fun statusOf(entry: GameEntry, seen: SeenRecord?, live: ServerMsg.Welcome?): StatusInfo`. The rules, from spec §5.2:
  - `port == null` → Soon "Coming later".
  - A live welcome with `supported == false` → Update `"This KH1 build isn't supported yet (needs Steam 1.0.0.2)"`.
  - A live welcome, or a seen record, whose plugin major.minor is older than the app's expected `0.2` → Update "Mod update available".
  - A seen record with supported → Ready "Mod seen <date>".
  - Otherwise → SetUp "Mod never seen".
- `data class SeenRecord(val plugin: String, val gameBuild: String, val supported: Boolean, val atMs: Long)`
- `class SeenStore(prefs: SharedPreferences)` with `fun get(id: String): SeenRecord?` and `fun put(id: String, r: SeenRecord)`. It stores JSON per game id.

- [ ] **Step 1: Write failing tests,** one per rule above, plus:
  - Review Focus 5: unsupported is never Ready, even with an old supported seen record;
  - a SeenStore round-trip with a fake SharedPreferences.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*StatusTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** it again. Expected: PASS.
- [ ] **Step 5: Commit:** `feat(shell): game catalog and honest setup status`.

### Task 3: Steam artwork

**Files:**
- Create: `shell/Artwork.kt`, `shell/CoverTile.kt`
- Modify: `app/app/build.gradle.kts` (add `io.coil-kt:coil-compose:2.7.0`)
- Test: `app/app/src/test/java/com/diptych/app/shell/ArtworkTest.kt`, screenshots `coverFallback`, `coverTtwSplit`

**Interfaces:**
- `enum class ArtKind(val file: String) { Cover("library_600x900.jpg"), Hero("library_hero.jpg"), Logo("logo.png") }`
- `fun artUrls(appId: Int, kind: ArtKind): List<String>`. It returns `[cloudflare url, akamai fallback url]` in that order.
- `@Composable fun CoverTile(entry: GameEntry, selected: Boolean, statusInfo: StatusInfo, artworkEnabled: Boolean)`:
  - A 2:3 cover with a status ribbon (✓ READY brass, SET UP amber `#e8b75d`, UPDATE amber, SOON muted).
  - The selected tile is larger, with a 2 dp brass outline offset by 2 dp.
  - TTW draws two half covers split by a 2 dp brass hinge.
  - While loading, or on failure, or with artwork off: the initials (e.g. "KH1") on the game's accent color.
  - An image request tries each URL in order. Coil's disk cache handles caching; set a 50 MB disk cache in an `ImageLoader` singleton.

- [ ] **Step 1: Write failing tests:**
  - `artUrls(2552430, Cover)` equals the two exact URLs;
  - the fallback order;
  - screenshots of the fallback tile (artwork off) and the TTW split (with Paparazzi's offline loader, both halves fall back).
- [ ] **Step 2: Implement, run, verify.** Review Focus 2: no infinite spinner. Use a fallback painter, not a loading indicator.
- [ ] **Step 3: Commit:** `feat(shell): Steam cover art with fallback tiles`.

### Task 4: Connection service

**Files:**
- Create: `app/app/src/main/java/com/diptych/app/service/ConnectionService.kt`, `service/ConnectionState.kt`
- Modify: `AndroidManifest.xml`:
  - permissions `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`;
  - the service, with `foregroundServiceType="specialUse"` and a `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` value of "Keeps the loopback connection to the game mod alive";
  - a `<queries>` entry for package `app.gamenative`.
- Modify: `MainActivity.kt`. Stop creating the `SpikeClient` and `Kh1Session` here. Bind to the service and collect its state.
- Test: `app/app/src/test/java/com/diptych/app/service/ConnectionStateTest.kt`

**Interfaces:**
- `data class ConnectionSnapshot(val ui: SpikeUi, val view: MapView?, val welcome: ServerMsg.Welcome?)`
- `class ConnectionService : Service` exposes `val state: StateFlow<ConnectionSnapshot>` through a local `Binder`. It also exposes `client: SpikeClient` for the diagnostics screen.
  - It creates `Kh1Session` BEFORE `client.start()`, because the events flow has no replay.
  - On every `welcome`, it writes a `SeenRecord` to `SeenStore`.
  - Its notification says: "Diptych is listening for your game" (low importance, silent).
  - `START_STICKY`.
- `fun reduceConnection(prev: ConnectionSnapshot, msg: ServerMsg): ConnectionSnapshot` is a pure reducer. Unit-test it.

- [ ] **Step 1: Write failing tests:**
  - for the reducer: welcome sets the welcome; state updates the view; disconnect clears the welcome but keeps the last view;
  - for Review Focus 1, the binding logic factored as `fun shouldStartService(bound: Boolean, running: Boolean): Boolean`.
- [ ] **Step 2: Implement.** On API 33+, the activity asks for `POST_NOTIFICATIONS` once on first launch. If it's denied, the service still runs; the notification just stays hidden.
- [ ] **Step 3: Run** the unit tests and `:app:assembleDebug`. Expected: PASS.
- [ ] **Step 4: Commit:** `feat(app): foreground connection service owns the game link`.

### Task 5: Shelf, fold-out, companion takeover and hinge button

**Files:**
- Create: `shell/ShelfScreen.kt`, `shell/FoldOut.kt`, `shell/ShellRoot.kt`, `shell/HingeButton.kt`, `shell/OledCare.kt`
- Modify: `MainActivity.kt`. `ShellRoot` becomes the content. The diagnostics long-press stays, now in the TopBar wordmark (1 s) and on the companion's header strip.
- Test: screenshots `shelfKh1Selected`, `foldOutKh1SetUp`, `foldOutKh1Ready`, `hingePullDown`; unit `ShellRouteTest.kt`

**Interfaces:**
- **Shelf** (diptych-v4, left board):
  - A status line: "1 of 3 ready · GameNative 1.3.0 · Focus Lock: check". The GameNative version comes from `PackageManager.getPackageInfo("app.gamenative", 0).versionName`, or "GameNative not found".
  - A horizontal row of `CoverTile`s.
  - An info row for the selected game: its title, tagline, and main button Play / Set up / Update.
    - **Play** opens GameNative via its launch intent.
    - **Set up** and **Update** open the fold-out.
- **Fold-out** (diptych-v4, right board):
  - **Left leaf, 44%:** the hero art dimmed to 72%, the logo, a status tag and the required build.
  - **Brass hinge:** a 2 dp line between the leaves.
  - **Right leaf:**
    - "Before you play" checklist. Each item is ticked from the data Diptych has:
      1. "Mod zip downloaded" is unticked in M1, with "Get it from the release page" opening `modAsset`'s release URL in the browser;
      2. "In GameNative: KH 1.5+2.5 → Mods → Import";
      3. "Add `WINEDLLOVERRIDES` = `dinput8=n,b` in Environment";
      4. "Start KH1 once; Diptych ticks this off", ticked when a SeenRecord exists.
    - "What goes where": Top · the game | Bottom · map, hints, party.
    - "Mod check": shows the seen plugin and build, or "not seen yet".
    - Buttons: "Show me how" (`guideUrl`) and "Changelog" (`changelogUrl`).
- **Routing:**
  - `sealed interface Route { Shell(tab: ShellTab, foldOut: String?); Companion(gameId: String) }`
  - `fun nextRoute(current: Route, previousShell: Route.Shell, snapshot: ConnectionSnapshot, disconnectedForMs: Long): Route`
    - A supported KH1 welcome → Companion("kh1").
    - Disconnected ≥ 10 000 ms → `previousShell`.
    - An unsupported welcome → stay in Shell. The ribbon says UPDATE.
- **HingeButton:** a 48 dp corner button over the companion. It opens a pull-down with Shelf, Device and Settings. Choosing one shows the shell with a "Back to game" chip while connected.
- **OledCare:**
  - every 60 s, shift the whole shell by up to 2 dp in a cycle;
  - after 5 min without a connection or touch, dim to 10% black overlay; any touch restores.
  - The companion is excluded from the dimming but keeps the pixel shift.

- [ ] **Step 1: Write failing `ShellRouteTest` cases for Review Focus 3 and 4:**
  - a welcome while on Device → Companion, and back returns to Device;
  - a disconnect at 9 999 ms stays, at 10 000 ms returns;
  - unsupported stays on the Shelf.
- [ ] **Step 2: Implement.** Record the four screenshots and look at each PNG.
- [ ] **Step 3: Run** all unit tests and the Paparazzi verify. Expected: PASS.
- [ ] **Step 4: Commit:** `feat(shell): Shelf, fold-out, companion takeover, hinge button, OLED care`.

### Task 6: Device and Guide tabs (basic), Settings

**Files:**
- Create: `shell/DeviceTab.kt`, `shell/GuideTab.kt`, `shell/SettingsSheet.kt`
- Test: screenshots `deviceTab`, `guideTab`

**Interfaces:**
- **Device:** one row per item, each with a plain "how to fix" line.
  - GameNative version: needs ≥ 1.3.0.
  - Battery exemption for GameNative: `PowerManager.isIgnoringBatteryOptimizations("app.gamenative")`. If not exempt, "Settings → Apps → GameNative → Battery → Unrestricted".
  - Focus Lock: shown as "check this" with the note "Not needed on your Thor so far: touches didn't steal the controller in the TTW test".
  - Screens: the current companion display id and size, plus an **Identify screens** button. It shows the display id in large text on this screen for 3 s.
- **Guide:** cards that open in the browser:
  - "KH1 on the Thor with Diptych" → `docs/spike/MAP-CHECKLIST.md` on GitHub main;
  - "TTW on the AYN Thor" → the ttw-dualscreen guide URL.
- **Settings sheet** (gear): a "Steam artwork" toggle (default on), the app version, and the licenses (lists the bundled OFL fonts and opens each file).

- [ ] **Step 1: Record the screenshots, implement, verify.**
- [ ] **Step 2: Commit:** `feat(shell): basic Device and Guide tabs, settings`.

### Task 7: Release v0.3.0 and the shell checklist

**Files:**
- Create: `docs/spike/SHELL-CHECKLIST.md` (Thor, plain steps, one action per line)
- Modify: `README.md`, `app/app/build.gradle.kts` (versionName "0.3.0", versionCode bumped)

- [ ] **Step 1: Write the checklist.** It covers:
  - installing the APK over the old one, or uninstalling first if Android refuses;
  - the Shelf appearing with covers;
  - KH1 showing READY, because you ran it before;
  - opening KH1's fold-out;
  - launching KH1 and checking the companion takes over;
  - the hinge button → Shelf → "Back to game";
  - closing the game, waiting 10 s, and checking the Shelf returns;
  - turning artwork off and seeing the tiles.
- [ ] **Step 2: Commit, then tag** `v0.3.0`. Watch `release.yml` until it is green. Check the release has the zip (namelist `['dinput8.dll']`) and the APK.
