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
PLUGIN_VERSION = "0.2.0"
STARTUP_LINES = [f"diptych_kh1 {PLUGIN_VERSION} loaded"]
# Fake memory: known state addresses hold the values the mock reports; the first
# 16 MiB (the "code" region) is a deterministic pattern; the rest reads as zero.
BATTLE_BLOCK = 0x2D5CC4C  # Sora HP, maxHP, MP, maxMP as u32 (PROTOCOL.md address table)
CHEST_FLAGS = 0x2DE992C   # chest-opened bit table; the mock's bytes come from --chests
FAKE_BYTES = {0x233FE94: 3, 0x233FE8C: 2, 0x233FE84: 3}
# Party in battle-block order (stride 0x100), as read live on the PC: Sora, Donald, Goofy. Sora's HP is 55 (> 0).
for _m, _vals in enumerate(((55, 57, 5, 5), (42, 42, 8, 8), (63, 63, 4, 4))):
    for _i, _v in enumerate(_vals):
        FAKE_BYTES.update({BATTLE_BLOCK + 0x100 * _m + 4 * _i + k: b for k, b in enumerate(_v.to_bytes(4, "little"))})
PARTY_SLOTS = 0x2DE97EF  # slot 1 / slot 2 char ids: 1 Donald, 2 Goofy
FAKE_BYTES.update({PARTY_SLOTS: 1, PARTY_SLOTS + 1: 2})
# Sora stats (0x2DE9364), magic tiers (0x2DE97F2) and stock items (0x2DE97F9) for companion UI testing:
SORA_STATS = 0x2DE9364
FAKE_BYTES.update({
    SORA_STATS + 0: 42,       # Level 42
    SORA_STATS + 5: 38,       # AP 38
    SORA_STATS + 6: 34,       # Strength 34
    SORA_STATS + 7: 32,       # Defense 32
    SORA_STATS + 33: 4,       # 4 item slots
    SORA_STATS + 34: 1,       # Potion equipped
    SORA_STATS + 35: 2,       # Hi-Potion equipped
    SORA_STATS + 36: 3,       # Ether equipped
    SORA_STATS + 50: 91,      # Divine Rose (91)
    SORA_STATS + 112: 0x5F,   # Fire, Blizzard, Thunder, Cure, Gravity, Aero unlocked
    0x2DE97F2 + 0: 3,         # Firaga
    0x2DE97F2 + 1: 2,         # Blizzara
    0x2DE97F2 + 2: 2,         # Thundara
    0x2DE97F2 + 3: 2,         # Cura
    0x2DE97F2 + 4: 2,         # Gravira
    0x2DE97F2 + 6: 2,         # Aerora
    0x2DE97F9 + 1: 6,         # Potion x6
    0x2DE97F9 + 2: 3,         # Hi-Potion x3
    0x2DE97F9 + 3: 4,         # Ether x4
    0x2DE97F9 + 4: 1,         # Elixir x1
    0x2DE97F9 + 142: 2,       # Tent x2
    0x2DEA86E: 60,            # Hollow Bastion story progress 0x3C: Riku I beaten; next: Castle Chapel
})
# M3 action state, as used by kh1.pressSequence `require`/`until`/`onlyIf` (offsets from community scripts, not yet
# checked live; docs/spike/M3-CHECKLIST.md settles them).
SHORTCUTS = 0x2DE9BA4  # 3 x u8 spell index (0 Fire .. 6 Aero), 255 = empty
AB_SWAP = 0x4D8632     # u8: 0 -> confirm is A (PS2 cross 0x40), 1 -> B (circle 0x20)
MENU = 0x2852790       # command-menu bytes 0x2852790..+0x28; byte 0 in this mock: 0 top level, 4 target picker
MENU_SPAN = 0x28
FAKE_BYTES.update({SHORTCUTS: 0, SHORTCUTS + 1: 1, SHORTCUTS + 2: 3, AB_SWAP: 0})  # Fire, Blizzard, Cure; not swapped
FAKE_BYTES.update({MENU + i: 0 for i in range(MENU_SPAN)})
# The mock's model of the game's reaction to presses (assumptions until the PC check):
LB = 0x04                                    # byte 1
SHORTCUT_FACE = {0x10: 0, 0x20: 1, 0x40: 2}  # LB+Y / LB+B / LB+A -> shortcut slot 1 / 2 / 3
NEEDS_TARGET = {3, 6}                        # Cure, Aero (app party.json needsTarget)
PICKER_OPEN = 4                              # placeholder value, as in command_sequence.json
SEQ_TICK = 0.002                             # seconds between the mock's sequence checks

