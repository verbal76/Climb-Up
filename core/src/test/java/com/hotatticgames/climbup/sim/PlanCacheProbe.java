package com.hotatticgames.climbup.sim;

import java.nio.file.*;
import java.util.*;
import org.junit.Test;

/**
 * Scratch probe (plan cache validation, owner overnight directive 2026-10-30, priority A; not a fixture). Grows the same climbs with the plan cache
 * off, in shadow mode and on, exactly as the game grows them (GameGrowth: backtracks included), and compares every final slice: the encoded slice
 * (SliceCodec: every element, hazard, enemy and traversal module with every field), the trace's tallies (module attempts, refusal reasons, proofs,
 * rebound and knock accounting, continuity verdicts), every module attempt the trace logged (kind, role, result, refusal counters, element and
 * hazard ranges), the backtrack count. Prints wall time per metre per mode and the cache's counters.
 *
 * <p>Env: CLIMB_PROBE (required), CLIMB_CASES = "mode:seed:top,..." (mode 0 NORMAL, 1 PUNISHER, 2 NGH), CLIMB_MODES = "off,shadow,on".
 */
public class PlanCacheProbe {
    @Test public void equivalence() throws Exception {
        org.junit.Assume.assumeTrue(System.getenv("CLIMB_PROBE") != null);
        String cases = System.getenv().getOrDefault("CLIMB_CASES", "0:7322:300,1:7322:300,2:7322:150");
        String[] modes = System.getenv().getOrDefault("CLIMB_MODES", "off,shadow,on").split(",");
        GenTrace.clock = System::nanoTime;
        Tuning base = Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json"))));
        int bad = 0;
        for (String cs : cases.split(",")) {
            String[] p = cs.split(":"); int lvl = Integer.parseInt(p[0]); long seed = Long.parseLong(p[1]); float top = Float.parseFloat(p[2]);
            Tuning t = lvl == 0 ? base : base.hardCopy(lvl);
            List<String> ref = null; String refMode = null; List<TreeMap<String, Integer>> refCnt = null;
            for (String m : modes) {
                PlanCache.MODE = PlanCache.parse(m.replace("early+", "")); PlanCache.resetStats();
                CourseGenerator.EARLY_REJECT = m.startsWith("early"); CourseGenerator.EARLY_REFUSED.set(0);
                boolean loose = Arrays.stream(modes).anyMatch(x -> x.startsWith("early"));          // (early rejection changes refusal diagnostics only: compare what generation produced)
                List<String> sig = new ArrayList<>(); List<TreeMap<String, Integer>> cnt = new ArrayList<>(); GameGrowth g = new GameGrowth(seed, t);
                long w0 = System.nanoTime(); double y = 0; int k = 0; String stop = "";
                try {
                    for (; k < 200 && y < top; k++) {
                        CourseGenerator.Framed f = g.next();
                        y = f.yBase + f.c.get(f.c.routeSize() - 1).y;
                        sig.add(signature(k, f, g, loose)); cnt.add(untimed(g.trace.counts));
                    }
                } catch (RuntimeException e) { stop = " STOPPED at slice " + g.failedSlice + ": " + e.getMessage(); sig.add("stop " + g.failedSlice + " " + e.getMessage()); }
                double wall = (System.nanoTime() - w0) / 1e9;
                System.out.printf("== %s seed %d mode %-6s: %d slices to %.0f m, wall %.1f s (%.3f s/m), backtracks %d%s%n   %s%n",
                        new String[]{"NORMAL", "PUNISHER", "NGH"}[lvl], seed, m, k, y, wall, wall / Math.max(1, y), g.backtracks, stop, PlanCache.stats() + " earlyRefused=" + CourseGenerator.EARLY_REFUSED.get());
                for (String s : PlanCache.MISMATCH_LOG) System.out.println("   MISMATCH " + s);
                bad += PlanCache.MISMATCHES.get();
                if (ref == null) { ref = sig; refMode = m; refCnt = cnt; continue; }
                int d = firstDiff(ref, sig);
                if (d < 0) System.out.printf("   IDENTICAL to %s: %d slices%n", refMode, sig.size());
                else { bad++; System.out.printf("   DIFFERS from %s at slice %d:%n     %s%n     %s%n", refMode, d, d < ref.size() ? ref.get(d) : "-", d < sig.size() ? sig.get(d) : "-");
                    if (d < refCnt.size() && d < cnt.size()) { TreeSet<String> ks = new TreeSet<>(refCnt.get(d).keySet()); ks.addAll(cnt.get(d).keySet());
                        for (String key : ks) if (!Objects.equals(refCnt.get(d).get(key), cnt.get(d).get(key))) System.out.printf("     tally %s: %s %s, %s %s%n", key, refMode, refCnt.get(d).get(key), m, cnt.get(d).get(key)); } }
            }
        }
        PlanCache.MODE = PlanCache.Mode.OFF; CourseGenerator.EARLY_REJECT = false;
        System.out.println(bad == 0 ? "== PLAN CACHE EQUIVALENCE: PASS" : "== PLAN CACHE EQUIVALENCE: FAIL (" + bad + ")");
        org.junit.Assert.assertEquals(0, bad);
    }

