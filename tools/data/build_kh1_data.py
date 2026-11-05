#!/usr/bin/env python3
"""Build the KH1 map assets from the research drafts and the hand-authored layouts.

Inputs:
  docs/research/kh1-data-draft/rooms.json, chests.json   (research drafts, with citations)
  docs/research/kh1-live-check-2026-11-04.md              (party addresses; authoritative)
  tools/data/layouts/kh1_layouts.json                     (hand-authored grid cells)
Outputs (committed; CI fails if regenerating changes them):
  app/app/src/main/assets/kh1/worlds.json
  app/app/src/main/assets/kh1/party.json

Run from anywhere: python3 tools/data/build_kh1_data.py
"""

import json
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DRAFT = ROOT / "docs/research/kh1-data-draft"
LAYOUTS = Path(__file__).resolve().parent / "layouts/kh1_layouts.json"
OUT = ROOT / "app/app/src/main/assets/kh1"

WORLDS = ["Neverland", "Hollow Bastion", "End of the World"]
MAX_SPAN = 256

# Research labels -> the four confidence values the data files use.
_CONF = {"source": "source", "derived": "derived", "guess": "guess", "live": "live",
         "ambiguous": "guess"}

SOURCES_WORLDS = [
    "Rooms: AmirulAndalib/MultiPresence@81cf9ac2 MultiPresence/Models/KH1/Rooms.cs lines 211-286 (MIT); "
    "room byte == list index, confirmed live for Deep Jungle 12/14",
    "World ids: AmirulAndalib/MultiPresence@81cf9ac2 MultiPresence/Models/KH1/Worlds.cs lines 14-86 (MIT); "
    "sagezaugg/kh-tracker@bb0dda01 src/games/kh1/data/constants.ts (MIT)",
    "Chest names: ArchipelagoMW/Archipelago@c835bff5 worlds/kh1/Locations.py (MIT)",
    "Chest bit flags: gaithern/KH-1FM-AP-LUA@4fe14a54 1fmAPConnector.lua lines 723-727 and 1652-1673 (MIT); "
    "opened = (u8[offset] >> bit) & 1, confirmed live by pattern 2026-11-04",
    "Neverland Hold Aero Chest: gaithern/KH-1FM-AP-LUA@4fe14a54 1fmAPConnector.lua line 967 (MIT)",
    "Hollow Bastion Emblem Piece (Chest): gaithern/KH-1FM-AP-LUA@4fe14a54 1fmAPConnector.lua line 1013 (MIT); "
    "collected when u8[offset] >= atLeast",
    "Chest contents: Denhonator/KHPCSpeedrunTools@aeb4972e 1FMMods/scripts/io_packages/Rando/chests.lua "
    "and rewards.lua (Unlicense)",
    "Edges: guesses from game knowledge (docs/research/kh1-data-draft/rooms.json adjacency); not sourced",
    "Layout: hand-authored, tools/data/layouts/kh1_layouts.json",
    "Per-row citations: docs/research/kh1-data-draft/*.json and docs/research/kh1-late-worlds.md",
]

SOURCES_PARTY = [
    "Battle block and party slots: live check on Steam 1.0.0.2, docs/research/kh1-live-check-2026-11-04.md",
    "Battle block layout: AmirulAndalib/MultiPresence@81cf9ac2 Presence/KH1.cs (MIT); "
    "Julumisan/kh-qol-manager@f35bfef6 KH_QoL/docs/FEATURE_RESEARCH.md lines 32-35 (MIT)",
    "Party slot ids (1 Donald, 2 Goofy, 0xFF empty): Denhonator/KHPCSpeedrunTools@aeb4972e "
    "SteamGlobal_1_0_0_2.lua line 237 (Unlicense)",
    "Peter Pan = 8: gaithern/KH-1FM-AP-LUA@4fe14a54 1fmAPFixWarps.lua line 266 (MIT)",
    "Beast = 9: Denhonator/KHPCSpeedrunTools@aeb4972e 1fmRandoSomeLogic.lua lines 2284-2292 (Unlicense)",
]


def load_draft(name):
    return json.loads((DRAFT / name).read_text(encoding="utf-8"))


def load_layouts():
    return json.loads(LAYOUTS.read_text(encoding="utf-8"))


def conf(label):
    """Map a research confidence label ("source (MultiPresence ...)", "derived", ...) to one value."""
    word = label.split()[0].strip("(:,").lower()
    if word not in _CONF:
        raise ValueError(f"unknown confidence label: {label!r}")
    return _CONF[word]


def cover_spans(offsets):
    """The fewest spans of at most MAX_SPAN bytes that cover every offset (greedy is optimal on a line).

    Each span starts and ends on a covered byte."""
    spans = []
    for off in sorted(set(offsets)):
        if spans and off < spans[-1]["offset"] + MAX_SPAN:
            spans[-1]["length"] = off - spans[-1]["offset"] + 1
        else:
            spans.append({"offset": off, "length": 1})
    return spans


