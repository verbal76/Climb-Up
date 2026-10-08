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
    private final Settings settings;
    private Music current;
    private String currentName = "";
    private String[] list;
    private int listIdx;
    public static final String[] GAME_TRACKS = {"music_game", "music_leaplike", "music_mountain_jig", "music_track4"};
    public static final String[] SFX = {"jump", "land", "bounce", "grab", "pull", "crumble", "checkpoint", "respawn", "win", "click", "rope", "step", "grunt1", "grunt2", "grunt3", "effort", "hit", "cannon", "saw", "spikes", "key", "door", "locked", "swing", "bonk", "squeak", "poof", "buzz", "whoosh"};

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
    }

    public void play(String name, float vol, float pitch) {
        Sound s = sounds.get(name);
        Sound[] v = variants.get(name);
        if (v != null) {
            int i = v.length == 1 ? 0 : MathUtils.random(v.length - 1);
            if (v.length > 1 && lastVariant.getOrDefault(name, -1) == i) i = (i + 1) % v.length;
            lastVariant.put(name, i); s = v[i];
        }
        if (s != null && settings.sfx > 0) s.play(MathUtils.clamp(vol, 0, 1) * settings.sfx / 10f, pitch, 0f);
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
            if (list != null) startListTrack();
            else { String n = currentName; currentName = ""; music(n); }
        } catch (Exception ignored) { }
    }

    @Override public void dispose() {
        for (Sound s : sounds.values()) s.dispose();
        for (Sound[] vs : variants.values()) for (Sound s : vs) s.dispose();
        for (Music m : tracks.values()) m.dispose();
    }
}
