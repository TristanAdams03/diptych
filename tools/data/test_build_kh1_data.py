"""Checks for the generated KH1 assets (app/app/src/main/assets/kh1).

The tests read the committed asset files, so they check what the app ships.
CI also regenerates the assets and fails on any diff.
"""

import json
from collections import Counter
from pathlib import Path

import pytest

import build_kh1_data as b

ASSETS = Path(__file__).resolve().parents[2] / "app/app/src/main/assets/kh1"

# Limits of kh1.watch (global constraints).
MAX_REGIONS = 8
MAX_SPAN = 256
MAX_TOTAL = 1024

COUNTS = {"Neverland": 12, "Hollow Bastion": 39, "End of the World": 26}
CONFIDENCE = {"live", "source", "derived", "guess"}


def load(name):
    return json.loads((ASSETS / name).read_text(encoding="utf-8"))


WORLDS = load("worlds.json")
PARTY = load("party.json")
WORLD_LIST = WORLDS["worlds"]
WORLD_IDS = [w["name"] for w in WORLD_LIST]


def per_world(fn):
    return pytest.mark.parametrize("world", WORLD_LIST, ids=WORLD_IDS)(fn)


def chest_byte(chest):
    return chest["offset"]


def in_spans(offset, spans):
    return any(s["offset"] <= offset < s["offset"] + s["length"] for s in spans)


# --- files ----------------------------------------------------------------


def test_files_have_version_and_sources():
    for doc in (WORLDS, PARTY):
        assert doc["version"] == 1
        assert doc["sources"] and all(isinstance(s, str) and s for s in doc["sources"])
    assert (ASSETS / "SOURCES.md").is_file()


def test_generator_output_matches_committed_assets():
    assert b.dumps(b.build_worlds()) == (ASSETS / "worlds.json").read_text(encoding="utf-8")
    assert b.dumps(b.build_party()) == (ASSETS / "party.json").read_text(encoding="utf-8")


def test_world_ids_and_counts():
    assert {w["name"]: w["id"] for w in WORLD_LIST} == {
        "Neverland": 13, "Hollow Bastion": 15, "End of the World": 16}
    assert {w["name"]: len(w["chests"]) for w in WORLD_LIST} == COUNTS


# --- rooms, edges, layout -------------------------------------------------


@per_world
def test_room_ids_unique(world):
    ids = [r["id"] for r in world["rooms"]]
    assert len(ids) == len(set(ids))


@per_world
def test_rooms_match_research_draft(world):
    draft = b.load_draft("rooms.json")[world["name"]]["rooms"]
    assert [r["id"] for r in world["rooms"]] == [r["id"] for r in draft]


@per_world
def test_room_cells_unique(world):
    cells = Counter((r["col"], r["row"]) for r in world["rooms"])
    assert [c for c, n in cells.items() if n > 1] == []
    assert all(r["col"] >= 0 and r["row"] >= 0 for r in world["rooms"])


@pytest.mark.parametrize("name", ["Neverland", "Hollow Bastion"])
def test_small_worlds_fit_four_columns(name):
    world = next(w for w in WORLD_LIST if w["name"] == name)
    assert max(r["col"] for r in world["rooms"]) <= 3


@per_world
def test_same_name_rooms_carry_their_id(world):
    labels = [r["name"] for r in world["rooms"]]
    assert len(labels) == len(set(labels))
    draft = b.load_draft("rooms.json")[world["name"]]["rooms"]
    base = Counter(r["name"] for r in draft)
    for d, r in zip(draft, world["rooms"]):
        want = f'{d["name"]} ({d["id"]})' if base[d["name"]] > 1 else d["name"]
        assert r["name"] == want


@per_world
def test_edges_join_existing_rooms(world):
    ids = {r["id"] for r in world["rooms"]}
    for a, b_ in world["edges"]:
        assert a in ids and b_ in ids and a != b_
    assert len({tuple(e) for e in world["edges"]}) == len(world["edges"])


@per_world
def test_edges_come_from_the_draft_only(world):
    adj = b.load_draft("rooms.json")[world["name"]]["adjacency"]
    draft = {tuple(sorted(e)) for e in adj["edges"]}
    assert {tuple(e) for e in world["edges"]} == draft
    assert world["edgesConfidence"] == adj["confidence"]
    assert world["edgesConfidence"] in CONFIDENCE


@per_world
def test_edges_do_not_pass_through_other_rooms(world):
    cell = {r["id"]: (r["col"], r["row"]) for r in world["rooms"]}
    for a, b_ in world["edges"]:
        (x1, y1), (x2, y2) = cell[a], cell[b_]
        for rid, (x, y) in cell.items():
            if rid in (a, b_):
                continue
            collinear = (x2 - x1) * (y - y1) == (y2 - y1) * (x - x1)
            between = min(x1, x2) <= x <= max(x1, x2) and min(y1, y2) <= y <= max(y1, y2)
            assert not (collinear and between), f"edge {a}-{b_} runs through room {rid}"


