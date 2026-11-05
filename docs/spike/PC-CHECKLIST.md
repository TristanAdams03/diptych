# PC checklist (KH1 spike)

Claude is live in the session for this. You play and say what you see. Claude runs the probe from WSL.

If KH1 won't start or the controls die: delete `dinput8.dll` from the game folder. Everything goes back to normal.

## Setup

1. Install KINGDOM HEARTS HD 1.5+2.5 ReMIX from Steam.
2. In Steam, right-click KINGDOM HEARTS HD 1.5+2.5 ReMIX.
3. Click Manage, then Browse local files.
4. Find `KINGDOM HEARTS FINAL MIX.exe` in that folder.
5. Download `Diptych-KH1-mod-v0.0.1-kh1-spike.zip` from the release and unzip it.
6. Copy `dinput8.dll` into the same folder as `KINGDOM HEARTS FINAL MIX.exe`.

## Session

1. Launch KH1 and load a save.
2. Check the controller and keyboard still work.
3. Tell Claude. Claude runs `tools/probe/probe.ps1` with `state` and `log`.
4. Walk to 3 different rooms and 2 different worlds. Claude checks that `world` and `room` change, and which of `world` and `worldAlt` is right.
5. Take some damage. Claude checks the HP numbers.
6. Hold L1. Then press each face button. Then each shoulder button. Claude records `inputBytes` for each.
7. Claude tries `press` (byte 1, PS2-style mask, sent through the XInput hook), aiming to cast shortcut 1. Tell Claude what Sora does.
8. Open the menu. Claude tries `press`. It should say `not safe now`.
9. Quit KH1. Delete `dinput8.dll`. Start KH1 again and check it starts normally.

## Logs

- The mod writes `diptych_kh1.log` next to `dinput8.dll`.
- `diptych_kh1.log.1` is the log from the previous run.
- Tell Claude if either file is missing.
