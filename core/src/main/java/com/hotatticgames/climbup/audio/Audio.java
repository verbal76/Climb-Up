package com.hotatticgames.climbup.audio;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Disposable;
import com.hotatticgames.climbup.Settings;
import java.util.HashMap;
import java.util.Map;

/** Procedurally synthesised effects and loops (assets/audio, made by tools/gen_audio.py). */
public final class Audio implements Disposable {
    private final Map<String, Sound> sounds = new HashMap<>();
    private final Map<String, Music> tracks = new HashMap<>();
    private final Map<String, Sound[]> variants = new HashMap<>();     // owner-supplied sound pack, chosen per sound by assets/data/sfx.json
    private final Map<String, Integer> lastVariant = new HashMap<>();
    private final Map<String, Sound[]> mf = new HashMap<>();           // OPTIONAL Minifantasy Forgotten Plains clips (assets/audio/pack_mf, fetched at build time, never in the repo): absent = the caller falls back
    private final Settings settings;
    private Music current;
    private String currentName = "";
    private String[] list;
    private int listIdx;
    public static final String[] GAME_TRACKS = {"music_game", "music_leaplike", "music_mountain_jig", "music_track4"};
    public static final String[] SFX = {"jump", "land", "bounce", "grab", "pull", "crumble", "checkpoint", "respawn", "win", "click", "rope", "step", "grunt1", "grunt2", "grunt3", "effort", "hit", "cannon", "saw", "spikes", "key", "door", "locked", "swing", "bonk", "squeak", "poof", "buzz", "whoosh",
            "dash", "skull", "wobble", "blobup", "splat", "clack", "dive", "slam", "motor", "scan"};

    public Audio(Settings s) {
        settings = s;
        for (String n : SFX) {
            try { sounds.put(n, Gdx.audio.newSound(Gdx.files.internal("audio/" + n + ".wav"))); } catch (Exception ignored) { }
        }
        try {
            com.badlogic.gdx.utils.JsonValue map = new com.badlogic.gdx.utils.JsonReader().parse(Gdx.files.internal("data/sfx.json"));
            for (com.badlogic.gdx.utils.JsonValue e = map.child; e != null; e = e.next) {
                if (e.name == null || e.name.startsWith("_") || !e.isArray()) continue;
                java.util.ArrayList<Sound> list = new java.util.ArrayList<>();
                for (com.badlogic.gdx.utils.JsonValue f = e.child; f != null; f = f.next) {
                    try { list.add(Gdx.audio.newSound(Gdx.files.internal("audio/pack/" + f.asString() + ".ogg"))); } catch (Exception ignored) { }
                }
                if (!list.isEmpty()) variants.put(e.name, list.toArray(new Sound[0]));
            }
        } catch (Exception ignored) { }
        for (String g : PACK_GROUPS) {
            java.util.ArrayList<Sound> list = new java.util.ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                try {
                    com.badlogic.gdx.files.FileHandle f = Gdx.files.internal("audio/pack_mf/" + g + "_" + i + ".ogg");
                    if (f.exists()) list.add(Gdx.audio.newSound(f));
                } catch (Exception ignored) { }
            }
            if (!list.isEmpty()) mf.put(g, list.toArray(new Sound[0]));
        }
    }

    /** Pack clip groups (files pack_mf/<group>_1..3.ogg, made by tools/ci/fetch_pack_sfx.sh). */
    public static final String[] PACK_GROUPS = {"step_grass", "step_dirt", "step_stone", "land_grass", "land_stone", "hit_wood", "hit_brick"};
    /** Quiet looping ambience beds (pack_mf/amb_*.ogg), chosen by world. */
    public static final String[] AMBIENCE = {"amb_birds", "amb_wind", "amb_crickets"};

    private boolean throttle(String key, float gap) {
        long now = System.nanoTime();
        Long last = lastPlay.get(key);
        if (last != null && (now - last) < gap * 1e9f) { throttled++; return true; }
        lastPlay.put(key, now); return false;
    }

    /**
     * Plays one random variant of an optional pack clip group. Returns false when the pack is not installed (tests, ci builds), so the caller can fall back to
     * the built-in sound; returns true when the group exists (even if the play was rate limited or sfx is off) so the fallback is not layered on top.
     */
    public boolean playPack(String group, float vol, float pitch) {
        Sound[] v = mf.get(group);
        if (v == null) return false;
        if (settings.sfx <= 0 || throttle("mf_" + group, 0.06f)) return true;
        int i = v.length == 1 ? 0 : MathUtils.random(v.length - 1);
        if (v.length > 1 && lastVariant.getOrDefault("mf_" + group, -1) == i) i = (i + 1) % v.length;
        lastVariant.put("mf_" + group, i);
        try { if (v[i].play(MathUtils.clamp(vol, 0, 1) * settings.sfx / 10f, pitch, 0f) == -1) refused++; played++; }
        catch (Exception e) { errors++; lastError = e.getClass().getSimpleName(); }
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
            if (humId != -1) s.setVolume(humId, humVol * HUM_LEVEL * settings.sfx / 10f);
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
            float v = bedVol * BED_LEVEL * settings.sfx / 10f;
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
        if (v != null) {
            int i = v.length == 1 ? 0 : MathUtils.random(v.length - 1);
            if (v.length > 1 && lastVariant.getOrDefault(name, -1) == i) i = (i + 1) % v.length;
            lastVariant.put(name, i); s = v[i];
        }
        if (s == null || settings.sfx <= 0) return;
        // a runaway caller must never flood the platform's sound pool (Android silently stops serving a saturated pool): cap how often one sound can start
        long now = System.nanoTime();
        Long last = lastPlay.get(name);
        float gap = name.equals("step") ? 0.07f : 0.045f;
        if (last != null && (now - last) < gap * 1e9f) { throttled++; return; }
        lastPlay.put(name, now);
        try { if (s.play(MathUtils.clamp(vol, 0, 1) * settings.sfx / 10f, pitch, 0f) == -1) refused++; played++; }
        catch (Exception e) { errors++; lastError = e.getClass().getSimpleName(); }
    }

    private long fallId = -1; private Sound fallSnd; private boolean falling;
    /** The long falling sound (owner-supplied): starts once when a real fall begins and is cut off when it ends. */
    public void fallStart(float vol) {
        if (falling || settings.sfx <= 0) return;
        Sound[] v = variants.get("fall"); if (v == null) return;
        falling = true; fallSnd = v[0];
        try { fallId = fallSnd.play(MathUtils.clamp(vol, 0, 1) * settings.sfx / 10f, 1f, 0f); played++; } catch (Exception e) { errors++; lastError = e.getClass().getSimpleName(); }
    }
    public void fallStop() {
        if (!falling) return;
        falling = false;
        try { if (fallSnd != null && fallId != -1) fallSnd.stop(fallId); } catch (Exception e) { errors++; lastError = e.getClass().getSimpleName(); }
        fallId = -1;
    }
    public boolean isFalling() { return falling; }

    private final Map<String, Long> lastPlay = new HashMap<>();
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
        list = names; listIdx = MathUtils.random(names.length - 1); currentName = "playlist";
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
