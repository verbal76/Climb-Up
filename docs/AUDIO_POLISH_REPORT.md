# Build 83 audio polish and game juice: report

Owner directive: "UPWARDLY BUILD 83 - GAME JUICE, SOUND DESIGN & AUDIO POLISH". Branch `av/build83-audio` (from `d218fa9`), kept separate from
the visual-polish branch until owner review. "Make the game feel better without making it play differently": nothing under `core/.../sim/`,
`assets/data/tuning.json`, collision, input timing, replays or determinism fixtures was changed. Every cue is derived in the presentation layer
from event bits or public counters the simulation already exposes.

How the numbers were measured:

- **L100** is the loudest 100 ms of a sound, K-weighted as in ITU-R BS.1770, in LUFS. It works for 40 ms clicks and 3 s fanfares alike.
- **True peak** is taken on a 4x-oversampled signal, in dBTP.
- **Desktop figures are the real output of the game's own `Audio` class.** It was driven through libGDX's desktop OpenAL backend with OpenAL Soft's
  wave-file writer as the output device (`desktop/src/test/.../AudioMixProbe.java`, analysed by `tools/audio/measure_mix.py`). The writer used
  float output with OpenAL Soft's output limiter off, so any overshoot is visible. These figures are stereo L100.
- **Android figures are computed, not recorded.** I had no device. SoundPool plays a mono effect at file level in both channels, so they are the
  file's L100 plus the game's play volume plus the slider. These figures are mono L100, about 3 dB below the stereo figures for the same signal.

## 1. EFFECTS VOLUME before and after

**What was wrong (measured):**

1. **The slider could not go louder.** It was `volume x step/10`, and libGDX clamps a Sound's volume to 1.0, so 10 could never be louder than the
   files.
2. **Spiky pack clips.** Many clips had sample peaks at or above 0 dBFS but were only -20 to -28 LUFS loud: grab, bounce-pad clang, landing thuds,
   UI click, footsteps. Normalising peaks cannot raise them.
3. **The CI conversion of the licensed Minifantasy clips** (dynamic `loudnorm`) left true peaks up to +1.6 dBTP (clipping) and variants of one
   sound up to 4.5 dB apart.
4. **Desktop only: a 7.9 dB loss per channel.** libGDX plays a mono effect at "pan 0" by placing its OpenAL source at (0, 0, 1), and OpenAL Soft
   renders that **7.87 dB down in each channel**. I calibrated this with 100 Hz, 1 kHz and 6 kHz tones; it is flat across frequency. Stereo music
   and Android's SoundPool play at file level. So on a PC every effect sat about 8 dB lower against the music than on a phone. Over the default
   music, Build 82's checkpoint chime was 1.6 dB *below* the music's loudest moments.

**New slider curve (`audio/SfxMix.bus`):**

- 0 is silence.
- 1 to 10 are equal 3.1 dB steps from -28 dB to 0 dB.
- Build 82: 1 was -20 dB, and 5 to 10 covered only 6 dB.

| step | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Build 82 gain (dB) | silent | -20.0 | -14.0 | -10.5 | -8.0 | -6.0 | -4.4 | -3.1 | -1.9 | -0.9 | 0 |
| Build 83 gain (dB) | silent | -28.0 | -24.9 | -21.8 | -18.7 | -15.6 | -12.4 | -9.3 | -6.2 | -3.1 | 0 |

The extra loudness at 10 comes from the mastered files and, on desktop, from the OpenAL level fix (sections 3 and 7).

**Saved settings:** the stored value is still 0..10, so the same number means the same slider position. No migration was needed. A file
without the key gets the default (9), and an out-of-range value is clamped. Music and effects stay independent (tested).

**Desktop, real output (stereo L100 in LUFS; peak in dBFS; caller volumes as in the game):**

