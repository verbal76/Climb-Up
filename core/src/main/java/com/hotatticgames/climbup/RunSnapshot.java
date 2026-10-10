package com.hotatticgames.climbup;

import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.JsonWriter;
import com.hotatticgames.climbup.sim.Sim;
import com.hotatticgames.climbup.sim.Tower;

/**
 * The exact moment of a climb in progress, written by SAVE &amp; EXIT (and on every pause / autosave) and read back by CONTINUE: where the hero is and how he is moving, what he is holding,
 * which platforms are crumbling or gone, the world clock (so every moving platform is mid-cycle where it was), the checkpoint, the keys and the features already done.
 * <p>
 * Everything is stored against the tower's own coordinates (absolute height and arc, slice-and-element identities), never against the floating window, so it restores into whatever window
 * the resumed tower builds. The geometry itself is not stored: the tower is rebuilt from the append-only slice history (see {@link HistoryStore}) exactly as before.
 * It lives in its own file (run.json), written atomically, so a half-written snapshot can never damage save.json; a snapshot that does not fit the climb on disk is ignored and the climb
 * resumes from its checkpoint as before.
 */
public final class RunSnapshot {
    public static final int VERSION = 1;

    public int version = VERSION;
    public long seed;
    public int maxSlice;                       // highest slice any stored identity refers to (the history must reach it)
    public int cpSlice, cpLocal;               // the checkpoint (red gem)
    // the hero
    public double absS, absY, lastGroundAbs, maxAbs, pullFromAbsS, pullFromAbsY, pullToAbsS, pullToAbsY;
    public float time, vx, vy, coyote, jumpBuf, lockout, pullT, ropeTopT, invuln, clubTime, swingT, shoveCd;
    public int facing = 1, ledgeSide = 1, falls, hits, keys, lastKeyColor;
    public String mode = "AIR";
    public long onKey = -1, lastPadKey = -1, bestKey = -1;
    public boolean jumpedUp, prevJumpHeld, won, finishedRun;
    // elements that are not in their resting state (identity, crumble clock, gone, gone clock, tilt, time stood on)
    public long[] eKey = new long[0];
    public float[] eCrumble = new float[0], eGoneT = new float[0], eTilt = new float[0], eOnT = new float[0];
    public boolean[] eGone = new boolean[0];
    public long[] doneKeys = new long[0];      // features done (keys taken, gates opened, crabs knocked off)

    public RunSnapshot() {}

    // ------------------------------------------------------------------ capture

    /** The state of a live climb, or null when it is not in a state that can be saved faithfully (a respawn is waiting for its window). */
    public static RunSnapshot capture(Sim sim, Tower tower, long seed) {
        if (sim.respawnPending || tower == null) return null;
        RunSnapshot r = new RunSnapshot();
        r.seed = seed;
        r.time = sim.time; r.vx = sim.vx; r.vy = sim.vy; r.coyote = sim.coyote; r.jumpBuf = sim.jumpBuf; r.lockout = sim.lockout; r.pullT = sim.pullT; r.ropeTopT = sim.ropeTopT;
        r.invuln = sim.invuln; r.clubTime = sim.clubTime; r.swingT = sim.swingT; r.shoveCd = sim.shoveCd;
        r.facing = sim.facing; r.ledgeSide = sim.ledgeSide; r.falls = sim.falls; r.hits = sim.hits; r.keys = sim.keys; r.lastKeyColor = sim.lastKeyColor;
        r.mode = sim.mode.name();
        r.jumpedUp = sim.jumpedUp; r.prevJumpHeld = sim.prevJumpHeld; r.won = sim.won; r.finishedRun = sim.finishedRun;
        r.absS = tower.originS + sim.s; r.absY = tower.absY(sim.y); r.lastGroundAbs = tower.absY(sim.lastGroundY); r.maxAbs = tower.absY(sim.maxHeight);
        r.pullFromAbsS = tower.originS + sim.pullFromS; r.pullFromAbsY = tower.absY(sim.pullFromY); r.pullToAbsS = tower.originS + sim.pullToS; r.pullToAbsY = tower.absY(sim.pullToY);
        r.onKey = tower.elemKeyAt(sim.onElem); r.lastPadKey = tower.elemKeyAt(sim.lastPad); r.bestKey = tower.elemKeyAt(sim.bestElem);
        Tower.Ref cp = sim.checkpoint >= 0 && sim.checkpoint < sim.course.size() ? tower.refOf(sim.checkpoint) : tower.checkpointRef();
        if (cp == null) return null;
        r.cpSlice = cp.slice; r.cpLocal = cp.local;
        int n = 0;
        for (int i = 0; i < sim.course.size(); i++) if (resting(sim, i)) continue; else n++;
        r.eKey = new long[n]; r.eCrumble = new float[n]; r.eGoneT = new float[n]; r.eTilt = new float[n]; r.eOnT = new float[n]; r.eGone = new boolean[n];
        int k = 0;
        for (int i = 0; i < sim.course.size(); i++) {
            if (resting(sim, i)) continue;
            r.eKey[k] = tower.elemKeyAt(i); r.eCrumble[k] = sim.crumbleT[i]; r.eGoneT[k] = sim.goneT[i]; r.eTilt[k] = sim.tilt[i]; r.eOnT[k] = sim.onT[i]; r.eGone[k] = sim.gone[i];
            k++;
        }
        java.util.List<Long> done = tower.doneKeys(sim);
        r.doneKeys = new long[done.size()]; for (int i = 0; i < r.doneKeys.length; i++) r.doneKeys[i] = done.get(i);
        r.maxSlice = Math.max(tower.sliceAtAbs(r.absY), r.cpSlice);
        for (long key : r.eKey) r.maxSlice = Math.max(r.maxSlice, (int) (key >> 16));
        return r.valid() ? r : null;
    }