WATCH_MAX_REGIONS, WATCH_MAX_LENGTH, WATCH_MAX_TOTAL = 16, 256, 2048
SEQ_MAX_STEPS, SEQ_MAX_REQUIRE, SEQ_MAX_TOTAL_MS = 16, 16, 5000


def fake_byte(off, chests=b""):
    if 0 <= off - CHEST_FLAGS < len(chests):
        return chests[off - CHEST_FLAGS]
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


class Fail(Exception):
    pass


def int_arg(args, key, lo, hi):
    """Mirror of the mod's int_arg: missing/non-integer -> bad command; outside [lo, hi] -> out of range."""
    v = args.get(key)
    if not is_int(v):
        raise Fail("bad command")
    if v < lo or v > hi:
        raise Fail("out of range")
    return v


def parse_watch(args):
    """Mirror of the mod's parse_watch: [(offset, length)], or raises Fail."""
    regions = args.get("regions")
    if not isinstance(regions, list):
        raise Fail("bad command")
    if len(regions) > WATCH_MAX_REGIONS:
        raise Fail("out of range")
    out, total = [], 0
    for r in regions:
        if not isinstance(r, dict):
            raise Fail("bad command")
        off = int_arg(r, "offset", 0, 2**63 - 1)
        n = int_arg(r, "length", 1, WATCH_MAX_LENGTH)
        if off >= IMAGE_SIZE or n > IMAGE_SIZE - off:
            raise Fail("out of range")
        total += n
        if total > WATCH_MAX_TOTAL:
            raise Fail("out of range")
        out.append((off, n))
    return out


def parse_condition(c):
    """Mirror of the mod's condition parse: (offset, value, mask), or raises Fail."""
    if not isinstance(c, dict):
        raise Fail("bad command")
    off = int_arg(c, "offset", 0, 2**63 - 1)
    value = int_arg(c, "value", 0, 255)
    mask = int_arg(c, "mask", 1, 255) if "mask" in c else 0xFF
    if off >= IMAGE_SIZE or value & ~mask:
        raise Fail("out of range")
    return off, value, mask


def parse_sequence(args):
    """Mirror of the mod's parse_sequence: {"require": [cond], "steps": [step]}, or raises Fail."""
    steps = args.get("steps")
    if not isinstance(steps, list):
        raise Fail("bad command")
    if not 1 <= len(steps) <= SEQ_MAX_STEPS:
        raise Fail("out of range")
    require = []
    if "require" in args:
        if not isinstance(args["require"], list):
            raise Fail("bad command")
        if len(args["require"]) > SEQ_MAX_REQUIRE:
            raise Fail("out of range")
        require = [parse_condition(c) for c in args["require"]]
    out, total = [], 0
    for s in steps:
        if not isinstance(s, dict):
            raise Fail("bad command")
        bi = int_arg(s, "byteIndex", 0, 1)
        mask = int_arg(s, "mask", 0, 255)
        if bi == 0 and mask & 0x08:  # start opens the pause menu
            raise Fail("out of range")
        st = {"byteIndex": bi, "mask": mask, "holdMs": int_arg(s, "holdMs", 16, 1000),
              "gapMs": int_arg(s, "gapMs", 16, 1000), "until": None, "timeoutMs": 0, "onlyIf": None}
        total += st["holdMs"] + st["gapMs"]
        if "until" in s:
            st["until"] = parse_condition(s["until"])
            st["timeoutMs"] = int_arg(s["until"], "timeoutMs", 16, 5000)
            total += st["timeoutMs"]
        if "onlyIf" in s:
            st["onlyIf"] = parse_condition(s["onlyIf"])
        out.append(st)
    if total > SEQ_MAX_TOTAL_MS:
        raise Fail("out of range")
    return {"require": require, "steps": out}


