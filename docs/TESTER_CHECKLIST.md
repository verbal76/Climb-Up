# Upwardly — tester checklist (Build 64)

Ten minutes per device. Note anything that feels wrong, with a screenshot or a short video if you can.

## Install
- **Android:** install `upwardly-1.1.5-build64-debug.apk` over Build 63 (no uninstall). Your players, climbs and times must still be there, and the run you had in progress continues with its times.
- **Windows:** unzip `Upwardly.Build.64.EXE.zip` anywhere and run `Upwardly\Upwardly.exe` (no Java needed). Saves live in `%APPDATA%\HotAtticGames\Upwardly`. The title's status bar must say `BUILD 64`.
- **Linux / Steam Deck:** unpack `Upwardly.Build.64.Linux.tar.gz` and run `Upwardly/bin/Upwardly`.
- Downloads: https://github.com/verbal76/Climb-Up/releases/tag/v1.1.5-build64 (APK) and https://github.com/verbal76/Climb-Up/releases/tag/build-64-desktop (Windows + Linux). Build 63 stays at its own links if you need to compare.

## New in Build 65 (screenshots)
1. PC: press F9 during play. A "SCREENSHOT SAVED" line appears at the bottom with the folder; the PNG is in `Pictures\Upwardly\Screenshots` (or the save folder's `Screenshots`). Press F9 twice quickly: two files, different names. The picture must show the HUD and not the saved-line itself.
2. Any device: pause → TAKE SCREENSHOT. The pause menu is still there afterwards and the run is still paused; the picture shows the game view without the menu.
3. Android: the picture appears in the gallery under Pictures/Upwardly (Android 10+); otherwise in Android/data/com.hotatticgames.climbup/files/Pictures/Screenshots.
4. Steam Deck: pause → TAKE SCREENSHOT with the pad; the file lands in `~/Pictures/Upwardly/Screenshots`. Steam's own screenshot (Steam + R1) must still work as before.

## New in Build 64
1. Title: the line under the name reads "TEN TOWERS. ONE CLOCK." one time and "TRAMPOLINES, CRABS AND CANNONS. YOU'RE WELCOME." the next (leave to a climb and come back). Both fit the screen on your device.
2. Start a climb: under the clocks the HUD shows `SEED XXXXX-XXXX-XXXX` (13 letters and digits, never 0, O, 1, I or L). The same code is in the pause menu and on the finish screen.
3. Title → NEW RUN → SEEDED RUN: type a friend's code on the picker (touch, keyboard or pad), OK. The HUD shows exactly that code and the course is the same as theirs (same platforms, the same cannons and crabs in the same places). Type a wrong code (too short, a letter that is not on the picker, all Z's): it says why and starts nothing. CANCEL keeps your saved climb.
4. Climb two castles on a code, then NEW RUN → SEEDED RUN with the same code again: the tower-by-tower times say + or - against your first run on that course (DIFF column in TIMES, the toast at each castle).
5. MORE → TIMES: the header shows the full code and RULES 1; `<` and `>` walk through every course you have timed; a screenshot of that screen is what you send a friend.
6. Settings → CONTROLS + DATA (phone) or GRAPHICS (PC/Deck): SHOW COURSE SEED off hides the code from the HUD only (still in the pause menu and TIMES); SHOW BEST TIMES off hides the PB marks and "vs best" lines from the HUD only (the clock still runs and TIMES still records). Both come back ON after a restart if you left them on.
7. Your Build 63 climb and times: still there after the update; a legacy run on the LEGACY RUNS screen shows its code too.

## New in Build 63
1. Pick the hamster: it now wears the bunny's skeleton and animations. Its arms are as long as the bunny's and hang and swing the same way, the hands (balls) land on the rope and the ledge, and there are no pale stubs at its shoulders. Run, jump, climb a rope, hang under a cable, pull up onto a ledge.
2. A side cannon stands on its own small slab just behind the path, its barrel pointing along the path, the ball leaving the muzzle. Every spiked ball you can see hurts if it touches you, including the ones that have flown on past the gap; they shrink away about 14 m past it.
3. Late in the climb (the night world) the turret cannon turns to follow you and fires along where it points.
4. A crab that sees you on its platform runs at you a moment after it spots you; it never leaves its platform, it stops to turn round when you hop over it, and if you stand just past its edge it gives up after a moment and goes back to its patrol. It does not see you while you line up the jump from the platform before.
5. Catch a ledge and pull up straight away, or land a close one: the words over the hero's head ("HUP!", "NICE CATCH!", "JUST MADE IT!") stack one above the other instead of printing over each other.

## Play (all devices)
1. Title: PLAY or CONTINUE, then MORE → PLAYERS, HERO, TIMES, CREDITS, BACK. Everything readable, nothing cut off.
2. Climb to the first red gem, then fall on purpose: you come back to the gem.
3. Open a castle (find its key below the path), then fall past the lowest platform: you come back **inside the open castle**, not the gem below.
4. Walk into a saw or spikes: you are thrown off a little harder than in Build 60, never killed.
5. Settings → ACCESS: turn on COLOURBLIND-SAFE COLOURS and check keys, castles and the carried-key marker still read by shape (circle, square, triangle, diamond). Try CAPTION SIZE and CAPTION BACKGROUND.
6. Pause mid-jump, wait, resume: the climb continues exactly where it was.

## Desktop and Steam Deck only
7. Play with a controller only (no keyboard, no mouse): menus, settings, the name picker, pause (Menu button), BACK (B).
8. Unplug the controller while climbing: the game pauses and says CONTROLLER DISCONNECTED. Plug it back in, press RESUME on it.
9. Settings → CONTROLS: the prompts show your pad's own buttons (Xbox / PlayStation / Switch; the Deck shows A/B/X/Y).
10. Steam Deck: first launch should be full screen at 1280×800. Suspend with the power button for a minute and resume.
11. Pick the hamster and walk slowly (half stick): arms hang beside the body and swing, no "wings".

## Report
Device, build number, what you did, what happened, what you expected.
