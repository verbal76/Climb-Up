# UPWARDLY (Windows) - Steam packaging

**Nothing here has been uploaded to Steam. No Steamworks app was created, no credit assigned, no store page, no review submission. Those are the owner's actions.**

## Build (repeatable)
`powershell -File tools/windows/package.ps1 -BuildNumber <n>` on Windows with JDK 17+ and Python 3 + Pillow. CI does exactly this (`.github/workflows/windows.yml`, job `windows`), on pushes to `exp/upwardly-windows` only, and uploads the zip as an `EXPERIMENTAL-...` workflow artifact (never a Release).

Steps: `:desktop:windowsJar` (shared game + LWJGL3 + **Windows natives only**) -> `jpackage --type app-image` (EXE, jlink runtime of `java.base, java.desktop, java.logging, java.management, java.naming, java.xml, jdk.unsupported, jdk.crypto.ec`) -> copy `assets/` to `app/assets` -> `BUILD_INFO.txt`, `SHA256SUMS.txt` -> `steam/content` + VDF templates -> zip.

## Layout
```
build/windows/dist/UPWARDLY/            == steam/content/  (the depot)
  UPWARDLY.exe                          launch executable (icon, version info 1.1.5.<build>, vendor Hot Attic Games)
  runtime/                              bundled Java runtime (no Java installation needed)
  app/upwardly.jar  app/UPWARDLY.cfg    game + launcher configuration
  app/assets/                           all game assets (read through app/assets, whatever the working directory)
  app/licenses/ASSETS.md                asset provenance
  BUILD_INFO.txt                        version, source SHA, channel, runtime
build/windows/SHA256SUMS.txt            checksum of every file and of the zip
build/windows/steam/scripts/*.vdf       app/depot build TEMPLATES (placeholders only)
```

## What the owner does later (not done here)
1. Create the Steamworks app with the Direct credit; note AppID and DepotID.
2. Steamworks: set the Windows launch option to `UPWARDLY.exe`, working directory empty, OS Windows 64-bit. Add the "Steam Input" controller configuration of choice (the game already reads standard gamepads; Steam Input virtual pads appear as Xbox-style controllers).
3. Copy the VDF templates, fill in IDs, run `steamcmd +run_app_build ...` from a machine with the Steamworks SDK (never from CI, never with secrets in this repository).
4. Set branches/review by hand.

## Notes
* Saves: `%APPDATA%\HotAtticGames\Upwardly` (not the install folder, so Steam updates never touch them). Steam Cloud is deferred.
* The game makes no network calls. Steam supplies updates.
* Not shipped / deferred: Steamworks API, achievements, cards, cloud, leaderboards, Workshop, multiplayer.
* The runtime is not code-signed; Windows SmartScreen may warn on a non-Steam copy. Signing certificates are an owner decision.
* Antivirus false positives on unsigned jpackage apps are possible; Steam distribution avoids most of this.
