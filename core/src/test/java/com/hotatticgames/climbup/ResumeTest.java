package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Saving a climb, killing the app, resuming it from disk: the same tower, the same checkpoint, the same keys and castles, and a fall from anywhere still ends on that checkpoint. */
public class ResumeTest {
    @Test public void aClimbResumedFromDiskIsTheSameTowerWithTheSameKeysCastlesAndCheckpointEvenAfterAnExtremeFall() throws Exception {
        Tuning t = TestUtil.tuning(); long seed = 33;
        Tower a = new Tower(seed, t); while (a.topAbsY() < 1200) a.extend();
        int cpIdx = -1; for (int i = 1; i < a.world.routeSize(); i++) if (a.world.get(i).checkpoint && a.absY(a.world.get(i).y) > 700) { cpIdx = i; break; }
        assertTrue("a checkpoint above 700 m exists", cpIdx > 0);
        Tower.Ref ref = a.refOf(cpIdx); double cpAbs = a.absY(a.world.get(cpIdx).y);

        File dir = Files.createTempDirectory("climb-resume2").toFile();
        HistoryStore hs = new HistoryStore(dir); hs.reset(seed); for (int k = 0; k < a.sliceCount(); k++) hs.append(a.blob(k));
        SaveStore st = new SaveStore(dir); SaveData sd = new SaveData(); sd.seed = seed; sd.cpSlice = ref.slice; sd.cpLocal = ref.local; sd.keysHeld = 2; sd.openedUpTo = 1; sd.towers = 1; st.saveGame(sd);

        // the app is killed; everything below is rebuilt from the files alone
        SaveData saved = new SaveStore(dir).loadGame();
        List<byte[]> hist = new HistoryStore(dir).read(saved.seed);
        assertNotNull(hist); assertEquals(a.sliceCount(), hist.size());
        Tower r = new Tower(saved.seed, t, hist, saved.cpSlice);
        Tower.Ref back = new Tower.Ref(saved.cpSlice, saved.cpLocal);
        int idx = r.worldIndex(back); assertTrue("the checkpoint's slice is resident after a resume", idx >= 0);
        assertEquals("the checkpoint is the same platform at the same height", cpAbs, r.absY(r.world.get(idx).y), 1e-3);
        assertEquals("and the same place around the tower", a.world.wrap(a.world.get(cpIdx).s), r.world.wrap(r.world.get(idx).s), 1e-3);
        r.setCheckpointRef(back);
        Sim s = Sim.startOn(r.world, t, idx); s.checkpoint = idx; s.keysFree = false; s.keys = 0; s.deferRespawn = true; s.floorOverride = r.floorLocal(); s.setRange(0, r.world.size() - 1);
        ResumeState.restore(saved, s, r);
        assertEquals("the keys carried", 2, s.keys);
        for (int i = 0; i < r.world.hazards.size(); i++) { Element h = r.world.hazards.get(i); if (h.type == Element.Type.GATE && h.skin > 0 && h.skin <= 1) assertTrue("castle 1 is still open (gate " + i + ")", s.featDone[i]); }

        InputState in = new InputState(); int[] z = {r.world.size()};
        for (int f = 0; f < 120; f++) { r.maintain(s); if (r.world.size() != z[0]) { s.setRange(0, r.world.size() - 1); z[0] = r.world.size(); } for (int k = 0; k < 6; k++) { s.step(in); s.consumeEvents(); } }
        assertEquals("standing on the checkpoint, nothing fell", 0, s.falls);
        assertEquals(cpAbs, r.absY(s.y), 0.2);

        // an extreme fall from far above the checkpoint: climb the resident window up, then drop out of the bottom of the whole tower
        while (r.topAbsY() < cpAbs + 400) r.extend();
        r.windowAround(s, cpAbs + 350);
        s.mode = Sim.Mode.AIR; s.onElem = -1; s.y = r.floorLocal() - 30f; s.vy = -20f; s.vx = 0f;
        for (int f = 0; f < 10 && s.falls == 0; f++) { r.maintain(s); if (r.world.size() != z[0]) { s.setRange(0, r.world.size() - 1); z[0] = r.world.size(); } for (int k = 0; k < 6; k++) { s.step(in); s.consumeEvents(); } }
        for (int f = 0; f < 120; f++) { r.maintain(s); if (r.world.size() != z[0]) { s.setRange(0, r.world.size() - 1); z[0] = r.world.size(); } for (int k = 0; k < 6; k++) { s.step(in); s.consumeEvents(); } }
        assertEquals("sent back once", 1, s.falls);
        assertEquals("standing on the last checkpoint again", Sim.Mode.GROUND, s.mode);
        assertEquals("at the same height as before", cpAbs, r.absY(s.y), 0.2);
        assertEquals("with the keys still in hand", 2, s.keys);
        Tower.Ref now = r.refOf(s.onElem); assertEquals(back.slice, now.slice); assertEquals(back.local, now.local);
    }
}
