import asyncio
import json

import pytest
from websockets.asyncio.client import connect
from websockets.exceptions import InvalidStatus

from ttw_mock import serve


async def start(**kw):
    args = dict(stall_after=None, world="dc", no_save=False)
    args.update(kw)
    server = await serve(0, **args)
    port = server.sockets[0].getsockname()[1]
    return server, f"ws://127.0.0.1:{port}"


async def rpc(ws, obj):
    await ws.send(obj if isinstance(obj, str) else json.dumps(obj))
    return json.loads(await asyncio.wait_for(ws.recv(), 2))


async def collect_states(ws, seconds):
    out = []
    end = asyncio.get_running_loop().time() + seconds
    while (left := end - asyncio.get_running_loop().time()) > 0:
        try:
            m = json.loads(await asyncio.wait_for(ws.recv(), left))
        except asyncio.TimeoutError:
            break
        if m["type"] == "state":
            out.append(m["data"])
    return out


async def test_hello_welcome():
    server, url = await start()
    async with server, connect(url) as ws:
        w = await rpc(ws, {"type": "hello", "client": "t", "protocol": 0})
        assert w["type"] == "welcome" and w["protocol"] == 0
        assert w["ttw"] is True and w["features"] == ["spike"]


async def test_subscribe_streams_increasing_frames():
    server, url = await start()
    async with server, connect(url) as ws:
        await ws.send(json.dumps({"type": "subscribe", "topics": ["spike"], "rateHz": 5}))
        states = await collect_states(ws, 1.0)
        assert len(states) >= 4
        frames = [s["frame"] for s in states]
        assert all(b > a for a, b in zip(frames, frames[1:]))
        assert states[0]["bInDCWasteland"] == 1 and states[0]["cell"] == "Megaton"


async def test_rate_clamped():
    server, url = await start()
    async with server, connect(url) as ws:
        await ws.send(json.dumps({"type": "subscribe", "topics": ["spike"], "rateHz": 1000}))
        assert len(await collect_states(ws, 1.0)) <= 12


async def test_stall_stops_state_but_pong_works():
    server, url = await start(stall_after=0.5)
    async with server, connect(url) as ws:
        await ws.send(json.dumps({"type": "subscribe", "topics": ["spike"], "rateHz": 10}))
        await asyncio.sleep(0.8)
        while True:  # drain
            try:
                await asyncio.wait_for(ws.recv(), 0.05)
            except asyncio.TimeoutError:
                break
        assert await collect_states(ws, 0.5) == []
        assert await rpc(ws, {"type": "ping", "t": 5}) == {"type": "pong", "t": 5}


async def test_no_save_command_and_state():
    server, url = await start(no_save=True)
    async with server, connect(url) as ws:
        r = await rpc(ws, {"type": "command", "id": "c1", "name": "spike.equipFirstWeapon", "args": {}})
        assert r == {"type": "result", "id": "c1", "ok": False, "error": "no save loaded", "data": None}
        await ws.send(json.dumps({"type": "subscribe", "topics": ["spike"], "rateHz": 5}))
        s = (await collect_states(ws, 0.5))[0]
        assert s["saveLoaded"] is False


async def test_commands_ok_and_unknown():
    server, url = await start()
    async with server, connect(url) as ws:
        r = await rpc(ws, {"type": "command", "id": "c1", "name": "spike.equipFirstWeapon", "args": {}})
        assert r["ok"] is True and r["error"] is None and r["data"]["formId"] == "0001A6B9"
        r = await rpc(ws, {"type": "command", "id": "c2", "name": "spike.dumpWorld", "args": {}})
        assert r["ok"] is True and r["error"] is None
        d = r["data"]
        assert set(d) == {"worldspace", "parentWorldspace", "cell", "interior", "pos"}
        assert d["worldspace"] == "Wasteland" and d["parentWorldspace"] is None
        assert d["cell"] == "Megaton" and d["interior"] is False
        assert isinstance(d["pos"], list) and len(d["pos"]) == 3
        r = await rpc(ws, {"type": "command", "id": "c3", "name": "nope", "args": {}})
        assert r["ok"] is False and r["error"] == "unknown command" and r["id"] == "c3"


async def test_errors_keep_connection_open():
    server, url = await start()
    async with server, connect(url) as ws:
        assert await rpc(ws, "{not json") == {"type": "error", "message": "invalid json"}
        assert await rpc(ws, {"type": "wat"}) == {"type": "error", "message": "unknown type"}
        assert await rpc(ws, {"type": "ping", "t": 9}) == {"type": "pong", "t": 9}


@pytest.mark.parametrize("world,dc,nv,ws_,cell,ttw", [
    ("dc", 1, 0, "Wasteland", "Megaton", True),
    ("nv", 0, 1, "WastelandNV", "Goodsprings", True),
    ("none", None, None, "WastelandNV", "Goodsprings", False),
])
async def test_worlds(world, dc, nv, ws_, cell, ttw):
    server, url = await start(world=world)
    async with server, connect(url) as ws:
        w = await rpc(ws, {"type": "hello", "client": "t", "protocol": 0})
        assert w["ttw"] is ttw
        await ws.send(json.dumps({"type": "subscribe", "topics": ["spike"], "rateHz": 5}))
        s = (await collect_states(ws, 0.5))[0]
        assert (s["bInDCWasteland"], s["bInNVWasteland"], s["worldspace"], s["cell"]) == (dc, nv, ws_, cell)


async def test_rejects_origin_and_bad_host():
    server, url = await start()
    async with server:
        with pytest.raises(InvalidStatus):
            async with connect(url, origin="http://evil.example"):
                pass
        with pytest.raises(InvalidStatus):
            async with connect(url, additional_headers={"Host": "evil.example"}):
                pass
        async with connect(url.replace("127.0.0.1", "localhost")) as ws:
            assert await rpc(ws, {"type": "ping", "t": 1}) == {"type": "pong", "t": 1}
