# Diptych KH1 protocol v0

JSON text frames over WebSocket, one object per frame. Every message has a `"type"`.
The envelope is identical to the TTW DualScreen protocol v0; only `welcome`, the topic and the commands differ.
Example frames for each message are in `fixtures/`. The mod listens on **127.0.0.1:7970** only.

| Dir | Type | Fields | Fixture |
|-----|------|--------|---------|
| C->S | `hello` | `client:string`, `protocol:0` | hello.json |
| S->C | `welcome` | `plugin:"0.1.0"`, `protocol:0`, `gameId:"kh1"`, `gameBuild:"steam-1.0.0.2"\|"egs-1.0.0.10"\|"unknown"`, `supported:bool`, `features:["spike","watch"]` | welcome_steam.json, welcome_unsupported.json |
| C->S | `subscribe` | `topics:["kh1.spike"]`, `rateHz:int 1..10` | subscribe.json |
| S->C | `state` | `topic:"kh1.spike"`, `data:{...}` (below) | state_spike.json, state_unsupported.json, state_watch.json |
| C->S | `command` | `id:string`, `name:"kh1.probe"\|"kh1.pressInput"\|"kh1.log"\|"kh1.watch"`, `args:{}` | command_probe.json, command_press.json, command_log.json, command_watch.json |
| S->C | `result` | `id:string`, `ok:bool`, `error:string\|null`, `data:object\|null` | result_probe.json, result_press_refused.json, result_log.json, result_watch.json |
| C->S | `ping` | `t:int` | ping.json |
| S->C | `pong` | `t:int` | pong.json |
| S->C | `error` | `message:string` | error.json |

## Build gate

Addresses are offsets from `GetModuleHandleW(nullptr)`. Gate (source: `KH1-LUA-LIBRARY` `VersionCheck.lua`):

- byte at `0x4698D2` == `0x6A` -> `steam-1.0.0.2`, `supported:true`
- byte at `0x46A822` == `0x6A` -> `egs-1.0.0.10`, recognised, `supported:false` in M0
- anything else -> `unknown`, `supported:false`

When `supported` is false, `state` carries `null` for every data field except `seq` and `ms` (see `state_unsupported.json`),
and every command except `kh1.log` fails with `unsupported build`. **`kh1.log` works on any build**, so a failed
attach can still be diagnosed from the app.

## state `kh1.spike` data

| Field | Type | Meaning |
|-------|------|---------|
| `seq` | int | state frame counter |
| `ms` | int | milliseconds since the mod started |
| `world` | int\|null | world id |
| `room` | int\|null | room id |
| `worldAlt` | int\|null | world id read from `0x233FE84` (the Archipelago `world_address`), to compare with `world` (`0x233FE94`) |
| `soraHp` | int\|null | Sora's current HP |
| `soraHpMax` | int\|null | Sora's max HP |
| `inMenu` | bool\|null | `menu` value != 0 |
| `inCutscene` | bool\|null | any of the 4 bytes at `inCutscene` != 0 |
| `inGummi` | bool\|null | `inGummi` u32 != 0 |
| `inputBytes` | string\|null | the 4 bytes at `0x23407B4`, lowercase hex (8 chars) |
| `regions` | array (only with a watch) | `[{offset:int, hex:string\|null}]`: this client's `kh1.watch` regions, in watch order (see `state_watch.json`) |

A field is `null` when unsupported or when its reader faulted (that reader is then off for the session).
`regions` is present only in the frames sent to a client that has a non-empty watch; without one the key is absent.
A region's `hex` (lowercase, two digits per byte) is `null` when that read faults; the other regions still read, and a
faulting region never turns off a state reader.

## Commands

- `kh1.probe` `args:{offset:int, length:1..256}` -> ok `data:{offset:int, hex:string}`. Reads `length` bytes at module base + `offset`.
  A read that faults or is outside the module image fails with `out of range`.
