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
    private final Settings settings;
    private Music current;
    private String currentName = "";
    public static final String[] SFX = {"jump", "land", "bounce", "grab", "pull", "crumble", "checkpoint", "respawn", "win", "click", "rope", "step", "grunt1", "grunt2", "grunt3", "effort"};

    public Audio(Settings s) {
        settings = s;
        for (String n : SFX) {
            try { sounds.put(n, Gdx.audio.newSound(Gdx.files.internal("audio/" + n + ".wav"))); } catch (Exception ignored) { }
        }
    }

    public void play(String name, float vol, float pitch) {
        Sound s = sounds.get(name);
        if (s != null && settings.sfx > 0) s.play(MathUtils.clamp(vol, 0, 1) * settings.sfx / 10f, pitch, 0f);
    }

    public void play(String name) { play(name, 0.9f, 1f); }

    public void music(String name) {
        if (name.equals(currentName)) { applyVolume(); return; }
        if (current != null) current.stop();
        Music m = tracks.get(name);
        if (m == null) {
            try {
                com.badlogic.gdx.files.FileHandle f = Gdx.files.internal("audio/" + name + ".ogg");
                if (!f.exists()) f = Gdx.files.internal("audio/" + name + ".wav");
                m = Gdx.audio.newMusic(f);
            } catch (Exception e) { return; }
            m.setLooping(true); tracks.put(name, m);
        }
        current = m; currentName = name; applyVolume(); if (settings.music > 0) m.play();
    }

    public void applyVolume() {
        if (current == null) return;
        current.setVolume(settings.music / 10f * 0.55f);
        if (settings.music == 0) current.pause(); else if (!current.isPlaying()) current.play();
    }

    public void pauseMusic() { if (current != null) current.pause(); }
    public void resumeMusic() { applyVolume(); }

    @Override public void dispose() {
        for (Sound s : sounds.values()) s.dispose();
        for (Music m : tracks.values()) m.dispose();
    }
}
