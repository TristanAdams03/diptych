import asyncio
import json
from pathlib import Path

import pytest
from websockets.asyncio.client import connect
from websockets.exceptions import InvalidStatus

from kh1_mock import AB_SWAP, IMAGE_SIZE, MENU, PLUGIN_VERSION, SHORTCUTS, serve

FIX = Path(__file__).resolve().parents[2] / "protocol" / "kh1" / "fixtures"


def fixture(name):
    return json.loads((FIX / name).read_text())


async def start(**kw):
    server = await serve(0, **kw)
    port = server.sockets[0].getsockname()[1]
    return server, f"ws://127.0.0.1:{port}"


async def rpc(ws, obj):
    await ws.send(obj if isinstance(obj, str) else json.dumps(obj))
    return json.loads(await asyncio.wait_for(ws.recv(), 2))


def cmd(name, args="__omit__", id="c1"):
    m = {"type": "command", "name": name}
    if id != "__omit__":
        m["id"] = id
    if args != "__omit__":
        m["args"] = args
    return m


async def collect_states(ws, seconds):
    out = []
    loop = asyncio.get_running_loop()
    end = loop.time() + seconds
    while (left := end - loop.time()) > 0:
        try:
            m = json.loads(await asyncio.wait_for(ws.recv(), left))
        except asyncio.TimeoutError:
            break
        if m["type"] == "state":
            out.append(m)
    return out


def nest(depth):  # frame object is level 1
    s = '{"type":"x","a":' + "[" * (depth - 2) + "1" + "]" * (depth - 2) + "}"
    return s


async def test_hello_welcome_matches_fixture():
    server, url = await start()
    async with server, connect(url) as ws:
        w = await rpc(ws, fixture("hello.json"))
        assert w == fixture("welcome_steam.json")
        assert w["gameId"] == "kh1"


async def test_welcome_unsupported_matches_fixture():
    server, url = await start(unsupported=True)
    async with server, connect(url) as ws:
        assert await rpc(ws, fixture("hello.json")) == fixture("welcome_unsupported.json")


async def test_subscribe_streams_states_no_ack():
    server, url = await start()
    async with server, connect(url) as ws:
        await ws.send(json.dumps(fixture("subscribe.json")))
        states = await collect_states(ws, 1.0)
        assert len(states) >= 4
        assert all(s["topic"] == "kh1.spike" for s in states)
        seqs = [s["data"]["seq"] for s in states]
        assert all(b > a for a, b in zip(seqs, seqs[1:]))
        assert set(states[0]["data"]) == set(fixture("state_spike.json")["data"])
        d = states[0]["data"]
        assert d["world"] == 3 and d["inMenu"] is False and d["inputBytes"] == "00000000"


async def test_rate_clamped_high_and_low():
    server, url = await start()
    async with server:
        async with connect(url) as ws:
            await ws.send(json.dumps({"type": "subscribe", "topics": ["kh1.spike"], "rateHz": 1000}))
            assert len(await collect_states(ws, 1.0)) <= 12
        async with connect(url) as ws:
            await ws.send(json.dumps({"type": "subscribe", "topics": ["kh1.spike"], "rateHz": 0}))
            assert 1 <= len(await collect_states(ws, 1.6)) <= 3


async def test_unsupported_state_all_null_except_seq_ms():
    server, url = await start(unsupported=True)
    async with server, connect(url) as ws:
        await ws.send(json.dumps(fixture("subscribe.json")))
        s = (await collect_states(ws, 0.5))[0]
        ref = fixture("state_unsupported.json")["data"]
        assert set(s["data"]) == set(ref)
        for k, v in s["data"].items():
            if k in ("seq", "ms"):
                assert isinstance(v, int)
            else:
                assert v is None, k


async def test_unsupported_commands():
    server, url = await start(unsupported=True)
    async with server, connect(url) as ws:
        for c in (cmd("kh1.probe", {"offset": 0, "length": 1}),
                  cmd("kh1.pressInput", {"byteIndex": 0, "mask": 1, "durationMs": 100}),
                  cmd("kh1.pressSequence", fixture("command_sequence.json")["args"])):
            r = await rpc(ws, c)
            assert r == {"type": "result", "id": "c1", "ok": False,
                         "error": "unsupported build", "data": None}
        r = await rpc(ws, cmd("kh1.log", {}))
        assert r["ok"] is True
        assert r["data"]["lines"][0] == f"diptych_kh1 {PLUGIN_VERSION} loaded"
        assert "not supported" in r["data"]["lines"][1]


