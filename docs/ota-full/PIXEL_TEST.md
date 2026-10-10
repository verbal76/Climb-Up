# Pixel 10 Pro XL test procedure for the OTA laboratory build

**Status: not yet run on a phone.** Nothing here is a claim about the Pixel. Everything the build has passed so far ran on GitHub-hosted Android emulators (x86_64, software graphics); see `STATUS.md` for exactly what.

This is an experiment, not the game. It installs two extra apps next to your game and changes nothing about the game you have installed.

## What gets installed (from one CI run, one source commit)
| App | Application id | What it is |
|---|---|---|
| Climb up EXP (module) / "Climb Up OTA lab" console | `com.hotatticgames.climbup.otaexp` | the new host: loads the game as a signed module; the console screen runs the tests |
| Climb up EXP (packaged ref) | `com.hotatticgames.climbup.otaref` | the same game classes packaged the normal way, for comparison |

Your installed Climb Up (`com.hotatticgames.climbup`, Build 44) is not touched. Neither app can replace it (different ids). Both are signed with a debug key made inside the CI run, so a newer lab build cannot be installed over an older one: uninstall the old lab apps first (this deletes only their own data).

## Get the files
GitHub, repository `verbal76/Climb-Up`, Actions, the run named in the report, artifact `climb-exp-apks-api34` (a zip with `host.apk` and `ref.apk`). Copy both to the phone and open them (allow "install unknown apps" for the file manager when asked).

## 1. First start (2 minutes)
1. Open **Climb Up OTA lab** (the console). It shows the phone model, Android version and ABI (should read arm64-v8a).
2. Press **Play the game (cold start)**. The studio splash, then the title screen appear, exactly as in the normal game. Play a few seconds. Press Home.
3. Return to the console. The STATE block should read: active v1, last known good v1.
Pass = the game looks and plays like your installed one.

## 2. Equivalence check on this phone (about 5 minutes)
Press **Equivalence check on this phone**. The game window opens in front; leave it. Switch back to the console from recents now and then. The console compares six deterministic simulation runs (autopilot, a flailing player, hazards) against the results computed on a desktop reference machine for the same game code.
Pass = `6/6 finished, 6 identical` and the line "PASS". If it says DIFFERENT, copy the lines under it (they are selectable) and send them: that means the simulation is not bit-for-bit the same on this phone, which is a finding, not a failure of the test.

## 3. Update scenarios (about 10 minutes)
Each button stages a signed release that is carried inside the app. It goes through the same download-verify-stage code as a real update; only the transport is local. A staged release never changes the running game; it activates at the **next cold start** (the console's Play button kills the game process first, which is a real cold start).
| Press | Expect |
|---|---|
| Stage v7: code update, then Play | "staged v7"; after Play the STATE shows active v7 and the log shows `code-marker`: the new executable code is running; the game looks and plays the same |
| Stage v3: changes level generation, then Play (with a climb in progress) | "staged v3" but it stays inactive while a climb is in progress; STATE still shows v7 or earlier |
| Stage v4: tampered | refused ("checksum mismatch") |
| Stage v5: signed but broken, then Play | the game still starts: it rolls back to the last good module and says so in STATE |
| Stage v2, v8 | accepted in order only if newer than everything seen; an older number is refused |
To test a running climb: Play, start a climb, climb a little, Home, stage v7, Play: your climb is still there.

## 4. Performance (about 2 minutes)
Press **Performance run**. After about 80 seconds the console shows `fps`, frame interval p50/p99 and memory. A healthy result on this phone is about 60 fps (or the display's rate) with p99 under 25 ms. To compare with the packaged build, run the same on the "packaged ref" app (open it and use the adb command below) or ask for the adb procedure:
`PERF_STRICT=1 tools/otalab/perf_capture.sh out 7 60 3` from a computer with adb (all three apps installed).

## What to send back
A screenshot of the console after each section, the phone's Android version, anything that looked different from your installed game (even slightly), any crash.

## Not covered
Google Play behaviour (there is a separate store build with no loader code, see `STATUS.md`), Android 17 on emulator, battery/thermal over long sessions, signed releases from a real server (this lab embeds its test releases; the real signing key and hosting are a later decision).
