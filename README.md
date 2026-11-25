# Diptych

*Diptych* (n.) /ˈdɪp.tɪk/ (DIP-tick)

A work of art made of two panels, hinged so it closes like a book.
From the Greek *díptychos*, "folded in two".

Also: a companion app for dual-screen Android handhelds that fold in two.

**Status:** one app for every supported game. The Shelf lists them; when a game's mod connects, its companion takes
the bottom screen.

| Game | Mod | Port | Companion |
|------|-----|------|-----------|
| Kingdom Hearts Final Mix | `mods/kh1` (`dinput8.dll` proxy) | 7970 | room maps, chests, party HP |
| Tale of Two Wastelands | `mods/ttw` (`ttw_dualscreen.dll`, xNVSE plugin) | 7960 | Pip-Boy status page: HP, wasteland, cell |

The app keeps one loopback WebSocket link per game and hands the screen to whichever one answers.

Design doc: [docs/superpowers/specs/2026-11-03-diptych-design.md](docs/superpowers/specs/2026-11-03-diptych-design.md)

Protocols: [KH1](protocol/kh1/PROTOCOL.md), [TTW](protocol/ttw/PROTOCOL.md)

TTW: [setup guide for the AYN Thor](docs/guide/ttw-on-ayn-thor.md), [spike checklist](docs/spike/TTW-CHECKLIST.md). Mock plugin: `tools/mockserver/ttw_mock.py`.

Spike checklists: [PC](docs/spike/PC-CHECKLIST.md), [Thor](docs/spike/THOR-CHECKLIST.md)

KH1 map (v0.2.1): [map checklist](docs/spike/MAP-CHECKLIST.md)

Launcher (v0.3.0): [launcher checklist](docs/spike/SHELL-CHECKLIST.md)

Licence: GPL-3.0-or-later.
