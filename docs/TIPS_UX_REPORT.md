# Mechanic-introduction pop-ups: owner playtest UX fix (branch `visual/build83-atmosphere`, for owner review, not merged)

Owner report: a new mechanic's pop-up (e.g. the ramp) stayed on screen too long and sat near the middle of the view, over the route.
Cosmetic/UI only: no change to the simulation, generation, physics, RNG, tuning, input timing or replays (`sim/**` untouched).

## What changed
- **Position.** Contextual tips are now a compact banner at the **top edge**, in the band between the height plate and the pause button
  (desktop: the right margin), centred in that band, top edge 28 px under the screen top. It used to be a box half the screen wide, centred,
  whose top sat 150 px down. A 4-line tip at 160 % text came down into the middle of the screen; at 1280 wide it also printed over the height plate.
- **Text.** One short line per mechanic ("RAMPS: JUST RUN UP"), 3.0 x TEXT SIZE (was 3.4 x). At most two lines; a text that would need more
  first shrinks (never below 75 % of its TEXT SIZE) and only then is cut with "...". No tip in the catalogue is ever cut, at any text size or
  aspect ratio (tested). The full instruction is kept, word for word, in `TipCatalog` and shown in the pause menu (below).
- **Duration.** 0.15 s fade-in, **2.5 s**, 0.35 s fade-out (was 5 s, no fade). One longer tip: the locked castle (4.0 s), which explains a
  multi-step task (find the key below the path).
- **Dismiss.** Tap the banner (touch: any finger, a 12 px slop; mouse click), or **TAB** on the keyboard, or the controller's **BACK/cancel button**
  (B by default, named per controller family, from the BACK binding). Neither is a gameplay binding. If the player rebinds a gameplay action onto TAB or
  that button, the dismiss is switched off for it (and its hint hidden), so a gameplay press is never also a dismiss. The banner shows the hint
  at its right end ("X" on touch, "TAB" / the button name on desktop).
- **No stacking.** One tip at a time, then a 1.2 s gap before another may start. The event toast and the world banner share the top band. While
  either is up, the tip waits hidden with its clock stopped, so they never overlap and the tip is not used up unseen. The club pickup queues at
  most one tip.
- **Seen state.** Unchanged and respected: `SaveData.shownTips` (same keys as before, so nobody sees an old tip again). A tip is marked seen only
  when it actually starts. CONTEXTUAL TIPS off still shows none.
- **Full text: pause menu.** A LAST TIP panel left of the pause menu shows the full instruction of the most recent tip. Tap/click it to step back
  through the older ones ("TIP 2/5"). No new menu was added. With a keyboard or controller only, the panel shows the most recent tip; it is not a
  focus stop.
- **Input / replays.** The banner never writes the `InputState`. A touch that lands on the visible banner is consumed (no jump, no stick start).
  The banner sits in the top band, away from the resting thumbs; a one-line banner lies within the top 15 %, where a touch never starts the
  stick anyway. The TAB/B dismiss is
  read beside, not through, the gameplay actions. Recorded runs and replays are unaffected.

## Audit of every in-play notification

