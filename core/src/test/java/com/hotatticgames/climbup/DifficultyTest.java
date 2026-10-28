package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Build 79 (owner): the three difficulties - NORMAL, PUNISHER, NEVER GONNA HAPPEN. NORMAL is the established game; the harder ones react 1.25 / 1.5 times faster
 * with one / two more crabs on every crab deck; each is reproducible from its seed, a category of its own (the same seed is a different course), climbable by
 * the solver under its own (faster) rules, and keeps its own times table without touching the normal one.
 */
public class DifficultyTest {
    static final int SLICES = Integer.getInteger("climb.diffSlices", 9);          // the first ~1,000 m: crab decks, bee pairs, drop rows and creature decks all appear

    private static final Map<Integer, List<Course>> CACHE = new HashMap<>();
    private static synchronized List<Course> slices(int hard, long seed) throws Exception {
        int key = hard * 1000 + (int) seed;
        List<Course> l = CACHE.get(key);
        if (l == null) {
            Tuning t = TestUtil.tuning().hardCopy(hard); l = new java.util.ArrayList<>(); Course prev = null;
            for (int k = 0; k < SLICES; k++) { prev = CourseGenerator.chunk(seed, k, prev, t); l.add(prev); }
            CACHE.put(key, l);
        }
        return l;
    }

    @Test public void reactionsAreTwentyFiveAndFiftyPercentFasterAndNothingElseChanges() throws Exception {
        Tuning t = TestUtil.tuning();
        assertEquals(0, t.hard); assertEquals(1f, t.react(), 0f);
        Tuning p = t.hardCopy(1), n = t.hardCopy(2);
        assertEquals(1.25f, p.react(), 0f); assertEquals(1.5f, n.react(), 0f);
        assertEquals("the copy leaves the original NORMAL", 0, t.hard);
        assertEquals(t.runSpeed, n.runSpeed, 0f); assertEquals(t.gravity, n.gravity, 0f); assertEquals(t.jumpVel, n.jumpVel, 0f); assertEquals(t.castleSpacing, n.castleSpacing, 0f);
    }

    @Test public void normalIsTheEstablishedGameAndEveryDifficultyIsReproducibleAndItsOwnCourse() throws Exception {
        Tuning t = TestUtil.tuning();
        byte[] plain = SliceCodec.encode(CourseGenerator.chunkFramed(4, 0, null, t)), copy0 = SliceCodec.encode(CourseGenerator.chunkFramed(4, 0, null, t.hardCopy(0)));
        assertArrayEquals("NORMAL through a difficulty copy is exactly the normal course", plain, copy0);
        byte[][] first = new byte[3][];
        for (int h = 0; h < 3; h++) {
            List<Course> a = slices(h, 4);
            Course again = CourseGenerator.chunk(4, 1, CourseGenerator.chunk(4, 0, null, t.hardCopy(h)), t.hardCopy(h));
            assertArrayEquals("difficulty " + h + ": the same seed makes the same course", SliceCodec.encode(new CourseGenerator.Framed(a.get(1), 0.0, 0.0)), SliceCodec.encode(new CourseGenerator.Framed(again, 0.0, 0.0)));
            StringBuilder sb = new StringBuilder(); for (Course c : a) sb.append(c.hazards.size()).append(',').append(c.size()).append(';');
            first[h] = sb.toString().getBytes();
        }
        assertFalse("the same seed on PUNISHER is another course", java.util.Arrays.equals(first[0], first[1]));
        assertFalse("and on NEVER GONNA HAPPEN another again", java.util.Arrays.equals(first[1], first[2]));
    }

    @Test public void everyCrabDeckHasTwoCrabsPlusOnePerLevel() throws Exception {
        for (int h = 0; h < 3; h++) {
            int decks = 0;
            for (long seed = 1; seed <= 2; seed++) for (Course c : slices(h, seed)) {
                Map<Integer, Integer> per = new HashMap<>();
                for (Element e : c.hazards) if (e.type == Element.Type.CRAB) per.merge(e.anchor, 1, Integer::sum);
                for (Map.Entry<Integer, Integer> d : per.entrySet()) { decks++; assertTrue("difficulty " + h + " seed " + seed + ": deck " + d.getKey() + " has " + d.getValue() + " crabs", d.getValue() >= 2 + h); }
            }
            assertTrue("difficulty " + h + ": crab decks were made", decks > 0);
        }
    }

