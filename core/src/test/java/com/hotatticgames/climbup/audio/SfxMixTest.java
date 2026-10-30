package com.hotatticgames.climbup.audio;

import static org.junit.Assert.*;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.hotatticgames.climbup.SaveStore;
import com.hotatticgames.climbup.Settings;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/** Build 83 effects bus: the EFFECTS VOLUME curve, voice limits, ducking, stacking, cosmetic randomness and the mastered data. */
public class SfxMixTest {
    private static String read(String p) throws Exception { return new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8); }
    private static SfxMix shipped() throws Exception { return new SfxMix(7L).load(read("../assets/data/sfx_mix.json")); }
    private static double db(float g) { return 20 * Math.log10(g); }

    @Test public void zeroIsSilenceTenIsFullAndEveryStepIsTheSameSizeInDecibels() throws Exception {
        SfxMix m = shipped();
        assertEquals(0f, m.bus(0), 0f);
        assertEquals(1f, m.bus(10), 1e-6f);
        double step = db(m.bus(2)) - db(m.bus(1));
        assertTrue("about 3 dB a step", step > 2.5 && step < 3.6);
        for (int s = 2; s <= 10; s++) assertEquals("equal steps", step, db(m.bus(s)) - db(m.bus(s - 1)), 1e-3);
    }

    @Test public void theBottomIsGenuinelyQuietAndTheMiddleIsBelowTheOldLinearSlider() throws Exception {
        SfxMix m = shipped();
        assertTrue("1 is at least 25 dB down (Build 82: -20 dB)", db(m.bus(1)) <= -25);
        assertTrue("2 is still well down", db(m.bus(2)) <= -22);
        for (int s = 1; s <= 9; s++) assertTrue("never louder than the old slider at " + s, m.bus(s) <= s / 10f + 1e-6f);
    }

    @Test public void outOfRangeSavedValuesAreClampedNotBroken() throws Exception {
        SfxMix m = shipped();
        assertEquals(0f, m.bus(-3), 0f);
        assertEquals(m.bus(10), m.bus(14), 0f);
        assertEquals(Float.NEGATIVE_INFINITY, m.busDb(0), 0f);
    }

    /** A Build 82 settings file (EFFECTS VOLUME saved as 0..10) loads unchanged: the same number means the same slider position. */
    @Test public void build82SettingsLoadAsTheSameSliderPosition() throws Exception {
        File dir = Files.createTempDirectory("sfxsettings").toFile();
        Files.write(new File(dir, "settings.json").toPath(), "{version:1,music:4,sfx:9,textScale:0,reducedMotion:false}".getBytes(StandardCharsets.UTF_8));
        Settings s = new SaveStore(dir).loadSettings();
        assertEquals(9, s.sfx); assertEquals(4, s.music);
        Files.write(new File(dir, "settings.json").toPath(), "{version:1,music:4}".getBytes(StandardCharsets.UTF_8));
        assertEquals("a file without the key gets the default", new Settings().sfx, new SaveStore(dir).loadSettings().sfx);
        Files.write(new File(dir, "settings.json").toPath(), "{version:1,sfx:0}".getBytes(StandardCharsets.UTF_8));
        Settings muted = new SaveStore(dir).loadSettings();
        assertEquals(0, muted.sfx); assertEquals(0f, shipped().bus(muted.sfx), 0f);
        Settings w = new Settings(); w.sfx = 3; w.music = 8;
        new SaveStore(dir).saveSettings(w);
        Settings r = new SaveStore(dir).loadSettings();
        assertEquals("music and effects stay independent", 3, r.sfx); assertEquals(8, r.music);
    }

    @Test public void copiesOfOneSoundAreCappedAndSpaced() throws Exception {
        SfxMix m = shipped();
        double t = 100;
        assertTrue(m.admit("door", 0.7f, t) >= 0f);                         // door: cap 1, but major: never refused
        assertTrue(m.admit("door", 0.7f, t + 0.1) >= 0f);
        assertTrue(m.admit("spikes", 0.3f, t) >= 0f);
        assertTrue(m.admit("spikes", 0.3f, t + 0.05) >= 0f);
        assertEquals("spikes: cap 2", -1f, m.admit("spikes", 0.3f, t + 0.1), 0f);
        assertTrue("one ended, room again", m.admit("spikes", 0.3f, t + 0.32) >= 0f);
        assertTrue(m.admit("jump", 0.2f, t + 1.0) >= 0f);
        assertEquals("gap: the same sound again 20 ms later", -1f, m.admit("jump", 0.2f, t + 1.02), 0f);
        assertTrue("and after its gap", m.admit("jump", 0.2f, t + 1.06) >= 0f);
    }

    @Test public void aRunawayCallerCannotFillThePlatformPool() throws Exception {
        SfxMix m = shipped();
        int started = 0;
        for (int i = 0; i < 400; i++) if (m.admit("n" + (i % 40), 2f, 50 + i * 0.002) >= 0f) started++;
        assertTrue("never more than the voice budget at once", m.active(50 + 0.8) <= m.maxVoices);
        assertTrue(started <= m.maxVoices);
        assertTrue("the budget sits well under Android's 24 streams and OpenAL's 16 sources", m.maxVoices <= 14);
    }

    @Test public void majorCuesAlwaysPlayAndDuckTheTextureForAMoment() throws Exception {
        SfxMix m = shipped();
        double t = 10;
        for (int i = 0; i < m.maxVoices; i++) assertTrue(m.admit("x" + i, 5f, t) >= 0f);
        assertEquals("budget full: an action sound waits", -1f, m.admit("slam", 0.4f, t + 0.2), 0f);
        assertTrue("a checkpoint is never refused", m.admit("checkpoint", 0.6f, t + 0.2) >= 0f);
        SfxMix d = shipped();
        assertEquals("no duck before", 1f, d.admit("step", 0.05f, 20), 0f);
        d.admit("key", 0.5f, 20.5);
        float g = d.admit("rope", 0.07f, 20.6);
        assertEquals("texture ducked by duckDb", d.duckDb, db(g), 0.01);
        assertEquals("action sounds are not ducked", 1f, d.admit("land", 0.1f, 20.6), 0f);
        assertEquals("the duck ends", 1f, d.admit("clack", 0.1f, 20.5 + d.duckTime + 0.01), 0f);
        assertEquals(1f, d.duckNow(30), 0f);
    }

    @Test public void aPileUpIsScaledEqualPowerSoItDoesNotAddUpToClipping() throws Exception {
        SfxMix m = shipped();
        double t = 5;
        float g1 = m.admit("cannon", 0.5f, t), g2 = m.admit("slam", 0.5f, t + 0.01), g3 = m.admit("spikes", 0.3f, t + 0.02);
        float g4 = m.admit("splat", 0.3f, t + 0.03), g5 = m.admit("bonk", 0.2f, t + 0.04);
        assertEquals(1f, g1, 0f); assertEquals(1f, g2, 0f); assertEquals(1f, g3, 0f);
        assertEquals(1 / Math.sqrt(2), g4, 1e-6);
        assertEquals(1 / Math.sqrt(3), g5, 1e-6);
        double power = g1 * g1 + g2 * g2 + g3 * g3 + g4 * g4 + g5 * g5;
        assertTrue("five at once add up to less than five", power < 4.0);
    }

    /** Plays a pile through the mix, applying its squeezes to the earlier voices as Audio does; returns the sum of the output peaks. */
    private static float pile(SfxMix m, String[] names, float[] peaks, double t0, double dt) {
        float[] out = new float[names.length];
        for (int i = 0; i < names.length; i++) {
            float g = m.admit(names[i], 0.5f, peaks[i], t0 + i * dt);
            for (int j = 0; j < i; j++) out[j] *= m.entry(names[j]).prio < SfxMix.MAJOR ? m.squeeze : m.squeezeMajor;
            out[i] = g < 0f ? 0f : peaks[i] * g;
        }
        float sum = 0f; for (float o : out) sum += o; return sum;
    }

    /** The peaks of effects starting together never add up past full scale (the Build 83 desktop recording's worst pile-up, replayed). */
    @Test public void simultaneousPeaksStayUnderFullScale() throws Exception {
        String[] p = {"hit", "cannon", "slam", "spikes", "land_stone", "crumble", "bounce", "enf_alert", "key", "splat", "bonk", "saw", "buzz", "clack"};
        float[] pk = {0.89f, 0.89f, 0.76f, 0.67f, 0.95f, 0.62f, 0.76f, 0.76f, 0.89f, 0.89f, 0.89f, 0.31f, 0.28f, 0.55f};
        assertTrue("minor pile-up", pile(shipped(), new String[]{"cannon", "slam", "spikes", "splat", "bonk", "crumble", "land", "swing"},
                new float[]{0.6f, 0.6f, 0.6f, 0.6f, 0.6f, 0.6f, 0.6f, 0.6f}, 40, 0.005) <= 1f + 1e-4f);
        float worst = pile(shipped(), p, pk, 40, 0.0055);
        assertTrue("three major cues and eleven others within 80 ms: " + worst, worst <= 1f + 1e-4f);
        SfxMix m = shipped();
        float g = m.admit("slam", 0.5f, 0.9f, 7), gm = m.admit("hit", 0.4f, 0.9f, 7.01);
        assertEquals(1f, g, 0f);
        assertEquals("a major cue makes room by turning the minor one down first (to its floor here)", SfxMix.MINOR_FLOOR, m.squeeze, 1e-6f);
        assertTrue("then gives up only a little itself: " + gm, gm > 0.8f && gm < 1f);
        m.admit("swing", 0.2f, 0.3f, 9.0);
        assertEquals("the squeeze is reported once", 1f, m.squeeze, 0f); assertEquals(1f, m.squeezeMajor, 0f);
        SfxMix q = shipped();
        assertEquals("one sound alone is untouched", 1f, q.admit("land", 0.3f, 0.95f, 3), 0f);
        assertEquals("an unknown peak does not count", 1f, q.admit("slam", 0.3f, 3.001), 0f);
        assertEquals("after the window there is room again", 1f, q.admit("jump", 0.2f, 0.6f, 4.0), 0f);
    }

    /** Cosmetic variation never draws from the shared MathUtils generator (or the simulation's), so it cannot shift anything replays depend on. */
    @Test public void cosmeticRandomnessHasItsOwnGenerator() {
        MathUtils.random.setSeed(4242L); float a = MathUtils.random(); long b = MathUtils.random.nextLong();
        MathUtils.random.setSeed(4242L);
        SfxMix m = new SfxMix(1L);
        for (int i = 0; i < 1000; i++) { m.vary(0.9f, 0.2f); m.pick(3, i % 3); }
        assertEquals(a, MathUtils.random(), 0f); assertEquals(b, MathUtils.random.nextLong());
        SfxMix x = new SfxMix(9L), y = new SfxMix(9L);
        for (int i = 0; i < 50; i++) assertEquals(x.vary(0f, 1f), y.vary(0f, 1f), 0f);
        for (int i = 0; i < 200; i++) { int p = m.pick(3, 1); assertTrue(p >= 0 && p < 3 && p != 1); }
        assertEquals(0, m.pick(1, 0));
    }

    /** The audio and juice code never reaches for a shared or gameplay random source. */
    @Test public void audioAndJuiceSourcesUseNoSharedRandom() throws Exception {
        for (String f : new String[]{"audio/Audio.java", "audio/SfxMix.java", "audio/Cues.java", "fx/Juice.java"}) {
            String src = read("src/main/java/com/hotatticgames/climbup/" + f);
            for (String bad : new String[]{"MathUtils.random", "Math.random", "ThreadLocalRandom", "Rng", ".rng("}) assertFalse(f + " uses " + bad, src.contains(bad));
        }
    }

    /** Every sound the game plays has a mix entry; every mapped file exists (mastered or original); the masters keep their headroom. */
    @Test public void theMixDataAndTheMasteredFilesAgree() throws Exception {
        JsonValue mix = new JsonReader().parse(read("../assets/data/sfx_mix.json"));
        Set<String> listed = new HashSet<>();
        for (JsonValue e = mix.get("sounds").child; e != null; e = e.next) {
            listed.add(e.name);
            assertNotNull(e.name + " class", mix.get("classes").get(e.getString("class")));
            float use = e.getFloat("use"); assertTrue(e.name + " use", use > 0f && use <= 1f);
            assertTrue(e.name + " cap", e.getInt("cap", 3) >= 1);
        }
        for (String n : Audio.SFX) if (!n.equals("motor")) assertTrue("no mix entry for " + n, listed.contains(n));
        for (String g : Audio.PACK_GROUPS) assertTrue("no mix entry for " + g, listed.contains(g));
        JsonValue map = new JsonReader().parse(read("../assets/data/sfx.json"));
        for (JsonValue e = map.child; e != null; e = e.next) {
            if (e.name.startsWith("_")) continue;
            if (!e.name.equals("steam")) assertTrue("sfx.json " + e.name + " has no mix entry", listed.contains(e.name));
            for (JsonValue f = e.child; f != null; f = f.next) {
                String n = f.asString();
                Path p = n.startsWith("@") ? Paths.get("../assets/audio/" + n.substring(1) + ".wav") : Paths.get("../assets/audio/pack/" + n + ".ogg");
                assertTrue("missing " + p, Files.exists(p));
            }
        }
        JsonValue files = new JsonReader().parse(read("../assets/data/sfx_levels.json")).get("files");
        int n = 0;
        for (JsonValue f = files.child; f != null; f = f.next, n++) {
            String ext = f.getString("src").endsWith(".ogg") ? ".ogg" : ".wav";
            assertTrue("mastered file " + f.name, Files.exists(Paths.get("../assets/audio/mastered/" + f.name + ext)));
            JsonValue o = f.get("out");
            float tp = o.getFloat("truePeakDb"), l = o.getFloat("l100"), dur = o.getFloat("dur");
            assertFalse(Float.isNaN(tp) || Float.isNaN(l) || Float.isNaN(dur));
            assertTrue(f.name + " true peak " + tp + " dBTP", tp <= -0.4f);
            assertTrue(f.name + " length", dur > 0.02f && dur < 6f);
            assertTrue(f.name + " limiting stays gentle", f.getFloat("grDb", 0f) <= 6.1f && f.getFloat("clipDb", 0f) <= 6.1f);
        }
        assertTrue(n >= 80);
        for (String s : Audio.SFX) assertTrue("no master for " + s, files.has(s));
    }
}
