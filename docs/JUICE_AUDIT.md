# Game-feel ("juice") audit

**Already there:** per-action SFX with pitch variance, landing dust, pad squash and bounce ring, crumble debris, checkpoint sparkle + toast, zone banners, hard-landing/bounce shake, haptics, parallax clouds/stars, fog, blob shadow, press-down buttons, hero idle personality, summit wave, respawn fade.

**Gaps found and fixed in this pass**
| Gap | Fix |
|---|---|
| Hero had no squash/stretch; the landing animation never fired (events were consumed before the renderer saw them) | event feed into `HeroRig`, spring squash-and-stretch on jump/land/bounce/grab, `Jump_Land` now plays |
| No run dust | dust puff at every footstep |
| Platforms didn't react to being landed on | spring dip under the landed platform |
| Ledge catches / last-moment landings only made a sound | pop-ups ("NICE CATCH!", "JUST MADE IT!"), 70-80 ms hit-stop, shake, haptic |
| No progress rewards between checkpoints | every 50 m: "50 M!" toast, sparkle, rising chime |
| Static air | zone ambience: pollen (meadow), snow (frost), embers (dusk), fireflies (night) |
| Rigid camera | vertical lead on fast rises/falls, FOV kick on jump and pad bounce |
| Respawn was only a fade | poof burst at the checkpoint |
| One summit burst | rolling confetti while the summit panel is up |
(Hit-stop, shake, FOV kick and camera lead are off when Reduced Motion is on.)

**Ledge strain (follow-up):** while hanging he kicks his legs (faster and wilder the longer he hangs), trembles, grunts (three synthesised variants, haptic tick, "[STRAINING]" caption) and shouts "UNGH!/NOPE NOPE NOPE!"; the pull-up has a rising "HUP!". Tip boxes, toasts and zone banners moved to the top of the screen so they no longer cover the action.

**Still open:** screen-to-screen transitions, per-zone music variation, a dedicated hang/pull-up/rope animation set, trail/wind lines at pad-launch speed, UI button hover/idle animation, combo-style pitch rise on rapid landings.