    @Test public void everySliceIsClimbableOnEveryDifficultyUnderItsOwnRules() throws Exception {
        for (int h = 0; h < 3; h++) {
            Tuning t = TestUtil.tuning().hardCopy(h);
            for (long seed = 1; seed <= 2; seed++) {
                List<Course> l = slices(h, seed);
                for (int k = 0; k < l.size(); k++) {
                    Autopilot.Report r = Autopilot.run(l.get(k), t, 4000f);
                    assertTrue(Tuning.HARD_NAMES[h] + " seed " + seed + " slice " + k + ": climbable (link " + r.failedLink + ": " + r.failInfo + ")", r.completed);
                }
            }
        }
    }

    @Test public void eachDifficultyKeepsItsOwnTimesAndNeverTouchesTheNormalOnes() throws Exception {
        File dir = Files.createTempDirectory("climbdiff").toFile(); SaveStore st = new SaveStore(dir);
        SaveData d = new SaveData(); long seed = 77; int rules = CourseGenerator.RULES_VERSION;
        d.seed = seed; d.difficulty = 0; d.board().legDone(seed, rules, 0, 90f); d.board().finish(seed, rules, 3000f);
        d.difficulty = 1; d.board().legDone(seed, rules, 0, 60f); d.board().finish(seed, rules, 2000f);
        d.difficulty = 2; d.board().legDone(seed, rules, 0, 70f);
        assertEquals("a faster PUNISHER leg is not a normal best", 90f, d.records().bestLegs[0], 0f);
        assertEquals("nor a faster PUNISHER run", 3000f, d.records().bestTotal, 0f);
        assertEquals(60f, d.board(1).bestLegs[0], 0f); assertEquals(2000f, d.board(1).bestTotal, 0f);
        assertEquals(70f, d.board(2).bestLegs[0], 0f); assertEquals(0f, d.board(2).bestTotal, 0f);
        assertEquals("the same seed is a separate course on each board", 90f, d.records().seedBests(seed, rules).bestLegs[0], 0f);
        assertEquals(60f, d.board(1).seedBests(seed, rules).bestLegs[0], 0f);
        st.saveGame(d);
        SaveData back = st.loadGame();
        assertEquals("the climb keeps its difficulty", 2, back.difficulty);
        assertEquals(90f, back.records().bestLegs[0], 0f); assertEquals(3000f, back.records().bestTotal, 0f);
        assertEquals(60f, back.board(1).bestLegs[0], 0f); assertEquals(2000f, back.board(1).bestTotal, 0f);
        assertEquals(70f, back.board(2).bestLegs[0], 0f);
        d.difficulty = 1; d.bestFinish = 3000f; d.runClock = 1000f; d.finished = false;
        assertEquals("a PUNISHER finish is never the normal best finish", 0, RunRecord.complete(d)); assertEquals(3000f, d.bestFinish, 0f);
        RunRecord.forgetClimb(d); assertEquals("a new climb starts NORMAL until the setting says otherwise", 0, d.difficulty);
    }

    @Test public void aSaveFromBeforeTheDifficultiesKeepsItsNormalTimes() throws Exception {
        File dir = Files.createTempDirectory("climbdiffold").toFile(); SaveStore st = new SaveStore(dir);
        SaveData d = new SaveData(); d.seed = 5; d.records().legDone(5, 5, 0, 81.5f); d.records().finish(5, 5, 2500f); st.saveGame(d);
        File f = new File(dir, "save.json");
        String json = new String(Files.readAllBytes(f.toPath()), "UTF-8");
        json = json.replaceAll(",?\\s*\"?difficulty\"?\\s*:\\s*0", "").replaceAll(",?\\s*\"?(punisher|never)RecordsJson\"?\\s*:\\s*\"[^\"]*\"", "");      // as Build 78 wrote it
        assertFalse(json.contains("difficulty")); assertFalse(json.contains("punisherRecordsJson"));
        Files.write(f.toPath(), json.getBytes("UTF-8"));
        SaveData back = st.loadGame();
        assertEquals(0, back.difficulty);
        assertEquals(81.5f, back.records().bestLegs[0], 0f); assertEquals(2500f, back.records().bestTotal, 0f);
        assertEquals(0f, back.board(1).bestTotal, 0f); assertEquals(0f, back.board(2).bestTotal, 0f);
    }
}
