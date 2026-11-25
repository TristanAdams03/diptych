# PC checklist (KH1 M3: magic and items)

Claude is live in the session for this. You play and say what you see. Claude runs the probe from WSL.

This settles what tap-to-cast needs before it is built: the A/B swap byte, which button each shortcut slot uses, the
command-menu and target-picker bytes, the shortest press the game notices, and one real cast. The mod only reads
memory and presses buttons through the controller hook. It writes no game memory.

If KH1 won't start or the controls die: delete `dinput8.dll` from the game folder. Everything goes back to normal.

## Before you start

1. Put the M3 mod (`dinput8.dll`, plugin 0.2.0) next to `KINGDOM HEARTS FINAL MIX.exe`.
2. Load a save with:
   - Donald and Goofy in the party;
   - Cure learned and set on a shortcut;
   - a Potion in one of Sora's item slots;
   - full MP.
3. Stand somewhere with no enemies, e.g. a save-point room. Keep the controller connected.
4. Claude runs `log`. It should show `diptych_kh1 0.2.0 loaded` and `build steam-1.0.0.2 supported`.

Claude's probe commands, from WSL:

- `P` = `powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$(wslpath -w tools/probe/probe.ps1)"`
- read: `P -Command probe -Offset <hex> -Length <n>`
- press: `P -Command press -ByteIndex 1 -Mask <m> -DurationMs <ms>`

Byte-1 masks: LB 4, Y 16, B 32, A 64. So LB+Y is 20, LB+B is 36, LB+A is 68.

## 1. A/B swap (`0x4D8632`)

1. Claude reads `0x4D8632`, length 1.
2. Tell Claude which button on your controller attacks and which one jumps.
3. Claude presses mask 64 (XInput A), then mask 32 (XInput B), 100 ms each. Say which one attacked and which one
   jumped.
4. If KH1's options or Steam Input have a confirm-button or button-layout setting, switch it. Claude repeats steps 1
   and 3. Then switch it back.

## 2. Shortcut slots (`0x2DE9BA4`, 3 bytes)

1. Open the pause menu's shortcut screen. Tell Claude the spell on each shortcut and the button shown next to it.
   Close the menu.
2. Claude reads `0x2DE9BA4`, length 3. Each byte should be a spell: 0 Fire, 1 Blizzard, 2 Thunder, 3 Cure,
   4 Gravity, 5 Stop, 6 Aero, 255 empty.
3. Claude presses LB+Y, then LB+B, then LB+A, 300 ms each, a few seconds apart. Say what each one cast, or whether a
   target picker opened. If one opens, back out of it with your cancel button.
4. Move one shortcut to a different spell in the menu. Claude reads again to confirm the byte changed. Put the
   shortcut back.

## 3. Command menu and target picker (`0x2852790`, 40 bytes)

Claude reads `0x2852790` with length 40 in each state below. Use the D-pad, not the stick. Hold still in each state
until Claude says done.

1. In the field, with nothing open.
2. Move the top-level cursor to Magic (don't confirm), then to Items, then back to Attack. Then press up once from
   Attack. Does the cursor wrap to the bottom?
3. Open Magic. Put the cursor on the first spell, then on the second.
4. Open Items. Put the cursor on slot 1, then on slot 2.
5. Choose a Potion, so the target picker opens. Put the cursor on Sora, then on Donald. Then cancel.
6. Cast Cure with its shortcut (from section 2), so the picker opens. Note where the cursor starts. Move it to
   Donald. Then cancel.
7. Use a Potion on Sora through the menu. Is the top-level cursor now on Attack or still on Items?

## 4. Shortest press

1. Claude presses the jump button from section 1 five times, 100 ms each. Count the jumps.
2. Repeat at 50 ms, 33 ms and 16 ms.

The probe sends one press at a time, so the shortest gap between presses is checked later, with the app's
`kh1.pressSequence`.

## 5. First live cast

1. Claude reads the swap byte, the shortcuts, and Sora's battle block (`0x2D5CC4C`, length 16: HP, max HP, MP,
   max MP).
2. Claude presses LB plus Cure's button for 100 ms. Did the target picker open?
3. Claude reads `0x2852790`, length 40. It should match the picker state from section 3.
4. Claude presses the confirm button (A if the swap byte is 0, B if it is 1) for 66 ms. Did Cure land on Sora?
5. Claude reads the battle block again. MP should have dropped by Cure's cost.
6. Open the pause menu. Claude presses LB plus Cure's button. The result must be `not safe now`, and nothing happens
   in the game.
7. Close the menu. Claude runs `log`.

## After

- Check your shortcuts are back as they were.
- If you don't want the mod any more, delete `dinput8.dll`.
- Claude writes the results below. Then the app data, the `command_sequence.json` placeholder (menu value 4) and the
  mock's game model (`tools/mockserver/kh1_mock.py`) are updated to match.
- On the Thor later, repeat section 5 with the app's Diagnostics screen. Long-press the header to open it.

## Results (Claude fills in)

| What | Answer |
|---|---|
| Swap byte value, and which XInput button confirms | |
| Physical attack button vs. XInput (Steam Input remap?) | |
| Shortcut byte 0 / 1 / 2 → LB + which button | |
| Does the swap change the shortcut buttons? | |
| Menu byte or value: top level / Magic open / Items open / picker open | |
| Cursor bytes: top level / Magic / Items / target | |
| Where the picker cursor starts; does the top-level cursor wrap? | |
| Top-level cursor after using an item | |
| Shortest press that worked 5 out of 5 | |
| Live cast: picker opened, Cure landed, MP dropped, refused in the menu | |