| id | trigger | old position / duration | new position / duration | dismissal |
|---|---|---|---|---|
| tip `jump` | start of a climb (2nd route element) | upper centre box (W/2 wide, top at H-150), 5 s | top-edge banner, 2.5 s + fade | tap / TAB / B |
| tip `pad` | bounce pad within 9 m | same | same | same |
| tip `rope` | rope within 9 m | same | same | same |
| tip `crumble` | crumbling tiles within 9 m | same | same | same |
| tip `move` | moving platform (H/V) within 9 m | same | same | same |
| tip `cable` | cable within 9 m | same | same | same |
| tip `swing` | swinging platform within 9 m | same | same | same |
| tip `spring` | spring within 9 m | same | same | same |
| tip `seesaw` | tipping bridge within 9 m | same | same | same |
| tip `ramp` | ramp within 9 m (the owner's example) | same | same | same |
| tip `rampcrumble` / `rampshake` / `rampspin` / `rampbelt` | ramp of that kind within 9 m | same | same | same |
| tip `beltbridge` | conveyor bridge within 9 m | same | same | same |
| tip `checkpoint` | first checkpoint ahead | same | same | same |
| tip `saw` / `cannon` / `trap` / `block` / `drop` / `crab` / `bee` | hazard within 9 m (7 m vertically) | same | same | same |
| tip `club` | club hazard nearby, **and now the first club pickup** | same | same | same |
| tip `gate` | coloured castle gate within 9 m | same | top-edge banner, **4.0 s** + fade | same |
| tip `ledge` | **none: no code triggers it** (pre-existing; text kept) | never shown | never shown | n/a |
| club pickup toast | every club pickup | top centre, "SPIKED CLUB! TAP SWING TO KNOCK CRABS AND BEES OFF", 3 s, **every pickup** | "SPIKED CLUB!" 1.6 s; the how-to is the one-time `club` tip (tips off: unchanged) | expires |
| event toasts | CHECKPOINT 2 s, BACK TO CHECKPOINT 1.6 s, OUCH 1.8 s, 50 M milestones 1.6 s, KEY 3 s, CASTLE OPENED 3-3.2 s, LOCKED 2.6 s | top centre band, sized to clear the plate (Build 60/63) | unchanged (event feedback, not instructions; already top band and short) | expires |
| save notice toast | a climb that could not be resumed | top centre, 6 s | unchanged (must not be missed) | expires |
| world banner | entering a world | top centre, 3 s | unchanged (tip waits for it) | expires |
| sound captions | sounds, with CAPTIONS on | bottom centre (above the desktop hint bar), 1.4 s, one at a time | unchanged (accessibility; Build 60 size/background options) | expires |
| pops over the hero | catches, close landings, bounces... | over the hero's head, ~1.1 s, stacking upward (Build 63) | unchanged; their ceiling is now the banner's lower edge | expires |
| hero speech bubble | idle beats | over the hero | unchanged | expires |
| BUILDING THE TOWER / tower stop | the climb is held for generation / failed | screen centre | unchanged (status while nothing can be played) | n/a |
| desktop control-hint bar | play start (14 s), device switch (6 s) | bottom edge | unchanged (controls reference at the edge, not over the route) | expires |

## Verification actually run (Linux sandbox, Xvfb + Mesa software GL, LOW graphics quality as the desktop picks for llvmpipe)
- Unit tests: `TipBannerTest` (10). The layout is checked at 16:9 (1280x720), 20:9 (1600x720; 2400x1080 maps to it) and 4:3 tablet (1280x960);
  at every TEXT SIZE step (70-160 %); desktop, touch and touch left-handed; every tip; every dismiss label. In all of them the banner stays clear
  of the player/route box (middle half of the width, 15-76 % of the height), in the top band, clear of the height plate and the pause button, at
  most 2 lines and never cut. Also tested: the duration and fades, the dismiss, no stacking and the gap, waiting for the toast/world banner, and
  the seen list. `InputManagerTest` gains 4 tests: TAB dismisses and is nothing else; TAB bound to JUMP does not dismiss; B dismisses unless B
  jumps; jump/move/escape never dismiss. Results: core TipBannerTest + PersistenceTest + ProfilesTest + render + KeyColors = 66 tests, 0 failures;
  `:desktop:test` = 133, 0 failures.
- Real game, same seed (7), autopilot climbing from a fresh profile, screenshots every second: before and after at 1280x720 desktop, 1600x720
  touch UI, 2400x1080 touch UI, plus 1600x720 touch at 160 % text. The first tip waited for the MEADOW BASE banner and then showed at the top edge.
  The ramp tip showed at the top edge while the ramp was in view and was gone after about 3 s (2-3 frames at 1 s; before: 5-6 frames). It never
  covered the hero, the ramp or the next platforms.
- Real game dismissal: scripted taps on the banner (the game's own `-Dclimb.taps` hook, through the normal UI tap path) every 0.5 s. Each tip was
  visible in at most one 0.5 s frame. The keyboard/controller path is covered by the unit tests only. A second-finger tap (Android multi-touch) was
  **not** exercised on a device.
- Pause menu: LAST TIP panel at 1280x720, 160 % text: full gate text wrapped inside the panel beside the menu.
- Not run: a physical phone, a real controller, the full core suite (long generator runs).

Screenshots in `docs/visual/`:
- `tips_1280x720_ramp.png` and `tips_1280x720_jump.png`: desktop 16:9, before | after
- `tips_1600x720_ramp_touch.png`: 20:9 touch, before | after
- `tips_2400x1080_ramp_touch.png`: the owner's 2400x1080 phone size
- `tips_1600x720_text160_touch.png`: after, 160 % text
- `tips_pause_last_tip_1280x720_text160.png`: the pause panel

## Left for the owner
- Top edge or bottom edge? The top was chosen because the bottom holds the stick and jump button (a tap there would collide with play) and the
  captions. In a climb the route is above you, so if the banner ever feels in the way at the top, say so and it can move.
- The brief wordings (in `TipCatalog`) are new text; please read them in play.
- The `ledge` tip has never been triggered by the code (pre-existing). Should it come back (for example the first time a grab happens)?
- The desktop control-hint bar stays 14 s at play start (bottom edge). Should it be shorter too?