| cue | build | @10 | @9 | @7 | @5 | @3 | @2 | @1 | @0 | peak @10 (dBFS) |
|---|---|---|---|---|---|---|---|---|---|---|
| jump | B82 | -18.2 | -19.2 | -21.3 | -24.3 | -28.7 | -32.2 | -38.2 | silent | -11.6 |
|  | B83 | -9.3 | -11.9 | -18.7 | -24.3 | -31.1 | -33.7 | -37.4 | silent | -3.9 |
| landing (grass, pack) | B82 | -24.6 | -26.6 | -27.7 | -31.0 | -36.2 | -39.8 | -45.2 | silent | -8.8 |
|  | B83 | -13.5 | -16.1 | -25.5 | -27.9 | -35.8 | -38.8 | -41.9 | silent | -1.9 |
| bounce pad | B82 | -28.3 | -30.9 | -30.8 | -35.8 | -40.1 | -43.6 | -49.4 | silent | -11.4 |
|  | B83 | -9.3 | -12.5 | -17.9 | -25.4 | -30.5 | -33.6 | -36.7 | silent | -2.3 |
| grab | B82 | -28.8 | -29.7 | -31.9 | -34.8 | -39.3 | -42.8 | -48.8 | silent | -11.2 |
|  | B83 | -17.6 | -20.7 | -26.9 | -33.2 | -39.4 | -42.5 | -45.6 | silent | -4.0 |
| checkpoint gem | B82 | -23.2 | -24.1 | -26.3 | -29.2 | -33.6 | -37.1 | -43.2 | silent | -16.7 |
|  | B83 | -5.9 | -9.0 | -15.3 | -21.5 | -27.7 | -30.8 | -33.9 | silent | -2.7 |
| hit by a hazard | B82 | -20.4 | -21.3 | -23.5 | -26.4 | -30.9 | -34.4 | -40.4 | silent | -11.7 |
|  | B83 | -5.4 | -9.6 | -14.7 | -22.0 | -27.2 | -31.4 | -33.4 | silent | -0.7 |
| cannon | B82 | -17.5 | -18.4 | -20.6 | -23.5 | -27.9 | -31.5 | -37.5 | silent | -9.2 |
|  | B83 | -7.6 | -10.7 | -17.0 | -23.2 | -29.4 | -32.5 | -35.6 | silent | -0.9 |
| key | B82 | -18.7 | -19.6 | -21.8 | -24.7 | -29.2 | -32.7 | -38.7 | silent | -9.0 |
|  | B83 | -5.4 | -8.5 | -14.7 | -20.9 | -27.2 | -30.3 | -33.4 | silent | -0.5 |
| menu click | B82 | -28.1 \* | -29.9 | -31.1 | -35.0 | -42.3 | -43.4 | -51.8 | silent | -8.9 \* |
|  | B83 | -11.5 | -14.6 | -22.0 | -29.4 | -35.2 | -38.2 | -40.5 | silent | -4.1 |
| footstep (stone, pack) | B82 | -26.5 | -29.7 | -33.6 | -32.7 | -41.3 | -42.9 | -50.6 | silent | -9.2 |
|  | B83 | -19.1 | -14.8 | -25.7 | -30.2 | -45.5 | -39.9 | -46.0 | silent | -1.3 |
| castle door | B82 | -20.8 | -20.2 | -22.2 | -25.6 | -29.6 | -33.3 | -39.1 | silent | -7.9 |
|  | B83 | -9.4 | -15.4 | -19.3 | -27.8 | -30.9 | -35.5 | -37.6 | silent | -0.8 |
| respawn | B82 | -17.4 | -17.9 | -20.1 | -23.0 | -27.4 | -30.9 | -37.0 | silent | -10.0 |
|  | B83 | -6.3 | -9.4 | -15.6 | -21.8 | -28.0 | -31.1 | -34.3 | silent | -2.9 |

Notes on the table:

- **Single-file cues show the curve cleanly** (checkpoint, key, cannon, respawn): about 3.1 dB per step.
- **Cues with several variants scatter by up to about 2 dB** (landing, footsteps, door, menu click), because a different variant plays at each
  step.
- **\* Measurement artefact:** in the final recording the Build 82 click at 10 measured -47.7. The value shown, -28.1 / -8.9, is the same cue from
  the first recording; Build 82's sounds are identical in both. The other Build 82 rows match the first recording within about 1 dB.