- `kh1.pressInput` `args:{byteIndex:0..1, mask:0..255, durationMs:16..1000}` -> ok `data:null`. Holds the buttons in
  `mask` (PS2 bit layout of input byte `byteIndex`) on controller 0 for `durationMs`, injected through a hook of
  `XInputGetState` (XINPUT1_4.dll ordinal 2) in the game's import table. No game memory is written. Refused with
  `not safe now` in a menu, cutscene or Gummi, when a safety-gate reader is off, or when the hook could not be installed
  (see `kh1.log`); with `unsupported build` on an unsupported build. The mod re-checks those flags every tick (~1 kHz)
  and ends the press early if any becomes set (or a read fails). If no controller is connected nothing is injected
  (logged once). Mapping:

  | byteIndex | bit | PS2 | XInput |
  |-----------|-----|-----|--------|
  | 1 | `0x01` | L2 | `bLeftTrigger` = 255 |
  | 1 | `0x02` | R2 | `bRightTrigger` = 255 |
  | 1 | `0x04` | L1 | `LEFT_SHOULDER` |
  | 1 | `0x08` | R1 | `RIGHT_SHOULDER` |
  | 1 | `0x10` | triangle | `Y` |
  | 1 | `0x20` | circle | `B` |
  | 1 | `0x40` | cross | `A` |
  | 1 | `0x80` | square | `X` |
  | 0 | `0x01` | select | `BACK` |
  | 0 | `0x02` | L3 | `LEFT_THUMB` |
  | 0 | `0x04` | R3 | `RIGHT_THUMB` |
  | 0 | `0x08` | start | refused: `out of range` (opens the pause menu) |
  | 0 | `0x10` | up | `DPAD_UP` |
  | 0 | `0x20` | right | `DPAD_RIGHT` |
  | 0 | `0x40` | down | `DPAD_DOWN` |
  | 0 | `0x80` | left | `DPAD_LEFT` |

  Any other `byteIndex`, or a byte-0 mask with `0x08`, is `out of range`. While the injection changes, the hook
  also changes `dwPacketNumber` so games that skip unchanged packets see it. The earlier path (ORing `mask` into the
  input block at `0x23407B4`) is proven not to work: on PC the bytes held the bits but the game never acted; that
  block is a derived copy.
- `kh1.log` `args:{}` -> ok `data:{lines:[string]}`. Recent mod log lines. Works on any build.
- `kh1.watch` `args:{regions:[{offset:int, length:int}]}` -> ok `data:{count:int}` (the number of regions). Read-only.
  From then on, every `kh1.spike` state frame sent to **this connection** carries `regions` (above); other clients'
  frames are unchanged. A new `kh1.watch` replaces the previous one; an empty `regions` list clears it. The watch belongs
  to the connection: it is dropped when the client disconnects, so a client re-sends it after every `welcome`.
  Limits: at most **8** regions, each `length` **1..256** bytes, **1024** bytes in total, and each region wholly inside
  the module image (`offset + length <= SizeOfImage`). Breaking a limit is `out of range`; `regions` missing or not an
  array, an element that is not an object, or a missing or non-integer `offset`/`length` is `bad command`; on an
  unsupported build it is `unsupported build`. A failed `kh1.watch` leaves the previous watch in place.
- Every failed `result` has `ok:false`, `error:<string below>`, `data:null`.

## Error strings

`error` frames (`message`), sent by the network thread; the connection stays open:

| message | When |
|---------|------|
| `invalid json` | the frame is not parseable JSON, or nests objects/arrays more than 32 levels deep |
| `unknown type` | not an object, `type` missing/not a string, or an unknown `type` |

`result` frames (`error`) for a `command`:

| error | When |
|-------|------|
| `bad command` | `command` without a string `id` and string `name`, `args` missing/not an object/over 64 KB, or `args` invalid for the command |
| `unknown command` | `name` is not one of the four commands |
| `unsupported build` | any command except `kh1.log` on an unsupported build |
| `out of range` | an argument is outside its range (including pressInput start, and the `kh1.watch` limits), or a probe read faulted |
| `not safe now` | `kh1.pressInput` in a menu, cutscene or Gummi, or without the XInput hook |
| `busy` | the command queue is full, or an input press is already running |
| `internal error` | a C++ exception escaped the command, or a failure carried no error string |