def _rooms(name, draft_rooms, cells):
    ids = [r["id"] for r in draft_rooms]
    if sorted(map(int, cells)) != sorted(ids):
        raise ValueError(f"{name}: layout cells {sorted(map(int, cells))} != draft rooms {sorted(ids)}")
    same = Counter(r["name"] for r in draft_rooms)
    rooms = []
    for r in draft_rooms:
        col, row = cells[str(r["id"])]
        label = f'{r["name"]} ({r["id"]})' if same[r["name"]] > 1 else r["name"]
        rooms.append({"id": r["id"], "name": label, "col": col, "row": row,
                      "confidence": conf(r["confidence"])})
    return rooms


def _chest(c):
    flag = c["flag"]
    out = {"name": c["name"], "room": c["room_ids"][0], "offset": int(flag["address"], 16)}
    if "bit" in flag:
        out["bit"] = flag["bit"]
    else:
        # Value rule, e.g. "collected when u8[0x2DEB20F] >= 2".
        rule = flag["rule"]
        if ">=" not in rule:
            raise ValueError(f"{c['name']}: flag has neither a bit nor a >= rule: {rule!r}")
        out["atLeast"] = int(rule.split(">=")[1].strip(), 0)
    contents = c.get("vanilla_contents") or {}
    out["contents"] = contents.get("item")
    if out["contents"] is not None:
        out["contentsConfidence"] = conf(contents["confidence"])
    out["confidence"] = conf(flag["confidence"])
    # Several candidate rooms means the sub-room is unknown, whatever the draft label says.
    out["roomConfidence"] = "guess" if len(c["room_ids"]) > 1 else conf(c["room_confidence"])
    return out


def build_worlds():
    rooms_draft = load_draft("rooms.json")
    chests_draft = load_draft("chests.json")
    layouts = load_layouts()["worlds"]
    worlds = []
    for name in WORLDS:
        rd, cd = rooms_draft[name], chests_draft[name]
        if rd["world_id"] != cd["world_id"]:
            raise ValueError(f"{name}: world id mismatch")
        rooms = _rooms(name, rd["rooms"], layouts[name]["cells"])
        adj = rd["adjacency"]
        edges = sorted({tuple(sorted(e)) for e in adj["edges"]})
        chests = sorted((_chest(c) for c in cd["chests"]),
                        key=lambda c: (c["offset"], c.get("bit", -1), c["name"]))
        if len(chests) != cd["count"]:
            raise ValueError(f"{name}: {len(chests)} chests, draft count {cd['count']}")
        worlds.append({
            "id": rd["world_id"],
            "name": name,
            "rooms": rooms,
            "edges": [list(e) for e in edges],
            "edgesConfidence": conf(adj["confidence"]),
            "chests": chests,
            "watch": cover_spans(c["offset"] for c in chests),
        })
    return {"version": 1, "sources": SOURCES_WORLDS, "worlds": worlds}


def build_party():
    # Values from docs/research/kh1-live-check-2026-11-04.md (authoritative).
    return {
        "version": 1,
        "sources": SOURCES_PARTY,
        "battleBlock": {
            "offset": 0x2D5CC4C,
            "stride": 0x100,
            "members": 3,
            "fields": {
                "hp": {"at": 0x0, "width": 4},
                "maxHp": {"at": 0x4, "width": 4},
                "mp": {"at": 0x8, "width": 4},
                "maxMp": {"at": 0xC, "width": 4},
            },
            "confidence": "live",
        },
        "slots": {"offset": 0x2DE97EF, "length": 2, "confidence": "live",
                  "empty": 0xFF, "emptyConfidence": "source"},
        "names": {"0": "Sora", "1": "Donald", "2": "Goofy", "8": "Peter Pan", "9": "Beast"},
        "namesConfidence": {"0": "live", "1": "live", "2": "live", "8": "guess", "9": "guess"},
    }


def party_regions(party):
    """The kh1.watch regions the app sends for the party, next to a world's chest spans."""
    bb = party["battleBlock"]
    width = max(f["at"] + f["width"] for f in bb["fields"].values())
    regions = [{"offset": bb["offset"] + i * bb["stride"], "length": width}
               for i in range(bb["members"])]
    regions.append({"offset": party["slots"]["offset"], "length": party["slots"]["length"]})
    return regions


def dumps(doc):
    return json.dumps(doc, indent=1, ensure_ascii=False, sort_keys=True) + "\n"


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "worlds.json").write_text(dumps(build_worlds()), encoding="utf-8")
    (OUT / "party.json").write_text(dumps(build_party()), encoding="utf-8")


if __name__ == "__main__":
    main()