    private static boolean resting(Sim sim, int i) {
        return sim.crumbleT[i] < 0f && !sim.gone[i] && sim.goneT[i] == 0f && sim.tilt[i] == 0f && sim.onT[i] == 0f;
    }

    // ------------------------------------------------------------------ validation and files

    private static boolean fin(double... v) { for (double d : v) if (Double.isNaN(d) || Double.isInfinite(d)) return false; return true; }

    /** Structural sanity: every number finite, a known mode, arrays that agree. A snapshot that fails this is never written and never applied. */
    public boolean valid() {
        if (version != VERSION || seed == 0) return false;
        if (!fin(absS, absY, lastGroundAbs, maxAbs, pullFromAbsS, pullFromAbsY, pullToAbsS, pullToAbsY, time, vx, vy, coyote, jumpBuf, lockout, pullT, ropeTopT, invuln, clubTime, swingT, shoveCd)) return false;
        if (time < 0f || cpSlice < 0 || cpLocal < 0 || maxSlice < 0) return false;
        try { Sim.Mode.valueOf(mode); } catch (Exception e) { return false; }
        if (eKey == null || eCrumble == null || eGoneT == null || eTilt == null || eOnT == null || eGone == null || doneKeys == null) return false;
        int n = eKey.length;
        if (eCrumble.length != n || eGoneT.length != n || eTilt.length != n || eOnT.length != n || eGone.length != n) return false;
        for (int i = 0; i < n; i++) if (!fin(eCrumble[i], eGoneT[i], eTilt[i], eOnT[i])) return false;
        return true;
    }

    public String toJson() { Json j = new Json(JsonWriter.OutputType.json); j.setUsePrototypes(false); return j.toJson(this); }

    /** Parses a stored snapshot, or null when the text is not a valid one. */
    public static RunSnapshot fromJson(String text) {
        try {
            Json j = new Json(); j.setIgnoreUnknownFields(true);
            RunSnapshot r = j.fromJson(RunSnapshot.class, text);
            return r != null && r.valid() ? r : null;
        } catch (Exception e) { return null; }
    }

    // ------------------------------------------------------------------ restore

    /** The slice the window should open around: the one holding the hero. */
    public double centreAbs() { return absY; }

    /**
     * Writes this state into a simulation built on the tower's (already re-centred) world. Returns false, leaving the caller to resume from the checkpoint instead, if what the hero
     * was holding is not in the world (it can't happen with a matching history, but a damaged file must never produce a hero standing on nothing).
     */
    public boolean apply(Sim sim, Tower tower) {
        Sim.Mode m = Sim.Mode.valueOf(mode);
        int on = onKey < 0 ? -1 : tower.indexOfElemKey(onKey);
        boolean attached = m == Sim.Mode.GROUND || m == Sim.Mode.LEDGE || m == Sim.Mode.ROPE || m == Sim.Mode.CABLE || m == Sim.Mode.BEAM || m == Sim.Mode.PULLUP;
        if (attached && on < 0) return false;
        sim.time = time;
        sim.mode = m; sim.onElem = attached ? on : -1;
        sim.s = sim.course.wrap((float) (absS - tower.originS)); sim.y = tower.local(absY);
        sim.vx = vx; sim.vy = vy; sim.facing = facing == 0 ? 1 : facing; sim.coyote = coyote; sim.jumpBuf = jumpBuf; sim.lockout = lockout; sim.pullT = pullT; sim.ropeTopT = ropeTopT;
        sim.ledgeSide = ledgeSide; sim.jumpedUp = jumpedUp; sim.prevJumpHeld = prevJumpHeld; sim.won = won; sim.finishedRun = finishedRun;
        sim.falls = falls; sim.hits = hits; sim.keys = keys; sim.lastKeyColor = lastKeyColor; sim.invuln = invuln; sim.clubTime = clubTime; sim.swingT = swingT; sim.shoveCd = shoveCd;
        sim.lastGroundY = tower.local(lastGroundAbs); sim.maxHeight = tower.local(maxAbs);
        sim.pullFromS = sim.course.wrap((float) (pullFromAbsS - tower.originS)); sim.pullFromY = tower.local(pullFromAbsY);
        sim.pullToS = sim.course.wrap((float) (pullToAbsS - tower.originS)); sim.pullToY = tower.local(pullToAbsY);
        sim.lastPad = lastPadKey < 0 ? -1 : tower.indexOfElemKey(lastPadKey);
        int best = bestKey < 0 ? -1 : tower.indexOfElemKey(bestKey);
        if (best >= 0) sim.bestElem = best;
        sim.checkpoint = tower.worldIndex(new Tower.Ref(cpSlice, cpLocal));
        sim.ps0 = sim.s; sim.py0 = sim.y; sim.teleported = true;
        sim.ensureCapacity();
        for (int i = 0; i < eKey.length; i++) {
            int w = tower.indexOfElemKey(eKey[i]);
            if (w < 0) continue;
            sim.crumbleT[w] = eCrumble[i]; sim.gone[w] = eGone[i]; sim.goneT[w] = eGoneT[i]; sim.tilt[w] = eTilt[i]; sim.onT[w] = eOnT[i];
        }
        tower.addDone(doneKeys);
        tower.applyDone(sim);
        sim.syncElements();
        return true;
    }
}