- A `command` with a missing or non-string `id` gets `bad command` with `id:""`.
- Missing or non-object `args` is `bad command`, checked before the `name` is looked up (so an unknown name without `args` is `bad command`, not `unknown command`).
- Limits: a frame may nest objects/arrays at most 32 levels deep (the frame object itself is level 1; deeper is `invalid json`); a command's `args` may serialize to at most 64 KB (65536 bytes; larger is `bad command`); a WebSocket message over 1 MiB closes the connection with 1009.

## Rules

- `pong` is answered by the network thread.
- `error` is sent for unparseable or unknown messages; the connection stays open.
- `welcome` is the reply to `hello`.
- Only the `KINGDOM HEARTS FINAL MIX.exe` process runs the server; other processes get a passthrough DLL.

## Address table (Steam 1.0.0.2)

All offsets are from the module base. Widths are the Lua read calls; where none was found, the cell says so.
Sources: `SteamGlobal` = `KH1-LUA-LIBRARY/scripts/io_packages/SteamGlobal_1_0_0_2.lua` (offsets), `lib` =
`KH1-LUA-LIBRARY/scripts/io_packages/kh1_lua_library.lua` (reads, commit e387282a).

| Field | Offset | Width | Source of width |
|---|---|---|---|
| world | `0x233FE94` | u8 | `lib` `get_world`: `ReadByte(world)` |
| room | `0x233FE8C` | u8 | `lib` `get_room`: `ReadByte(room)` |
| worldAlt | `0x233FE84` | u8 | `KH-1FM-AP-LUA/1fmAPConnector.lua` `read_world`: `ReadByte(world_address[game_version])`, `world_address = {0x2340E5C, 0x233FE84}`, `game_version` 2 = Steam |
| soraHp | `0x2D5CC4C` | u8 | `KH-1FM-AP-LUA/1fmAPHandleDeathlink.lua`: `ReadByte(soraHP[game_version])` |
| maxHP (soraHpMax) | `0x2D5CC50` | u8 (low byte of a u32) | **confirmed on PC 2026-11-04** (Steam 1.0.0.2): `soraHP + 4` in the battle block `HP, maxHP, MP, maxMP` (u32 each). Read 55/57/5/5 at HP 55/57. The library's `maxHP` `0x2DE9366` is in a stats copy (`level, HP, ?` at `0x2DE9364`) whose HP byte tracks *current* HP, so it is not max HP |
| menu | `0x232DFA0` | u8 | `KH1-RANDOMIZER` `1fmFastCamera.lua` and `KH1-CROWDCONTROL`: `ReadByte(menu)` (0 = no menu) |
| inCutscene | `0x23AB2D0` | the mod reads 4 bytes, true if any is non-zero (scripts disagree: u8 flag bits / u32) | `lib`/`modules/text_boxes.lua`: `ReadByte(inCutscene)`, bit 0 = window syscalls off, bit 1 (`0x02`) = event active; `modules/spawn_enemy.lua` also uses `ReadInt(inCutscene) ~= 0` |
| inGummi | `0x5075A8` | u32 | `lib` `is_in_gummi_garage`: `ReadInt(inGummi) > 0` |
| inputAddress | `0x23407B4` | 4 bytes | `lib` `get_inputs`: `ReadArray(inputAddress, 4)` |

Notes: the mod reads `menu` as a byte and `inCutscene` and `inGummi` as 4 bytes, each "non-zero = set" (the scripts
disagree on the `inCutscene` width and on `> 0` vs `!= 0` for `inGummi`; reading all 4 bytes is the conservative choice
for the safety gate). It logs the raw 4-byte `inCutscene` value and the bytes `maxHP-1 .. maxHP+3` whenever they change,
so the spike can settle both widths against the real game.
`inGummi` comment in `SteamGlobal`: "differs to cover a change on linux systems".
