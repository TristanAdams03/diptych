"""Protocol v0 mock game server for TTW DualScreen (dev tool, binds 127.0.0.1 only)."""
import argparse
import asyncio
import json
import time
from http import HTTPStatus

from websockets.asyncio.server import serve as ws_serve
from websockets.exceptions import ConnectionClosed

WORLDS = {
    "dc": dict(dc=1, nv=0, worldspace="Wasteland", cell="Megaton", ttw=True,
               pos=[-4113.5, 5291.0, 1024.25]),
    "nv": dict(dc=0, nv=1, worldspace="WastelandNV", cell="Goodsprings", ttw=True,
               pos=[-70160.0, 2872.5, 8640.0]),
    "none": dict(dc=None, nv=None, worldspace="WastelandNV", cell="Goodsprings", ttw=False,
                 pos=[-70160.0, 2872.5, 8640.0]),
}
COLORS = {"hudMain": [255, 182, 66], "hudAlt": [255, 67, 42], "terminal": [33, 231, 121],
          "pipboy": [255, 182, 66], "menu": [255, 182, 66]}
FRAME_HZ = 60
ALLOWED_HOSTS = {"127.0.0.1", "localhost"}


class Mock:
    def __init__(self, stall_after, world, no_save):
        self.stall_after = stall_after
        self.world = WORLDS[world]
        self.no_save = no_save
        self.t0 = time.monotonic()

    def elapsed(self):
        return time.monotonic() - self.t0

    def stalled(self):
        return self.stall_after is not None and self.elapsed() >= self.stall_after

    def welcome(self):
        return {"type": "welcome", "plugin": "0.0.1-spike", "protocol": 0,
                "game": "FalloutNV 1.4.0.525", "xnvse": "6.4.9",
                "ttw": self.world["ttw"], "features": ["spike"]}

    def state(self):
        e = self.elapsed()
        w = self.world
        data = {"frame": int(e * FRAME_HZ), "ms": int(e * 1000) + 205750,
                "saveLoaded": not self.no_save}
        if self.no_save:
            data.update(hp=None, hpMax=None, hardcore=None, bInDCWasteland=None,
                        bInNVWasteland=None, worldspace=None, cell=None,
                        interior=None, colors=None)
        else:
            data.update(hp=245.0, hpMax=280.0, hardcore=False, bInDCWasteland=w["dc"],
                        bInNVWasteland=w["nv"], worldspace=w["worldspace"], cell=w["cell"],
                        interior=False, colors=COLORS)
        return {"type": "state", "topic": "spike", "data": data}

    def command(self, msg):
        cid, name = msg.get("id"), msg.get("name")
        def res(ok, error=None, data=None):
            return {"type": "result", "id": cid, "ok": ok, "error": error, "data": data}
        if name not in ("spike.equipFirstWeapon", "spike.dumpWorld"):
            return res(False, "unknown command")
        if self.no_save:
            return res(False, "no save loaded")
        if name == "spike.equipFirstWeapon":
            return res(True, data={"formId": "0001A6B9", "name": "Lincoln's Repeater"})
        w = self.world
        # Same shape as the plugin (plugin/src/game/commands.cpp NvseGame::dumpWorld).
        return res(True, data={"worldspace": w["worldspace"], "parentWorldspace": None,
                               "cell": w["cell"], "interior": False, "pos": list(w["pos"])})

    async def stream(self, ws, rate):
        try:
            while True:
                await asyncio.sleep(1.0 / rate)
                if not self.stalled():
                    await ws.send(json.dumps(self.state()))
        except ConnectionClosed:
            pass

    async def handler(self, ws):
        task = None
        try:
            async for raw in ws:
                try:
                    msg = json.loads(raw)
                    if not isinstance(msg, dict):
                        raise ValueError
                except ValueError:
                    await ws.send(json.dumps({"type": "error", "message": "invalid json"}))
                    continue
                t = msg.get("type")
                if t == "hello":
                    out = self.welcome()
                elif t == "ping":
                    out = {"type": "pong", "t": msg.get("t")}
                elif t == "command":
                    out = self.command(msg)
                elif t == "subscribe":
                    try:
                        rate = max(1, min(10, int(msg.get("rateHz", 1))))
                    except (TypeError, ValueError):
                        rate = 1
                    if task:
                        task.cancel()
                    task = asyncio.create_task(self.stream(ws, rate))
                    continue  # no ack
                else:
                    out = {"type": "error", "message": "unknown type"}
                await ws.send(json.dumps(out))
        except ConnectionClosed:
            pass
        finally:
            if task:
                task.cancel()


def process_request(connection, request):
    host = (request.headers.get("Host") or "").rsplit(":", 1)[0]
    if "Origin" in request.headers or host not in ALLOWED_HOSTS:
        return connection.respond(HTTPStatus.FORBIDDEN, "forbidden\n")
    return None


async def serve(port=7960, stall_after=None, world="dc", no_save=False):
    mock = Mock(stall_after, world, no_save)
    return await ws_serve(mock.handler, "127.0.0.1", port, process_request=process_request)


async def main():
    p = argparse.ArgumentParser()
    p.add_argument("--port", type=int, default=7960)
    p.add_argument("--stall-after", type=float, default=None)
    p.add_argument("--world", choices=list(WORLDS), default="dc")
    p.add_argument("--no-save", action="store_true")
    a = p.parse_args()
    server = await serve(a.port, a.stall_after, a.world, a.no_save)
    print(f"mock listening on ws://127.0.0.1:{a.port}")
    await server.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())
