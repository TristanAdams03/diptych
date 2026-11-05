# KH1FM late worlds: Neverland, Hollow Bastion, End of the World

Target: Steam KH 1.5+2.5 (app 2552430), KH1FM build 1.0.0.2, Global. Addresses are offsets from the
exe base, the same as LuaBackend (base = 0). Researched 2026-11-04. No live memory reads were made for
this note; "confirmed live" means the user had already confirmed the value.

Machine-readable drafts are in `kh1-data-draft/`: `worlds.json`, `rooms.json`, `chests.json`,
`party_stats.json` and `story_flags.json`. Every row in them carries its own source and confidence.

Confidence labels:
- **source**: read directly from the cited file.
- **derived**: computed from sourced facts. The rule used is stated.
- **guess**: game knowledge or extrapolation. Verify live before relying on it.

## Sources and licenses

| Key | Repo @ commit | Files | License |
|---|---|---|---|
| DEN | Denhonator/KHPCSpeedrunTools @ aeb4972e | `1FMMods/scripts/io_packages/SteamGlobal_1_0_0_2.lua`, `SteamJP_1_0_0_2.lua`, `EGSGlobal_1_0_0_10.lua`, `Rando/chests.lua`, `Rando/rewards.lua`, `1fmRandoSomeLogic.lua`, `1fmEnemyRando.lua` | Unlicense |
| AP | gaithern/KH-1FM-AP-LUA @ 4fe14a54 | `1fmAPConnector.lua`, `1fmAPFixWarps.lua`, `1fmAPHandleDeathlink.lua`, `1fmAPEotWDoor.lua` | MIT |
| APW | ArchipelagoMW/Archipelago @ c835bff5 | `worlds/kh1/Locations.py` | MIT |
| LIB | gaithern/KH1-LUA-LIBRARY @ e387282a | `scripts/io_packages/SteamGlobal_1_0_0_2.lua`, `kh1_lua_library.lua` | MIT |
| RANDO | gaithern/KH1FM-RANDOMIZER @ 57e701fc | `Static Files/scripts/1fmRandoFixWarps.lua`, `1fmRandoHandleItems.lua` | MIT |
| MP | AmirulAndalib/MultiPresence @ 81cf9ac2 | `MultiPresence/Models/KH1/Worlds.cs`, `Rooms.cs`, `Presence/KH1.cs` | MIT |
| JUL | Julumisan/kh-qol-manager @ f35bfef6 | `KH_QoL/docs/FEATURE_RESEARCH.md` | MIT |
| KHT | sagezaugg/kh-tracker @ bb0dda01 | `scripts/kh1/extract_locations.py`, `src/games/kh1/data/constants.ts` | MIT |
| CMIX | Drflash55/KH1-Critical-Mix-Additions @ f705c7c0 | `scripts/CMix_SpiritLink.lua` | **AGPL-3.0**: cite addresses only, copy no code |

KHT itself cites Kingdom Save Editor (GPL-3.0) for world IDs. KHT uses only the documented values,
not KSE's code. The AP connector credits retroachievements.org code notes (game 2780) for its
progress thresholds.

## Build differences (Steam, EGS, Global, JP)

- **Steam JP 1.0.0.2 vs Steam Global 1.0.0.2.** Every data address used here is identical. Only code
  and text hooks differ (beepHack, roomWarp/worldWarp, skipArrays, etc.). This comes from diffing the
  two DEN io_packages.
- **EGS 1.0.0.10.** Save-block data (stats, chests, progress, party) is Steam **+0xA00**. `soraHP` is
  also +0xA00. `world` and `room` are +0x1038 (DEN EGS `world` = 0x2340ECC). AP's `g_WorldNumber`
  on EGS is 0x2340E5C, which is not +0x1038. `inCutscene` is −0x2E3F0. `menu` is +0x9E0.
  `party2` is +0x9C0. Do not apply a single delta to the EGS build.
- **Steam build detection.** AP checks `u8[0x3A29A6] == 0xF0` for Steam and `u8[0x3A2B86] == 0xF0`
  for EGS (AP `1fmAPConnector.lua` 2152–2163). DEN instead matches a version string at
  0x4698D2 for Steam Global 1.0.0.2 (DEN `VersionCheck.lua`).
- **Progress-byte conflict (DEN vs AP).** DEN `cutsceneFlags = 0x2DEA860` (line 123), and
  `1fmRandoSomeLogic.lua` 2438 treats +10 (0x2DEA86A) as HB progress. DEN's own `1fmEnemyRando.lua`
  366, AP and RANDO all use **0x2DEA86E** for HB. Use 0x2DEA86E, and dump 0x2DEA860–0x2DEA872 once
  live to settle it.

