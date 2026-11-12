# TTW DualScreen protocol v0

JSON text frames over WebSocket, one object per frame. Every message has a `"type"`.
Example frames for each message are in `fixtures/`.

| Dir | Type | Fields | Fixture |
|-----|------|--------|---------|
| C->S | `hello` | `client:string`, `protocol:0` | hello.json |
| S->C | `welcome` | `plugin:"0.0.1-spike"`, `protocol:0`, `game:string`, `xnvse:string`, `ttw:bool`, `features:["spike"]` | welcome.json |
| C->S | `subscribe` | `topics:["spike"]`, `rateHz:int 1..10` | subscribe.json |
| S->C | `state` | `topic:"spike"`, `data:{...}` (below) | state_spike.json |
| C->S | `command` | `id:string`, `name:"spike.equipFirstWeapon"\|"spike.dumpWorld"`, `args:{}` | command_equip.json |
| S->C | `result` | `id:string`, `ok:bool`, `error:string\|null`, `data:object\|null` | result_ok.json, result_no_save.json |
| C->S | `ping` | `t:int` | ping.json |
| S->C | `pong` | `t:int` | pong.json |
| S->C | `error` | `message:string` | error.json |

## state `spike` data

`frame:int`, `ms:int`, `saveLoaded:bool`, `hp:float|null`, `hpMax:float|null`, `hardcore:bool|null`,
`bInDCWasteland:int|null`, `bInNVWasteland:int|null`, `worldspace:string|null`, `cell:string|null`,
`interior:bool|null`, `colors:{hudMain,hudAlt,terminal,pipboy,menu}|null` where each color is `[r,g,b]`.

## command results

- `spike.equipFirstWeapon` ok -> `data:{formId:"XXXXXXXX", name:string}`
- `spike.dumpWorld` ok -> `data:{worldspace:string|null, parentWorldspace:string|null, cell:string|null, interior:bool, pos:[x,y,z]}`
- Every failed `result` has `ok:false`, `error:<string below>`, `data:null`.

## Error strings

`error` frames (`message`), sent by the network thread; the connection stays open:

| message | When |
|---------|------|
| `invalid json` | the frame is not parseable JSON |
| `unknown type` | not an object, `type` missing/not a string, or an unknown `type` |
| `bad command` | `command` without a string `id` and string `name` |

`result` frames (`error`) for a `command`:

| error | When |
|-------|------|
| `busy` | 64 commands already queued for the main thread (sent at once by the network thread) |
| `unknown command` | `name` is not one of the two spike commands |
| `no save loaded` | main menu / no game loaded |
| `not on main thread` | internal guard; the command ran off the game main thread |
| `menu state unreadable` | equip: reading the menu state faulted; that reader stays off until the game restarts |
| `loading` | equip: a load screen is up |
| `in menu` | equip: a menu (Pip-Boy, pause, container, ...) or dialogue is open |
| `console interface unavailable` | equip: xNVSE gave no console interface |
| `inventory read failed` | equip: reading the inventory faulted (that reader is then off for the session) |
| `no unequipped weapon` | equip: the player carries no weapon that is not already equipped |
| `console disabled after fault` | equip: an earlier console call faulted; console is off for the session |
| `console command faulted` | equip: the `EquipItem` console line faulted (console then off for the session) |
| `console command failed` | equip: the console line ran but reported failure |
| `world read failed` | dumpWorld: reading the player's cell/worldspace failed or faulted |
| `internal error` | a C++ exception escaped the command |
| `failed` | fallback when a command failed without a reason (should not happen) |

## Rules

- `pong` is answered by the network thread, so it proves the socket works even if the game's main loop stalls.
- `error` is sent for unparseable or unknown messages; the connection stays open.
- `welcome` is the reply to `hello`, and is also pushed unsolicited when the server info changes
  (at DeferredInit, once `ttw` is known). Clients replace any earlier `welcome` with the newest.
- Commands queue for the game main thread; a command sent while the loop is stalled runs when it resumes.
