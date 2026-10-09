package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import java.io.File;
import java.nio.file.Files;
import org.junit.Test;

/** A resumed climb only has the slice of its last checkpoint: keys carried, castles opened and keys left in older sections must not strand the run. */
public class ResumeStateTest {
    @Test public void keysAndOpenedCastlesSurviveASaveRoundTrip() throws Exception {
        File dir = Files.createTempDirectory("climb-resume").toFile(); SaveStore st = new SaveStore(dir);
        SaveData d = new SaveData(); d.keysHeld = 5; d.openedUpTo = 3; d.towers = 3; st.saveGame(d);
        SaveData r = st.loadGame(); assertEquals(5, r.keysHeld); assertEquals(3, r.openedUpTo);
    }

    @Test public void aCastleCountsOnceEvenIfItsDoorIsWalkedThroughAgainAfterAResume() {
        SaveData d = new SaveData();
        assertTrue(ResumeState.gateOpened(d, 1)); assertFalse("the same door again after a restart", ResumeState.gateOpened(d, 1));
        assertTrue(ResumeState.gateOpened(d, 2)); assertFalse(ResumeState.gateOpened(d, 1));
    }

    @Test public void resumingAtAGemNeverLeavesTheNextCastleWithoutItsKey() throws Exception {
        Tuning t = TestUtil.tuning(); int resumedFromBelow = 0, checked = 0;
        for (long seed : new long[]{1, 2, 3, 4, 5, 6, 7, 8}) {
            Tower tw = new Tower(seed, t); while (tw.topY() < 620f) tw.extend();
            Course w = tw.world;
            int gem = -1; for (int i = 1; i < w.routeSize() && gem < 0; i++) if (w.get(i).skin == 3 && w.get(i).y > 200f && w.get(i).y < 300f) gem = i;
            if (gem < 0) continue;
            Tower.Slice sl = tw.sliceOf(gem);
            Tower resumed = new Tower(seed, t, sl.index, sl.data);
            int start = resumed.slices.get(0).toWorld(Math.max(0, Math.min(sl.toLocal(gem), sl.data.routeSize() - 1)));
            Sim sim = Sim.startOn(resumed.world, t, start); sim.keysFree = false; sim.keys = 0;
            SaveData sd = new SaveData(); sd.seed = seed; sd.towers = 0;
            ResumeState.restore(sd, sim, resumed.world, t);
            int colour = CourseGenerator.castleColor(seed, 1);
            float lowest = Float.MAX_VALUE; for (int i = 0; i < resumed.world.size(); i++) lowest = Math.min(lowest, resumed.world.get(i).y);
            boolean lost = CourseGenerator.keyHeight(seed, 1, t.castleSpacing) < lowest + 40f;          // its section lies below everything that exists after the resume
            boolean held = (sim.keys & (1 << colour)) != 0; checked++; if (lost) resumedFromBelow++;
            if (lost) assertTrue("castle 1's key lay in a lost older section, so it is handed back (seed " + seed + ")", held);
            else assertFalse("a key that is still ahead (in the resumed world or a slice not built yet) is never handed out early (seed " + seed + ")", held);
        }
        assertTrue("cases checked", checked >= 5);
        System.out.println("resumes checked " + checked + ", of which the key lay in a lost older section: " + resumedFromBelow);
    }

    @Test public void anOpenedCastleStaysOpenAfterAResume() throws Exception {
        Tuning t = TestUtil.tuning();
        Tower tw = new Tower(3, t); while (tw.topY() < 560f) tw.extend();
        int gate = -1; for (int i = 0; i < tw.world.hazards.size(); i++) if (tw.world.hazards.get(i).type == Element.Type.GATE) { gate = i; break; }
        assertTrue(gate >= 0);
        Tower.Slice sl = tw.sliceOf(tw.world.hazards.get(gate).anchor);
        Tower resumed = new Tower(3, t, sl.index, sl.data);
        Sim sim = Sim.startOn(resumed.world, t, resumed.slices.get(0).toWorld(0)); sim.keysFree = false; sim.keys = 0;
        SaveData sd = new SaveData(); sd.seed = 3; sd.openedUpTo = 1; sd.towers = 1;
        ResumeState.restore(sd, sim, resumed.world, t);
        int open = 0; for (int i = 0; i < resumed.world.hazards.size(); i++) { Element h = resumed.world.hazards.get(i); if (h.type == Element.Type.GATE && h.skin == 1) { assertTrue("castle 1's door stays open", sim.featDone[i]); open++; } }
        assertTrue("the gate is in the resumed world", open >= 1);
    }

    @Test public void theRunClockCountsWholeStepsExactlyForHours() {
        SaveData d = new SaveData();
        for (int i = 0; i < 60 * 3600 * 3; i++) RunRecord.tick(d, 1f / 60f, true);      // three hours of steps
        assertEquals("exactly 10,800 s after 3 h of steps", 10800.0, d.runClock, 0.01);
        SaveData old = new SaveData(); old.runClock = 123.4f;          // a clock saved by an older build carries on from where it was
        RunRecord.tick(old, 1f / 60f, true);
        assertEquals(123.4f + 1f / 60f, old.runClock, 0.02f);
        d.finished = true; long t0 = d.runTicks; RunRecord.tick(d, 1f / 60f, true); assertEquals("a finished run's clock stays frozen", t0, d.runTicks);
    }
}
