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
    public long[] doneKeys = new long[0];
    // Build 71: hazards that are mid-move (cannon aim, crabs, bees, skulls, blobs), Sim.HAZ_STATE floats each, and the ice skid cooldown. Optional: older files have none, and those hazards start fresh.
    public long[] hKey = new long[0];
    public float[] hState = new float[0];
    public float slipCd;      // features done (keys taken, gates opened, crabs knocked off)
    // Build 72: where the hero stood at the start of the last step: ramps and seesaws report their surface there (optional: older files fall back to absS)
    public boolean hasS0;
    public double absS0;
    // 2026-10-27: spike-block avalanches under way or spent (Sim.AVL_W floats each) and when the last one fired. Optional: older files have none.
    // rules 14: the floating origin the climb was captured in (restoreWindow rebuilds with it). Optional: older files restore around their own height.
    public boolean hasOrigin;
    public double originAbsY, originAbsS;
    public long[] aKey = new long[0];
    public float[] aState = new float[0];
    public float lastAvlT = -1e9f;
    // DC3 (2026-10-27): a side catch in progress (the platform whose side the body slipped into, and from which side). Optional: older files have none.
    public long sideKey = -1;
    public int sideDir = 1;
    // Rules 19 (owner decision 2026-10-29): holding a Tarzan swing, how far down the rope the hands are (Sim.tarzanR, from the pivot). The rope's own
    // place follows from the course and the clock. Optional: hasTarzanR false (older files) means absent, never a grip of 0.
    public boolean hasTarzanR;
    public float tarzanR;
    // Rules 19: a swing just let go of (Sim.tarzanLast) and how long it stays out of reach. Optional: older files have none (-1, 0).
    public long tarzanLastKey = -1;
    public float tarzanAgainT;

    public RunSnapshot() {}

    // ------------------------------------------------------------------ capture

    /**
     * The state of a live climb, or null when it cannot be saved faithfully. DC3 (owner correction 2026-10-27): a save in the very frame of a fall
     * (the respawn waiting for its window) is the climb as it will be a moment later, back at the checkpoint, when the checkpoint is resident; it used
     * to give null, and the run file was deleted, so the next CONTINUE was a checkpoint-only resume.
     */
    public static RunSnapshot capture(Sim sim, Tower tower, long seed) {
        if (tower == null) return null;
        if (sim.respawnPending) {
            if (sim.checkpoint < 0 || sim.checkpoint >= sim.course.size()) return null;
            Sim back = sim.copy(); back.completeRespawn(sim.checkpoint); sim = back;
        }
        RunSnapshot r = new RunSnapshot();
        r.seed = seed;
        r.time = sim.time; r.vx = sim.vx; r.vy = sim.vy; r.coyote = sim.coyote; r.jumpBuf = sim.jumpBuf; r.lockout = sim.lockout; r.pullT = sim.pullT; r.ropeTopT = sim.ropeTopT;
        r.invuln = sim.invuln; r.clubTime = sim.clubTime; r.swingT = sim.swingT; r.shoveCd = sim.shoveCd;
        r.facing = sim.facing; r.ledgeSide = sim.ledgeSide; r.falls = sim.falls; r.hits = sim.hits; r.keys = sim.keys; r.lastKeyColor = sim.lastKeyColor;
        r.mode = (sim.mode == Sim.Mode.GROUND && sim.onElem < 0) ? Sim.Mode.AIR.name() : sim.mode.name();      // standing on a spike slab's top: restored just above it, it lands again at once
        r.jumpedUp = sim.jumpedUp; r.prevJumpHeld = sim.prevJumpHeld; r.won = sim.won; r.finishedRun = sim.finishedRun;
        r.hasOrigin = true; r.originAbsY = tower.originY; r.originAbsS = tower.originS; r.absS = tower.originS + sim.s; r.hasS0 = true; r.absS0 = tower.originS + sim.ps0; r.absY = tower.absY(sim.y); r.lastGroundAbs = tower.absY(sim.lastGroundY); r.maxAbs = tower.absY(sim.maxHeight);
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
        r.slipCd = sim.slipCd;
        java.util.ArrayList<Long> hk = new java.util.ArrayList<>(); java.util.ArrayList<float[]> hv = new java.util.ArrayList<>();
        sim.ensureCapacity();
        for (int i = 0; i < sim.course.hazards.size(); i++) {
            float[] v = sim.hazardState(i); long key = tower.hazKeyAt(i);
            if (key < 0 || pristine(v)) continue;
            hk.add(key); hv.add(v);
        }
        r.hKey = new long[hk.size()]; r.hState = new float[hk.size() * Sim.HAZ_STATE];
        for (int i = 0; i < hk.size(); i++) { r.hKey[i] = hk.get(i); System.arraycopy(hv.get(i), 0, r.hState, i * Sim.HAZ_STATE, Sim.HAZ_STATE); }
        java.util.ArrayList<Long> ak = new java.util.ArrayList<>(); java.util.ArrayList<Integer> ai = new java.util.ArrayList<>();
        for (int i = 0; i < sim.course.hazards.size(); i++) {
            if (sim.course.hazards.get(i).type != com.hotatticgames.climbup.sim.Element.Type.AVALANCHE || sim.avalancheState(i) == 0) continue;
            long key = tower.hazKeyAt(i); if (key < 0) continue;
            ak.add(key); ai.add(i);
        }
        r.aKey = new long[ak.size()]; r.aState = new float[ak.size() * Sim.AVL_W];
        for (int i = 0; i < ak.size(); i++) { r.aKey[i] = ak.get(i); System.arraycopy(sim.avl, ai.get(i) * Sim.AVL_W, r.aState, i * Sim.AVL_W, Sim.AVL_W); }
        r.lastAvlT = sim.lastAvlT;
        if (sim.mode == Sim.Mode.TARZAN) { r.hasTarzanR = true; r.tarzanR = sim.tarzanR; }
        if (sim.tarzanAgainT > 0f && sim.tarzanLast >= 0) { r.tarzanLastKey = tower.elemKeyAt(sim.tarzanLast); r.tarzanAgainT = sim.tarzanAgainT; }
        int[] se = sim.sideEntry();
        if (se[0] >= 0) { r.sideKey = tower.elemKeyAt(se[0]); r.sideDir = se[1]; }
        java.util.List<Long> done = tower.doneKeys(sim);
        r.doneKeys = new long[done.size()]; for (int i = 0; i < r.doneKeys.length; i++) r.doneKeys[i] = done.get(i);
        r.maxSlice = Math.max(tower.sliceAtAbs(r.absY), r.cpSlice);
        for (long key : r.eKey) r.maxSlice = Math.max(r.maxSlice, (int) (key >> 16));
        return r.valid() ? r : null;
    }

    /** A hazard still in its never-touched state (what a fresh Sim gives it), so it needs no record. */
    private static boolean pristine(float[] v) {
        for (int k = 0; k < v.length; k++) if (k == 1 ? v[k] != -1e9f : v[k] != 0f) return false;
        return true;
    }

    private static boolean resting(Sim sim, int i) {
        return sim.crumbleT[i] < 0f && !sim.gone[i] && sim.goneT[i] == 0f && sim.tilt[i] == 0f && sim.onT[i] == 0f;
    }

    // ------------------------------------------------------------------ validation and files

    private static boolean fin(double... v) { for (double d : v) if (Double.isNaN(d) || Double.isInfinite(d)) return false; return true; }

    /** Structural sanity: every number finite, a known mode, arrays that agree. A snapshot that fails this is never written and never applied. */
    public boolean valid() {
        if (version != VERSION || seed == 0) return false;
        if (hasTarzanR && !(fin(tarzanR) && tarzanR >= 0f)) return false;
        if (!fin(tarzanAgainT) || tarzanAgainT < 0f) return false;
        if (!fin(absS, absY, lastGroundAbs, maxAbs, pullFromAbsS, pullFromAbsY, pullToAbsS, pullToAbsY, time, vx, vy, coyote, jumpBuf, lockout, pullT, ropeTopT, invuln, clubTime, swingT, shoveCd)) return false;
        if (time < 0f || cpSlice < 0 || cpLocal < 0 || maxSlice < 0) return false;
        try { Sim.Mode.valueOf(mode); } catch (Exception e) { return false; }
        if (eKey == null || eCrumble == null || eGoneT == null || eTilt == null || eOnT == null || eGone == null || doneKeys == null) return false;
        int n = eKey.length;
        if (eCrumble.length != n || eGoneT.length != n || eTilt.length != n || eOnT.length != n || eGone.length != n) return false;
        for (int i = 0; i < n; i++) if (!fin(eCrumble[i], eGoneT[i], eTilt[i], eOnT[i])) return false;
        if (hKey == null || hState == null || hState.length != hKey.length * Sim.HAZ_STATE || !fin(slipCd)) return false;
        for (float f : hState) if (!fin(f)) return false;
        if (aKey == null || aState == null || !fin(lastAvlT)) return false;
        if (aState.length == aKey.length * AVL_W_R13 && AVL_W_R13 != Sim.AVL_W) aState = widenAvl(aState, aKey.length, AVL_W_R13);          // a save from before rules 14 (5 block slots)
        else if (aState.length == aKey.length * AVL_W_R17 && AVL_W_R17 != Sim.AVL_W) aState = widenAvl(aState, aKey.length, AVL_W_R17);     // rules 14-17 (7 block slots)
        if (aState.length != aKey.length * Sim.AVL_W) return false;
        for (float f : aState) if (!fin(f)) return false;
        return true;
    }

    /** Rules 12-13 kept 5 block slots per avalanche (2 + 5 x Sim.AVL_B floats); rules 14 keeps Element.AVL_MAX. The extra slots of an old save are empty. */
    /** How far off the rope's line a grip-less Tarzan save's hands may be and still be taken as holding it (m). */
    static final float TARZAN_LEGACY_TOL = 0.5f;
    static final int AVL_W_R13 = 2 + 5 * Sim.AVL_B, AVL_W_R17 = 2 + 7 * Sim.AVL_B;          // (rules 14-17 kept 7 slots; rules 18 keeps 12)
    static float[] widenAvl(float[] old, int n) { return widenAvl(old, n, AVL_W_R13); }
    static float[] widenAvl(float[] old, int n, int oldW) {
        float[] w = new float[n * Sim.AVL_W];
        for (int i = 0; i < n; i++) System.arraycopy(old, i * oldW, w, i * Sim.AVL_W, oldW);
        return w;
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
    /** Moves the tower's resident window to this climb before {@link #apply}: around its height, in the floating origin it was saved in (when the file has it). */
    public void restoreWindow(Tower tower) {
        if (hasOrigin && Double.isFinite(originAbsY) && Double.isFinite(originAbsS)) tower.windowAroundAbs(centreAbs(), originAbsY, originAbsS);
        else tower.windowAroundAbs(centreAbs());
    }

    /**
     * Writes this state into a simulation built on the tower's (already re-centred) world. Returns false, leaving the caller to resume from the checkpoint instead, if what the hero
     * was holding is not in the world (it can't happen with a matching history, but a damaged file must never produce a hero standing on nothing).
     */
    /**
     * An absolute arc in the restoring window's frame, wrapped in double before the one rounding to float (rules 14 fix): wrapping the float difference
     * lost up to an ulp of the unwrapped value (seed 7 slice 6: -288 m before the wrap, 8e-6 m off), and a skull chasing the restored climber grew that to
     * 2e-4 m within 183 steps (PlayDeterminismTest). With the window origins a whole number of circumferences apart the restored arc is now exact.
     */
    static float localS(Sim sim, Tower tower, double abs) {
        double c = sim.course.circumference, v = (abs - tower.originS) % c;
        if (v < 0) v += c;
        float f = (float) v;
        return f >= sim.course.circumference ? 0f : f;
    }

    public boolean apply(Sim sim, Tower tower) {
        if (aKey != null && aState != null && aState.length == aKey.length * AVL_W_R13 && AVL_W_R13 != Sim.AVL_W) aState = widenAvl(aState, aKey.length);
        Sim.Mode m = Sim.Mode.valueOf(mode);
        int on = onKey < 0 ? -1 : tower.indexOfElemKey(onKey);
        boolean attached = m == Sim.Mode.GROUND || m == Sim.Mode.LEDGE || m == Sim.Mode.ROPE || m == Sim.Mode.CABLE || m == Sim.Mode.BEAM || m == Sim.Mode.PULLUP || m == Sim.Mode.TARZAN;
        if (attached && on < 0) return false;
        sim.time = time;
        sim.mode = m; sim.onElem = attached ? on : -1;
        sim.s = localS(sim, tower, absS); sim.y = tower.local(absY);
        sim.vx = vx; sim.vy = vy; sim.facing = facing == 0 ? 1 : facing; sim.coyote = coyote; sim.jumpBuf = jumpBuf; sim.lockout = lockout; sim.pullT = pullT; sim.ropeTopT = ropeTopT;
        sim.ledgeSide = ledgeSide; sim.jumpedUp = jumpedUp; sim.prevJumpHeld = prevJumpHeld; sim.won = won; sim.finishedRun = finishedRun;
        sim.falls = falls; sim.hits = hits; sim.keys = keys; sim.lastKeyColor = lastKeyColor; sim.invuln = invuln; sim.clubTime = clubTime; sim.swingT = swingT; sim.shoveCd = shoveCd;
        sim.lastGroundY = tower.local(lastGroundAbs); sim.maxHeight = tower.local(maxAbs);
        sim.pullFromS = localS(sim, tower, pullFromAbsS); sim.pullFromY = tower.local(pullFromAbsY);
        sim.pullToS = localS(sim, tower, pullToAbsS); sim.pullToY = tower.local(pullToAbsY);
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
        sim.slipCd = slipCd;
        for (int i = 0; i < hKey.length; i++) {
            int w = tower.indexOfHazKey(hKey[i]);
            if (w >= 0) sim.setHazardState(w, java.util.Arrays.copyOfRange(hState, i * Sim.HAZ_STATE, (i + 1) * Sim.HAZ_STATE));
        }
        sim.lastAvlT = lastAvlT;
        for (int i = 0; i < aKey.length; i++) {
            int w = tower.indexOfHazKey(aKey[i]);
            if (w >= 0 && (w + 1) * Sim.AVL_W <= sim.avl.length) System.arraycopy(aState, i * Sim.AVL_W, sim.avl, w * Sim.AVL_W, Sim.AVL_W);
        }
        tower.addDone(doneKeys);
        tower.applyDone(sim);
        sim.syncElements(hasS0 ? localS(sim, tower, absS0) : sim.s);
        if (sideKey >= 0 && sim.mode == Sim.Mode.AIR) { int w = tower.indexOfElemKey(sideKey); if (w >= 0) sim.setSideEntry(w, sideDir); }          // (after syncElements, which clears it)
        int tl = tarzanLastKey < 0 ? -1 : tower.indexOfElemKey(tarzanLastKey);
        sim.tarzanLast = tl; sim.tarzanAgainT = tl >= 0 ? tarzanAgainT : 0f;
        if (m == Sim.Mode.TARZAN) {          // rules 19: back on the same rope, the same distance down it, wherever it has swung to at the saved time
            if (sim.course.get(on).type != com.hotatticgames.climbup.sim.Element.Type.TARZAN) return false;
            float r = hasTarzanR ? tarzanR : sim.tarzanGripFromPosition(on, TARZAN_LEGACY_TOL);      // a file without the grip: project the hands onto the rope
            if (Float.isNaN(r)) return false;                                                         // not on the rope after all: the checkpoint resume
            sim.attachTarzan(on, r);
        }
        return true;
    }
}