async def test_log_pinned_startup_lines_first():
    server, url = await start()
    async with server, connect(url) as ws:
        r = await rpc(ws, fixture("command_log.json"))
        assert r["ok"] and r["error"] is None
        ref = fixture("result_log.json")["data"]["lines"]
        # The fixture's first line is example text from an older build; the mock reports its own version.
        assert ref[0].startswith("diptych_kh1 ") and ref[0].endswith(" loaded")
        assert r["data"]["lines"][0] == f"diptych_kh1 {PLUGIN_VERSION} loaded"
        assert r["data"]["lines"][1:3] == ref[1:3]


async def test_probe_fixture():
    server, url = await start()
    async with server, connect(url) as ws:
        assert await rpc(ws, fixture("command_probe.json")) == fixture("result_probe.json")


async def test_probe_deterministic_and_length():
    server, url = await start()
    async with server, connect(url) as ws:
        a = await rpc(ws, cmd("kh1.probe", {"offset": 0x1000, "length": 256}))
        b = await rpc(ws, cmd("kh1.probe", {"offset": 0x1000, "length": 256}))
        assert a == b and len(a["data"]["hex"]) == 512
        assert a["data"]["hex"] == a["data"]["hex"].lower()


@pytest.mark.parametrize("args,err", [
    ({"offset": IMAGE_SIZE - 4, "length": 4}, None),            # last bytes ok
    ({"offset": IMAGE_SIZE - 3, "length": 4}, "out of range"),  # one past
    ({"offset": IMAGE_SIZE, "length": 1}, "out of range"),
    ({"offset": -1, "length": 1}, "out of range"),
    ({"offset": 0, "length": 0}, "out of range"),
    ({"offset": 0, "length": 257}, "out of range"),
    ({"offset": 0, "length": 256}, None),
    ({"offset": "0", "length": 1}, "bad command"),
    ({"offset": 0}, "bad command"),
    ({"offset": 0, "length": 1.5}, "bad command"),
    ({"offset": True, "length": 1}, "bad command"),
])
async def test_probe_bounds(args, err):
    server, url = await start()
    async with server, connect(url) as ws:
        r = await rpc(ws, cmd("kh1.probe", args))
        if err is None:
            assert r["ok"] is True and r["error"] is None
        else:
            assert r == {"type": "result", "id": "c1", "ok": False, "error": err, "data": None}


async def test_press_ok_then_busy_then_free():
    server, url = await start()
    async with server, connect(url) as ws:
        r = await rpc(ws, fixture("command_press.json"))
        assert r == {"type": "result", "id": "c2", "ok": True, "error": None, "data": None}
        r = await rpc(ws, cmd("kh1.pressInput", {"byteIndex": 0, "mask": 16, "durationMs": 100}))
        assert r["error"] == "busy"
        await asyncio.sleep(0.2)
        r = await rpc(ws, cmd("kh1.pressInput", {"byteIndex": 0, "mask": 16, "durationMs": 16}))
        assert r["ok"] is True


async def test_press_reflected_in_input_bytes():
    server, url = await start()
    async with server, connect(url) as ws:
        await ws.send(json.dumps({"type": "subscribe", "topics": ["kh1.spike"], "rateHz": 10}))
        await rpc_skip_states(ws, cmd("kh1.pressInput", {"byteIndex": 1, "mask": 16, "durationMs": 1000}))
        states = await collect_states(ws, 0.4)
        assert states[-1]["data"]["inputBytes"] == "00100000"


async def rpc_skip_states(ws, obj):
    await ws.send(json.dumps(obj))
    while True:
        m = json.loads(await asyncio.wait_for(ws.recv(), 2))
        if m["type"] == "result":
            return m


async def test_press_unsafe_refused_matches_fixture():
    server, url = await start(unsafe=True)
    async with server, connect(url) as ws:
        assert await rpc(ws, fixture("command_press.json")) == fixture("result_press_refused.json")


