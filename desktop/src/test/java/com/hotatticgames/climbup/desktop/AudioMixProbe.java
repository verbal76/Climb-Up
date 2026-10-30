package com.hotatticgames.climbup.desktop;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Files;
import com.badlogic.gdx.backends.lwjgl3.audio.OpenALLwjgl3Audio;
import com.hotatticgames.climbup.Settings;
import com.hotatticgames.climbup.audio.Audio;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Build 83 audio measurement (not a unit test; no window, no game): drives the real {@link Audio} class through libGDX's desktop OpenAL
 * backend and writes a timeline of what it played. Run it with OpenAL Soft's wave-file writer as the output device (ALSOFT_CONF pointing at
 * a config with drivers=wave, float output, output limiter off) and the recorded file is exactly what the game sends to the speakers.
 * tools/audio/measure_mix.py then cuts the recording by the timeline and measures each segment.
 *
 *   working directory = an assets folder;  args: <timeline.tsv> <old-files-dir or "-">
 *
 * Segments: (1) single cues at EFFECTS VOLUME 10, 9, 7, 5, 3, 2, 1, 0 - the Build 83 path; (2) the same cues played the Build 82 way (the old
 * files at volume x step/10, no bus) when the old files are given; (3) pile-ups of many hazards at once at 10, new and old; (4) a cue over music
 * at its default 7.
 */
public final class AudioMixProbe {
    static final String[][] CUES = {   // game sound, caller volume (the game's own value), Build 82 file (relative to the old-files dir)
            {"jump", "0.7", "pack/woosh_2.ogg"}, {"land_grass", "0.8", "pack_mf_old/land_grass_1.ogg"}, {"bounce", "0.9", "pack/clang11.ogg"},
            {"grab", "0.7", "pack/clickandrelease_1.ogg"}, {"checkpoint", "0.8", "pack/sucesss2.ogg"}, {"hit", "1.0", "pack/crunch_1.ogg"},
            {"cannon", "1.0", "pack/shell_explosion_1.ogg"}, {"key", "1.0", "pack/sucess1.ogg"}, {"click", "0.9", "pack/click_1.ogg"},
            {"step_stone", "0.5", "pack_mf_old/step_stone_1.ogg"}, {"door", "1.0", "pack/thudd3.ogg"}, {"respawn", "0.8", "pack/woosh_3.ogg"}};
    static final String[] PILE = {"hit", "cannon", "slam", "spikes", "land_stone", "crumble", "bounce", "enf_alert", "key", "splat", "bonk", "saw", "buzz", "clack"};
    static final String[] PILE_OLD = {"pack/crunch_1.ogg", "pack/shell_explosion_1.ogg", "slam.wav", "pack/snap_1.ogg", "pack_mf_old/land_stone_1.ogg", "pack/crunch_3.ogg",
            "pack/clang11.ogg", "enf_alert.wav", "pack/sucess1.ogg", "splat.wav", "pack/thud1.ogg", "pack/scratch2.ogg", "buzz.wav", "clack.wav"};
    static final float[] PILE_VOL = {1f, 1f, 0.85f, 0.75f, 1f, 0.7f, 0.9f, 0.85f, 1f, 1f, 1f, 0.35f, 0.4f, 0.7f};

    static PrintWriter log; static long t0; static OpenALLwjgl3Audio al;

    /** Build 82 played every effect with OpenAL's default spatial source: libGDX reuses sources, so put the default back on this one. */
    static void b82(Sound o, long id) {
        int src = al.getSourceId(id);
        if (src >= 0) { org.lwjgl.openal.AL10.alSourcei(src, 0x1214 /* AL_SOURCE_SPATIALIZE_SOFT */, 2 /* AL_AUTO_SOFT, the default */); }
    }
    static void mark(String what) { log.printf("%.4f\t%s%n", (System.nanoTime() - t0) / 1e9, what); log.flush(); }
    static void idle(double sec) throws Exception {
        long end = System.nanoTime() + (long) (sec * 1e9);
        while (System.nanoTime() < end) { al.update(); Thread.sleep(5); }
    }

    public static void main(String[] a) throws Exception {
        Gdx.files = new Lwjgl3Files();
        al = new OpenALLwjgl3Audio(16, 9, 512);           // the desktop launcher's defaults
        Gdx.audio = al;
        if (!Boolean.getBoolean("probe.noAlLevels")) OpenAlLevels.install();          // as the desktop launcher does
        log = new PrintWriter(new FileWriter(a[0]));
        String oldDir = a.length > 1 && !a[1].equals("-") ? a[1] : null;
        Settings s = new Settings(); s.music = 0;
        Audio au = new Audio(s);
        List<Sound> old = new ArrayList<>();
        t0 = System.nanoTime();
        idle(1.0);
        int[] steps = {10, 9, 7, 5, 3, 2, 1, 0};
        for (int st : steps) {
            s.sfx = st;
            for (String[] c : CUES) {
                mark("new\t" + st + "\t" + c[0]);
                if (c[0].startsWith("step_") || c[0].startsWith("land_")) au.playPack(c[0], Float.parseFloat(c[1]), 1f); else au.play(c[0], Float.parseFloat(c[1]), 1f);
                idle(1.3);
            }
            if (oldDir != null) for (String[] c : CUES) {
                Sound o = Gdx.audio.newSound(Gdx.files.absolute(oldDir + "/" + c[2])); old.add(o);
                mark("old\t" + st + "\t" + c[0]);
                if (st > 0) b82(o, o.play(Float.parseFloat(c[1]) * st / 10f, 1f, 0f));
                idle(1.3);
            }
        }
        // pile-ups at 10: everything within ~60 ms, three times
        s.sfx = 10;
        for (int r = 0; r < 3; r++) {
            mark("pile_new\t10\t" + r);
            for (int i = 0; i < PILE.length; i++) { if (PILE[i].startsWith("land_")) au.playPack(PILE[i], PILE_VOL[i], 1f); else au.play(PILE[i], PILE_VOL[i], 1f); idle(0.004); }
            idle(2.0);
        }
        if (oldDir != null) for (int r = 0; r < 3; r++) {
            mark("pile_old\t10\t" + r);
            for (int i = 0; i < PILE_OLD.length; i++) { Sound o = Gdx.audio.newSound(Gdx.files.absolute(oldDir + "/" + PILE_OLD[i])); old.add(o); b82(o, o.play(PILE_VOL[i], 1f, 0f)); idle(0.004); }
            idle(2.0);
        }
        // music at its default 7 alone, then with cues on top (music is Build 82's chain: unchanged)
        s.music = 7; s.sfx = 9;
        au.music("music_game");
        idle(1.0);
        mark("music_only\t7\t-"); idle(3.0);
        for (String n : new String[]{"jump", "checkpoint", "hit"}) { mark("over_music\t9\t" + n); au.play(n, n.equals("jump") ? 0.7f : n.equals("hit") ? 1f : 0.8f, 1f); idle(1.3); }
        mark("end\t-\t-");
        idle(0.5);
        log.close();
        au.dispose(); for (Sound o : old) o.dispose();
        al.dispose();
        System.exit(0);
    }
}
