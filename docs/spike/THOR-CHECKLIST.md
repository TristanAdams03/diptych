# Thor checklist (KH1 spike)

You need GameNative 1.3.0 or newer.

If KH1 won't start or the controls stop working: GameNative → Mods → remove the mod. Nothing else changes.

## Read this first

The banner measures the mod's own thread. It does not measure the game's main loop.
STALLED means the mod, or the whole game process, stopped. For example, GameNative paused it.
STALLED is not proof that the game's frame loop is running or frozen.

## Steps

1. From the release, download `Diptych-KH1-mod-v0.0.1-kh1-spike.zip` and `Diptych-v0.0.1-kh1-spike.apk`.
2. Open GameNative. Open the page for KH 1.5+2.5.
3. Tap Mods, then Import.
4. Pick the zip.
5. If GameNative says `dinput8.dll` already exists, stop. Tell Claude.
6. Enable the mod. GameNative adds the `dinput8` DLL override by itself.
7. Install the APK.
   - If it won't install over the old one: uninstall "Diptych (spike)" first.
   - Then install the APK again.
8. Open "Diptych (spike)".
9. If needed, move the app to the bottom screen.
10. Launch KH1 and load a save.
    - If KH1 won't start: GameNative → Mods → remove the mod. Nothing else changes.
    - Then tell Claude.
11. Write down the banner: ALIVE, STALLED or SOCKET DEAD.
12. Write down the HP and the world and room shown.
13. Check the controls still work.
    - If they don't: GameNative → Mods → remove the mod. Nothing else changes.
    - Then tell Claude.
14. Tap the bottom screen 5 times. Check the game keeps running.
15. Tap **Fetch mod log**. (The game folder is private on Android, so this is the only way to get the log.)
16. Tap **Copy report**.
17. Paste both to Claude.
18. Optional: try **Focus mode** and say what it does.

## Uninstall

1. In GameNative, open Mods.
2. Remove the mod.
3. Launch KH1 and check it still starts.

`diptych_kh1.log` and `diptych_kh1.log.1` may stay in the game folder. They are harmless.