    /** The trace's tallies minus the wall-clock ones (avalanche_proof_ms: milliseconds, different on every run whatever the cache does). */
    static TreeMap<String, Integer> untimed(Map<String, Integer> m) { TreeMap<String, Integer> o = new TreeMap<>(m); o.keySet().removeIf(k -> k.endsWith("_ms")); return o; }

    static int firstDiff(List<String> a, List<String> b) {
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) if (i >= a.size() || i >= b.size() || !a.get(i).equals(b.get(i))) return i;
        return -1;
    }

    /** Everything observable about one final slice and how it was generated (timings excluded). */
    static String signature(int k, CourseGenerator.Framed f, GameGrowth g) { return signature(k, f, g, false); }
    /** With {@code loose}: the slice and every module attempt's outcome, kind and placement, without refusal reasons and tallies. */
    static String signature(int k, CourseGenerator.Framed f, GameGrowth g, boolean loose) {
        if (loose) {
            StringBuilder mods = new StringBuilder();
            for (GenTrace.Module m : g.trace.modules) mods.append(m.attempt).append(m.kind).append(m.role).append(m.picked).append(Float.floatToRawIntBits(m.d)).append(Float.floatToRawIntBits(m.y)).append(m.ok).append(m.e0).append(m.e1).append(m.h0).append(m.h1).append(';');
            return "slice " + k + " bytes " + Arrays.hashCode(SliceCodec.encode(f)) + " len " + SliceCodec.encode(f).length + " bt " + g.backtracks + " modules " + g.trace.modules.size() + " " + mods.toString().hashCode()
                    + " final " + g.trace.finalAttempt + " attempts " + g.trace.attemptNanos.size();
        }
        StringBuilder sb = new StringBuilder("slice ").append(k).append(" bytes ").append(Arrays.hashCode(SliceCodec.encode(f))).append(" len ").append(SliceCodec.encode(f).length);
        sb.append(" bt ").append(g.backtracks).append(" counts ").append(untimed(g.trace.counts).hashCode()).append(" ").append(g.trace.counts.size());
        sb.append(" phaseKeys ").append(g.trace.phaseNanos.keySet().hashCode());
        StringBuilder mods = new StringBuilder();
        for (GenTrace.Module m : g.trace.modules) mods.append(m.attempt).append(m.kind).append(m.role).append(m.picked).append(Float.floatToRawIntBits(m.d)).append(Float.floatToRawIntBits(m.y)).append(m.ok).append(m.e0).append(m.e1).append(m.h0).append(m.h1).append(Arrays.toString(m.fails)).append(';');
        sb.append(" modules ").append(g.trace.modules.size()).append(" ").append(mods.toString().hashCode());
        sb.append(" failures ").append(g.trace.sliceFailures.hashCode()).append(" final ").append(g.trace.finalAttempt);
        sb.append(" attempts ").append(g.trace.attemptNanos.size());
        return sb.toString();
    }
}