async def _no_send(_text):
    pass


class Mock:
    def __init__(self, unsupported, unsafe, stall_after=None, chests=b"", world=3, room=2):
        self.world, self.room = world, room
        self.chests = bytes(chests)  # the chest-flag bytes from CHEST_FLAGS on; unset bytes read 0
        self.unsupported = unsupported
        self.unsafe = unsafe
        self.stall_after = stall_after  # seconds after start; then no more states (pongs/results continue)
        self.t0 = time.monotonic()
        self.seq = 0
        self.press = None  # (byteIndex, mask, end_time)
        self.extra_log = []
        self.mem = {}  # bytes changed at run time (tests, the game model); over FAKE_BYTES and --chests
        self.seq_task = None  # the running kh1.pressSequence
        self.seq_pad = None   # (byteIndex, mask) the sequence holds right now

    def stalled(self):
        return self.stall_after is not None and time.monotonic() - self.t0 >= self.stall_after

    def ms(self):
        return int((time.monotonic() - self.t0) * 1000) + 205750

    def build(self):
        return "egs-1.0.0.10" if self.unsupported else "steam-1.0.0.2"

    def welcome(self):
        return {"type": "welcome", "plugin": PLUGIN_VERSION, "protocol": 0, "gameId": "kh1",
                "gameBuild": self.build(), "supported": not self.unsupported,
                "features": ["spike", "watch", "sequence"]}

    def press_active(self):
        if self.press and time.monotonic() >= self.press[2]:
            self.press = None
        return self.press

    def busy(self):
        return self.press_active() is not None or self.seq_task is not None

    def byte(self, off):
        return self.mem[off] if off in self.mem else fake_byte(off, self.chests)

    def read(self, off, n):
        return bytes(self.byte(off + i) for i in range(n))

    def met(self, cond):
        off, value, mask = cond
        return self.byte(off) & mask == value

    def react(self, bi, mask):
        """The mock's model of the game: LB + a face button whose shortcut holds Cure or Aero opens the target
        picker (menu byte 0 -> 4); confirm or cancel (A/B, swapped by AB_SWAP) closes it again."""
        if bi != 1:
            return
        confirm = 0x20 if self.byte(AB_SWAP) == 1 else 0x40
        if self.byte(MENU) == PICKER_OPEN:
            if mask & (0x20 | 0x40):  # confirm casts, the other face button backs out
                self.mem[MENU] = 0
                self.extra_log.append("mock game: target picker " + ("confirmed" if mask & confirm else "cancelled"))
            return
        slot = SHORTCUT_FACE.get(mask & ~LB) if mask & LB else None
        if slot is not None and self.byte(MENU) == 0 and self.byte(SHORTCUTS + slot) in NEEDS_TARGET:
            self.mem[MENU] = PICKER_OPEN
            self.extra_log.append(f"mock game: shortcut {slot + 1} opened the target picker")

    async def hold_safe(self, seconds):
        """Waits; False as soon as the game is unsafe (the mod checks every tick)."""
        end = time.monotonic() + seconds
        while not self.unsafe:
            left = end - time.monotonic()
            if left <= 0:
                return True
            await asyncio.sleep(min(SEQ_TICK, left))
        return False

    async def run_sequence(self, cid, seq, send):
        """Runs an accepted kh1.pressSequence and sends its result when it ends (PROTOCOL.md)."""
        t0 = time.monotonic()
        pressed = skipped = 0
        failed = None  # (error, log text)
        try:
            for i, st in enumerate(seq["steps"], 1):
                if st["onlyIf"] is not None and not self.met(st["onlyIf"]):
                    skipped += 1
                    continue
                if self.unsafe:
                    failed = ("not safe now", f"aborted: not safe now at step {i}")
                    break
                self.seq_pad = (st["byteIndex"], st["mask"])
                self.react(st["byteIndex"], st["mask"])
                held = await self.hold_safe(st["holdMs"] / 1000)
                self.seq_pad = None
                if not held:
                    failed = ("not safe now", f"aborted: not safe now at step {i}")
                    break
                if st["until"] is not None:
                    end = time.monotonic() + st["timeoutMs"] / 1000
                    while failed is None and not self.met(st["until"]):
                        if self.unsafe:
                            failed = ("not safe now", f"aborted: not safe now at step {i}")
                        elif time.monotonic() >= end:
                            failed = ("timeout", f"timeout at step {i}")
                        else:
                            await asyncio.sleep(SEQ_TICK)
                    if failed:
                        break
                if not await self.hold_safe(st["gapMs"] / 1000):
                    failed = ("not safe now", f"aborted: not safe now at step {i}")
                    break
                pressed += 1
        finally:
            self.seq_pad = None
            self.seq_task = None
        took = int((time.monotonic() - t0) * 1000)
        if failed is None:
            what = f"done: {pressed} pressed, {skipped} skipped"
            r = {"type": "result", "id": cid, "ok": True, "error": None, "data": {"steps": pressed, "skipped": skipped}}
        else:
            what = failed[1]
            r = {"type": "result", "id": cid, "ok": False, "error": failed[0], "data": None}
        self.extra_log.append(f"sequence {cid[:32]} end ({what}) after {took} ms")
        try:
            await send(json.dumps(r))
        except ConnectionClosed:
            pass  # the mod sends to every client; here the requester has gone

    def state(self, watch=None):
        self.seq += 1
        d = dict(seq=self.seq, ms=self.ms())
        if self.unsupported:
            d.update(dict.fromkeys(("world", "room", "worldAlt", "soraHp", "soraHpMax", "inMenu",
                                    "inCutscene", "inGummi", "inputBytes")))
        else:
            inp = bytearray(4)
            for p in (self.press_active(), self.seq_pad):
                if p:
                    inp[p[0]] |= p[1]
            d.update(world=self.world, room=self.room, worldAlt=self.world, soraHp=self.byte(BATTLE_BLOCK),
                     soraHpMax=self.byte(BATTLE_BLOCK + 4), inMenu=self.unsafe,
                     inCutscene=False, inGummi=False, inputBytes=inp.hex())
            if watch:
                d["regions"] = [{"offset": off, "hex": self.read(off, n).hex()} for off, n in watch]
        return {"type": "state", "topic": "kh1.spike", "data": d}

    def log_lines(self):
        tail = [f"build {self.build()} " + ("not supported" if self.unsupported else "supported"),
                "server listening on 127.0.0.1:7970"]
        return STARTUP_LINES + tail + self.extra_log

    def command(self, msg, ctx=None):
        ctx = {} if ctx is None else ctx
        cid = msg.get("id")
        cid = cid if isinstance(cid, str) else ""

        def res(ok, error=None, data=None):
            return {"type": "result", "id": cid, "ok": ok, "error": error, "data": data}

        name, args = msg.get("name"), msg.get("args")
        if (not isinstance(msg.get("id"), str) or not isinstance(name, str)
                or not isinstance(args, dict)
                or len(json.dumps(args, separators=(",", ":"), ensure_ascii=False).encode()) > MAX_ARGS_BYTES):
            return res(False, "bad command")
        if name not in ("kh1.probe", "kh1.pressInput", "kh1.pressSequence", "kh1.log", "kh1.watch"):
            return res(False, "unknown command")
        if name == "kh1.log":
            return res(True, data={"lines": self.log_lines()})
        if self.unsupported:
            return res(False, "unsupported build")
        try:
            if name == "kh1.watch":
                w = parse_watch(args)
                ctx["watch"] = w  # this connection only; dropped with it
                return res(True, data={"count": len(w)})
            if name == "kh1.probe":
                off = int_arg(args, "offset", 0, 2**63 - 1)
                n = int_arg(args, "length", 1, 256)
                if off >= IMAGE_SIZE or n > IMAGE_SIZE - off:
                    raise Fail("out of range")
                return res(True, data={"offset": off, "hex": self.read(off, n).hex()})
            if name == "kh1.pressSequence":
                return self.start_sequence(cid, parse_sequence(args), ctx.get("send", _no_send), res)
            bi = int_arg(args, "byteIndex", 0, 1)
            mask = int_arg(args, "mask", 0, 255)
            if bi == 0 and mask & 0x08:  # start opens the pause menu
                raise Fail("out of range")
            dur = int_arg(args, "durationMs", 16, 1000)
        except Fail as f:
            return res(False, str(f))
        if self.busy():  # the mod checks busy before not safe now
            return res(False, "busy")
        if self.unsafe:
            return res(False, "not safe now")
        self.press = (bi, mask, time.monotonic() + dur / 1000)
        self.extra_log.append(f"press start byte {bi} mask {mask} for {dur} ms")
        return res(True)

    def start_sequence(self, cid, seq, send, res):
        """Refusals answer at once; an accepted sequence returns None and sends its result when it ends."""
        if self.busy():
            return res(False, "busy")
        if self.unsafe or not all(self.met(c) for c in seq["require"]):
            return res(False, "not safe now")
        self.extra_log.append(f"sequence {cid[:32]} start: {len(seq['steps'])} steps")
        self.seq_task = asyncio.create_task(self.run_sequence(cid, seq, send))
        return None

    async def stream(self, ws, rate, ctx):
        try:
            while True:
                await asyncio.sleep(1.0 / rate)
                if not self.stalled():  # simulates the mod/process freezing (not the game main loop)
                    await ws.send(json.dumps(self.state(ctx.get("watch"))))
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
            return {"type": "pong", "t": tv if is_int(tv) else 0}
        if t == "command":
            return self.command(msg, ctx)
        if t == "subscribe":
            tp = msg.get("topics")
            ctx["spike"] = isinstance(tp, list) and "kh1.spike" in tp
            rh = msg.get("rateHz")
            if isinstance(rh, (int, float)) and not isinstance(rh, bool):
                ctx["rate"] = int(max(1.0, min(10.0, float(rh))))
            return None  # no ack
        return err("unknown type")

    async def handler(self, ws):
        task = None
        ctx = {"rate": 5, "send": ws.send}
        try:
            async for raw in ws:
                if isinstance(raw, bytes):
                    raw = raw.decode("utf-8", "replace")
                ctx.pop("spike", None)
                out = self.reply(raw, ctx)
                if "spike" in ctx:
                    if task:
                        task.cancel()
                        task = None
                    if ctx["spike"]:
                        task = asyncio.create_task(self.stream(ws, ctx["rate"], ctx))
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


