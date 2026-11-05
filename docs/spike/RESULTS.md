# KH1 spike results (2026-11-04)

Steam KH 1.5+2.5, KH1 build steam-1.0.0.2 (PE TimeDateStamp 0x669e37ce, SizeOfImage 0x02f91000).

## PC (Windows 11, Xbox-style pad)

- Mod loads; version gate says supported; game unaffected.
- world (5 = Deep Jungle), room (14 = Tent, 12 = Camp), soraHp: correct.
- maxHP: the library's `0x2DE9366` is wrong (a stats copy whose HP byte tracks current HP).
  Correct: `0x2D5CC50` = soraHP + 4, in the battle block HP, maxHP, MP, maxMP (u32 each). Fixed.
- Stats copy at `0x2DE9364`: level (45), HP, ...; party members' copies follow (for party HP in M1).
- menu flag: correct.
- inputBytes byte 1 = PS2 pad bits: LT 01, RT 02, LB 04, RB 08, Y 10, B 20, A 40, X 80.
- Writing the input block (held byte, +0x0D, +0x11) shows in memory but the game ignores it.
- Game imports XINPUT1_4 #2/#3, HID.DLL, DINPUT8. An IAT hook of XInputGetState works:
  injected A = attack, B = jump (8/8), LB+Y = the LB+Y shortcut (Cura, opens the target picker).
  The user's attack is on physical B: an A/B swap setting (cf. community address `swapped` 0x4D8632).

## Thor (AYN Thor, Android 13, GameNative 1.3.0)

- GameNative imported and placed `dinput8.dll` in GAME_DIR but did NOT add the DLL override
  (likely: GameNative starts KH1 through a Steam starter outside the game folder, so its
  auto-detect looks in the wrong folder). With `WINEDLLOVERRIDES=dinput8=n,b` set by hand: works.
- Loopback socket from the Wine game to the Android app works: ALIVE, ~4.9 seq/s at 5 Hz, rtt 2 ms.
- XInput hook installs under Wine; presses from the app reach the game (33 rapid presses fine).
- Wine's dinput8 lacks `GetdfDIJoystick` (logged; harmless unless the game calls it).
- inCutscene (raw 2) is also set while talking to people and during room transitions (loading): good for the safety gate (spec wants
  dialogue gated), but M1 must not label it "cutscene" in the UI.
- Runtime loop 97-167 Hz idle-mostly.

## For M1

- Quick magic: shortcut press works; target picker needs a confirm (or pick) step.
- Read the A/B swap setting before pressing confirm/attack.
- Install guide: add the manual WINEDLLOVERRIDES step; report the auto-override miss to GameNative.
