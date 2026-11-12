# Tale of Two Wastelands on the AYN Thor: Setup Guide

*Part of TTW DualScreen. Researched 2026-11-03 from the sources linked in each step. Every step is tagged with how well it is confirmed. If you already have TTW running on your Thor, skip to [Part F](#part-f-install-ttw-dualscreen).*

This guide takes you from "I own Fallout 3 and New Vegas" to "TTW boots on the Thor's top screen". The second-screen companion goes on top of that (see [Part F](#part-f-install-ttw-dualscreen)).

**How to read the confidence tags**

| Tag | Meaning |
|---|---|
| **[Verified-by-source]** | An official or primary source says so: the TTW/ModdingLinked guide, a mod's own page, a project README, or GameNative source code. |
| **[Community-report]** | One or more players report that it worked for them: Reddit, YouTube, EmuReady, community guides. Other setups may differ. |
| **[Unverified-guess]** | Our own inference. Needs testing before this guide ships. |

**Backbone source.** The main route follows Timeless Jupiter's firsthand video, [*Fallout Tale of Two Wastelands - Ayn Thor - GameNative - Vortex (2.0)*](https://www.youtube.com/watch?v=JiqVAiGlCtU) (2026-07-27, GameNative 1.1.1). Timestamps like `[13:13]` point into that video. Where other sources disagree, the step says so.

---

## The short version

1. **Build TTW on a Windows PC.** The TTW installer is a Windows program and takes 15 minutes to several hours. Do not try to build it on the Thor.
2. **Add the essential stability mods on the PC with Vortex**, then test that TTW boots on the PC.
3. **Pack the finished New Vegas folder** with your settings, load order, and a few installers, and **copy it to the Thor**.
4. **Add it to GameNative as a custom game**, set the container options, run the installers and the registry fix inside the container, and launch.

## Which frontend? GameNative.

**Recommendation: GameNative, current stable release (v1.3.0, 2026-10-14).** [Community-report]

- **It's where the TTW-on-Thor work is.** Every TTW-on-Thor walkthrough we found uses GameNative:
  - the [Timeless Jupiter video](https://www.youtube.com/watch?v=JiqVAiGlCtU)
  - the [Shonk's Pocket Wastelands guide](https://cybershonk.github.io/PocketWastelands/), which has a "Verified working" Snapdragon 8 Gen 2 profile
  - the [FNV GameNative 4GB Patcher](https://github.com/CyberShonk/fnv-gamenative-4gb-patcher), tested on a real AYN Thor
  - the [Droid Mod Loader](https://github.com/CyberShonk/DroidModLoader) Android mod manager, which has a TTW profile
- **It already knows about New Vegas.** GameNative ships New Vegas registry fixes for the Steam, GOG, and Epic store versions. Recent releases also fixed Thor-specific controller problems. [Verified-by-source: [STEAM_22380.kt](https://github.com/utkarshdalal/GameNative/blob/master/app/src/main/java/app/gamenative/gamefixes/STEAM_22380.kt), [v1.4.0-prerelease notes](https://github.com/utkarshdalal/GameNative/releases/tag/v1.4.0-prerelease)]
- **Avoid v1.4.0-prerelease for now.** It makes "gamescope" the default renderer, which its own release notes call a "potential breaking change". [Verified-by-source: [release notes](https://github.com/utkarshdalal/GameNative/releases/tag/v1.4.0-prerelease)]

**GameHub (or GameHub Lite)** runs plain New Vegas well on the Thor, at 50–60 FPS in [EmuReady reports](https://www.emuready.com/listings/9923f28c-6770-487c-9bd3-a52cc041f8d3). xNVSE, which TTW requires, is the problem there:

- One tester got "error 998" with xNVSE on GameHub ([r/AynThor](https://www.reddit.com/r/AynThor/comments/1r8t7z6/fallout_new_vegas_with_mods_60fps_gamenative/)).
- The reported workaround: temporarily switch to `proton_11.0arm64` to run NVSE once. [Community-report]
- There are no full TTW-on-GameHub walkthroughs.

**Winlator forks** have run TTW before (Winlator Frost 10.0 b2 on a Snapdragon 870 in 2024, [r/winlator](https://www.reddit.com/r/winlator/comments/1hgi5bq/anyone_has_run_tale_of_two_wastelands/)). In 2026 one tester found Winlator/star slower than GameNative, around 30 FPS. With its Performance preset it crashed every few minutes ([same r/AynThor thread](https://www.reddit.com/r/AynThor/comments/1r8t7z6/fallout_new_vegas_with_mods_60fps_gamenative/)). Use it only as a fallback. [Community-report]

---

## What you need

| Item | Notes | Confidence |
|---|---|---|
| **Fallout 3 GOTY** and **Fallout: New Vegas Ultimate Edition** | English versions with all DLC, from Steam, GOG, or Epic. You can mix stores. Microsoft Store/Game Pass copies are **not** supported. The German "No Gore" New Vegas is incompatible with xNVSE. The Games for Windows Live version of Fallout 3 works if it is the English GOTY. | [Verified-by-source: [TBoT Introduction](https://thebestoftimes.moddinglinked.com/intro.html)] |
| **A Windows 10/11 64-bit PC** | Needed to run the TTW installer, with at least 40 GB free. TTW itself is about 17 GB. Running it on Linux is unofficial and only supported in the TTW Discord's Linux channel (tools: Fluorine, Amethyst). | [Verified-by-source: [TBoT Intro](https://thebestoftimes.moddinglinked.com/intro.html), [TBoT FAQ](https://thebestoftimes.moddinglinked.com/faq.html)] |
| **A MOD:PUB account** (to download TTW) and **a Nexus Mods account** | The TTW download needs a login. | [Verified-by-source: [mod.pub TTW files](https://mod.pub/ttw/133/files)] |
| **AYN Thor Base, Pro, or Max** | All three use the Snapdragon 8 Gen 2, with 8 / 12 / 16 GB RAM respectively. The Thor Lite uses a Snapdragon 865, and **we found no TTW reports for the Lite.** | [Community-report: [Retro Handhelds Thor guide](https://retrohandhelds.gg/ayn-thor-setup-guide/)] |
| **Free space on the Thor's internal storage** | TTW is about 17 GB. New Vegas is about 9 GB. If you transfer a zip, you temporarily need room for both the zip and the unpacked folder. Internal storage is preferred, because PC games on microSD can stutter or crash. | Sizes: [TBoT](https://thebestoftimes.moddinglinked.com/intro.html), [EmuReady](https://www.emuready.com/listings/9923f28c-6770-487c-9bd3-a52cc041f8d3). Total is our estimate. Storage advice: [Community-report: [Retro Handhelds](https://retrohandhelds.gg/ayn-thor-setup-guide/), [Pocket Wastelands](https://cybershonk.github.io/PocketWastelands/setup.html)] |
| **A fast USB-C data cable** | Used to copy the folder from the PC to the Thor. | [Community-report: [r/AynThor](https://www.reddit.com/r/AynThor/comments/1v6sao6/fallout_tale_of_two_wastelands_on_ayn_thor/)] |

### Which store version should I use?

| | **GOG** (recommended) | **Steam** | **Epic** |
|---|---|---|---|
| Why | DRM-free, so the simplest to copy to Android. | Works, but needs extra steps. | Should work, but no TTW-on-Android report yet. |
| Patcher (PC, Step 8) | [4GB Patcher](https://www.nexusmods.com/newvegas/mods/62552) | [4GB Patcher](https://www.nexusmods.com/newvegas/mods/62552) | [Epic Games Patcher](https://www.nexusmods.com/newvegas/mods/81281), **not** the 4GB Patcher |
| On the Thor | Needs the registry fix (Step 22). | The video copies `Steam.exe` into the game folder `[14:33]`. Another route installs New Vegas through GameNative's Steam login instead (see [Alternative routes](#alternative-routes)). | Launch `FalloutNV.exe`, **not** `nvse_loader.exe` (the patcher author says so). |
| Sources | [Pocket Wastelands](https://cybershonk.github.io/PocketWastelands/setup.html), [EmuReady (GOG, Thor Pro)](https://www.emuready.com/listings/9f418d9c-16ef-4c3e-955a-515e92b16c13) | Video, [r/AynThor](https://www.reddit.com/r/AynThor/comments/1ry3t7g/fallout_4new_vegas_pro_vs_max/) ("Steam versions work but require registry keys for NV") | [Epic Games Patcher page](https://www.nexusmods.com/newvegas/mods/81281), [Pocket Wastelands](https://cybershonk.github.io/PocketWastelands/setup.html) |

Only New Vegas gets copied to the Thor. Fallout 3 is needed on the PC only while the TTW installer runs. You can uninstall it afterwards. [Verified-by-source: [TBoT Setup](https://thebestoftimes.moddinglinked.com/setup.html)]

---

## Part A: Build TTW on your PC

### Step 1: Install both games outside the Windows system folders
1. Install Fallout 3 and New Vegas somewhere like `C:\Games`. Do **not** use `Program Files`, `Program Files (x86)`, your Desktop, or Documents.
2. Steam users: make a new Steam library folder first, outside `Program Files`. TBoT uses *Steam Library Setup Tool* for this.
3. Steam/GOG/Epic all work. Use clean installs. If you've modded these copies before, uninstall them and delete the INI files in `Documents\My Games\FalloutNV` (or `FalloutNV_Epic` for Epic).

[Verified-by-source: [TBoT Setup → Choosing a Location / Installing the Games](https://thebestoftimes.moddinglinked.com/setup.html)] · Video `[03:42]`

### Step 2: Launch each game once
1. Run each game's launcher once and click OK on the "Detecting Video Hardware" pop-ups. This creates the INI files and registry entries.
2. If no pop-ups appear, delete the INIs in `Documents\My Games\FalloutNV` (or `Fallout3`) and try again.

[Verified-by-source: [TBoT Setup → Generating Fresh INI Files](https://thebestoftimes.moddinglinked.com/setup.html)] · Video `[04:18]`

### Step 3: Install the Visual C++ runtimes on the PC
1. Follow TBoT's `winget` one-liner to install every Visual C++ redistributable from 2005 to 2015+, both x86 and x64. Then restart.
2. Turn on file-name extensions in File Explorer.
3. Make sure Windows' *Mandatory ASLR* (Exploit protection → "Force randomization for images") is **Off**.

[Verified-by-source: [TBoT Setup](https://thebestoftimes.moddinglinked.com/setup.html)]

### Step 4: Set up Vortex and a TTW staging folder
1. Create a fresh Vortex profile for Fallout: New Vegas `[05:06]`.
2. In Vortex, choose **Open → Open Mod Staging Folder**.
3. Create a folder named `Tale of Two Wastelands` and copy its path `[05:33][05:56]`.

[Community-report: video]

> **Why Vortex and not MO2?** The TTW team's official guide ([The Best of Times](https://thebestoftimes.moddinglinked.com/ttw.html), or TBoT) uses **Mod Organizer 2**. MO2 keeps mods in a *virtual* folder, though, so the real `Data` folder you would copy to the Thor doesn't contain them. Vortex deploys mods into the real `Data` folder, so one ordinary folder holds everything `[00:57]`.
>
> MO2 users have made it work by exporting through MO2's virtual file view ([r/AynThor](https://www.reddit.com/r/AynThor/comments/1r8t7z6/fallout_new_vegas_with_mods_60fps_gamenative/)), or by running MO2 inside the container ([2024 Winlator report](https://www.reddit.com/r/winlator/comments/1hgi5bq/anyone_has_run_tale_of_two_wastelands/), which needs Wine Mono and is slow).
>
> Expect ModdingLinked support to assume MO2. [Community-report]

### Step 5: Run the TTW installer
1. Download **Tale of Two Wastelands 3.4** (v3.4.0, 1.2 GiB) from [mod.pub](https://mod.pub/ttw/133/files).
2. **Extract** the archive. Running it from inside the zip causes the "xdelta3.dll was not found" error. Extract it outside the Windows system folders.
3. Right-click `TTW Install.exe` → **Run as Administrator** `[02:50]`.
4. Check that the Fallout 3 and New Vegas paths it found are correct.
5. Paste your staging-folder path from Step 4 as the destination (no quotes) `[06:16]`, then click **Install**.
6. Wait. Audio encoding is very CPU-heavy. TBoT says about 40 minutes on a Ryzen 5 5600X, and possibly hours on slow CPUs. The video took about 15–20 minutes.
7. **Check that the output folder is about 17 GB.** If it isn't, the install failed: empty the folder and reinstall.

**If the installer fails:**

| Error | Fix |
|---|---|
| "BASS Init" or "XD3 Input" | Both games must be English, include all DLC, and have been launched once. Then verify game files. |
| "Folder … is not absolute" | Remove the quotes from the path. |
| Installer closes itself, or an NTDLL error | Your CPU is probably overheating. Add `-threads:1` to the installer's launch command. |
| "This app can't run on your PC" | Right-click the downloaded archive → Properties → **Unblock**, then extract again. |

[Verified-by-source: [TBoT TTW page](https://thebestoftimes.moddinglinked.com/ttw.html), [TBoT FAQ → installer issues](https://thebestoftimes.moddinglinked.com/faq.html), [mod.pub files](https://mod.pub/ttw/133/files)] · Video `[06:16]`

### Step 6: Deploy TTW in Vortex
1. Restart Vortex and accept the "external changes" prompt.
2. Deploy, then enable all TTW plugins `[06:43][06:56]`.
   - Vortex does **not** turn the Fallout 3 plugins on by itself. On the **Plugins** tab, check that each of these says **Enabled**: `Fallout3.esm`, `Anchorage.esm`, `ThePitt.esm`, `BrokenSteel.esm`, `PointLookout.esm`, `Zeta.esm`, `TaleOfTwoWastelands.esm` and `YUPTTW.esm`.
   - If `TaleOfTwoWastelands.esm` is disabled, the game boots as plain New Vegas.
   - Leave `FalloutNV_lang.esp` disabled.
3. Install **YUPTTW 13.9.1** from the same [mod.pub files page](https://mod.pub/ttw/133/files) as a separate mod, loading **after** TTW. YUPTTW is TTW's own version of the Yukichigai Unofficial Patch.
4. Optional: add the "Updated OGG Vorbis Files" from mod.pub to the New Vegas root folder.

[Verified-by-source: mod.pub file descriptions] · Vortex steps: [Community-report: video]

> **Do not install the regular Yukichigai Unofficial Patch (YUP).** The TTW FAQ lists it as incompatible, because TTW ships YUPTTW instead. Unofficial Patch NVSE Plus is also on the incompatible list. [Verified-by-source: [TBoT FAQ → What mods work with TTW?](https://thebestoftimes.moddinglinked.com/faq.html)]

Expected plugin order for TTW 3.4:

`FalloutNV.esm, DeadMoney.esm, HonestHearts.esm, OldWorldBlues.esm, LonesomeRoad.esm, GunRunnersArsenal.esm, Fallout3.esm, Anchorage.esm, ThePitt.esm, BrokenSteel.esm, PointLookout.esm, Zeta.esm, CaravanPack.esm, ClassicPack.esm, MercenaryPack.esm, TribalPack.esm, TaleOfTwoWastelands.esm, YUPTTW.esm`

[Community-report: [Pocket Wastelands → Load Order](https://cybershonk.github.io/PocketWastelands/load-order.html)]

### Step 7: Install xNVSE into the game's root folder
1. Download **xNVSE 6.4.9** (2026-09-16) as `nvse_6_4_9.7z` from [GitHub](https://github.com/xNVSE/NVSE/releases/tag/6.4.9).
2. Drop everything in it into the New Vegas **root** folder, the one that contains `FalloutNV.exe`. Not `Data`. `[08:48]`
3. `nvse_steam_loader.dll` is needed even on GOG and Epic copies.

[Verified-by-source: [xNVSE release notes](https://github.com/xNVSE/NVSE/releases/tag/6.4.9), [TBoT Essentials](https://thebestoftimes.moddinglinked.com/essentials.html)]

### Step 8: Patch the game executable
- **Steam or GOG:** put [4GB Patcher](https://www.nexusmods.com/newvegas/mods/62552)'s `FNVpatch.exe` in the root folder and run it. It should print `FalloutNV.exe patched!` and leave a `FalloutNV_backup.exe`. `[09:23]`
- **Epic:** use the [Epic Games Patcher](https://www.nexusmods.com/newvegas/mods/81281) instead. Put its files in the root folder and run `Patcher.exe`. It downgrades the game to the Steam executable, makes it 4 GB-aware, and auto-loads xNVSE. Its author says: *"Do not use nvse_loader.exe … after running the patcher."*

Both patchers make the game load xNVSE by itself. The 4GB Patcher page says you can then launch the game "directly, the Default Launcher, or Steam".

[Verified-by-source: [TBoT Essentials → Game Patchers](https://thebestoftimes.moddinglinked.com/essentials.html), the two Nexus pages]

### Step 9: Install the essential mods with Vortex
Install these in order. Pick the exact files named below.

| # | Mod | File(s) to pick | Where it goes |
|---|---|---|---|
| 1 | [TTW NVSE Plugin](https://www.nexusmods.com/newvegas/mods/77415) | TTWNVSE. **Required by TTW.** | Vortex |
| 2 | [JIP LN NVSE](https://www.nexusmods.com/newvegas/mods/58277) | JIP LN NVSE Plugin + JIP LN Settings INI | Vortex |
| 3 | [JohnnyGuitar NVSE](https://www.nexusmods.com/newvegas/mods/66927) | Main file + "All Tweaks Preset" from [JG INI Presets](https://www.nexusmods.com/newvegas/mods/86200) | Vortex |
| 4 | [ShowOff xNVSE](https://www.nexusmods.com/newvegas/mods/72541) | Main file + ShowOff INI | Vortex |
| 5 | [NVTF – New Vegas Tick Fix](https://www.nexusmods.com/newvegas/mods/66537) | Main file + NVTF INI | Vortex |
| 6 | [lStewieAl's Tweaks](https://www.nexusmods.com/newvegas/mods/66347) | Main file + [Stewie Tweaks VNV INI](https://github.com/ModdingLinked/Stewie-Tweaks-INIs/releases/latest). One Thor tester also credits it with better controller aiming. | Vortex |
| 7 | [lStewieAl's Engine Optimizations](https://www.nexusmods.com/newvegas/mods/80993) | Main file | Vortex |
| 8 | [Combat Lag Fix](https://www.nexusmods.com/newvegas/mods/71973) | Fast Weapon Lag Fix | Vortex |
| 9 | [VATS Lag Fix](https://www.nexusmods.com/newvegas/mods/84823) | Main file | Vortex |
| 10 | [ActorCause Save Bloat Fix](https://www.nexusmods.com/newvegas/mods/80666) | Main file. Shrinks saves by 15–30%. | Vortex |
| 11 | [kNVSE](https://www.nexusmods.com/newvegas/mods/71336) | Main file | Vortex |
| 12 | [UIO](https://www.nexusmods.com/newvegas/mods/57174) | Main file | Vortex |
| 13 | [Mod Limit Fix](https://www.nexusmods.com/newvegas/mods/68714) | MLF | Vortex |
| 14 | [Yvile's Crash Logger](https://www.nexusmods.com/newvegas/mods/82540) | Main file. You'll want crash logs on Android. | Vortex |
| 15 | [Improved Console](https://www.nexusmods.com/newvegas/mods/70801), [Console Paste](https://www.nexusmods.com/newvegas/mods/65906) | Optional debugging aids | Vortex |

[Verified-by-source: [TBoT Essentials](https://thebestoftimes.moddinglinked.com/essentials.html). The order matches [Pocket Wastelands → Mod List](https://cybershonk.github.io/PocketWastelands/mods.html). Controller note: [r/AynThor](https://www.reddit.com/r/AynThor/comments/1r8t7z6/fallout_new_vegas_with_mods_60fps_gamenative/)]

**Shortcut:** the video author's [TTW Essentials Collection](https://www.nexusmods.com/games/newvegas/collections/c1infa/revisions/2) bundles these for Vortex `[09:49]`. We haven't compared it with TBoT's list. [Community-report]

**Avoid on Android for now:**
- **Sound Extender NVSE.** Pocket Wastelands says it has been linked to crashes under GameNative, with no known fix.
- **ENB, Wabbajack lists, and generated LOD.** Not a fit for this setup.
- **Huge texture packs and heavy grass/particle mods.** Test them separately, and only after the base game works.

[Community-report: [Pocket Wastelands → Avoid](https://cybershonk.github.io/PocketWastelands/avoid.html)]

### Step 10: Add the custom INI
1. Paste TBoT's custom INI block into `Documents\My Games\FalloutNV\FalloutCustom.ini` `[11:04][11:26]`. JIP LN reads this file and its values override the game's other INIs.
2. The block includes `SCharGenQuest=001FFFF8`. Without it you won't get TTW's choice of which wasteland to start in.
3. It also sets `bMultiThreadAudio=1`, `iNumHWThreads=3`, `bDrawShadows=0`, `bFull Screen=1`, and `iPresentInterval=1`.

[Verified-by-source: [TBoT Essentials → Custom INI](https://thebestoftimes.moddinglinked.com/essentials.html)]

> **Conflict:** TBoT says don't use BethINI. Pocket Wastelands recommends BethINI Pie with conservative settings. Pick one; we suggest TBoT's INI because it is the official one. [See notes file]

### Step 11: Test on the PC before going near the Thor
1. Launch through `nvse_loader.exe` (as the video does `[12:02]`) or `FalloutNV.exe`.
2. Reach the TTW title screen.
3. Optional: temporarily enable the [ML Utilities Checker](https://www.nexusmods.com/newvegas/mods/79358). It reports at the main menu whether your utilities loaded. Disable it afterwards.

[Verified-by-source: TBoT Essentials → Testing Utilities] · Video `[12:02]`

---

## Part B: Package the game folder for the Thor

Do this inside the New Vegas **root** folder on the PC `[13:13]`. [Community-report: video, unless noted]

### Step 12: Add a `Redistributables` folder
Put two installers in it, to run later inside the container:
- Visual C++ Redistributable All-in-One `[01:38]`
- DirectX End-User Runtimes (June 2010) offline installer, from [TechPowerUp](https://www.techpowerup.com/download/directx-redistributable-runtime/) `[13:22][14:49]`

### Step 13: Add the files for your store's DRM and registry
- **GOG:**
  - GOG's `beth_registry.cmd`, which comes in `beth_registry.zip` from [GOG's support article](https://support.gog.com/hc/en-us/articles/115003633889-Fallout-New-Vegas-Greyed-out-options-or-missing-Play-button-in-the-launcher). The video calls it "beth_registry_command".
  - The [Fallout New Vegas Registry Fix 32_64 Bit](https://www.nexusmods.com/newvegas/mods/57486) `.bat`.
  - Both go in the root `[13:47][18:02][18:10]`. [Verified-by-source for what each file does: GOG article, Nexus page]
- **Steam:** copy `Steam.exe` into the root `[14:33]`. [Community-report. We haven't confirmed why this works, so watch `[14:33]` before publishing.]
- **Epic:** the video doesn't cover Epic. [Unverified-guess: the same registry fix should apply, since GameNative's own Epic fix writes the same registry key.]

### Step 14: Bring your settings and load order along
1. Create a `config` folder and copy into it everything in `C:\Users\<you>\Documents\My Games\FalloutNV`, including your `.ini` files and `FalloutCustom.ini` `[15:01][15:13]`.
2. Create an `appdata` folder and copy into it everything in `C:\Users\<you>\AppData\Local\FalloutNV`. That's `plugins.txt`, the list of active plugins `[15:27][15:33]`.

### Step 15: Copy the whole root folder to the Thor
1. Copy the packaged New Vegas folder to the Thor's **internal** storage `[16:20]`.
2. Pocket Wastelands uses `/storage/emulated/0/PcGames/Fallout New Vegas/`.
3. The video's author copies over a high-speed USB-C cable. He says ADB transfers "can sometimes reorganize files". [Community-report: [r/AynThor thread](https://www.reddit.com/r/AynThor/comments/1v6sao6/fallout_tale_of_two_wastelands_on_ayn_thor/)]
4. Zipping the folder first and unzipping on the Thor (e.g. with ZArchiver) makes the transfer faster and more reliable, and several testers do it. [Community-report: [r/AynThor](https://www.reddit.com/r/AynThor/comments/1r8t7z6/fallout_new_vegas_with_mods_60fps_gamenative/), [Pocket Wastelands](https://cybershonk.github.io/PocketWastelands/setup.html)]

> **⚠ Load-order risk.** New Vegas and TTW decide plugin order by each plugin file's **modified date**, not by the order inside `plugins.txt`. [Community-report: [Pocket Wastelands → Load Order](https://cybershonk.github.io/PocketWastelands/load-order.html)]
>
> If your copy method resets file dates, the in-game order can change. [Unverified-guess: Android's USB file transfer (MTP) may not keep dates; a zip extracted with ZArchiver may. Not yet tested.]
>
> If TTW boots as plain New Vegas, or crashes at the menu after a copy that worked on the PC, check this first.

---

## Part C: Set up GameNative on the Thor

### Step 16: Install GameNative
1. Install **GameNative v1.3.0** from [GitHub Releases](https://github.com/utkarshdalal/GameNative/releases/tag/v1.3.0). Use the standard APK, not the XR (Quest headset) build.
2. If custom games or the D: drive don't show up, check that you installed the standard APK. An older v1.0.0 "modern" prerelease lacked custom-game support. [Community-report: [Pocket Wastelands](https://cybershonk.github.io/PocketWastelands/)]
3. The video was made on GameNative **1.1.1**, and Pocket Wastelands' profile on **1.0.0**. Setting names may have moved since. [Unverified-guess]

### Step 17: Add New Vegas as a custom game
In GameNative, tap **`+`** and pick the folder you copied over `[18:46]`. [Community-report: video]

### Step 18: Edit the container settings
Open the game's settings (gear icon) → **Edit container**. Start with the video's values. If they don't work, try the Pocket Wastelands "Verified working" Snapdragon 8 Gen 2 profile, which was tested on TTW on 2026-06-11. A dash means the source doesn't list that setting. **Do not mix columns randomly. Change one thing at a time.**

| Setting | Video (TTW, Thor, GN 1.1.1) `[19:55]–[21:08]` | Pocket Wastelands 8 Gen 2 TTW profile ([JSON](https://cybershonk.github.io/PocketWastelands/assets/profiles/snapdragon-8-gen-2-pocket-wastelands.json)) | Other Thor New Vegas reports |
|---|---|---|---|
| Executable | `nvse_loader.exe` | `FalloutNV.exe` (the profile JSON shows `FalloutNVLauncher.exe`) | `FalloutNV.exe` ([_joshd, Max](https://www.reddit.com/r/AynThor/comments/1vb2q28/fallout_new_vegas_on_thor/)) |
| Container variant | — | bionic | bionic |
| Wine/Proton | — | `proton-10.0-arm64ec-2` (avoid Proton 11; GameNative marks it testing-only) | `proton-10.0-arm64ec-2` |
| Graphics wrapper | `Wrapper-leegao`, or the legacy wrapper | `wrapper` | `wrapper-v2` |
| Turnip driver | `Turnip 26.2 R4` | `turnip26.0.0_R8`, Vulkan 1.3 | "t28-toaster" (Mr Purple); KIMCHI Turnip v26.0.0 R8 |
| DX wrapper / version | DXVK, version heard as "1.11 CAK" (**probably DXVK-Sarek 1.11**, see note) | DXVK `2.6.1-gplasync` | DXVK `2.6.1-gplasync` |
| Device memory / video memory | 4096 MB / 4096 MB | maxDeviceMemory 4096; videoMemorySize 2048 | 0 MB (EmuReady, enasty) |
| Present mode | Mailbox (everywhere) | Mailbox; renderer present mode fifo | Mailbox; renderer fifo |
| 32-bit emulator | FEXCore `2512` | FEXCore `2604`, preset **Intermediate** (Box64 0.3.6, Compatibility) | FEXCore 2512 Intermediate; Box64 0.3.6 Compatibility |
| Screen size | — | 1280×720 | 1280×720 |
| Audio | — | PulseAudio (`PULSE_LATENCY_MSEC=144`) | pulse or ALSA |
| FPS cap | — | 60 (FPS limiter + `DXVK_FRAME_RATE=60`) | — |
| Extra environment variables | `FD_DEV_FEATURES` = `enable_tp_ubwc` (as heard; see note) | `WINEESYNC=1 MESA_VK_WSI_PRESENT_MODE=mailbox TU_DEBUG=noconform DXVK_FRAME_RATE=60 PULSE_LATENCY_MSEC=144 …` (full list in the JSON) | — |

Sources:
- [Community-report: [video](https://www.youtube.com/watch?v=JiqVAiGlCtU&t=1195s)]
- [Pocket Wastelands compatibility page](https://cybershonk.github.io/PocketWastelands/compatibility.html)
- [r/AynThor _joshd](https://www.reddit.com/r/AynThor/comments/1vb2q28/fallout_new_vegas_on_thor/)
- [EmuReady enasty (Thor Max)](https://www.emuready.com/listings/739d40eb-cdd2-46df-b2b1-00b6f18e885b)
- [EmuReady truskawka (Thor Pro, GOG)](https://www.emuready.com/listings/9f418d9c-16ef-4c3e-955a-515e92b16c13)

> **Notes on the table**
> - **"1.11 CAK".** In the Reddit thread, the video's author told a commenter with a black screen to "try switching to 1.11 sarek" and to use the legacy wrapper. [DXVK-Sarek](https://github.com/pythonlover02/DXVK-Sarek) is a DXVK fork whose release 1.11.0 exists, so "CAK" is very likely a mishearing of "Sarek". Confirm in the video at `[20:05]`.
> - **`FD_DEV_FEATURES`.** The documented value for Snapdragon 8 Gen 2 rendering glitches is **`enable_tp_ubwc_flag_hint=1`** ([Azahar #2559](https://github.com/azahar-emu/azahar/issues/2559), [Dolphin #14171](https://bugs.dolphin-emu.org/issues/14171)). "enable_tp_ubwc" is probably truncated. Confirm at `[21:08]`.
> - **Memory.** Pocket Wastelands warns: "Use 2 GB memory and 2 GB video memory unless your Android device has 16 GB of RAM." That means 4096 MB is only safe on the **Max**. On a Base or Pro, start at 2048. [Community-report]
> - **Steam copies installed through GameNative's Steam login:** turn **off** "Use Legacy DRM" and turn **on** "Unpack Files". [Community-report: [r/AynThor](https://www.reddit.com/r/AynThor/comments/1vb2q28/fallout_new_vegas_on_thor/)]

### Step 19: Press Play once to build the container
1. Save, then press **Play**.
2. **The game will not start yet.** This first run sets up the Wine container, installs Mono, and builds the virtual `C:\` drive `[21:36][21:40]`. [Community-report: video]

### Step 20: Run the installers inside the container
1. Open the container's desktop or file explorer (game menu → **Open Container**). Your game folder appears as drive **`A:\`** `[22:06][22:10]`.
2. Run the VC++ All-in-One `.bat` `[22:21]`.
3. Run the DirectX June 2010 installer `[23:31]`.

[Community-report: video]

The DirectX June 2010 install is also the community fix for **missing voices or no sound** in Bethesda games on the Thor. An EmuReady report for Skyrim SE on GameHub fixed missing dialogue with `directx_Jun2010_redist.exe`, after adding Mono, Gecko, and .NET 4.0 as components. Another Thor user says Bethesda games need DirectX installed by hand. [Community-report: [EmuReady 80f24173](https://www.emuready.com/listings/80f24173-dec8-4e44-acff-dfabc2b5d085) (Skyrim, *not* New Vegas), [r/AynThor](https://www.reddit.com/r/AynThor/comments/1um5p4b/fallout_3_new_vegas_optimal_settings/)]

### Step 21: Copy your settings and load order into the container's user folders
- Copy `A:\appdata\*` to `C:\users\xuser\AppData\Local\FalloutNV` `[24:44]`.
- Copy `A:\config\*` to `C:\users\xuser\Documents\My Games\FalloutNV` `[25:02]`.

[Community-report: video. The `xuser` path is confirmed by [Pocket Wastelands](https://cybershonk.github.io/PocketWastelands/load-order.html).]

### Step 22: Fix the registry
1. Run `registry.bat` from `A:\` `[25:59]`.
2. Then run `beth_registry.cmd` and type `yes` when asked `[26:06]`.

[Community-report: video]

Why this is needed: the New Vegas launcher looks up its install folder in the registry under `HKLM\SOFTWARE\WOW6432Node\Bethesda Softworks\FalloutNV`, in a value called `Installed Path`. Without it, the launcher greys out its options or says "Install" instead of "Play".
- GameNative writes this key itself, but only for **Steam, GOG, or Epic copies installed through GameNative**. Its fixes are tied to those stores' game IDs, so a custom game copied from your PC won't get it. [Verified-by-source for the key: [GameNative gamefix code](https://github.com/utkarshdalal/GameNative/blob/master/app/src/main/java/app/gamenative/gamefixes/GOG_1454587428.kt). Unverified-guess that custom games miss out]
- **Manual alternative:** in the container's Registry Editor, create the string value `Installed Path` = `A:\` at that key. [Community-report: [EmuReady truskawka](https://www.emuready.com/listings/9f418d9c-16ef-4c3e-955a-515e92b16c13)]

### Step 23: Check the launcher, then set resolution and aspect ratio
1. Temporarily set the executable to `FalloutNVLauncher.exe` and press Play.
2. The launcher should show **Play**, not **Install** `[27:04][27:12]`.
3. While you're in the launcher, set **16:9** and **1280×720** under Options. This fixes the picture filling only the top-left corner of the screen.
4. Close the launcher without playing.

[Community-report: video, [r/AynThor (aspect-ratio fix)](https://www.reddit.com/r/AynThor/comments/1um5p4b/fallout_3_new_vegas_optimal_settings/)]

The first launcher start can take about a minute. Tapping and mouse input in the launcher sometimes don't respond; one user reports it, and it's unresolved. [Community-report: [r/AynThor](https://www.reddit.com/r/AynThor/comments/1vb2q28/fallout_new_vegas_on_thor/), [r/AynThor](https://www.reddit.com/r/AynThor/comments/1u3sr3b/fallout_nv_help/)]

### Step 24: Point GameNative at xNVSE and launch TTW
1. Set the executable back to **`nvse_loader.exe`**, save, and press Play. TTW should boot `[27:33]`. [Community-report: video]
2. **Epic copies:** use **`FalloutNV.exe`** here instead. The Epic patcher's author says not to use `nvse_loader.exe`. [Verified-by-source: [Epic Games Patcher](https://www.nexusmods.com/newvegas/mods/81281)]
3. **Steam/GOG with the 4GB Patcher:** `FalloutNV.exe` should also work, because the patched executable loads xNVSE itself. Pocket Wastelands and the GameNative 4GB patcher both use it. Try it if `nvse_loader.exe` gives a black screen. [Verified-by-source that the patched exe auto-loads xNVSE: [4GB Patcher](https://www.nexusmods.com/newvegas/mods/62552). Unverified-guess that it fixes black screens]
4. **Controls dead at the title screen?** Quit and launch again. Several users needed this once, on GameNative, GameHub, and Winlator alike. [Community-report: [r/AynThor](https://www.reddit.com/r/AynThor/comments/1ry3t7g/fallout_4new_vegas_pro_vs_max/)]

### Step 25: Confirm it really works
1. The main menu loads, and a **new game** offers the TTW start (Vault 101 or the Mojave).
2. You can play for at least 15 minutes.
3. Make a manual save, quit completely, relaunch, and load that save.
4. **Old saves won't work.** Vanilla New Vegas saves and pre-3.x TTW saves are incompatible, so start a new character.

[Verified-by-source for saves: [TBoT FAQ](https://thebestoftimes.moddinglinked.com/faq.html). Test routine: [Community-report: [Pocket Wastelands](https://cybershonk.github.io/PocketWastelands/compatibility.html)]]

---

## Part D: Tune for the Thor

### Performance and fan modes
The Thor's quick menu has:
- **Performance Mode:** Standard / Medium / High
- **Fan Control:** Quiet / Smart / Sports. You can edit a custom curve via Smart → Edit.

[Community-report: [Retro Handhelds Thor guide](https://retrohandhelds.gg/ayn-thor-setup-guide/)]

Players report that High mode mostly forces maximum clocks, and that Standard is enough for most games. They keep High for "Switch and heavier PC games". **No New Vegas/TTW-specific comparison exists.** We have no tested value: start on Medium or High with the Smart fan, and measure. [Community-report: [r/AynThor PSA](https://www.reddit.com/r/AynThor/comments/1pzal05/quick_psa_about_performance_mode/)]

### ClusterTune (heat and battery)
1. Install [ClusterTune v1.3.0](https://github.com/AurelioB/ClusterTune/releases/tag/v1.3.0). It caps maximum CPU/GPU clock speeds per cluster and works on AYN devices through AYN's built-in system service (PServer), so no root is needed. It has per-app profiles and an experimental Auto Tune with an FPS target.
2. Known display bug: the Thor's performance overlay can show "3.19 GHz" when the CPU actually drops below 1 GHz.

[Verified-by-source: [ClusterTune README](https://github.com/AurelioB/ClusterTune)]

The TTW video's author runs ClusterTune "on Medium" and sees about **70 °C** during TTW. [Community-report: [r/AynThor](https://www.reddit.com/r/AynThor/comments/1v6sao6/fallout_tale_of_two_wastelands_on_ayn_thor/)]

GameNative also controls clocks and fan through the same service; v1.3.0 "resets clocks and fan after the app is force killed". Whether GameNative and ClusterTune overwrite each other's settings is **untested**. [Verified-by-source: [v1.3.0 notes](https://github.com/utkarshdalal/GameNative/releases/tag/v1.3.0). Interaction is an Unverified-guess]

### Frame rate
- **Cap at 60 FPS.** Pocket Wastelands does this with GameNative's FPS limiter plus `DXVK_FRAME_RATE=60`. [Community-report]
- **Never go above about 120 FPS.** NVTF's safe maximum is around 120, and higher frame rates cause bouncing NPCs and a sped-up game. [Verified-by-source: [TBoT](https://thebestoftimes.moddinglinked.com/essentials.html), [TBoT FAQ](https://thebestoftimes.moddinglinked.com/faq.html)]
- **Reported performance:**
  - TTW: about 46 FPS average in the Pocket Wastelands profile's logged session, and 45–60 FPS for a TTW tester with the Box64/FEXCore presets on Intermediate.
  - Modded plain New Vegas: 55–60 FPS. [Community-report]

### In-game graphics (launcher → Options)
**No TTW-on-Thor graphics preset has been tested.** Reports from plain New Vegas on Snapdragon 8 Gen 2/Elite:

| Report | Settings |
|---|---|
| truskawka ([EmuReady](https://www.emuready.com/listings/9f418d9c-16ef-4c3e-955a-515e92b16c13)) | **Medium** preset, 720p |
| _joshd ([r/AynThor](https://www.reddit.com/r/AynThor/comments/1vb2q28/fallout_new_vegas_on_thor/)) | 720p, AA 4 samples, AF 8 samples, V-sync on |
| One r/GameNative user, posting on an Xperia thread ([r/GameNative](https://www.reddit.com/r/GameNative/comments/1uxy6en/fallout_new_vegas_has_one_little_issue/)) | Medium, AA off, AF off, shadows off, water reflections off, texture quality High, object/actor/item fade 14, grass 3, distant land quality 85 |

TBoT's custom INI already turns off actor shadows and full-scene water reflections. [Community-report / Verified-by-source for the INI]

### Audio
- TBoT's custom INI sets `bMultiThreadAudio=1`.
- One Thor tester found it raises FPS during gunfights but **may cause crackling**. If audio crackles, try `bMultiThreadAudio=0`.

[Community-report: [EmuReady](https://www.emuready.com/listings/9f418d9c-16ef-4c3e-955a-515e92b16c13)]

### Controller
- New Vegas supports an Xbox-style controller natively. Use the Thor in **Xbox layout** (quick menu → Handle Style).
- **Thor quirk:** the controls go to whichever screen you touched last. If buttons stop working in-game after you touch the bottom screen, **tap the top screen**. [Community-report: [Retro Handhelds](https://retrohandhelds.gg/ayn-thor-setup-guide/)] *(This matters for the companion. See Part F.)*
- GameNative v1.4.0-prerelease fixes some games seeing two controllers on the Thor. Until that reaches a stable release, check GameNative's controller settings if input is doubled. [Verified-by-source: [v1.4.0-pre notes](https://github.com/utkarshdalal/GameNative/releases/tag/v1.4.0-prerelease)]
- One Thor Max user's working controller settings: Use Steam Input on, XInput on, DirectInput on, DirectInput mapper Standard. [Community-report: [EmuReady enasty](https://www.emuready.com/listings/739d40eb-cdd2-46df-b2b1-00b6f18e885b)]

---

## Part E: Updating mods later
1. Install or update mods in Vortex on the PC.
2. Copy the changed game folder and `AppData\Local\FalloutNV\plugins.txt` back to the Thor.
3. Overwrite the files in the existing container `[30:30]`. [Community-report: video]

**Updating TTW itself:**
- A new **patch** version (third number, e.g. 3.4.0 → 3.4.1) is save-safe.
- A new major or minor version (first or second number) needs a **new game**.

[Verified-by-source: [TBoT FAQ → How do I update TTW?](https://thebestoftimes.moddinglinked.com/faq.html)]

**Phone-only option:** [Droid Mod Loader](https://github.com/CyberShonk/DroidModLoader) v0.8.0-beta is an Android mod manager with a TTW profile. It deploys mods into the game's `Data` folder and writes `plugins.txt`. Older versions did **not** set plugin file dates, and v0.6.0-beta needed you to copy `plugins.txt` into GameNative by hand. Read its user guide first and back up your game folder. [Verified-by-source: DML README. Older-version behavior: [Pocket Wastelands](https://cybershonk.github.io/PocketWastelands/load-order.html)]

**Don't use GameNative's built-in Nexus mod feature for TTW.**
- GameNative (v1.1.1+) can install Nexus mods itself.
- When it rewrites `plugins.txt`, it puts the *vanilla* New Vegas list first: base game, then DLC, then the preorder packs. TTW needs the preorder packs after the Fallout 3 files.
- It has no TTW knowledge and no TTW test reports.

[Verified-by-source: [BethesdaPluginManager.kt](https://github.com/utkarshdalal/GameNative/blob/master/app/src/main/java/app/gamenative/mods/BethesdaPluginManager.kt). Advice is an Unverified-guess]

---

## Part F: Install TTW DualScreen

Right now TTW DualScreen is a **test build** (v0.0.1-spike). Its job is to confirm the plugin and the bottom-screen app work together on your Thor before the full Pip-Boy screen gets built.

1. On the Thor, open the [test release](https://github.com/TristanAdams03/ttw-dualscreen/releases/tag/v0.0.1-spike) and download both files: the plugin zip and the APK.
2. Follow the [device checklist](https://github.com/TristanAdams03/ttw-dualscreen/blob/v0.0.1-spike/docs/spike/CHECKLIST.md) step by step. It covers:
   - copying the plugin into `Data\NVSE\Plugins` in the game folder from Step 15;
   - installing the app;
   - the four tap tests;
   - sending the report back.

TTW DualScreen needs xNVSE (Step 7). The plugin only loads when TTW is started through xNVSE (Step 24).

## Known issues and fixes

| Symptom | What to try | Confidence / source |
|---|---|---|
| Black screen. The `nvse_loader` console window appears but the game never does. | 1. Re-check the container settings against the video.<br>2. Try the **legacy wrapper** and **DXVK 1.11 Sarek**.<br>3. Re-copy the files without ADB.<br>4. The video's author fixed his own repeat by following the updated (2.0) video's GameNative steps. | Community-report: [r/AynThor TTW thread](https://www.reddit.com/r/AynThor/comments/1v6sao6/fallout_tale_of_two_wastelands_on_ayn_thor/) |
| Black screen with the launcher, or "can't find exe" | Set the executable to `FalloutNV.exe` directly. Switch back to the launcher only to change graphics options. | Community-report: [r/AynThor](https://www.reddit.com/r/AynThor/comments/1u3sr3b/fallout_nv_help/) |
| Launcher says **Install**, or its options are greyed out | Registry fix (Step 22) | Verified-by-source: [GOG support](https://support.gog.com/hc/en-us/articles/115003633889-Fallout-New-Vegas-Greyed-out-options-or-missing-Play-button-in-the-launcher) |
| Picture fills only the top-left corner | Launcher → Options → 16:9 | Community-report: [r/AynThor](https://www.reddit.com/r/AynThor/comments/1um5p4b/fallout_3_new_vegas_optimal_settings/) |
| Game starts as plain New Vegas, with no TTW start | 1. Check that `plugins.txt` in `C:\users\xuser\AppData\Local\FalloutNV` lists `TaleOfTwoWastelands.esm` and `YUPTTW.esm`.<br>2. Check plugin file dates (Step 15 warning).<br>3. Check that `SCharGenQuest=001FFFF8` is in FalloutCustom.ini. | Community-report: [Pocket Wastelands](https://cybershonk.github.io/PocketWastelands/load-order.html). INI: Verified-by-source (TBoT FAQ) |
| Crashes when loading a save | Change the Box64 and FEXCore presets from **Performance** to **Intermediate**. | Community-report: [r/AynThor](https://www.reddit.com/r/AynThor/comments/1r8t7z6/fallout_new_vegas_with_mods_60fps_gamenative/) |
| Random crash after 10–30 minutes (vanilla New Vegas) | 1. Install the Step 9 stability mods.<br>2. Use the FEXCore Intermediate preset.<br>3. Older Box64 history: New Vegas needed `STRONGMEM=1` to stay stable under Box64, at a cost in FPS; Box64's developer suggested `BOX64_X87DOUBLE=1` as an alternative. | Community-report: [r/AynThor](https://www.reddit.com/r/AynThor/comments/1t5cey9/fallout_new_vegas_wont_work_on_my_ayn_thor/). Box64: Verified-by-source ([box64 #1936](https://github.com/ptitSeb/box64/issues/1936), 2024) |
| Crash at Vault 101's "6 years later" cutscene | One tester (Winlator, 2024) skipped it by loading a post-vault starting save ([Nexus 70729](https://www.nexusmods.com/newvegas/mods/70729)). | Community-report: [r/winlator](https://www.reddit.com/r/winlator/comments/1hgi5bq/anyone_has_run_tale_of_two_wastelands/) |
| Crash during loading screens when you touch the device | That 2024 tester says: don't touch the device during loading screens. | Community-report: same thread |
| No voices or no sound | Install DirectX June 2010 inside the container (Step 20). GameHub Lite users also mention the `xact_64` component. | Community-report: [EmuReady (Skyrim)](https://www.emuready.com/listings/80f24173-dec8-4e44-acff-dfabc2b5d085), [r/AynThor](https://www.reddit.com/r/AynThor/comments/1r8t7z6/fallout_new_vegas_with_mods_60fps_gamenative/) |
| Audio crackling in gunfights | Try `bMultiThreadAudio=0`. | Community-report: [EmuReady](https://www.emuready.com/listings/9f418d9c-16ef-4c3e-955a-515e92b16c13) |
| NPC heads spinning | Reported under GameNative; no fix found. | Community-report: [r/AynThor](https://www.reddit.com/r/AynThor/comments/1t5cey9/fallout_new_vegas_wont_work_on_my_ayn_thor/) |
| Slowdown during explosions or lots of physics debris | Lower the fade settings or restore their defaults. A newer Turnip driver helped one user. | Community-report: [r/GameNative](https://www.reddit.com/r/GameNative/comments/1uxy6en/fallout_new_vegas_has_one_little_issue/) |
| FPS drops in dark underground areas | Reported on vanilla New Vegas (60 → 35–40). | Community-report: [r/AynThor](https://www.reddit.com/r/AynThor/comments/1r8t7z6/fallout_new_vegas_with_mods_60fps_gamenative/) |
| Stutter in the DC ruins / Capital Wasteland | **No Thor-specific report found.** Desktop players use TTW interior and occlusion optimisation mods (e.g. the [TTW Interior Optimization Project](https://www.nexusmods.com/newvegas/mods/85909), currently "temporarily removed" from the Wasteland Survival Guide) and Fog-based Object Culling. Untested on Android. Lowering object/actor fade distances is the cheapest thing to try. | Unverified-guess, based on the [Wasteland Survival Guide](https://wastelandsurvival.guide/docs/optimizations) |
| Save bloat | Keep ActorCause Save Bloat Fix (Step 9). Avoid *Increased Wasteland Spawns*, which the FAQ says causes "massive save bloat and crashes". | Verified-by-source: [TBoT FAQ](https://thebestoftimes.moddinglinked.com/faq.html) |
| Bouncing NPCs or a sped-up game | Cap FPS below 120; 60 is better on the Thor. | Verified-by-source: TBoT FAQ |

## Alternative routes

- **Steam copy installed through GameNative's Steam login.** [Unverified-guess: combination not reported end to end]
  1. Install New Vegas from GameNative's Steam library.
  2. Turn on **Unpack Files**, launch once, and close it.
  3. Install xNVSE.
  4. Run the [FNV GameNative 4GB Patcher](https://github.com/CyberShonk/fnv-gamenative-4gb-patcher) (v0.1.3-alpha, tested on a Thor with GameNative 1.1.1) by temporarily setting the container's executable to `FNVGameNativePatcher.exe`.
  5. Put the executable back to `FalloutNV.exe`.
  6. Copy your PC-built TTW `Data` folder over the top.

  Why use this patcher: the normal 4GB Patcher's change is undone, because GameNative restores its cached unpacked `FalloutNV.exe`. [Verified-by-source: patcher README]
- **[Shonk's Pocket Wastelands](https://cybershonk.github.io/PocketWastelands/) with [Droid Mod Loader](https://github.com/CyberShonk/DroidModLoader).** You still build TTW on the PC, but install mods on the phone. Its Snapdragon 8 Gen 2 profile is marked verified. [Community-report]
- **GameHub / GameHub Lite.** Good for vanilla New Vegas. See the error-998 workaround above. [Community-report]

## Where to get help
- **TTW Discord:** <https://discord.taleoftwowastelands.com>. The old forum is read-only. Linux and Wine questions go to its dedicated channel. *Piracy talk gets you banned.*
- **ModdingLinked Discord** for The Best of Times: <https://discord.gg/S99Ary5eba>
- **xNVSE Discord:** <https://discord.gg/EebN93s>
- **r/AynThor** (search "TTW", "New Vegas"), **r/GameNative**, the **GameNative Discord** (linked from its GitHub), and the **ARM64Nexus Discord** (Droid Mod Loader): <https://discord.gg/RvTWcEeJ7Z>
- **EmuReady** listings for New Vegas on the Thor: <https://www.emuready.com/listings?search=fallout%20new%20vegas>
- When asking for help, include: Thor model, GameNative version, Proton/FEXCore/DXVK/Turnip versions, game store, and whether plain New Vegas worked first. ([Pocket Wastelands report template](https://cybershonk.github.io/PocketWastelands/compatibility.html))
