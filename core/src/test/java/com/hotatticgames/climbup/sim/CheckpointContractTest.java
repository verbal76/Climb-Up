package com.hotatticgames.climbup.sim;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.RunSnapshot;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;

/**
 * Owner correction directive 2026-10-27, DC3: one checkpoint contract. A retry meets every encounter in progress as it was generated (creatures,
 * aimed cannons, seesaws, ramps, belts, avalanches above the checkpoint), keeps what the run achieved (keys, gates, resolved encounters, time), a
 * spent avalanche stays spent through a slice unload, and a save taken in the frame of a fall is the respawned climb, not a deleted run file.
 */
public class CheckpointContractTest {
    static Tuning T() throws Exception { return Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json")))); }
    static Element el(Element.Type ty, float s, float y, float w) { return new Element(ty, s, y, w); }

    @Test public void aRetryResetsEncountersInProgressAndKeepsWhatTheRunAchieved() throws Exception {
        Tuning t = T();
        Course c = new Course(1, t.circumference());
        Element cp = el(Element.Type.STATIC, 0f, 0f, 4f); cp.checkpoint = true; c.add(cp);          // 0 checkpoint
        Element saw = el(Element.Type.SEESAW, 6f, 0.5f, 5f); c.add(saw);                             // 1
        Element ramp = el(Element.Type.RAMP, 12f, 1f, 4f); ramp.skin = 2; ramp.amp = 0.3f; c.add(ramp);   // 2 shaking ramp
        c.add(el(Element.Type.STATIC, 18f, 1.5f, 4f));                                               // 3
        Element crab = el(Element.Type.CRAB, 18f, 1.5f, 1f); crab.anchor = 3; crab.amp = 0; crab.period = 4; c.hazards.add(crab);          // 0
        Element above = el(Element.Type.AVALANCHE, 18f, 1.5f, 1f); above.anchor = 3; above.len = 2; above.period = 5; c.hazards.add(above); // 1
        Element below = el(Element.Type.AVALANCHE, 0f, 0f, 1f); below.anchor = 0; below.len = 2; below.period = 5; c.hazards.add(below);    // 2
        Element key = new Element(Element.Type.KEY, 12f, 2.2f, 0f); key.anchor = 2; key.color = 1; c.hazards.add(key);                     // 3
        c.finishRoute();
        Sim m = new Sim(c, t); m.ensureCapacity();
        m.tilt[1] = 0.3f; m.tilt[2] = 5f; m.onT[2] = 0.2f;
        float[] hs = m.hazardState(0); hs[3] = 1.5f; hs[5] = 2f; hs[7] = 0.4f; m.setHazardState(0, hs);          // crab mid-chase (pos, mode, cool-down)
        m.avl[1 * Sim.AVL_W] = 2f; m.featDone[1] = true;          // the avalanche above the checkpoint was spent on the failed attempt
        m.avl[2 * Sim.AVL_W] = 2f; m.featDone[2] = true;          // the one at the checkpoint was spent before it was reached
        m.featDone[3] = true; m.keys = 1 << 1;                    // the key was taken
        m.lastAvlT = 3f; m.time = 9f;
        m.completeRespawn(0);
        assertEquals("seesaw level again", 0f, m.tilt[1], 0f);
        assertEquals("shaking ramp counts from 0 again", 0f, m.tilt[2], 0f);
        assertEquals(0f, m.onT[2], 0f);
        float[] h2 = m.hazardState(0); assertEquals("crab home", 0f, h2[3], 0f); assertEquals(0f, h2[5], 0f); assertEquals(0f, h2[7], 0f);
        assertEquals("avalanche above the checkpoint armed again", 0f, m.avl[1 * Sim.AVL_W], 0f); assertFalse(m.featDone[1]);
        assertEquals("avalanche at the checkpoint stays spent", 2f, m.avl[2 * Sim.AVL_W], 0f); assertTrue(m.featDone[2]);
        assertTrue("the key stays taken", m.featDone[3]); assertEquals(1 << 1, m.keys);
        assertEquals("the run clock keeps running", 9f, m.time, 0f);
        assertEquals("cooldown cleared with the re-armed avalanche", -1e9f, m.lastAvlT, 0f);
    }

    /** The audit's probe P6: the second attempt at a shaking ramp met the opposite kick to the first and to the proof. */
    @Test public void aShakingRampKicksTheSameWayOnEveryAttempt() throws Exception {
        Tuning t = T();
        Course c = new Course(1, t.circumference()); c.add(el(Element.Type.STATIC, -20, 0, 4));
        Element rp = el(Element.Type.RAMP, 0, 0, 4); rp.skin = 2; rp.amp = 0.3f; c.add(rp);
        Sim a = new Sim(c, t); a.spawnAtCheckpoint(1);
        float[] k1 = kick(a); a.completeRespawn(1); float[] k2 = kick(a); a.completeRespawn(1); float[] k3 = kick(a);
        assertArrayEquals(k1, k2, 0f); assertArrayEquals(k1, k3, 0f);
    }
    static float[] kick(Sim m) { int k = 0; while (m.mode == Sim.Mode.GROUND && k++ < 600) m.step(new InputState()); return new float[]{m.vx, m.vy}; }

    /** A spent avalanche is a resolved encounter: it records itself as done, which Tower keeps when its slice unloads, and a done one never fires. */
    @Test public void aSpentAvalancheStaysSpent() throws Exception {
        Tuning t = T();
        Course c = new Course(1, t.circumference()); c.add(el(Element.Type.STATIC, 0f, 0f, 6f));
        Element av = el(Element.Type.AVALANCHE, 0f, 0f, 1f); av.anchor = 0; av.len = 2; av.period = 5; c.hazards.add(av);
        c.finishRoute();
        Sim m = new Sim(c, t); m.ensureCapacity(); m.avl[0] = 2f;
        m.step(new InputState());
        assertTrue("spent is recorded as done", m.featDone[0]);
        Sim fresh = new Sim(c, t); fresh.ensureCapacity(); fresh.featDone[0] = true;          // as Tower.applyDone restores it after a reload
        fresh.spawnAtCheckpoint(0);
        for (int i = 0; i < 120; i++) fresh.step(new InputState());
        assertEquals("a done avalanche never fires again", 0, fresh.avlFired);
    }

    /** The audit's N5: a save in the frame of a fall wrote nothing and deleted run.json. Now it is the climb respawned, and restores into the same climb. */
    @Test public void aSaveInTheFrameOfAFallIsTheRespawnedClimb() throws Exception {
        Tuning t = T();
        long seed = 33;
        Tower a0 = new Tower(seed, t); while (a0.topAbsY() < 200) a0.extend();
        List<byte[]> blobs = new ArrayList<>(); for (int k = 0; k < a0.sliceCount(); k++) blobs.add(a0.blob(k));
        Tower ta = new Tower(seed, t, blobs, 0); ta.windowAroundAbs(0);
        Sim a = new Sim(ta.world, t); a.keysFree = false; a.deferRespawn = true; a.floorOverride = ta.floorLocal(); a.setRange(0, ta.world.size() - 1);
        InputState in = new InputState();
        int step = 0;
        // walk off the start, into the void below, until the fall is pending (the frame a pause would save)
        while (!a.respawnPending && step < 6000) { ta.maintain(a); in.clear(); in.moveX = -1f; a.step(in); a.consumeEvents(); step++; }
        assertTrue("the fall happened", a.respawnPending);
        RunSnapshot snap = RunSnapshot.capture(a, ta, seed);
        assertNotNull("a save during the fall is written", snap);
        RunSnapshot back = RunSnapshot.fromJson(snap.toJson());
        assertNotNull(back);
        ta.maintain(a);          // the real game completes the respawn at its next frame
        Tower tb = new Tower(seed, t, blobs, 0); tb.setCheckpointRef(new Tower.Ref(back.cpSlice, back.cpLocal)); back.restoreWindow(tb);
        Sim b = new Sim(tb.world, t); b.keysFree = false; b.deferRespawn = true; b.floorOverride = tb.floorLocal(); b.setRange(0, tb.world.size() - 1);
        assertTrue(back.apply(b, tb));
        assertEquals("standing at the checkpoint", a.mode, b.mode);
        assertEquals(ta.elemKeyAt(a.onElem), tb.elemKeyAt(b.onElem));
        assertEquals("the fall counted once", a.falls, b.falls);
        assertEquals(ta.absY(a.y), tb.absY(b.y), 1e-4);
    }
}