@pytest.mark.parametrize("args,err", [
    ({"byteIndex": 2, "mask": 1, "durationMs": 100}, "out of range"),
    ({"byteIndex": 31, "mask": 1, "durationMs": 100}, "out of range"),
    ({"byteIndex": 0, "mask": 8, "durationMs": 100}, "out of range"),  # start
    ({"byteIndex": 0, "mask": 0x18, "durationMs": 100}, "out of range"),
    ({"byteIndex": 0, "mask": 8, "durationMs": "x"}, "out of range"),  # mapping checked before duration
    ({"byteIndex": -1, "mask": 1, "durationMs": 100}, "out of range"),
    ({"byteIndex": 0, "mask": 256, "durationMs": 100}, "out of range"),
    ({"byteIndex": 0, "mask": 1, "durationMs": 15}, "out of range"),
    ({"byteIndex": 0, "mask": 1, "durationMs": 1001}, "out of range"),
    ({"byteIndex": 0, "mask": 1}, "bad command"),
    ({"byteIndex": 40, "mask": "x", "durationMs": 100}, "out of range"),  # per-field order
    ({"byteIndex": "x", "mask": 999, "durationMs": 100}, "bad command"),
    ({"byteIndex": 0, "mask": 999, "durationMs": "x"}, "out of range"),
    ({"byteIndex": 0, "mask": 1, "durationMs": 5.5}, "bad command"),
    ({"byteIndex": "0", "mask": 1, "durationMs": 100}, "bad command"),
])
async def test_press_arg_validation(args, err):
    server, url = await start()
    async with server, connect(url) as ws:
        r = await rpc(ws, cmd("kh1.pressInput", args))
        assert r["ok"] is False and r["error"] == err and r["data"] is None


async def test_bad_command_variants():
    server, url = await start()
    async with server, connect(url) as ws:
        # missing id -> id ""
        r = await rpc(ws, cmd("kh1.log", {}, id="__omit__"))
        assert r == {"type": "result", "id": "", "ok": False, "error": "bad command", "data": None}
        # non-string id -> id ""
        r = await rpc(ws, {"type": "command", "id": 5, "name": "kh1.log", "args": {}})
        assert r["id"] == "" and r["error"] == "bad command"
        # missing / non-string name
        r = await rpc(ws, {"type": "command", "id": "x", "args": {}})
        assert r["id"] == "x" and r["error"] == "bad command"
        r = await rpc(ws, {"type": "command", "id": "x", "name": 3, "args": {}})
        assert r["error"] == "bad command"
        # args missing / non-object
        for a in ("__omit__", [], "s", 1, None):
            r = await rpc(ws, cmd("kh1.log", a))
            assert r["error"] == "bad command", a
        # unknown name without args is bad command, with args unknown command
        r = await rpc(ws, cmd("kh1.nope"))
        assert r["error"] == "bad command"
        r = await rpc(ws, cmd("kh1.nope", {}))
        assert r == {"type": "result", "id": "c1", "ok": False, "error": "unknown command", "data": None}


async def test_args_size_cap():
    server, url = await start()
    async with server, connect(url) as ws:
        ok = {"pad": "a" * (65536 - len('{"pad":""}'))}
        assert len(json.dumps(ok, separators=(",", ":"))) == 65536
        assert (await rpc(ws, cmd("kh1.log", ok)))["ok"] is True
        big = {"pad": "a" * (65536 - len('{"pad":""}') + 1)}
        assert (await rpc(ws, cmd("kh1.log", big)))["error"] == "bad command"


async def test_invalid_json_and_unknown_type():
    server, url = await start()
    async with server, connect(url) as ws:
        assert await rpc(ws, "{nope") == fixture("error.json")
        for raw in ('[1,2]', '5', '"x"', 'null', '{}', '{"type":5}', '{"type":"zzz"}'):
            assert await rpc(ws, raw) == {"type": "error", "message": "unknown type"}, raw
        assert (await rpc(ws, fixture("ping.json"))) == fixture("pong.json")  # still open


async def test_depth_cap():
    server, url = await start()
    async with server, connect(url) as ws:
        ok = '{"type":"ping","t":1,"a":' + "[" * 31 + "]" * 31 + "}"   # depth 32
        assert (await rpc(ws, ok))["type"] == "pong"
        bad = '{"type":"ping","t":1,"a":' + "[" * 32 + "]" * 32 + "}"  # depth 33
        assert await rpc(ws, bad) == {"type": "error", "message": "invalid json"}
        deep = "[" * 100000
        assert await rpc(ws, deep) == {"type": "error", "message": "invalid json"}
        # brackets inside strings do not count
        s = '{"type":"ping","t":2,"a":"' + "[" * 100 + '"}'
        assert (await rpc(ws, s))["t"] == 2


