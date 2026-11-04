"""Protocol v0 mock of the Diptych KH1 mod (dev tool, binds 127.0.0.1 only).

Mirrors protocol/kh1/PROTOCOL.md so the Android app can be tested without the game.
"""
import argparse
import asyncio
import json
import time
from http import HTTPStatus

from websockets.asyncio.server import serve as ws_serve
from websockets.exceptions import ConnectionClosed

IMAGE_SIZE = 0x3000000  # fake module image size for kh1.probe bounds
MAX_DEPTH = 32
MAX_ARGS_BYTES = 65536
ALLOWED_HOSTS = {"127.0.0.1", "localhost"}
STARTUP_LINES = ["diptych_kh1 0.0.1-spike loaded"]
# Fake memory: known state addresses hold the values the mock reports; the first
# 16 MiB (the "code" region) is a deterministic pattern; the rest reads as zero.
FAKE_BYTES = {0x233FE94: 3, 0x233FE8C: 2, 0x233FE84: 3, 0x2D5CC4C: 52, 0x2DE9366: 60}


def fake_byte(off):
    if off in FAKE_BYTES:
        return FAKE_BYTES[off]
    return (off * 31 + 7) & 0xFF if off < 0x1000000 else 0


def is_int(v):
    return isinstance(v, int) and not isinstance(v, bool)


def too_deep(raw):
    """True if objects/arrays nest deeper than MAX_DEPTH (frame object = level 1)."""
    depth = 0
    in_str = esc = False
    for ch in raw:
        if in_str:
            if esc:
                esc = False
            elif ch == "\\":
                esc = True
            elif ch == '"':
                in_str = False
        elif ch == '"':
            in_str = True
        elif ch in "[{":
            depth += 1
            if depth > MAX_DEPTH:
                return True
        elif ch in "]}":
            depth -= 1
    return False


