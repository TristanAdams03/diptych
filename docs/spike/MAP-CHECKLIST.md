# KH1 map checklist (v0.2.1-kh1-map)

On the Thor. One action per line.

If KH1 won't start or the controls stop working: GameNative → Mods → remove the mod. Nothing else changes.

This build replaces v0.2.0. The bottom screen only shows information. Nothing on it presses buttons in the game.

## Install

1. From the release, download `Diptych-KH1-mod-v0.2.1-kh1-map.zip` and `Diptych-v0.2.1-kh1-map.apk`.
2. Open GameNative. Open the page for KH 1.5+2.5.
3. Tap Mods.
4. Remove the old Diptych mod.
5. Tap Import. Pick the new zip.
6. Enable the mod.
7. Check the game's settings → **Environment** still has `WINEDLLOVERRIDES` = `dinput8=n,b`. (You added it for v0.0.3.)
8. Install the APK.
   - If it won't install over the old one: uninstall "Diptych" first.
   - Then install the APK again.
9. Open "Diptych". It shows "Waiting for Kingdom Hearts…" until the game is running.

## In the game

10. Launch KH1 and load your save.
11. Check the bottom screen shows your world and room at the top.
    - Every world has a map now.
12. Check the gold **Next:** line under the world name. It says what the story wants next in this world.
    - The eye button (top right) hides the Next line and the gold and dashed boxes, if you don't want spoilers.
    - A gold box on the map is the next room. A dashed box is a room you can't reach yet.
13. Check the party panel: each member's HP matches the game.
14. Travel to **Neverland**.
15. Check the room you are in is lit blue on the map.
16. Walk into another room. Check the blue box moves.
17. Check the chest counts. Neverland should show 11 of 12 chests opened.
18. Find the room showing a chest not yet opened. Go open it.
19. Check that room's count goes up by one.
20. Later, in **Hollow Bastion** and **End of the World**, check steps 15–19 again.
    - In Hollow Bastion, check the whole map fits: the top row sits under the "ROOMS" title, the bottom row above the key.
    - Check the Next line matches where you are in the story (Riku, Maleficent, Dragon Maleficent, and so on).

## Quick Magic box

21. The Quick Magic box (under the party) says "Tap-to-cast is coming next". Casting from the bottom screen comes in the next update.
22. Under it, a line shows what the game is doing:
    - "Game ready" when you are walking around.
    - "Game busy (cutscene, dialogue or loading)" while people talk or a room loads.
    - "Menu open" while the pause menu is open. "Gummi Ship" on the Gummi Ship.
23. Open the pause menu. Check the line says "Menu open". Close it again.

## The other tabs

24. Tap **Magic & Items**. It lists the spells you have learned with their MP cost, and the items in Sora's item slots. Nothing there can be tapped.
25. Tap **Party**. It shows Sora's level, keyblade, Strength, Defense and AP.
26. Tap **Journal**. It lists every world with its chest total.
27. Tap **Map** to go back.

## If something looks wrong

28. Tap **This looks wrong** (under the Quick Magic box).
29. Paste what it copied to Claude, with a few words on what was wrong.

## Diagnostics (only if Claude asks)

- Hold the top strip of the screen for one second. The old diagnostics screen opens (banner, Test press buttons, mod log).
- Hold it again to go back to the map.

## Uninstall

1. In GameNative, open Mods. Remove the mod.
2. In KH 1.5+2.5's settings → Environment, delete the `WINEDLLOVERRIDES` line (or just its `dinput8=n,b` part). Save.
3. Launch KH1 and check it still starts.
