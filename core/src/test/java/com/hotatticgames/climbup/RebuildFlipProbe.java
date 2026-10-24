package com.hotatticgames.climbup;

import com.hotatticgames.climbup.sim.*;

/**
 * Build 79 dev probe for TowerStreamingTest.rebuildingTheWindow...: replays the test's inputs into a resident tower and a rebuilt one and prints, around
 * the first frame whose mode differs, both states (height above the surface underfoot, speeds, the surface's identity) and the origin offset of each rebuild.
 *   ./gradlew :core:probe -Pmain=com.hotatticgames.climbup.RebuildFlipProbe -Pargs="[origin step, default 500]"
 */
public class RebuildFlipProbe {
    static Sim managed(Tower tw, Tuning t) { Sim s = Sim.startOn(tw.world, t, 0); s.keysFree = true; s.deferRespawn = true; s.floorOverride = tw.floorLocal(); s.setRange(0, tw.world.size() - 1); return s; }
    static void frame(Tower tw, Sim s, InputState in, int[] n) { tw.maintain(s); if (tw.world.size() != n[0]) { s.setRange(0, tw.world.size() - 1); n[0] = tw.world.size(); } s.step(in); s.consumeEvents(); }
    public static void main(String[] args) throws Exception {
        double step = args.length > 0 ? Double.parseDouble(args[0]) : 500.0;
        Tuning t = TestUtil.tuning();
        Tower a = new Tower(5, t), b = new Tower(5, t);
        for (int i = 0; i < 13; i++) { a.extend(); b.extend(); }
        Sim sa = managed(a, t), sb = managed(b, t);
        int[] za = {a.world.size()}, zb = {b.world.size()}; InputState in = new InputState();
        int firstBit = -1, flip = -1, diffFrames = 0, runLen = 0, maxRun = 0; double worstAfter = 0, endGap = 0; boolean fallsDiffer = false; StringBuilder seqA = new StringBuilder(), seqB = new StringBuilder(); String[] hist = new String[8];
        for (int f = 0; f < 60 * 40; f++) {
            in.clear(); in.moveX = (float) Math.sin(f / 37.0) * 1.2f; in.jumpPressed = f % 53 == 0; in.jumpHeld = f % 53 < 22; in.moveY = (f / 211) % 3 == 1 ? 1f : 0f;
            frame(a, sa, in, za);
            if (f % 90 == 45) { int ps = b.sliceAt(b.absY(sb.y)), below = 1 + (f / 90) % 3, above = 1 + (f / 90) % 2; b.rebuildExplicit(sb, Math.max(0, ps - below), Math.min(b.sliceCount() - 1, ps + above), (f / 90 % 3) * step); }
            frame(b, sb, in, zb);
            double dy = a.absY(sa.y) - b.absY(sb.y), ds = a.world.dsWrap(sa.s, sb.s);
            if (firstBit < 0 && (dy != 0 || ds != 0)) firstBit = f;
            String ra = sa.onElem >= 0 ? a.refOf(sa.onElem).slice + ":" + a.refOf(sa.onElem).local : "-", rb = sb.onElem >= 0 ? b.refOf(sb.onElem).slice + ":" + b.refOf(sb.onElem).local : "-";
            hist[f % 8] = String.format("f %d A %s on %s absY %.6f vy %.4f | B %s on %s absY %.6f vy %.4f | dy %.2e ds %.2e", f, sa.mode, ra, a.absY(sa.y), sa.vy, sb.mode, rb, b.absY(sb.y), sb.vy, dy, ds);
            if (sa.mode != sb.mode || !ra.equals(rb)) { if (flip < 0) { flip = f; for (int k = 7; k >= 0; k--) { String h = hist[(flip - k + 800) % 8]; if (h != null) System.out.println("  " + h); } } diffFrames++; runLen++; maxRun = Math.max(maxRun, runLen); }
            else runLen = 0;
            if (flip >= 0) { worstAfter = Math.max(worstAfter, Math.max(Math.abs(dy), Math.abs(ds))); if (f == 2399) endGap = Math.max(Math.abs(dy), Math.abs(ds)); }
            if (!ra.startsWith("-") && !seqA.toString().endsWith("|" + ra)) seqA.append("|").append(ra);
            if (!rb.startsWith("-") && !seqB.toString().endsWith("|" + rb)) seqB.append("|").append(rb);
            if (sa.falls != sb.falls) fallsDiffer = true;
        }
        System.out.println("REBUILD first bit difference at frame " + firstBit + ", first mode/surface difference at frame " + flip);
        System.out.println("REBUILD frames with a mode/surface difference " + diffFrames + ", longest run " + maxRun + " steps, worst gap after it " + worstAfter + " m, gap at the end " + endGap + " m, same surface sequence " + seqA.toString().equals(seqB.toString()) + " (" + seqA.toString().split("\\|").length + " surfaces), falls " + sa.falls + "/" + sb.falls + (fallsDiffer ? " (differed)" : ""));
    }
}