class Mock:
    def __init__(self, unsupported, unsafe):
        self.unsupported = unsupported
        self.unsafe = unsafe
        self.t0 = time.monotonic()
        self.seq = 0
        self.press = None  # (byteIndex, mask, end_time)
        self.extra_log = []

    def ms(self):
        return int((time.monotonic() - self.t0) * 1000) + 205750

    def build(self):
        return "egs-1.0.0.10" if self.unsupported else "steam-1.0.0.2"

    def welcome(self):
        return {"type": "welcome", "plugin": "0.0.1-spike", "protocol": 0, "gameId": "kh1",
                "gameBuild": self.build(), "supported": not self.unsupported,
                "features": ["spike"]}

    def press_active(self):
        if self.press and time.monotonic() >= self.press[2]:
            self.press = None
        return self.press

    def state(self):
        self.seq += 1
        d = dict(seq=self.seq, ms=self.ms())
        if self.unsupported:
            d.update(dict.fromkeys(("world", "room", "worldAlt", "soraHp", "soraHpMax", "inMenu",
                                    "inCutscene", "inGummi", "inputBytes")))
        else:
            inp = bytearray(4)
            p = self.press_active()
            if p:
                inp[p[0]] |= p[1]
            d.update(world=3, room=2, worldAlt=3, soraHp=52, soraHpMax=60, inMenu=self.unsafe,
                     inCutscene=False, inGummi=False, inputBytes=inp.hex())
        return {"type": "state", "topic": "kh1.spike", "data": d}

    def log_lines(self):
        tail = (["build egs-1.0.0.10 unsupported"]
                if self.unsupported else
                ["build steam-1.0.0.2 supported", "server listening on 127.0.0.1:7970"])
        return STARTUP_LINES + tail + self.extra_log

    def command(self, msg):
        cid = msg.get("id")
        cid = cid if isinstance(cid, str) else ""

        def res(ok, error=None, data=None):
            return {"type": "result", "id": cid, "ok": ok, "error": error, "data": data}

        name, args = msg.get("name"), msg.get("args")
        if (not isinstance(msg.get("id"), str) or not isinstance(name, str)
                or not isinstance(args, dict)
                or len(json.dumps(args, separators=(",", ":"), ensure_ascii=False).encode()) > MAX_ARGS_BYTES):
            return res(False, "bad command")
        if name not in ("kh1.probe", "kh1.pressInput", "kh1.log"):
            return res(False, "unknown command")
        if name == "kh1.log":
            return res(True, data={"lines": self.log_lines()})
        if self.unsupported:
            return res(False, "unsupported build")
        if name == "kh1.probe":
            off, n = args.get("offset"), args.get("length")
            if not is_int(off) or not is_int(n):
                return res(False, "bad command")
            if n < 1 or n > 256 or off < 0 or off + n > IMAGE_SIZE:
                return res(False, "out of range")
            return res(True, data={"offset": off,
                                   "hex": bytes(fake_byte(off + i) for i in range(n)).hex()})
        bi, mask, dur = args.get("byteIndex"), args.get("mask"), args.get("durationMs")
        if not (is_int(bi) and is_int(mask) and is_int(dur)):
            return res(False, "bad command")
        if not (0 <= bi <= 3 and 0 <= mask <= 255 and 16 <= dur <= 1000):
            return res(False, "out of range")
        if self.unsafe:
            return res(False, "not safe now")
        if self.press_active():
            return res(False, "busy")
        self.press = (bi, mask, time.monotonic() + dur / 1000)
        self.extra_log.append(f"pressInput byte {bi} mask 0x{mask:02x} for {dur} ms")
        return res(True)

    async def stream(self, ws, rate):
        try:
            while True:
                await asyncio.sleep(1.0 / rate)
                await ws.send(json.dumps(self.state()))
        except ConnectionClosed:
            pass

    def reply(self, raw, ctx):
        """Handle one frame; returns the reply dict or None. ctx['rate'] set on subscribe."""
        err = lambda m: {"type": "error", "message": m}
        if too_deep(raw):
            return err("invalid json")
        try:
            msg = json.loads(raw)
        except (ValueError, RecursionError):
            return err("invalid json")
        t = msg.get("type") if isinstance(msg, dict) else None
        if t == "hello":
            return self.welcome()
        if t == "ping":
            tv = msg.get("t")
            return {"type": "pong", "t": tv} if is_int(tv) else {"type": "pong"}
        if t == "command":
            return self.command(msg)
        if t == "subscribe":
            rh = msg.get("rateHz", 1)
            ctx["rate"] = max(1, min(10, rh)) if is_int(rh) else 1
            return None  # no ack
        return err("unknown type")

    async def handler(self, ws):
        task = None
        ctx = {}
        try:
            async for raw in ws:
                if isinstance(raw, bytes):
                    raw = raw.decode("utf-8", "replace")
                ctx.pop("rate", None)
                out = self.reply(raw, ctx)
                if "rate" in ctx:
                    if task:
                        task.cancel()
                    task = asyncio.create_task(self.stream(ws, ctx["rate"]))
                if out is not None:
                    await ws.send(json.dumps(out))
        except ConnectionClosed:
            pass
        finally:
            if task:
                task.cancel()


def process_request(connection, request):
    hosts = request.headers.get_all("Host")
    host = hosts[0].rsplit(":", 1)[0] if len(hosts) == 1 else None
    if "Origin" in request.headers or host not in ALLOWED_HOSTS:
        return connection.respond(HTTPStatus.FORBIDDEN, "forbidden\n")
    return None


async def serve(port=7970, unsupported=False, unsafe=False, host="127.0.0.1"):
    mock = Mock(unsupported, unsafe)
    return await ws_serve(mock.handler, host, port, process_request=process_request)


async def main():
    p = argparse.ArgumentParser()
    p.add_argument("--port", type=int, default=7970)
    p.add_argument("--unsupported", action="store_true")
    p.add_argument("--unsafe", action="store_true")
    p.add_argument("--host", default="127.0.0.1", help=argparse.SUPPRESS)  # test-only escape hatch
    a = p.parse_args()
    server = await serve(a.port, a.unsupported, a.unsafe, a.host)
    print(f"kh1 mock listening on ws://{a.host}:{a.port}", flush=True)
    await server.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())