## 1. World IDs

There are two world bytes, and both were confirmed live. `world` is u8 at **0x233FE94** (DEN line 40).
`g_WorldNumber` is u8 at **0x233FE84** (LIB line 315; AP `read_world` line 765). `room` is
**0x233FE8C**. `g_SetNumber` (0x233FE90) holds the room's map-state row (LIB line 317). Every comparison
in the sources uses the same IDs on both world bytes.

| ID | World | Confidence |
|---|---|---|
| 0 | Dive into the Heart | source (MP Worlds.cs 14–86, KHT constants.ts) |
| 1 | Destiny Islands | source |
| 2 | Disney Castle | source |
| **3** | **Traverse Town** | source. DEN uses world==3, room 19 (1fmRandoSomeLogic 2162) |
| 4 | Wonderland | source |
| 5 | Deep Jungle | confirmed live |
| 6 | 100 Acre Wood | source |
| 7 | (unused) | no source names it |
| 8 | Agrabah | source |
| 9 | Atlantica | source |
| 10 | Halloween Town | source |
| 11 | Olympus Coliseum | source |
| 12 | Monstro | source |
| **13** | **Neverland** | source. AP FixWarps 243: world 0x0D, room 7 |
| 14 | "Hollow Bastion" according to MP only | guess. KSE/KHT have no 14 |
| **15** | **Hollow Bastion** | source. AP FixWarps 306/312: world 0x0F |
| **16** | **End of the World** | source. AP Connector 1474: world 0x10, room 0x20 is the finale |
| 255 | title/menu | source (MP Presence/KH1.cs 63) |

## 2. Rooms

The room byte is the 0-based index into MP `Rooms.cs`. This was confirmed live for Deep Jungle:
index 12 is Camp and index 14 is Camp: Tent, as in MP. The map files line up as well: room r is
`<prefix>{r+1:02}.ard`. The AP mod ships 39 `ew*.ard` files and MP lists 39 EotW rooms (derived).

**Neverland** (world 13, `pp`; MP Rooms.cs 211–223): 0 Ship: Hold · 1 Ship: Hold · 2 Ship: Hold ·
3 Ship: Freezer · 4 Ship: Galley · 5 Ship: Cabin · 6 Captain's Cabin · 7 Ship: Hold · 8 Pirate Ship (deck) ·
9 Clock Tower. Two of these are checked in other sources: room 9 is the Clock Tower in DEN
1fmRandoSomeLogic 2204, and room 8 is the Hook deck in DEN 1fmEnemyRando 572.
**Gap:** `pp11`/`pp12` exist, so rooms 10–11 exist, but no source names them.

**Hollow Bastion** (world 15, `pc`; MP 224–242): 0 Rising Falls · 1 Castle Gates · 2 Great Crest ·
3 High Tower · 4 Entrance Hall · 5 Library · 6 Lift Stop · 7 Base Level · 8 Waterway · 9 Waterway ·
10 Dungeon · 11 Castle Chapel · 12 Castle Chapel · 13 Castle Chapel · 14 Grand Hall · 15 Dark Depths ·
16 Castle Chapel. Some of these are checked in other sources:
- Room 4 is the emblem door (DEN 2470).
- Room 5 is the Library, where Khama and Theon are (DEN 2480).
- Room 11 is the Ansem/Riku cutscene (AP FixWarps 306).
- Room 14 is the Riku II fight (DEN EnemyRando 366).

**End of the World** (world 16, `ew`; MP 244–286): 0 Gate to the Dark · 1–12 Final Dimension ·
13 Dark Sphere · 14 Giant Crevasse · 15 World Terminus · 16–25 the World Terminus portals, in order
TT, WL, OC, DJ, AG, AT, HT, NL, 100AW, HB · 26 Evil Grounds · 27 Volcanic Crater · 28 Linked Worlds ·
29 Final Rest · 30 Homecoming · 31 Crumbling Island · 32 Final Door · 33 The Void · 34 Crater ·
35 Homecoming · 36–38 The Void. AP's finale check uses room 0x20 (32). The current portal at the
World Terminus is u8 0x2380A14 (DEN line 163).

**Traverse Town** (sanity check; MP 48–75): 26 rooms, 0 1st District … 22 Secret Waterway … 25 Small House.

**Adjacency:** no source gives room-to-room connections. The edges in `rooms.json` are **guesses**
from game knowledge. Recommendation: have Diptych learn the edges by logging (prev_room, room)
pairs whenever `room` changes outside a cutscene (`inCutscene == 0`), and ship the learned graph.
The world-alt byte matters only for detecting world changes. No source uses it per room.