@per_world
def test_rooms_carry_confidence(world):
    assert all(r["confidence"] in CONFIDENCE for r in world["rooms"])


# --- chests and watch spans -----------------------------------------------


@per_world
def test_every_chest_room_exists(world):
    ids = {r["id"] for r in world["rooms"]}
    assert all(c["room"] in ids for c in world["chests"])


@per_world
def test_chest_has_exactly_one_flag_kind(world):
    for c in world["chests"]:
        kinds = [k for k in ("bit", "atLeast") if k in c]
        assert len(kinds) == 1, c["name"]
        if "bit" in c:
            assert 0 <= c["bit"] <= 7
        else:
            assert 1 <= c["atLeast"] <= 255
        assert c["confidence"] in CONFIDENCE
        assert c["roomConfidence"] in CONFIDENCE
        if c["contents"] is not None:
            assert c["contentsConfidence"] in CONFIDENCE


def test_both_flag_kinds_are_present():
    hb = next(w for w in WORLD_LIST if w["name"] == "Hollow Bastion")
    emblem = [c for c in hb["chests"] if "atLeast" in c]
    assert [(c["offset"], c["atLeast"]) for c in emblem] == [(0x2DEB20F, 2)]
    nl = next(w for w in WORLD_LIST if w["name"] == "Neverland")
    aero = next(c for c in nl["chests"] if c["name"] == "Neverland Hold Aero Chest")
    assert (aero["offset"], aero["bit"]) == (0x2DEB1C2, 1)


@per_world
def test_every_chest_byte_is_watched(world):
    for c in world["chests"]:
        assert in_spans(chest_byte(c), world["watch"]), c["name"]


@per_world
def test_watch_spans_within_limits(world):
    spans = world["watch"]
    assert all(1 <= s["length"] <= MAX_SPAN for s in spans)
    assert sum(s["length"] for s in spans) <= MAX_TOTAL
    # Spans are sorted and disjoint.
    ends = [(s["offset"], s["offset"] + s["length"]) for s in spans]
    assert ends == sorted(ends)
    assert all(a[1] <= b_[0] for a, b_ in zip(ends, ends[1:]))


@per_world
def test_watch_spans_are_tight(world):
    # Every span starts and ends on a chest byte (nothing wasted at the edges).
    chest_bytes = {chest_byte(c) for c in world["chests"]}
    for s in world["watch"]:
        assert s["offset"] in chest_bytes
        assert s["offset"] + s["length"] - 1 in chest_bytes


@per_world
def test_world_watch_plus_party_fits_one_kh1_watch(world):
    regions = world["watch"] + b.party_regions(PARTY)
    assert len(regions) <= MAX_REGIONS
    assert sum(r["length"] for r in regions) <= MAX_TOTAL
    assert all(1 <= r["length"] <= MAX_SPAN for r in regions)


def test_cover_spans_splits_far_bytes():
    assert b.cover_spans([10, 12, 10 + 255, 10 + 256]) == [
        {"offset": 10, "length": 256}, {"offset": 266, "length": 1}]
    assert b.cover_spans([5]) == [{"offset": 5, "length": 1}]


# --- party ----------------------------------------------------------------


def test_party_matches_live_check():
    bb = PARTY["battleBlock"]
    assert (bb["offset"], bb["stride"], bb["members"]) == (0x2D5CC4C, 0x100, 3)
    assert bb["fields"] == {
        "hp": {"at": 0, "width": 4}, "maxHp": {"at": 4, "width": 4},
        "mp": {"at": 8, "width": 4}, "maxMp": {"at": 12, "width": 4}}
    assert bb["confidence"] == "live"
    slots = PARTY["slots"]
    assert (slots["offset"], slots["length"], slots["confidence"]) == (0x2DE97EF, 2, "live")
    names = PARTY["names"]
    assert {k: names[k] for k in ("0", "1", "2")} == {"0": "Sora", "1": "Donald", "2": "Goofy"}
    assert set(PARTY["namesConfidence"]) == set(names)
    assert all(v in CONFIDENCE for v in PARTY["namesConfidence"].values())


def test_party_regions():
    assert b.party_regions(PARTY) == [
        {"offset": 0x2D5CC4C, "length": 16},
        {"offset": 0x2D5CD4C, "length": 16},
        {"offset": 0x2D5CE4C, "length": 16},
        {"offset": 0x2DE97EF, "length": 2},
    ]
