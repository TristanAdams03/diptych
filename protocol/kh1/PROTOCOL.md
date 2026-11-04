# Diptych KH1 protocol v0

JSON text frames over WebSocket, one object per frame. Every message has a `"type"`.
The envelope is identical to the TTW DualScreen protocol v0; only `welcome`, the topic and the commands differ.
Example frames for each message are in `fixtures/`. The mod listens on **127.0.0.1:7970** only.

| Dir | Type | Fields | Fixture |
|-----|------|--------|---------|
| C->S | `hello` | `client:string`, `protocol:0` | hello.json |
| S->C | `welcome` | `plugin:"0.0.1-spike"`, `protocol:0`, `gameId:"kh1"`, `gameBuild:"steam-1.0.0.2"\|"egs-1.0.0.10"\|"unknown"`, `supported:bool`, `features:["spike"]` | welcome_steam.json, welcome_unsupported.json |
| C->S | `subscribe` | `topics:["kh1.spike"]`, `rateHz:int 1..10` | subscribe.json |
| S->C | `state` | `topic:"kh1.spike"`, `data:{...}` (below) | state_spike.json, state_unsupported.json |
| C->S | `command` | `id:string`, `name:"kh1.probe"\|"kh1.pressInput"\|"kh1.log"`, `args:{}` | command_probe.json, command_press.json, command_log.json |
| S->C | `result` | `id:string`, `ok:bool`, `error:string\|null`, `data:object\|null` | result_probe.json, result_press_refused.json, result_log.json |
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
| `inCutscene` | bool\|null | `inCutscene` value != 0 |
| `inGummi` | bool\|null | `inGummi` value > 0 |
| `inputBytes` | string\|null | the 4 bytes at `0x23407B4`, lowercase hex (8 chars) |

A field is `null` when unsupported or when its reader faulted (that reader is then off for the session).

## Commands

- `kh1.probe` `args:{offset:int, length:1..256}` -> ok `data:{offset:int, hex:string}`. Reads `length` bytes at module base + `offset`.
  A read that faults or is outside the module image fails with `out of range`.
- `kh1.pressInput` `args:{byteIndex:0..3, mask:0..255, durationMs:16..1000}` -> ok `data:null`. ORs `mask` into
  byte `byteIndex` of the input block for `durationMs`. The only game-memory write in M0; refused with `not safe now`
  in a menu, cutscene or Gummi, and with `unsupported build` on an unsupported build.
- `kh1.log` `args:{}` -> ok `data:{lines:[string]}`. Recent mod log lines. Works on any build.
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
| `unknown command` | `name` is not one of the three commands |
| `unsupported build` | any command except `kh1.log` on an unsupported build |
| `out of range` | an argument is outside its range, or a probe read faulted |
| `not safe now` | `kh1.pressInput` in a menu, cutscene or Gummi |
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
| maxHP (soraHpMax) | `0x2DE9366` | width unconfirmed (assume u8) | only sibling reads found: `ReadByte(maxHP + off)` for stats, `ReadByte(maxHP - 0x1)`; no direct `ReadX(maxHP)` |
| menu | `0x232DFA0` | u8 | `KH1-RANDOMIZER` `1fmFastCamera.lua` and `KH1-CROWDCONTROL`: `ReadByte(menu)` (0 = no menu) |
| inCutscene | `0x23AB2D0` | u8 (flag bits) | `lib`/`modules/text_boxes.lua`: `ReadByte(inCutscene)`, bit 0 = window syscalls off, bit 1 (`0x02`) = event active; `modules/spawn_enemy.lua` also uses `ReadInt(inCutscene) ~= 0` |
| inGummi | `0x5075A8` | u32 | `lib` `is_in_gummi_garage`: `ReadInt(inGummi) > 0` |
| inputAddress | `0x23407B4` | 4 bytes | `lib` `get_inputs`: `ReadArray(inputAddress, 4)` |

Notes: the mod's `inMenu` / `inCutscene` use "value != 0" on the byte read; the spike exists to check that against the real game.
`inGummi` comment in `SteamGlobal`: "differs to cover a change on linux systems".