async def test_pong_echoes_only_integer_t():
    server, url = await start()
    async with server, connect(url) as ws:
        assert await rpc(ws, {"type": "ping", "t": 5}) == {"type": "pong", "t": 5}
        for t in ("x", 1.5, True, None, [1]):
            assert await rpc(ws, {"type": "ping", "t": t}) == {"type": "pong", "t": 0}
        assert await rpc(ws, {"type": "ping"}) == {"type": "pong", "t": 0}


async def test_origin_and_host_rejected():
    server, url = await start()
    async with server:
        with pytest.raises(InvalidStatus) as e:
            async with connect(url, origin="http://evil.example"):
                pass
        assert e.value.response.status_code == 403
        port = int(url.rsplit(":", 1)[1])
        async def raw(host):
            r, w = await asyncio.open_connection("127.0.0.1", port)
            w.write((f"GET / HTTP/1.1\r\nHost: {host}\r\nUpgrade: websocket\r\n"
                     "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                     "Sec-WebSocket-Version: 13\r\n\r\n").encode())
            line = await asyncio.wait_for(r.readline(), 2)
            w.close()
            return line
        assert b" 403 " in await raw("evil.example")
        assert b" 403 " in await raw("evil.example:7970")
        assert b" 101 " in await raw(f"127.0.0.1:{port}")
        assert b" 101 " in await raw(f"localhost:{port}")


async def test_probe_per_field_order():
    server, url = await start()
    async with server, connect(url) as ws:
        r = await rpc(ws, cmd("kh1.probe", {"offset": -1, "length": "x"}))
        assert r["error"] == "out of range"
        r = await rpc(ws, cmd("kh1.probe", {"offset": "x", "length": 999}))
        assert r["error"] == "bad command"


async def test_busy_checked_before_not_safe_now():
    server, url = await start()
    async with server, connect(url) as ws:
        press = cmd("kh1.pressInput", {"byteIndex": 0, "mask": 16, "durationMs": 1000})
        assert (await rpc(ws, press))["ok"] is True
        server.mock.unsafe = True
        assert (await rpc(ws, press))["error"] == "busy"
        server.mock.press = None
        assert (await rpc(ws, press))["error"] == "not safe now"


async def test_subscribe_defaults_and_topics():
    server, url = await start()
    async with server:
        async with connect(url) as ws:  # default 5 Hz
            await ws.send(json.dumps({"type": "subscribe", "topics": ["kh1.spike"]}))
            assert 4 <= len(await collect_states(ws, 1.0)) <= 6
        async with connect(url) as ws:  # float rate accepted and clamped
            await ws.send(json.dumps({"type": "subscribe", "topics": ["kh1.spike"], "rateHz": 99.5}))
            assert 8 <= len(await collect_states(ws, 1.0)) <= 12
        async with connect(url) as ws:  # wrong / missing topics: no stream
            for m in ({"type": "subscribe", "topics": ["other"]}, {"type": "subscribe"}):
                await ws.send(json.dumps(m))
                assert await collect_states(ws, 0.5) == []
            await ws.send(json.dumps({"type": "subscribe", "topics": ["kh1.spike"]}))
            assert await collect_states(ws, 0.5)
            await ws.send(json.dumps({"type": "subscribe", "topics": []}))  # unsubscribes
            await collect_states(ws, 0.3)
            assert await collect_states(ws, 0.5) == []


async def test_log_wording_unsupported_and_press_line():
    server, url = await start(unsupported=True)
    async with server, connect(url) as ws:
        r = await rpc(ws, cmd("kh1.log", {}))
        assert "build egs-1.0.0.10 not supported" in r["data"]["lines"]
    server, url = await start()
    async with server, connect(url) as ws:
        await rpc(ws, fixture("command_press.json"))
        r = await rpc(ws, cmd("kh1.log", {}))
        assert "press start byte 0 mask 16 for 100 ms" in r["data"]["lines"]


async def test_stall_after_stops_states_but_keeps_pongs():
    server, url = await start(stall_after=0.6)
    async with server, connect(url) as ws:
        await ws.send(json.dumps(fixture("subscribe.json")))
        before = await collect_states(ws, 0.5)
        assert len(before) >= 1
        await asyncio.sleep(0.3)  # past the stall point
        await collect_states(ws, 0.2)  # drain anything in flight
        assert await collect_states(ws, 0.8) == []
        assert await rpc(ws, {"type": "ping", "t": 9}) == {"type": "pong", "t": 9}
        log = await rpc(ws, cmd("kh1.log", {}))
        assert log["ok"] is True


@pytest.mark.parametrize("bi,mask", [(1, 0x40), (1, 0xFF), (0, 0x10), (0, 0xF7), (1, 0)])
async def test_press_accepts_mapped_masks(bi, mask):
    server, url = await start()
    async with server, connect(url) as ws:
        r = await rpc(ws, cmd("kh1.pressInput", {"byteIndex": bi, "mask": mask, "durationMs": 16}))
        assert r["ok"] is True


# ---- kh1.watch -----------------------------------------------------------------------------------

BATTLE = 0x2D5CC4C
CHESTS = 0x2DE992C


def watch(*regions, id="w1"):
    return cmd("kh1.watch", {"regions": [{"offset": o, "length": n} for o, n in regions]}, id=id)


async def next_state(ws, want_regions=True, tries=10):
    """The next state frame; with want_regions, skips frames sent before the watch landed."""
    for _ in range(tries):
        m = json.loads(await asyncio.wait_for(ws.recv(), 2))
        if m["type"] == "state" and ("regions" in m["data"] or not want_regions):
            return m
    raise AssertionError("no matching state frame")


async def test_watch_fixture_and_state_regions():
    server, url = await start(chests=bytes.fromhex("0102"))
    async with server, connect(url) as ws:
        assert await rpc(ws, fixture("command_watch.json")) == fixture("result_watch.json")
        await ws.send(json.dumps(fixture("subscribe.json")))
        s = await next_state(ws)
        ref = fixture("state_watch.json")["data"]
        assert set(s["data"]) == set(ref)
        assert s["data"]["regions"] == [
            {"offset": BATTLE, "hex": "37000000390000000500000005000000"},
            {"offset": CHESTS, "hex": "0102000000000000"},
        ]
        assert ref["regions"][0] == s["data"]["regions"][0]
        assert (s["data"]["soraHp"], s["data"]["soraHpMax"]) == (55, 57)


async def test_watch_chests_default_zero_and_probe_agrees():
    server, url = await start()
    async with server, connect(url) as ws:
        r = await rpc(ws, cmd("kh1.probe", {"offset": BATTLE, "length": 16}))
        assert r["data"]["hex"] == "37000000390000000500000005000000"
        assert (await rpc(ws, watch((CHESTS, 256))))["data"] == {"count": 1}
        await ws.send(json.dumps(fixture("subscribe.json")))
        s = await next_state(ws)
        assert s["data"]["regions"] == [{"offset": CHESTS, "hex": "00" * 256}]


async def test_watch_is_per_connection_and_dropped_on_reconnect():
    server, url = await start()
    async with server:
        async with connect(url) as a, connect(url) as b:
            assert (await rpc(a, watch((BATTLE, 16))))["ok"] is True
            for ws in (a, b):
                await ws.send(json.dumps(fixture("subscribe.json")))
            assert "regions" in (await next_state(a))["data"]
            for s in await collect_states(b, 0.5):
                assert "regions" not in s["data"]
        async with connect(url) as a2:
            await a2.send(json.dumps(fixture("subscribe.json")))
            states = await collect_states(a2, 0.5)
            assert states and all("regions" not in s["data"] for s in states)


async def test_watch_empty_list_clears():
    server, url = await start()
    async with server, connect(url) as ws:
        await rpc(ws, watch((BATTLE, 4)))
        r = await rpc(ws, watch())
        assert r == {"type": "result", "id": "w1", "ok": True, "error": None, "data": {"count": 0}}
        await ws.send(json.dumps(fixture("subscribe.json")))
        states = await collect_states(ws, 0.5)
        assert states and all("regions" not in s["data"] for s in states)


@pytest.mark.parametrize("args,err", [
    ({"regions": [{"offset": i, "length": 1} for i in range(17)]}, "out of range"),
    ({"regions": [{"offset": BATTLE, "length": 0}]}, "out of range"),
    ({"regions": [{"offset": BATTLE, "length": 257}]}, "out of range"),
    ({"regions": [{"offset": i * 256, "length": 256} for i in range(8)] + [{"offset": 2048, "length": 1}]},
     "out of range"),  # 2049 bytes
    ({"regions": [{"offset": IMAGE_SIZE - 15, "length": 16}]}, "out of range"),
    ({"regions": [{"offset": -1, "length": 4}]}, "out of range"),
    ({}, "bad command"),
    ({"regions": 5}, "bad command"),
    ({"regions": [1]}, "bad command"),
    ({"regions": [{"offset": "0x2D5CC4C", "length": 16}]}, "bad command"),
    ({"regions": [{"offset": 1.5, "length": 16}]}, "bad command"),
    ({"regions": [{"offset": True, "length": 16}]}, "bad command"),
    ({"regions": [{"offset": BATTLE}]}, "bad command"),
])
async def test_watch_errors(args, err):
    server, url = await start()
    async with server, connect(url) as ws:
        r = await rpc(ws, cmd("kh1.watch", args))
        assert r == {"type": "result", "id": "c1", "ok": False, "error": err, "data": None}


async def test_watch_accepts_16_regions_and_2048_bytes():
    server, url = await start()
    async with server, connect(url) as ws:
        r = await rpc(ws, watch(*[(i * 256, 128) for i in range(16)]))
        assert r["data"] == {"count": 16}
        r = await rpc(ws, watch(*[(i * 256, 256) for i in range(8)]))
        assert r["data"] == {"count": 8}


async def test_watch_failure_keeps_previous_watch():
    server, url = await start()
    async with server, connect(url) as ws:
        await rpc(ws, watch((BATTLE, 4)))
        assert (await rpc(ws, watch((BATTLE, 300))))["error"] == "out of range"
        await ws.send(json.dumps(fixture("subscribe.json")))
        assert (await next_state(ws))["data"]["regions"] == [{"offset": BATTLE, "hex": "37000000"}]


async def test_watch_unsupported_build():
    server, url = await start(unsupported=True)
    async with server, connect(url) as ws:
        r = await rpc(ws, fixture("command_watch.json"))
        assert r["error"] == "unsupported build"


async def test_world_and_room_flags_set_the_reported_location():
    server, url = await start(world=15, room=6)
    async with server, connect(url) as ws:
        await ws.send(json.dumps(fixture("subscribe.json")))
        d = (await collect_states(ws, 0.5))[0]["data"]
        assert d["world"] == 15 and d["worldAlt"] == 15 and d["room"] == 6


# ---- kh1.pressSequence ---------------------------------------------------------------------------


def step(bi, mask, hold=20, gap=20, **extra):
    return {"byteIndex": bi, "mask": mask, "holdMs": hold, "gapMs": gap, **extra}


def seq(*steps, require=None, id="s1"):
    args = {"steps": list(steps)}
    if require is not None:
        args["require"] = require
    return cmd("kh1.pressSequence", args, id=id)


async def result_for(ws, cid, timeout=3):
    """The result frame for `cid`, skipping state frames and other results."""
    loop = asyncio.get_running_loop()
    end = loop.time() + timeout
    while True:
        m = json.loads(await asyncio.wait_for(ws.recv(), max(0.01, end - loop.time())))
        if m["type"] == "result" and m["id"] == cid:
            return m


async def probe_byte(ws, off):
    return int((await rpc(ws, cmd("kh1.probe", {"offset": off, "length": 1}, id="pb")))["data"]["hex"], 16)


async def test_sequence_defaults_in_fake_memory():
    server, url = await start()
    async with server, connect(url) as ws:
        r = await rpc(ws, cmd("kh1.probe", {"offset": SHORTCUTS, "length": 3}))
        assert r["data"]["hex"] == "000103"  # Fire, Blizzard, Cure
        assert await probe_byte(ws, AB_SWAP) == 0
        r = await rpc(ws, cmd("kh1.probe", {"offset": MENU, "length": 0x28}))
        assert r["data"]["hex"] == "00" * 0x28
        r = await rpc(ws, cmd("kh1.probe", {"offset": 0x2D5CC4C, "length": 4}))
        assert int.from_bytes(bytes.fromhex(r["data"]["hex"]), "little") == 55  # Sora HP > 0


async def test_sequence_fixture_casts_cure_through_the_picker():
    server, url = await start()
    server.mock.mem[SHORTCUTS] = 3  # Cure on shortcut 1 (LB+Y), as the fixture requires
    async with server, connect(url) as ws:
        await ws.send(json.dumps(fixture("command_sequence.json")))
        with pytest.raises(asyncio.TimeoutError):  # deferred: nothing comes back while it runs
            await asyncio.wait_for(ws.recv(), 0.1)
        assert json.loads(await asyncio.wait_for(ws.recv(), 2)) == fixture("result_sequence.json")
        assert await probe_byte(ws, MENU) == 0  # the confirm closed the picker
        lines = (await rpc(ws, cmd("kh1.log", {})))["data"]["lines"]
        assert "sequence c5 start: 2 steps" in lines
        assert "mock game: shortcut 1 opened the target picker" in lines
        assert "mock game: target picker confirmed" in lines
        assert any(l.startswith("sequence c5 end (done: 2 pressed, 0 skipped) after ") for l in lines)


async def test_sequence_fixture_refused_when_require_fails():
    server, url = await start()  # default shortcut 1 is Fire, not Cure
    async with server, connect(url) as ws:
        r = await rpc(ws, fixture("command_sequence.json"))
        assert r == {"type": "result", "id": "c5", "ok": False, "error": "not safe now", "data": None}
        server.mock.mem[SHORTCUTS] = 3
        server.mock.mem[AB_SWAP] = 1  # swapped: the plan's confirm button would be wrong
        assert (await rpc(ws, fixture("command_sequence.json")))["error"] == "not safe now"


async def test_sequence_until_timeout_matches_fixture():
    server, url = await start()
    async with server, connect(url) as ws:
        args = fixture("command_sequence.json")["args"]
        del args["require"]  # shortcut 1 is Fire: no target picker, so the until never holds
        t0 = asyncio.get_running_loop().time()
        await ws.send(json.dumps(cmd("kh1.pressSequence", args, id="c5")))
        assert await result_for(ws, "c5") == fixture("result_sequence_timeout.json")
        assert asyncio.get_running_loop().time() - t0 >= 0.6  # 100 ms hold + 500 ms timeout
        lines = (await rpc(ws, cmd("kh1.log", {})))["data"]["lines"]
        assert any(l.startswith("sequence c5 end (timeout at step 1) after ") for l in lines)


async def test_sequence_default_cure_on_lb_a_and_onlyif_skip():
    server, url = await start()
    async with server, connect(url) as ws:
        picker = {"offset": MENU, "value": 4}
        # Shortcut 3 (LB+A) holds Cure: the picker opens, the until holds, A confirms.
        await ws.send(json.dumps(seq(step(1, 0x44, until={**picker, "timeoutMs": 300}), step(1, 0x40, onlyIf=picker))))
        assert (await result_for(ws, "s1"))["data"] == {"steps": 2, "skipped": 0}
        # Shortcut 1 (LB+Y) holds Fire: no picker, so the confirm step is skipped.
        await ws.send(json.dumps(seq(step(1, 0x14), step(1, 0x40, onlyIf=picker), id="s2")))
        assert await result_for(ws, "s2") == {"type": "result", "id": "s2", "ok": True, "error": None,
                                               "data": {"steps": 1, "skipped": 1}}


async def test_sequence_busy_both_ways():
    server, url = await start()
    async with server, connect(url) as ws:
        await ws.send(json.dumps(seq(step(1, 0x40, hold=200, gap=50))))
        r = await rpc(ws, seq(step(1, 0x40), id="s2"))
        assert r == {"type": "result", "id": "s2", "ok": False, "error": "busy", "data": None}
        r = await rpc(ws, cmd("kh1.pressInput", {"byteIndex": 1, "mask": 0x40, "durationMs": 16}, id="p"))
        assert r["error"] == "busy"
        assert (await result_for(ws, "s1"))["ok"] is True
        assert (await rpc(ws, cmd("kh1.pressInput", {"byteIndex": 1, "mask": 0x40, "durationMs": 200}, id="p")))["ok"]
        assert (await rpc(ws, seq(step(1, 0x40), id="s3")))["error"] == "busy"


async def test_sequence_not_safe_at_start_and_mid_run():
    server, url = await start(unsafe=True)
    async with server, connect(url) as ws:
        assert (await rpc(ws, seq(step(1, 0x40))))["error"] == "not safe now"
    server, url = await start()
    async with server, connect(url) as ws:
        await ws.send(json.dumps(seq(step(1, 0x40, hold=500))))
        await asyncio.sleep(0.1)
        server.mock.unsafe = True  # the player opens the menu mid-press
        r = await result_for(ws, "s1")
        assert r == {"type": "result", "id": "s1", "ok": False, "error": "not safe now", "data": None}
        assert server.mock.seq_pad is None  # buttons released


async def test_sequence_press_shows_in_input_bytes():
    server, url = await start()
    async with server, connect(url) as ws:
        await ws.send(json.dumps({"type": "subscribe", "topics": ["kh1.spike"], "rateHz": 10}))
        await ws.send(json.dumps(seq(step(1, 0x40, hold=400, gap=16))))
        seen = set()
        while True:
            m = json.loads(await asyncio.wait_for(ws.recv(), 2))
            if m["type"] == "state":
                seen.add(m["data"]["inputBytes"])
            elif m["type"] == "result":
                break
        assert "00400000" in seen


@pytest.mark.parametrize("args,err", [
    ({}, "bad command"),
    ({"steps": 5}, "bad command"),
    ({"steps": [1]}, "bad command"),
    ({"steps": []}, "out of range"),
    ({"steps": [step(1, 0x40, 16, 16)] * 17}, "out of range"),
    ({"steps": [step(1, 0x40, 156, 156)] * 16}, None),  # 4992 ms
    ({"steps": [{"byteIndex": 1, "mask": 0x40, "holdMs": 100}]}, "bad command"),
    ({"steps": [step("1", 0x40)]}, "bad command"),
    ({"steps": [step(0, 0x08)]}, "out of range"),  # start
    ({"steps": [step(0, 0x18)]}, "out of range"),
    ({"steps": [step(2, 0x40)]}, "out of range"),
    ({"steps": [step(1, 256)]}, "out of range"),
    ({"steps": [step(1, 0, 16, 16)]}, None),  # a plain wait
    ({"steps": [step(1, 0x40, 15, 100)]}, "out of range"),
    ({"steps": [step(1, 0x40, 1001, 100)]}, "out of range"),
    ({"steps": [step(1, 0x40, 100, 15)]}, "out of range"),
    ({"steps": [step(1, 0x40, 100, 1001)]}, "out of range"),
    ({"steps": [step(1, 0x40, 1000, 1000)] * 3}, "out of range"),  # 6000 ms
    ({"steps": [step(1, 0x40, 1000, 1000, until={"offset": 0, "value": 1, "timeoutMs": 3001})]}, "out of range"),
    ({"steps": [step(1, 0x40, 100, 100, until={"offset": 0, "value": 1, "timeoutMs": 15})]}, "out of range"),
    ({"steps": [step(1, 0x40, until={"offset": 0, "value": 1})]}, "bad command"),  # no timeoutMs
    ({"steps": [step(1, 0x40, until=4)]}, "bad command"),
    ({"steps": [step(1, 0x40, onlyIf=[])]}, "bad command"),
    ({"steps": [step(1, 0x40, onlyIf={"offset": 0})]}, "bad command"),
    ({"steps": [step(1, 0x40, onlyIf={"offset": 0, "value": 1, "mask": "ff"})]}, "bad command"),
    ({"steps": [step(1, 0x40, onlyIf={"offset": IMAGE_SIZE, "value": 0})]}, "out of range"),
    ({"steps": [step(1, 0x40, onlyIf={"offset": -1, "value": 0})]}, "out of range"),
    ({"steps": [step(1, 0x40, onlyIf={"offset": 0, "value": 256})]}, "out of range"),
    ({"steps": [step(1, 0x40, onlyIf={"offset": 0, "value": 3, "mask": 1})]}, "out of range"),
    ({"steps": [step(1, 0x40, onlyIf={"offset": 0, "value": 0, "mask": 0})]}, "out of range"),
    ({"require": 1, "steps": [step(1, 0x40)]}, "bad command"),
    ({"require": [1], "steps": [step(1, 0x40)]}, "bad command"),
    ({"require": [{"offset": i, "value": 0} for i in range(17)], "steps": [step(1, 0x40)]}, "out of range"),
    ({"require": [{"offset": IMAGE_SIZE, "value": 0}], "steps": [step(1, 0x40)]}, "out of range"),
])
async def test_sequence_arg_validation(args, err):
    server, url = await start()
    async with server, connect(url) as ws:
        await ws.send(json.dumps(cmd("kh1.pressSequence", args)))
        if err is not None:
            assert await result_for(ws, "c1") == {"type": "result", "id": "c1", "ok": False, "error": err, "data": None}
            return
        # Accepted: refusals answer at once, so nothing in 100 ms means it is running; a long one is ended early.
        try:
            r = json.loads(await asyncio.wait_for(ws.recv(), 0.1))
        except asyncio.TimeoutError:
            server.mock.unsafe = True
            r = await result_for(ws, "c1")
            assert r["error"] == "not safe now"
        else:
            assert r["ok"] is True