- **The footstep rows are not monotonic** (-45.5 at 3, -39.9 at 2). Each segment measures a single footstep and plays one random variant, and the
  stone variants differ by up to 1.1 dB, so the steps scatter. Read them as "about the same as Build 82 at matching settings", not as a curve.

**Android (computed per channel, mono L100 in LUFS):**

| cue | B82 @10 | B83 @10 | change @10 | B82 @5 | B83 @5 | B82 @2 | B83 @2 | B82 @1 | B83 @1 |
|---|---|---|---|---|---|---|---|---|---|
| jump | -13.4 | -11.8 | +1.6 | -19.4 | -27.3 | -27.4 | -36.7 | -33.4 | -39.8 |
| landing (grass, pack) | -18.5 | -16.1 | +2.4 | -24.6 | -31.7 | -32.5 | -41.0 | -38.5 | -44.1 |
| bounce pad | -19.4 | -11.2 | +8.2 | -25.4 | -26.7 | -33.4 | -36.1 | -39.4 | -39.2 |
| grab | -24.0 | -20.5 | +3.5 | -30.0 | -36.1 | -37.9 | -45.4 | -44.0 | -48.5 |
| checkpoint | -18.3 | -8.9 | +9.4 | -24.3 | -24.5 | -32.3 | -33.8 | -38.3 | -36.9 |
| hit | -15.6 | -9.5 | +6.1 | -21.6 | -25.1 | -29.5 | -34.4 | -35.6 | -37.5 |
| cannon | -12.6 | -10.6 | +2.0 | -18.7 | -26.2 | -26.6 | -35.5 | -32.6 | -38.6 |
| key | -13.9 | -8.4 | +5.5 | -19.9 | -24.0 | -27.9 | -33.3 | -33.9 | -36.4 |
| castle door | -13.8 | -12.0 | +1.8 | -19.9 | -27.6 | -27.8 | -36.9 | -33.9 | -40.0 |
| respawn | -12.1 | -9.2 | +2.9 | -18.2 | -24.8 | -26.1 | -34.1 | -32.1 | -37.2 |
| menu click | -23.2 | -14.5 | +8.7 | -29.2 | -30.1 | -37.2 | -39.4 | -43.2 | -42.5 |
| footstep (stone, pack) | -23.3 | -23.8 | -0.5 | -29.3 | -39.4 | -37.3 | -48.7 | -43.3 | -51.8 |
| bee buzz | -13.6 | -14.5 | -0.9 | -19.6 | -30.1 | -27.6 | -39.4 | -33.6 | -42.5 |
| Enforcer step | -30.8 | -16.0 | +14.9 | -36.8 | -31.5 | -44.8 | -40.8 | -50.8 | -44.0 |
| saw | -21.1 | -16.8 | +4.3 | -27.2 | -32.3 | -35.1 | -41.7 | -41.1 | -44.8 |
| spikes | -11.0 | -11.8 | -0.8 | -17.1 | -27.4 | -25.0 | -36.7 | -31.0 | -39.8 |

- **At 10 on a phone:** the important cues gain 1.6 to 15 dB.
- **Three cues are 0.5 to 0.9 dB lower:** bee buzz, footsteps and spikes. They were already among the loudest, or were clipping (spikes peaked at
  +1.1 dBFS).
- **The middle of the slider is quieter than before on both platforms** (see section 11).

## 2. Audio asset inventory

**Sources found in the repository (the "additional pack" was located, not assumed):**

- **Original synthesised effects:** `assets/audio/*.wav`, made by `tools/gen_audio.py`, plus the 13 new Build 83 cues from
  `tools/audio/gen_cues83.py`.
- **jeageroni "FREE Retro Action Platformer Sound Effects" (56 clips):** owner-supplied, author's licence (modification allowed, no standalone
  redistribution). Files in `assets/audio/pack/*.ogg`, mapped by `assets/data/sfx.json`.