## 3. Chests

The AP connector (`1fmAPConnector.lua` 723–727, 1652–1673) reads 509 bytes from **0x2DE992C** on
Steam. For every set bit it reports the location `2650000 + k*10 + b`, where k is the 1-based byte
index and b is the 1-based bit. Only bits 0–3 are ever used, and k is always ≡ 1 (mod 4). KHT
`extract_locations.py` 82–85 decodes the same way. Rearranged:

- **Opened check (derived):** for AP id `C`, set k = (C−2650000)//10 and b = C%10. The chest is
  opened when `u8[0x2DE992C + k − 1]` has bit `b−1` set.
- **Chest index (derived):** index = k + b − 1. Equivalently, byte = 0x2DE992C + 4·⌊(i−1)/4⌋ and
  bit = (i−1) mod 4. This index matches DEN `Rando/chests.lua` keys, which give each chest's
  vanilla item and room label. For example, Pirate Ship deck is AP 2653772, which gives i = 378,
  which is "Dalmatians 15, Pirate Ship".
- **"Reward XX" contents (derived):** a DEN item of the form "Reward XX" decodes as
  `rewards.lua[0xXX + 1]`. The +1 offset was checked on two known chests. Geppetto's House is
  "Reward 33", which gives 52 = Wishing Star. Grand Hall is "Reward 31", which gives 50 = Oblivion.
- **Two locations are not chest bits:**
  - Neverland *Hold Aero Chest* (AP 2656344) is bit 1 of 0x2DEB1C2 (Connector 967).
  - HB *Emblem Piece (Chest)* counts as collected when u8 0x2DEB20F ≥ 2 (Connector 1013).

| World | Chest-type locations | With a flag | With vanilla contents | Room known exactly |
|---|---|---|---|---|
| Neverland | 12 (11 bit chests + Aero) | 12 | 12 (Aero is a guess) | 5. The other 7 are in "Ship: Hold", one of rooms {0,1,2,7} |
| Hollow Bastion | 39 (incl. 4 Library carousel chests, the statue chest and the emblem) | 39 | 34 | 35. Waterway is {8,9} |
| End of the World | 26 | 26 | 19 | 16. Final Dimension chests 1–10 are in rooms 1–12, exact room unknown |

The flag ranges used are 0x2DE9AA4–0x2DE9AC0 for Neverland, 0x2DE9AC0–0x2DE9AEC for HB (+0x2DE9AD0 for
Entrance Hall/Library) and 0x2DE9AF0–0x2DE9B08 for EotW. For Traverse Town the same formula gives,
for example, Candle Puzzle chest = AP 2650211, which is 0x2DE9940 bit 0.

There are 12 chests with no vanilla contents: the 4 Library carousel chests, the Entrance Hall statue
chest, Final Dimension 2nd/3rd/4th/7th/8th/10th and the Atlantica terminus chest. DEN's list leaves
them out because the randomizer doesn't touch them. Per-chest data is in `kh1-data-draft/chests.json`.

## 4. Party HP/MP

**Persistent stats table (save block).** Each character's entry starts at **0x2DE9364 + id·0x74**,
all fields u8. Offsets within an entry: +0 level, +1 HP, +2 max HP, +3 MP, +4 max MP. The layout
comes from these sources:
- AP `1fmAPHandleDeathlink.lua` 31–34: Sora HP is 0x2DE9360+5, Donald is +0x74, Goofy is +0x74·2.
- CMIX 101–106: Sora's current/max HP and MP.
- KHT `constants.ts` 96: level at slot+4.
- DEN 1fmRandoSomeLogic 2501: stride 116 = 0x74.
- The ten entries end at 0x2DE97EC, right before the party slots at 0x2DE97EF (derived).

| Char (id) | HP | Max HP | MP | Max MP | Confidence |
|---|---|---|---|---|---|
| Sora (0) | 0x2DE9365 | 0x2DE9366 | 0x2DE9367 | 0x2DE9368 | source |
| Donald (1) | 0x2DE93D9 | 0x2DE93DA | 0x2DE93DB | 0x2DE93DC | HP: source (deathlink). Max HP/MP: derived |
| Goofy (2) | 0x2DE944D | 0x2DE944E | 0x2DE944F | 0x2DE9450 | HP: source. Max HP/MP: derived |
| Peter Pan (8) | 0x2DE9705 | 0x2DE9706 | 0x2DE9707 | 0x2DE9708 | **guess**. ID 8 from AP FixWarps 266 (party == 0xFF08 before Hook) |
| Beast (9) | 0x2DE9779 | 0x2DE977A | 0x2DE977B | 0x2DE977C | **guess**. ID 9 from DEN 2289 (HB1 party1 = 9) |

