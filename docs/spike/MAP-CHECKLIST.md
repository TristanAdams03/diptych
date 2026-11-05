# KH1 map checklist (v0.1.0-kh1-map)

On the Thor. One action per line.

If KH1 won't start or the controls stop working: GameNative → Mods → remove the mod. Nothing else changes.

## Install

1. From the release, download `Diptych-KH1-mod-v0.1.0-kh1-map.zip` and `Diptych-v0.1.0-kh1-map.apk`.
2. Open GameNative. Open the page for KH 1.5+2.5.
3. Tap Mods.
4. Remove the old Diptych mod (v0.0.3).
5. Tap Import. Pick the new zip.
6. Enable the mod.
7. Check the game's settings → **Environment** still has `WINEDLLOVERRIDES` = `dinput8=n,b`. (You added it for v0.0.3.)
8. Install the APK.
   - If it won't install over the old one: uninstall "Diptych (spike)" first.
   - Then install the APK again.
9. Open "Diptych". It shows "Waiting for Kingdom Hearts…" until the game is running.

## In the game

10. Launch KH1 and load your save.
11. Check the bottom screen shows your world and room at the top.
    - Deep Jungle and other early worlds show "No map for this world yet". That is expected.
12. Check the party panel: each member's HP matches the game.
13. Travel to **Neverland**.
14. Check the room you are in is lit blue on the map.
15. Walk into another room. Check the blue box moves.
16. Check the chest counts. Neverland should show 11 of 12 chests opened.
17. Find the room showing a chest not yet opened. Go open it.
18. Check that room's count goes up by one.
19. Later, in **Hollow Bastion** and **End of the World**, check steps 14–18 again.

## If something looks wrong

20. Tap **This looks wrong** (under the party panel).
21. Paste what it copied to Claude, with a few words on what was wrong.

## Diagnostics (only if Claude asks)

- Hold the top strip of the screen for one second. The old diagnostics screen opens (banner, Test press buttons, mod log).
- Hold it again to go back to the map.

## Uninstall

1. In GameNative, open Mods. Remove the mod.
2. In KH 1.5+2.5's settings → Environment, delete the `WINEDLLOVERRIDES` line (or just its `dinput8=n,b` part). Save.
3. Launch KH1 and check it still starts.