async def serve(port=7970, unsupported=False, unsafe=False, host="127.0.0.1", stall_after=None, chests=b"", world=3, room=2):
    mock = Mock(unsupported, unsafe, stall_after, chests, world, room)
    server = await ws_serve(mock.handler, host, port, process_request=process_request)
    server.mock = mock  # test hook: tests flip mock.unsafe mid-run
    return server


async def main():
    p = argparse.ArgumentParser()
    p.add_argument("--port", type=int, default=7970)
    p.add_argument("--unsupported", action="store_true")
    p.add_argument("--unsafe", action="store_true")
    p.add_argument("--stall-after", type=float, default=None,
                   help="stop sending state frames this many seconds after start (pongs continue); simulates the mod or whole process freezing, not the game main loop")
    p.add_argument("--chests", type=bytes.fromhex, default=b"", metavar="HEX",
                   help=f"chest-flag bytes from 0x{CHEST_FLAGS:X} on, as hex (default all zero)")
    p.add_argument("--world", type=int, default=3, help="world id reported in state frames (default 3)")
    p.add_argument("--room", type=int, default=2, help="room id reported in state frames (default 2)")
    p.add_argument("--host", default="127.0.0.1", help=argparse.SUPPRESS)  # test-only escape hatch
    a = p.parse_args()
    server = await serve(a.port, a.unsupported, a.unsafe, a.host, a.stall_after, a.chests, a.world, a.room)
    print(f"kh1 mock listening on ws://{a.host}:{a.port}", flush=True)
    await server.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())