Your reads at 0x2DE9440 and 0x2DE94C0 don't fit this layout: Donald's entry starts at 0x2DE93D8 and
Goofy's at 0x2DE944C. Re-check those reads.

**Party slots.** Slot 1 is u8 **0x2DE97EF** and slot 2 is **0x2DE97F0**. Values: 1 Donald, 2 Goofy,
8 Peter Pan, 9 Beast, 0xFF empty (DEN line 237; CMIX 353/359).

**Battle block (u32 values).** There are 32 blocks of 0x100 bytes starting at 0x2D5CC10. Each block
has HP at +0x3C, max HP at +0x40, MP at +0x44 and max MP at +0x48 (JUL FEATURE_RESEARCH 32–35).
- **There are party entries after Sora's (derived).** DEN `1fmEnemyRando.lua` 567–573 takes boss HP
  from block 3 when two allies are present, and from block 1 when the party is empty.
- **Slot 1:** HP 0x2D5CD4C, max HP 0x2D5CD50, MP 0x2D5CD54, max MP 0x2D5CD58.
- **Slot 2:** HP 0x2D5CE4C, max HP 0x2D5CE50, MP 0x2D5CE54, max MP 0x2D5CE58.
- **These are derived, not confirmed.** JUL warns that block order isn't guaranteed.
- **Robust alternative:** follow the party object pointers 0x2D37288 / 0x2D37290 (LIB 344–345). The
  object holds a handle at +0x6C. The game resolves it with the function at 0x38ADC0 into a stats
  block (JUL line 33).
- **How to read them in practice:** check that the block's max HP equals that character's persistent
  max HP. Fall back to the persistent table, which is always valid, including in menus.

## 5. Story progress (for "where next" hints)

There is one progress byte per world at **0x2DEA864 + i** (AP Connector 784–794; RANDO/AP FixWarps
`StoryProgress = 0x2DEA861`). Neverland is **0x2DEA86D**, HB is **0x2DEA86E** and EotW is
**0x2DEA86F**. Each step in the tables below is a threshold: the byte is ≥ the value once that step
is reached. Full citations are in `story_flags.json`.

**Neverland:**

| Value | Step |
|---|---|
| 0x14 | Neverland 2 start |
| 0x35 | Anti-Sora beaten |
| 0x3C | Peter Pan rejoins |
| 0x50 | Before Hook |
| 0x56 | Hook beaten. Next: Clock Tower (dock bit 0x2DEB25F+7 \|= 4) |
| 0x6E | Keyhole sealed (Glide) |
| 0x96 | Phantom beaten |

**Hollow Bastion:**

| Value | Step |
|---|---|
| 0x32 | Riku I beaten |
| 0x46 | Ansem possesses Riku |
| 0x50 | Before Maleficent |
| 0x5A | Maleficent beaten |
| 0x6E | Dragon Maleficent beaten |
| 0x82 | Riku II beaten |
| 0xB9 | Behemoth beaten |
| 0xC3 | Princesses (gates EotW) |

**End of the World:**

| Value | Step |
|---|---|
| 0x33 | Chernabog beaten |
| 0x9B | Final, together with world 16, room 32 |

**Other useful flags:**

| Address | Meaning | Source |
|---|---|---|
| 0x2DEB1C1 / 0x2DEB1C2 bits | Clock Tower doors | Connector 1016–1027 |
| 0x2DEB20E–11 | HB emblem pieces | Connector 1012–1015 |
| 0x2DEB21C | Emblem door | DEN 167 |
| 0x2DEAD20 | Ansem report bits | Connector 769 |
| 0x2DEB25F+n | World-map dock points | AP FixWarps 12 |

## Biggest gaps

1. **Room adjacency.** No source gives it. The edges are guesses, so log room transitions live.
2. **Chest rooms.** Some sub-rooms are ambiguous: Neverland "Ship: Hold" ×4, HB Waterway ×2, and
   EotW Final Dimension ×12. Neverland rooms 10–11 have no names.
3. **Guest stats.** Peter Pan and Beast persistent-stat addresses are extrapolated. Battle-block
   slots 1 and 2 are derived and not confirmed live. Verify both in Neverland and in HB1.
4. **Missing contents and a byte conflict.** 12 chests have no vanilla contents. The DEN vs AP
   progress-byte conflict needs one live dump of 0x2DEA860–0x2DEA872.
