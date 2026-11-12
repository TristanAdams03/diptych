# TTW DualScreen spike: device checklist

Run this on the AYN Thor. One action per line.
Fill in the results template at the bottom as you go.

Setup used: TTW through GameNative. The game folder is mapped as `A:\`.

Rule for the whole test: if the controller stops working at any point, tap the top (game)
screen once. Note it in the results ("Anything odd").

## Safety

- Before testing, make a new save in the game. Test on that save, not your main one.
- To uninstall: delete `Data\NVSE\Plugins\ttw_dualscreen.dll` and `Data\NVSE\Plugins\ttw_dualscreen.ini`.
- Step 25 adds `bAlwaysActive=1` to `Fallout.ini`. Remove that line afterwards if you don't want it.

## A. Install

1. On the Thor, open https://github.com/TristanAdams03/ttw-dualscreen/releases
2. Open the newest pre-release, `v0.0.1-spike`.
3. Download `TTWDualScreen-Plugin-v0.0.1-spike.zip`.
4. Download `TTWDualScreen-v0.0.1-spike.apk`.
5. Unzip the plugin zip.
6. With the Thor's Files app, copy the zip's `Data` folder into your TTW game folder.
   When asked, choose merge. Do not replace anything else.
   - The game folder is the Fallout New Vegas / TTW folder you copied onto the Thor's storage.
   - It is the folder you picked when you added the game in GameNative with "+". GameNative shows it as `A:\`.
   - It already has a `Data` folder; the zip's `Data` merges into it.
7. Check that this file now exists: `Data\NVSE\Plugins\ttw_dualscreen.dll`
8. Check that this file exists too: `Data\NVSE\Plugins\ttw_dualscreen.ini`
9. Install the APK. Allow "install unknown apps" if Android asks.
10. Open the app. It moves itself to the bottom screen.
11. If it did not move, drag it to the bottom screen by hand. Note this in the results.

## B. First run

12. In GameNative, launch TTW as usual.
13. Load your new test save (see Safety). Wait until you can walk around.
14. Look at the bottom-screen app. The big banner says one of: `LOOP: ALIVE`, `LOOP: STALLED`, `LOOP: SOCKET DEAD`.
15. If it says SOCKET DEAD, go to the troubleshooting section below.
16. Write down: the banner, the fps, the HP shown, the world flags, and the worldspace/cell.
17. Do the colors look right (compared with the game's Pip-Boy)? Write yes or no.

## C. Controller test (the main question)

Question: after you touch the bottom screen, does the controller still move your character?

Do this routine each time ("the touch test"):
- Walk with the controller. Confirm it works.
- Tap the log area of the bottom screen 5 times (the box at the bottom, not the buttons).
- Watch the banner. Write down the state.
- Push the stick, and press a button, in the game.
- Write yes or no: does the controller still move the character?

18. Touch test #1: Focus mode ON (the default). Record it.
19. In the app, turn Focus mode OFF. Touch test #2. Record it.
20. Turn Focus mode back ON.
    Tap the game (top) screen once and check the controller moves your character before continuing.
21. Open the AYN quick settings drawer. Set Focus Lock to **Top screen**.
    Focus mode in the app stays ON. Run touch test #3 and record it.
22. In GameNative, exit the game (or long-press GameNative, App info, Force stop).
23. Open the container's `Fallout.ini`:
    `C:\users\xuser\Documents\My Games\FalloutNV\Fallout.ini`
    (use the container's file explorer in GameNative).
24. Edit this one. A second copy may exist in the game folder's `config` folder. Ignore that one.
25. Under `[General]`, add the line `bAlwaysActive=1`
26. Save the file.
    Open the AYN quick settings drawer and turn Focus Lock back off.
    Check Focus mode in the app is ON.
    Launch the game and load your save.
27. Touch test #4 (Focus Lock OFF, Focus mode ON, `bAlwaysActive=1`). Record it.

## D. Equip and world dump

Equip is refused in menus, the Pip-Boy, the console and loading screens.
Test it in normal gameplay with the Pip-Boy closed.
If it is refused, "last result" shows `FAIL` and the reason. Write the reason down exactly.
- Expected: `in menu`, `loading`, `no save loaded`, `no unequipped weapon` (you carry no spare weapon).
- Problems (just record them): `console command failed`, `console command faulted`,
  `console disabled after fault`, `console interface unavailable`, `inventory read failed`,
  `world read failed`, `not on main thread`, `internal error`, `busy`, `unknown command`.
- `menu state unreadable`: record it, then quit and restart the game before continuing.

If the banner says STALLED when you tap a button, nothing happens yet.
The command runs as soon as the game loop resumes. That is expected.

28. Stand in a Capital Wasteland exterior, Pip-Boy closed.
29. Tap **Equip first weapon**. Record what happened.
30. Tap **Dump world**. Record the result.
31. Go inside an interior. Repeat steps 29 and 30.
32. Go to any DLC world you can reach. Repeat steps 29 and 30.
33. Record the worldspace/cell for each place.

## E. Send the results

34. Tap **Copy report** in the app.
35. Paste it into the chat with Claude.
36. Also paste both log files:
    - `Data\NVSE\Plugins\ttw_dualscreen.log`
    - `Data\NVSE\Plugins\ttw_dualscreen.log.1` (the previous run)
    - If a log is long, attach the file instead of pasting.
37. Paste your filled-in results template too.

## If the banner says SOCKET DEAD

1. Does `Data\NVSE\Plugins\ttw_dualscreen.log` exist?
   - No: the plugin did not load. Check the DLL is in `Data\NVSE\Plugins\` and that xNVSE itself is installed.
2. Open the log. Does it say "server listening"?
   - No: read the lines above it. Paste them to Claude.
3. In the game console (press the ~ key), type `GetNVSEVersion`. Does it answer?
   - No: xNVSE did not load. The plugin cannot run.
4. Does the log mention the port (7960) being in use, or a bind error?
   - Don't change the port (the app only uses 7960). Paste the log to Claude.
5. The plugin only loads on FNV 1.4.0.525.
   - The German no-gore exe is refused. The log says why.

## Results template

Copy this and fill it in.

```
Plugin and app installed OK:        yes / no
App moved to bottom screen itself:  yes / no

First run (steps 14-17)
  Banner:
  fps:
  HP shown:
  World flags:
  Worldspace / cell:
  Colors look right:                yes / no

Touch test (banner state / fps / controller still works)
  #1 Focus mode ON:          ___ / ___ / yes / no
  #2 Focus mode OFF:         ___ / ___ / yes / no
  #3 Focus Lock = Top screen:      ___ / ___ / yes / no
  #4 bAlwaysActive=1, Focus Lock off:      ___ / ___ / yes / no

Equip first weapon (result or error text)
  Capital Wasteland exterior:
  Interior:
  DLC world:

Dump world (worldspace / cell)
  Capital Wasteland exterior:
  Interior:
  DLC world:

Anything odd:
```