- **Helton Yan "FREE Retro Mecha SFX" (8 clips, the PUNISHER Enforcer):** CC BY 4.0, owner exception, credited.
- **Leohpaz "Minifantasy - Forgotten Plains Audio Pack":**
  - It is the additional pack. The owner licensed it; it may not be redistributed.
  - It is **not in the repository.** CI fetches it from the owner's draft `Sfx` release (`tools/ci/fetch_pack_sfx.sh`).
  - The worktree has no `assets/audio/pack_mf/`, so a local run uses the built-in fallbacks.
  - For measurement I used the owner's copy already in the session's scratch area (sha256 `d94e9fd5...`, equal to the pinned hash). It was
    converted only into scratch folders; nothing from it is committed.
  - It holds 37 clips: footsteps, landings, wood/brick hits, rustling, fruit drop, water and other ambience loops. The game uses 20 of them.
    None of the rest fills a gap (there is no jump, pickup or UI sound in it), so no new pack clip was added.
- **Music:** owner-supplied (Suno), unchanged.

**Effects-to-music chain (Build 83):**

`clip (mastered) x caller volume x SfxMix (copy cap, gap, voice budget, equal-power stack scaling, peak budget, texture duck) x slider curve -> Sound.play`.

- On desktop, `OpenAlLevels` then sets the voice non-spatial and restores 4.5 dB as source gain.
- The conveyor hum, the falling sound and the ambience beds follow the same slider curve. The hum also follows the duck.
- Music: `music/10 x 0.55` (unchanged).

**Platform paths:**

- **Android:** SoundPool with 24 streams, decoded to PCM at load.
- **Desktop:** OpenAL with 16 sources (libGDX default), OGG decoded at load.
- Music streams on both.

**Sound-event inventory (what plays when; class / copy cap / gap from `assets/data/sfx_mix.json`):**

