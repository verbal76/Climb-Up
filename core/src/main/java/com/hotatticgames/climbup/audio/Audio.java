package com.hotatticgames.climbup.audio;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Disposable;
import com.hotatticgames.climbup.Settings;
import java.util.HashMap;
import java.util.Map;

/**
 * Effects and music. Effects are the game's own (assets/audio, tools/gen_audio.py and tools/audio/gen_cues83.py), the owner's packs mapped by
 * assets/data/sfx.json, and the optional licensed Minifantasy clips; Build 83 plays the mastered copy of each (assets/audio/mastered,
 * tools/audio/master_sfx.py) and routes every effect through {@link SfxMix} (slider curve, voice limits, ducking, stacking guard).
 */
public final class Audio implements Disposable {
    private final Map<String, Sound> sounds = new HashMap<>();
    private final Map<String, Music> tracks = new HashMap<>();
    private final Map<String, Sound[]> variants = new HashMap<>();     // owner-supplied sound pack, chosen per sound by assets/data/sfx.json
    private final Map<String, Integer> lastVariant = new HashMap<>();
    private final Map<String, Sound[]> mf = new HashMap<>();           // OPTIONAL Minifantasy Forgotten Plains clips (assets/audio/pack_mf, fetched at build time, never in the repo): absent = the caller falls back
    private final Map<Sound, Float> lengths = new HashMap<>();          // seconds per clip (sfx_levels.json), for the voice accounting
    private final Map<Sound, Float> peaks = new HashMap<>();            // linear true peak per clip (sfx_levels.json), for the peak budget
    private final Map<String, Float> levelPeak = new HashMap<>();

    /**
     * Platform level hook (Build 83): the desktop launcher installs one that corrects OpenAL's level for each effect voice (libGDX places a mono
     * effect where OpenAL Soft renders it 7.9 dB down per channel, while Android's SoundPool and the stereo music play at file level). Called
     * after an effect starts and whenever a looping effect's volume changes, with the volume the game gave it. Null on Android and in tests.
     */
    public interface LevelHook { void level(Sound sound, long id, float volume); }
    public static volatile LevelHook levelHook;
    /** Build 83 effects bus; public for the About line and tests. */
    public final SfxMix mix;
    private final Map<String, Float> levelDur = new HashMap<>();
    private final Settings settings;
    private Music current;
    private String currentName = "";
    private String[] list;
    private int listIdx;
    public static final String[] GAME_TRACKS = {"music_game", "music_leaplike", "music_mountain_jig", "music_track4"};
    public static final String[] SFX = {"jump", "land", "bounce", "grab", "pull", "crumble", "checkpoint", "respawn", "win", "click", "rope", "step", "grunt1", "grunt2", "grunt3", "effort", "hit", "cannon", "saw", "spikes", "key", "door", "locked", "swing", "bonk", "squeak", "poof", "buzz", "whoosh",
            "dash", "skull", "wobble", "blobup", "splat", "clack", "dive", "slam", "motor", "scan",
            "enf_step1", "enf_step2", "enf_step3", "enf_step4", "enf_stomp", "enf_alert", "enf_hit", "enf_jet",          // PUNISHER Enforcer: Helton Yan, Mecha Footsteps (CC BY 4.0, owner exception; tools/audio/mecha_steps.py)
            "boing", "turbo", "fizzle", "slap", "zing", "zip", "ropecatch", "thump", "warp", "pb", "back", "rebound", "select"};   // Build 83 cues (tools/audio/gen_cues83.py)

