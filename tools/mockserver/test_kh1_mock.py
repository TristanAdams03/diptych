import asyncio
import json
from pathlib import Path

import pytest
from websockets.asyncio.client import connect
from websockets.exceptions import InvalidStatus

from kh1_mock import IMAGE_SIZE, serve

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
                  cmd("kh1.pressInput", {"byteIndex": 0, "mask": 1, "durationMs": 100})):
            r = await rpc(ws, c)
            assert r == {"type": "result", "id": "c1", "ok": False,
                         "error": "unsupported build", "data": None}
        r = await rpc(ws, cmd("kh1.log", {}))
        assert r["ok"] is True
        assert r["data"]["lines"][0] == "diptych_kh1 0.0.1-spike loaded"
        assert "not supported" in r["data"]["lines"][1]


async def test_log_pinned_startup_lines_first():
    server, url = await start()
    async with server, connect(url) as ws:
        r = await rpc(ws, fixture("command_log.json"))
        assert r["ok"] and r["error"] is None
        assert r["data"]["lines"][:3] == fixture("result_log.json")["data"]["lines"]


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
    ({"byteIndex": 4, "mask": 1, "durationMs": 100}, "out of range"),
    ({"byteIndex": -1, "mask": 1, "durationMs": 100}, "out of range"),
    ({"byteIndex": 0, "mask": 256, "durationMs": 100}, "out of range"),
    ({"byteIndex": 0, "mask": 1, "durationMs": 15}, "out of range"),
    ({"byteIndex": 0, "mask": 1, "durationMs": 1001}, "out of range"),
    ({"byteIndex": 0, "mask": 1}, "bad command"),
    ({"byteIndex": 9, "mask": "x", "durationMs": 100}, "out of range"),  # per-field order
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