| trigger (presentation, observe-only) | sound | file(s) Build 83 (all mastered) | class |
|---|---|---|---|
| EV_JUMP | jump | pack woosh_2 / woosh_1 | action |
| EV_LAND | land_* / hit_wood (pack groups by surface), else land | Minifantasy landings (CI) / pack thud1, thudd3 | action |
| EV_LAND at landSpeed > 15 | + thump **(new)** | thump.wav | action |
| EV_BOUNCE (bounce pad) | bounce | **boing.wav (new; was clang10/11)** | action |
| turbo ramp launch (sim.turboLaunches counter) | turbo **(new)**, replaces the pad boing on that launch | turbo.wav | action |
| turbo flight cancelled (sim.turboCancels) | fizzle **(new)** | fizzle.wav | action |
| timed rebound (sim.rebounds) | rebound **(new)** | rebound.wav | action |
| EV_GRAB | grab | pack clickandrelease_1 | action |
| EV_GRAB + EV_ROPE / EV_CABLE / LEDGE | + ropecatch / zing / slap **(new)** | ropecatch / zing / slap.wav | action |
| rope climbing (stick held) | rope (every 0.32 s) | rope.wav | texture |
| cable shimmy (stick held) | zip **(new)** (every 0.28 s) | zip.wav | texture |
| ledge hang | grunt1-3 (per-character pitch) | grunt*.wav | action |
| EV_PULL | pull + effort (per-character voice) | pack snap_3/4 + **effort.wav (was snap_1/2, the spike trap's clips)** | action |
| footsteps (running) | step_* (pack groups) else step | Minifantasy steps (CI) / pack unclick | texture |
| real fall (>0.22 s, vy < -9) | fall (one voice, cut when it ends) | pack fall | texture |
| EV_CHECKPOINT | checkpoint | pack sucesss2 | major |
| every 50 m | checkpoint at pitch 1.35 | pack sucesss2 | major |
| EV_RESPAWN | respawn | **warp.wav (new; was woosh_3, also a jump whoosh)** | major |
| EV_HIT | hit + hit_brick | pack crunch_1/2 + Minifantasy | major |
| EV_KEY / EV_CLUB | key | pack sucess1 | major |
| EV_DOOR (castle) | door | pack thudd3 | major |
| castle personal best (with SHOW BEST TIMES) | pb **(new)** | pb.wav | major |
| EV_BLOCKED (locked gate) | locked | pack clang8/9 | major |
| EV_FINISH | win | pack brass1/3 | major |
| EV_CRUMBLE | crumble + hit_wood | pack crunch_3 + Minifantasy | action |
| EV_SWING (club) | swing | pack woosh_1-3 | action |
| EV_SHOVE | bonk + clack | pack thud1/2 + clack.wav | action |
| EV_SLIP | slip | pack parry2/3 | action |
| EV_SQUASH / EV_LAUNCH / EV_CRAB_OFF | splat, bonk / bounce / squeak, clack | various | action |
| EV_DASH (skull) | dash | dash.wav | action |
| EV_BLOB_JUMP / EV_BLOB_LAND | blobup / splat | synth | action |
| EV_ENF_ALERT / EV_ENF_HIT / EV_ENF_JUMP | enf_alert / enf_hit / enf_jet | Helton Yan (mastered) | major / action |
| EV_AVL_WARN, avalanche impacts (sim.avlImpacts) | slam + hit_brick | slam.wav + Minifantasy | action |
| EV_AIM (turret) | cannon at pitch 0.85 | pack shell_explosion_1 | action |
| cannon / mortar / spike trap cycles (nearby) | cannon / cannon at pitch 1.25 / spikes | pack shell_explosion_1 / snap_1, snap_2 | action |
| spike drop | slam + hit_brick | synth + Minifantasy | action |
| saw (nearby) | saw | **saw.wav (was scratch2, the rope's own creak)** | texture |
| bee (nearby) / dive | buzz / dive | synth | texture / action |
| crab, skull mood, blob wobble, Enforcer steps | clack / skull / wobble / enf_step1-4 | synth / Helton Yan | texture / action |
| conveyor nearby | motor (one looping voice) | motor.wav (level unchanged) | loop |
| world ambience | amb_birds / amb_wind / amb_crickets (Music) | Minifantasy (CI) | bed |
| cloud burst, fly-by ship, scan ship, speech bubbles | poof / whoosh / scan / talk | synth / pack transition, textscroll | action / texture |
| menus: choose, back / cancel | click / back **(new)** | **select.wav (new; was pack click_1)** / back.wav | texture |

## 3. Replaced sounds (all reversible)

**Previous mapping:** kept in `assets/data/sfx.json` under `_build82`. Copy the entries back to restore them.

**Originals:** every original file is untouched. Mastering writes copies to `assets/audio/mastered/`. Setting `"mastered": false` in
`assets/data/sfx_mix.json`, or deleting the folder, plays the originals again.

| game sound | Build 82 | Build 83 | why |
|---|---|---|---|
| bounce (pad) | pack clang10 / clang11 (about -25 LUFS, a thin metal clang) | boing.wav: a knock plus a sprung, wobbling rise with a faint metal ring | weak and quiet; a pad should "boing" |
| respawn | pack woosh_3 (also one of the jump and club whooshes) | warp.wav: a quick rising shimmer | shared identity; a retry should feel instant and positive |
| saw | pack scratch2 (**the same clip as the rope creak**) | saw.wav (original whir) | a hazard sounded like your own rope climb |
| effort (pull-up) | pack snap_1 / snap_2 (**the same clips as the spike trap**) | effort.wav, the per-character "huup" voice | a safe pull-up sounded like a spike trap |
| click (menus) | pack click_1 (a 50 ms spike, about -22 LUFS; cannot be made louder without distortion) | select.wav: a soft rising blip | inaudible at 10; pairs with the new back blip |

## 4. Added sounds (original, `tools/audio/gen_cues83.py`; own seeded generator, no earlier file changed)

boing, turbo, fizzle, rebound, ropecatch, zing, slap, zip, thump, warp, pb, back, select. All are 22.05 kHz mono and band-limited by construction
(sines, filtered noise, gentle FM). Spectrograms were checked; the menu blips got a short end fade after the check showed a cut-off click.

**Not auditioned by ear: I have no audio output in this environment.** Every new sound was designed and checked by measurement and spectrogram
only, so the owner should listen to them.

## 5. Events that now have audio (or their own audio)

- **Turbo ramp:** the booster lights, and the flight ends with a fizzle when you cancel it. Before, a turbo launch made the bounce-pad sound and
  the cancel was silent.
- **Timed rebound** off a cannon ball or saw (was silent).
- **Catching things:** a rope, a cable and a ledge now each sound different under the shared grab click (rope creak, wire ring, hand slap).
- **Cable movement:** hand over hand along a cable (was silent; rope climbing already had a creak).
- **Hard landing:** a long-fall landing gets extra weight.
- **Personal best:** a castle split or total PB gets its own sting (only with SHOW BEST TIMES on, like the PB text).
- **Menus:** back / cancel now sounds different from choosing.
- **New captions** (only with captions on): [BOOSTER], [PING], [NEW BEST].

## 6. Juice (`core/.../fx/Juice.java`, presentation only)

**Accents added:**

- Rope catch: a few rope fibres.
- Cable catch: a spray of small sparks along the wire.
- Ledge catch: a puff of grit from the edge.
- Hard landing: a low dust ring skimming out both ways.
- Turbo launch: a flame flash flung back, then exhaust embers for the length of the powered flight.
- Turbo cancel: a grey puff.
- Rebound: a tight white star where it met.
- Castle PB: a small confetti burst above the head (gold / mint / pink, so it never reads as a hazard).

Landing dust, bounce rings, crumble debris, gem bursts, avalanche impact dust, key and door bursts and respawn bursts were already there and were
left alone.

**Rules:**

- Every accent is 4 to 14 cube particles near the player, gone in 0.2 to 1.0 s, so nothing covers the route.
- Nothing new shakes the camera, flashes the screen or adds hit-stop.
- All of it is off with REDUCED MOTION and at LOW graphics quality, which keeps the existing minimal-effects presentation.
- The particle pool stays the renderer's fixed 96 cubes, so there is no allocation per effect.

**Not captured:** I did not take screenshots of the juice. It is only on screen during specific live events (rope, cable, turbo, rebound, PB) that
the existing shot harnesses do not stage.

## 7. Clipping and mixing results

**Masters:**

- Every mastered file decodes at or under -0.5 dBTP; most are at -1.0 dBTP (`SfxMixTest` checks `sfx_levels.json`).
- Limiting stays gentle: at most 6 dB of soft clipping on spikes and at most 6 dB of limiting.
- 53 of 92 files stop short of their class target to keep their transients (the full per-file table is in `assets/data/sfx_levels.json`).
- The Minifantasy clips from the new CI conversion decode at -0.5 to -3.0 dBTP. Build 82's conversion went up to +1.6 dBTP.

**Worst-case pile-up, desktop, real output:** 14 effects within 80 ms at EFFECTS VOLUME 10 (hit, cannon, slam, spikes, landing, crumble, pad,
Enforcer alert, key, splat, bonk, saw, buzz, clack).

| build | output peak (3 runs, dBFS) | samples over full scale |
|---|---|---|
| Build 82 | -0.5, -0.2, -0.3 | 0 (only because of the 7.9 dB desktop pan loss) |
| Build 83 before the peak budget | +1.2 to +1.7 | 56 to 110 per run |
| Build 83 final | **-1.9, -1.0, -1.9** | **0** |

**What fixed the pile-up:**

- A peak budget: the peaks of effects starting within 100 ms may not sum past full scale.
- A major cue makes room by turning down the voices that just started, minor ones first (`Sound.setVolume`).
- Pile-ups beyond three effects are scaled equal-power.
- A 14-voice budget.
- `SfxMixTest` replays the same pile against the shipped data.

**Music on and off** (desktop, music 7, effects 9; music alone measures -22.5 LUFS stereo L100):

| cue | Build 83 over the music | Build 82 estimate (effects 9) |
|---|---|---|
| jump | -11.8 (10.7 dB above the music) | -19.2 (3.3 dB above) |
| checkpoint | -8.9 (13.6 dB above) | -24.1 (1.6 dB *below*) |
| hit | -8.3 (14.2 dB above) | about -21 |

**Ducking:** major cues dip texture sounds (steps, creaks, buzzes, hum) by 6 dB for 0.35 s, so a warning cuts through a busy scene.

## 8. Performance (Android and desktop)

- **Decoded PCM of every loaded effect:** 4.10 MB in Build 83 (including the 13 new cues) against 4.53 MB for the same Build 82 set. Trimming the
  long silent pack tails saved more than the new cues cost.
- **Repository / package size:** `assets/audio/mastered/` adds about 2.0 MB (60 small 22 kHz WAVs plus 32 OGG q6). The originals stay beside them,
  so the APK and desktop packages grow by about that, less zip compression.
- **Voices:** at most 14 effects counted at once, against Android's 24 streams and desktop OpenAL's 16 sources (music and the ambience bed have
  their own).
- **Repeats:** every sound has a copy cap and a minimum gap. Steps and zips are paced; a runaway caller cannot flood the pool (tested).
- **Allocation per effect:** fixed arrays in `SfxMix`. The only per-play allocation is one boxed timestamp per sound name (as in Build 82).
- **Decoding:** no repeated decoding; everything is decoded once at load, as before.
- **Desktop level fix cost:** three `alSource*` calls per started effect.
- **Not measured:** I measured no frame times. The changes add no rendering beyond up to 14 particles per event, from the existing pool.

## 9. Screenshots and recordings

- **Audio recordings** (made, not committed; they are large and the Minifantasy clips must not be redistributed). About 4.5-minute OpenAL wave captures
  of the probe were made for each stage: before the desktop fix, after it, and final.
- **To reproduce:**
  1. `./gradlew :desktop:testClasses`.
  2. In an assets folder, run `ALSOFT_CONF=<conf with drivers=wave, sample-type=float32, output-limiter=false, [wave] file=out.wav> java -cp <desktop test classpath> com.hotatticgames.climbup.desktop.AudioMixProbe timeline.tsv <old-files-dir or ->`.
  3. Then run `python3 -I tools/audio/measure_mix.py out.wav timeline.tsv`.
- **Desktop smoke run:** see the end of this report.

## 10. Branch and commits

Branch `av/build83-audio`, from `d218fa9`. The commits are listed in the hand-off note and by `git log d218fa9..av/build83-audio`.

## 11. Outstanding decisions for the owner

1. **Listen.** Nothing here was auditioned by ear (no audio device in the build environment). Please play a climb with music on and off, at 10 and
   at a low setting. Every level is data:
   - `assets/data/sfx_mix.json`: class targets, slider range, caps, duck.
   - The play volumes in the code.
   - Re-run `python3 -I tools/audio/master_sfx.py` after changing a target.
2. **The middle of the slider is quieter than before.** The new curve has equal steps (3.1 dB each). Build 82's linear slider barely changed from
   5 to 10. So a saved 5 now plays about 4 to 9 dB quieter than Build 82's 5, while 9 and 10 are louder. The bottom of the range is
   `curve.bottomDb` (-28 dB). Raising it (for example to -22) makes the middle louder.
3. **Effects against the music.** At the defaults (music 7, effects 9) effects now sit 10 to 14 dB above the music's loudest moments (Build 82 on a
   PC: about level with it, sometimes below). If that is too forward, lower the defaults or the class targets.
4. **Desktop level fix** (`desktop/OpenAlLevels`, on by default, `-Dclimb.noAlLevels=true` turns it off).
   - It makes desktop match Android, so a PC player at 10 hears effects 9 to 19 dB louder than Build 82.
   - Side effect: libGDX reuses OpenAL sources, so a mono ambience bed that lands on a source an effect used plays non-spatial, about 3.4 dB louder
     than otherwise. That is quiet either way.
5. **Mastered copies of the jeageroni clips in the public repository.** They are the same licence position as the raw files already there:
   modification is allowed, standalone redistribution is not. The open DECISIONS item about keeping those files in a public repo now covers them
   too.
6. **Helton Yan clips (CC BY 4.0) are now level-matched and peak-limited.** ASSETS.md records the modification; the credit is unchanged.
7. **Cues that would need a new simulation event (not added, by rule):**
   - A distinct sound when a crumbling platform first starts to give way, as opposed to when it breaks. Only the break has an event.
   - A pre-fire "fuse" for side cannons. Their timing is visible to the presentation layer but there is no event; it could be derived from the
     cycle phase as the existing boom is, if wanted.
   - A seesaw tipping-over moment (only the landing has a sound; the creak is state-driven).
8. **Not done:** extra Minifantasy clips (none fills a gap), a screen-shake slider (no new shake was added, so none was needed), and screenshots of
   the juice.