    public Audio(Settings s) {
        settings = s;
        mix = new SfxMix().load(text("data/sfx_mix.json"));
        try {                                                   // clip lengths of the mastered files (and of the originals, as measured)
            com.badlogic.gdx.utils.JsonValue lv = new com.badlogic.gdx.utils.JsonReader().parse(text("data/sfx_levels.json")).get("files");
            for (com.badlogic.gdx.utils.JsonValue e = lv == null ? null : lv.child; e != null; e = e.next) {
                com.badlogic.gdx.utils.JsonValue o = e.get(mix.mastered ? "out" : "in");
                if (o != null) { levelDur.put(e.name, o.getFloat("dur", 0.6f)); levelPeak.put(e.name, (float) Math.pow(10.0, o.getFloat("truePeakDb", -1f) / 20.0)); }
            }
        } catch (Exception ignored) { }
        for (String n : SFX) {
            Sound snd = load(n, "audio/" + n + ".wav");
            if (snd != null) sounds.put(n, snd);
        }
        try {
            com.badlogic.gdx.utils.JsonValue map = new com.badlogic.gdx.utils.JsonReader().parse(Gdx.files.internal("data/sfx.json"));
            for (com.badlogic.gdx.utils.JsonValue e = map.child; e != null; e = e.next) {
                if (e.name == null || e.name.startsWith("_") || !e.isArray()) continue;
                java.util.ArrayList<Sound> list = new java.util.ArrayList<>();
                for (com.badlogic.gdx.utils.JsonValue f = e.child; f != null; f = f.next) {
                    String n = f.asString();
                    Sound snd = n.startsWith("@") ? own(n.substring(1)) : load("p_" + n, "audio/pack/" + n + ".ogg");    // @name = one of the game's own effects
                    if (snd != null) list.add(snd);
                }
                if (!list.isEmpty()) variants.put(e.name, list.toArray(new Sound[0]));
            }
        } catch (Exception ignored) { }
        for (String g : PACK_GROUPS) {
            java.util.ArrayList<Sound> list = new java.util.ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                try {
                    com.badlogic.gdx.files.FileHandle f = Gdx.files.internal("audio/pack_mf/" + g + "_" + i + ".ogg");
                    if (f.exists()) { Sound snd = Gdx.audio.newSound(f); lengths.put(snd, g.startsWith("step") ? 0.5f : 0.9f); peaks.put(snd, 0.95f); list.add(snd); }    // CI converts them to decode at or under about -0.5 dBTP (fetch_pack_sfx.sh)
                } catch (Exception ignored) { }
            }
            if (!list.isEmpty()) mf.put(g, list.toArray(new Sound[0]));
        }
    }

    private static String text(String path) {
        try { com.badlogic.gdx.files.FileHandle f = Gdx.files.internal(path); return f.exists() ? f.readString("UTF-8") : null; } catch (Exception e) { return null; }
    }
    /** Loads the mastered copy (audio/mastered/<key>.wav or .ogg) when mastering is on and it exists, else the original; remembers its length. */
    private Sound load(String key, String original) {
        try {
            com.badlogic.gdx.files.FileHandle f = null;
            if (mix.mastered) { f = Gdx.files.internal("audio/mastered/" + key + ".wav"); if (!f.exists()) f = Gdx.files.internal("audio/mastered/" + key + ".ogg"); }
            if (f == null || !f.exists()) f = Gdx.files.internal(original);
            if (!f.exists()) return null;
            Sound snd = Gdx.audio.newSound(f);
            lengths.put(snd, levelDur.getOrDefault(key, 0.6f)); peaks.put(snd, levelPeak.getOrDefault(key, 1f));
            return snd;
        } catch (Exception e) { return null; }
    }
    /** One of the game's own effects by name (shared with the sfx.json entries that point at it). */
    private Sound own(String name) {
        Sound snd = sounds.get(name);
        if (snd == null) { snd = load(name, "audio/" + name + ".wav"); if (snd != null) sounds.put(name, snd); }
        return snd;
    }
    private static double now() { return System.nanoTime() / 1e9; }
    /** The effects bus gain for the current EFFECTS VOLUME (0 = silent). */
    public float bus() { return mix.bus(settings.sfx); }
    /** Cosmetic variation from the audio's own generator (never the simulation's or MathUtils'): base + 0..spread. */
    public float vary(float base, float spread) { return mix.vary(base, spread); }

    /** Pack clip groups (files pack_mf/<group>_1..3.ogg, made by tools/ci/fetch_pack_sfx.sh). */
    public static final String[] PACK_GROUPS = {"step_grass", "step_dirt", "step_stone", "land_grass", "land_stone", "hit_wood", "hit_brick"};
    /** Quiet looping ambience beds (pack_mf/amb_*.ogg), chosen by world. */
    public static final String[] AMBIENCE = {"amb_birds", "amb_wind", "amb_crickets"};

    /**
     * Plays one random variant of an optional pack clip group. Returns false when the pack is not installed (tests, ci builds), so the caller can fall back to
     * the built-in sound; returns true when the group exists (even if the play was rate limited or sfx is off) so the fallback is not layered on top.
     */
    public boolean playPack(String group, float vol, float pitch) {
        Sound[] v = mf.get(group);
        if (v == null) return false;
        if (settings.sfx <= 0) return true;
        int i = mix.pick(v.length, lastVariant.getOrDefault("mf_" + group, -1));
        start(group, v[i], vol, pitch);
        lastVariant.put("mf_" + group, i);
        return true;
    }
    public boolean hasPack(String group) { return mf.containsKey(group); }

    // ---- conveyor motor hum: ONE looping instance, volume eased toward a target (fades in and out, never stacks)
    private long humId = -1; private float humVol;
    /** Call every frame while playing: target 0..1 (0 = silent). The loop starts when the target rises and stops once it has faded out. */
    public void hum(float target, float dt) {
        Sound s = sounds.get("motor");
        if (s == null || settings.sfx <= 0) { humStop(); return; }
        float k = Math.min(1f, dt * (target > humVol ? 4f : 2.5f));
        humVol += (target - humVol) * k;
        if (humVol < 0.01f && target <= 0f) { humStop(); return; }
        try {
            if (humId == -1) { humId = s.loop(0f, 1f, 0f); }
            if (humId != -1) { float v = humVol * HUM_LEVEL * bus() * mix.duckNow(now()); s.setVolume(humId, v); if (levelHook != null) levelHook.level(s, humId, v); }
        } catch (Exception e) { errors++; lastError = e.getClass().getSimpleName(); humId = -1; }
    }
    public static final float HUM_LEVEL = 0.22f;
    public void humStop() {
        try { Sound s = sounds.get("motor"); if (s != null && humId != -1) s.stop(humId); } catch (Exception e) { errors++; lastError = e.getClass().getSimpleName(); }
        humId = -1; humVol = 0f;
    }

    // ---- optional quiet biome ambience (Music so the bed streams): fades out the old bed, then in the new one; follows the sfx volume, never the music setting
    private final Map<String, Music> beds = new HashMap<>();
    private String bedName = "", bedWant = ""; private Music bed; private float bedVol;
    public static final float BED_LEVEL = 0.10f;
    public void ambience(String name, float dt) {
        bedWant = name == null ? "" : name;
        if (!bedName.equals(bedWant)) {
            bedVol -= dt * 0.8f;
            if (bedVol <= 0f) {
                bedVol = 0f;
                if (bed != null) { try { bed.pause(); } catch (Exception ignored) { } }
                bed = null; bedName = "";
                if (!bedWant.isEmpty()) {
                    Music m = beds.get(bedWant);
                    if (m == null) {
                        try { com.badlogic.gdx.files.FileHandle f = Gdx.files.internal("audio/pack_mf/" + bedWant + ".ogg"); if (f.exists()) { m = Gdx.audio.newMusic(f); m.setLooping(true); beds.put(bedWant, m); } }
                        catch (Exception e) { m = null; }
                    }
                    if (m != null) { bed = m; bedName = bedWant; }
                }
            }
        } else if (bed != null) bedVol = Math.min(1f, bedVol + dt * 0.5f);
        if (bed == null) return;
        try {
            float v = bedVol * BED_LEVEL * bus();
            bed.setVolume(v);
            if (v > 0f && !bed.isPlaying()) bed.play(); else if (v <= 0f && bed.isPlaying()) bed.pause();
        } catch (Exception e) { errors++; lastError = e.getClass().getSimpleName(); }
    }
    /** Stops the hum and pauses the ambience (pause menu, leaving the screen, app backgrounded). */
    public void stopLoops() {
        humStop();
        try { if (bed != null && bed.isPlaying()) bed.pause(); } catch (Exception ignored) { }
        bedVol = 0f;
    }

    public void play(String name, float vol, float pitch) {
        Sound s = sounds.get(name);
        Sound[] v = variants.get(name);
        int i = -1;
        if (v != null) { i = mix.pick(v.length, lastVariant.getOrDefault(name, -1)); s = v[i]; }
        if (s == null || settings.sfx <= 0) return;
        if (start(name, s, vol, pitch) && i >= 0) lastVariant.put(name, i);
    }

    /**
     * Starts one effect through the bus: the mix decides whether it may start now (gap, copies, voice budget - a runaway caller can never flood
     * the platform's sound pool, which Android silently stops serving when saturated) and how much it is ducked or stack-scaled; the slider
     * curve sets the rest. Returns true when it was started.
     */
    private boolean start(String name, Sound s, float vol, float pitch) {
        float len = lengths.getOrDefault(s, 0.6f) / Math.max(0.25f, pitch), v = MathUtils.clamp(vol, 0f, 1f) * bus();
        double t = now();
        float g = mix.admit(name, len, v * peaks.getOrDefault(s, 1f), t);
        if (g < 0f) { throttled++; return false; }
        if (mix.squeeze < 1f || mix.squeezeMajor < 1f) squeezeRecent(mix.squeeze, mix.squeezeMajor, t);
        try {
            long id = s.play(v * g, pitch, 0f);
            if (id == -1) refused++;
            else {
                if (levelHook != null) levelHook.level(s, id, v * g);
                int k = recentNext; recentNext = (recentNext + 1) % RECENT;          // remembered briefly, so a major cue can make room (SfxMix.squeeze)
                recentSound[k] = s; recentId[k] = id; recentVol[k] = v * g; recentAt[k] = t; recentMinor[k] = mix.entry(name).prio < SfxMix.MAJOR;
            }
            played++;
        } catch (Exception e) { errors++; lastError = e.getClass().getSimpleName(); }
        return true;
    }

    private static final int RECENT = 16;
    private final Sound[] recentSound = new Sound[RECENT];
    private final long[] recentId = new long[RECENT];
    private final float[] recentVol = new float[RECENT];
    private final double[] recentAt = new double[RECENT];
    private final boolean[] recentMinor = new boolean[RECENT];
    private int recentNext;
    /** Turns down the effects that started within the mix's peak window (a major cue needs the room): minor ones by f, major ones by fMajor. */
    private void squeezeRecent(float f, float fMajor, double t) {
        for (int k = 0; k < RECENT; k++) {
            if (recentSound[k] == null || t - recentAt[k] >= mix.peakWindow) continue;
            float by = recentMinor[k] ? f : fMajor;
            if (by >= 1f) continue;
            recentVol[k] *= by;
            try { recentSound[k].setVolume(recentId[k], recentVol[k]); if (levelHook != null) levelHook.level(recentSound[k], recentId[k], recentVol[k]); } catch (Exception ignored) { }
        }
    }

    private long fallId = -1; private Sound fallSnd; private boolean falling;
    /** The long falling sound (owner-supplied): starts once when a real fall begins and is cut off when it ends. */
    public void fallStart(float vol) {
        if (falling || settings.sfx <= 0) return;
        Sound[] v = variants.get("fall"); if (v == null) return;
        falling = true; fallSnd = v[0];
        try { float fv = MathUtils.clamp(vol, 0, 1) * bus(); fallId = fallSnd.play(fv, 1f, 0f); if (fallId != -1 && levelHook != null) levelHook.level(fallSnd, fallId, fv); played++; } catch (Exception e) { errors++; lastError = e.getClass().getSimpleName(); }
    }
    public void fallStop() {
        if (!falling) return;
        falling = false;
        try { if (fallSnd != null && fallId != -1) fallSnd.stop(fallId); } catch (Exception e) { errors++; lastError = e.getClass().getSimpleName(); }
        fallId = -1;
    }
    public boolean isFalling() { return falling; }

    private int played, throttled, refused, errors, musicRestarts;
    private String lastError = "-";
    /** One line for Settings > About: what the audio system has been doing (helps when a phone loses sound). */
    public String diag() {
        String m = current == null ? "NONE" : (current.isPlaying() ? "PLAYING" : "STOPPED");
        return "AUDIO: MUSIC " + m + "  FX " + played + " (REFUSED " + refused + ", ERR " + errors + " " + lastError + ")  RESTARTS " + musicRestarts;
    }
    /** Settings > About > restart audio: rebuilds the music player and starts the current track again. */
    public void restart() {
        musicRestarts++;
        mix.reset();
        try {
            for (Music m : tracks.values()) { try { m.setOnCompletionListener(null); m.stop(); m.dispose(); } catch (Exception ignored) { } }
            tracks.clear(); current = null;
            String n = currentName; String[] l = list; currentName = "";
            if (l != null) { list = null; playlist(l); } else if (!n.isEmpty()) music(n);
        } catch (Exception e) { errors++; lastError = e.getClass().getSimpleName(); }
    }

    public void play(String name) { play(name, 0.9f, 1f); }

    private Music load(String name) {
        Music m = tracks.get(name);
        if (m == null) {
            try {
                com.badlogic.gdx.files.FileHandle f = Gdx.files.internal("audio/" + name + ".ogg");
                if (!f.exists()) f = Gdx.files.internal("audio/" + name + ".wav");
                m = Gdx.audio.newMusic(f);
            } catch (Exception e) { return null; }
            tracks.put(name, m);
        }
        return m;
    }

    /** Looping single track (menu). */
    public void music(String name) {
        if (name.equals(currentName)) { applyVolume(); return; }
        if (current != null) { current.setOnCompletionListener(null); current.stop(); }
        Music m = load(name);
        if (m == null) return;
        m.setLooping(true); m.setOnCompletionListener(null);
        current = m; currentName = name; list = null; applyVolume(); if (settings.music > 0) m.play();
    }

    /** Plays the tracks one after another, forever, starting at a random one. */
    public void playlist(String... names) {
        if (list != null && currentName.equals("playlist")) { applyVolume(); return; }
        if (current != null) { current.setOnCompletionListener(null); current.stop(); }
        list = names; listIdx = mix.pick(names.length, -1); currentName = "playlist";
        startListTrack();
    }

    private void startListTrack() {
        if (list == null) return;
        Music m = load(list[listIdx]);
        if (m == null) { current = null; return; }
        m.setLooping(false);
        m.setOnCompletionListener(done -> Gdx.app.postRunnable(() -> {
            if (list == null || current != done) return;
            listIdx = (listIdx + 1) % list.length; startListTrack();
        }));
        current = m; m.setPosition(0); applyVolume(); if (settings.music > 0) m.play();
    }

    public void applyVolume() {
        if (current == null) return;
        current.setVolume(settings.music / 10f * 0.55f);
        if (settings.music == 0) current.pause(); else if (!current.isPlaying()) current.play();
    }

    public void pauseMusic() { held = true; if (current != null) current.pause(); }
    public void resumeMusic() { held = false; applyVolume(); }

    private boolean held;
    private float watch;

    /** Watchdog, called every frame: if the music silently stopped (audio focus loss, decoder hiccup) restart it, rebuilding the player if needed. */
    public void update(float dt) {
        watch += dt;
        if (watch < 1.0f) return;
        watch = 0;
        if (held || settings.music == 0 || currentName.isEmpty()) return;
        try {
            if (current != null && current.isPlaying()) return;
            if (current != null && list == null && current.isLooping()) { current.play(); if (current.isPlaying()) return; }
            else if (current != null && list != null && current.getPosition() > 0.5f && !current.isPlaying()) { current.play(); if (current.isPlaying()) return; }
            // still silent: rebuild the player for this track
            String name = list != null ? list[listIdx] : currentName;
            Music old = tracks.remove(name);
            if (old != null) { old.setOnCompletionListener(null); try { old.dispose(); } catch (Exception ignored) { } }
            musicRestarts++;
            if (list != null) startListTrack();
            else { String n = currentName; currentName = ""; music(n); }
        } catch (Exception ignored) { }
    }

    @Override public void dispose() {
        for (Sound s : sounds.values()) s.dispose();
        for (Sound[] vs : variants.values()) for (Sound s : vs) s.dispose();
        for (Music m : tracks.values()) m.dispose();
        for (Sound[] vs : mf.values()) for (Sound s : vs) s.dispose();
        for (Music m : beds.values()) m.dispose();
    }
}
