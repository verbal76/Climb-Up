package com.hotatticgames.climbup.sim;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Speed only, same results (owner overnight directive 2026-10-30, priority A; NGH cost audit: about a fifth of the generator's plans repeat a plan
 * already made from identical inputs). Remembers {@link Autopilot#planTo} results inside one slice attempt and returns a copy when the very same
 * plan is asked for again.
 *
 * <p>The key is everything a plan can read, by value: the call's arguments, the planner's switches, and the starting simulation's whole object
 * graph - every field of the climber, every element and hazard state array, the course (every element and hazard with every field, the route and
 * decoy indices, key rooms, forks) and the tuning - encoded field by field by reflection, so a field added later is in the key without anyone
 * remembering it. Entries are found by hash and matched by comparing the whole key, never by hash alone. The only fields left out are the
 * simulation's motion-cache handle ({@link #EXCLUDED}): every trial installs its own, and the cache is value-transparent (MotionCache). The planner
 * draws no random numbers; its only side effects are the observe-only report tallies, which are recorded with the entry and replayed on a hit
 * ({@link GenTrace.Rec}), and the returned Result, which callers modify, so each hit gets its own copy.
 *
 * <p>Modes ({@code -Dclimb.planCache}): {@code off} (default: nothing is opened, nothing changes), {@code shadow} (every plan is still computed;
 * a would-be hit is compared field by field, by the chosen move's behaviour, by the tallies raised and by the starting state left untouched, and
 * any difference is logged in {@link #MISMATCH_LOG}; generation is never affected), {@code on} (hits return the stored result).
 */
final class PlanCache {
    enum Mode { OFF, SHADOW, ON }
    static volatile Mode MODE = parse(System.getProperty("climb.planCache", "off"));
    static Mode parse(String s) { return "on".equalsIgnoreCase(s) ? Mode.ON : "shadow".equalsIgnoreCase(s) ? Mode.SHADOW : Mode.OFF; }

    /** Fields never in the key: the motion-cache handle a trial replaces before it steps (see the class comment). */
    static final java.util.Set<String> EXCLUDED = new java.util.HashSet<>(Arrays.asList("Sim.motion", "Sim.motionGen", "Sim.mstep"));
    /** Memory bound per scope (key ints held); reaching it clears the scope (a clear only turns later hits into misses). */
    static final long CAP_INTS = 8_000_000L;

    // diagnostics (observe-only)
    static final AtomicLong LOOKUPS = new AtomicLong(), HITS = new AtomicLong(), STORES = new AtomicLong(), MISMATCHES = new AtomicLong(), UNCACHEABLE = new AtomicLong(),
            CLEARS = new AtomicLong(), KEY_NANOS = new AtomicLong(), HIT_PLAN_NANOS = new AtomicLong(), SHADOW_CHECK_NANOS = new AtomicLong(), SCOPES = new AtomicLong();
    static final List<String> MISMATCH_LOG = java.util.Collections.synchronizedList(new ArrayList<>());
    static void resetStats() { for (AtomicLong a : new AtomicLong[]{LOOKUPS, HITS, STORES, MISMATCHES, UNCACHEABLE, CLEARS, KEY_NANOS, HIT_PLAN_NANOS, SHADOW_CHECK_NANOS, SCOPES}) a.set(0); MISMATCH_LOG.clear(); }
    static String stats() {
        return String.format("mode=%s scopes=%d lookups=%d hits=%d stores=%d uncacheable=%d clears=%d mismatches=%d key=%.1fs hitPlans=%.1fs shadowChecks=%.1fs",
                MODE, SCOPES.get(), LOOKUPS.get(), HITS.get(), STORES.get(), UNCACHEABLE.get(), CLEARS.get(), MISMATCHES.get(), KEY_NANOS.get() / 1e9, HIT_PLAN_NANOS.get() / 1e9, SHADOW_CHECK_NANOS.get() / 1e9);
    }

    private static final ThreadLocal<PlanCache> SCOPE = new ThreadLocal<>();
    /** The open scope on this thread, or null (always null with the cache off). */
    static PlanCache current() { return SCOPE.get(); }
    /** Opens a scope (one slice attempt) on this thread; null when the cache is off. Close it with {@link #close} in a finally block. */
    static PlanCache open() {
        if (MODE == Mode.OFF) return null;
        PlanCache p = new PlanCache(SCOPE.get(), MODE); SCOPE.set(p); SCOPES.incrementAndGet();
        return p;
    }
    static void close(PlanCache p) { if (p == null) return; if (p.prev == null) SCOPE.remove(); else SCOPE.set(p.prev); }

    private final PlanCache prev; private final Mode mode;
    private final HashMap<Long, ArrayList<Entry>> map = new HashMap<>();
    private long held;
    private PlanCache(PlanCache prev, Mode mode) { this.prev = prev; this.mode = mode; }

    private static final class Entry {
        final int[] key; final Course course; final Autopilot.Result res; final String[] evN; final int[] evV;
        Entry(int[] key, Course course, Autopilot.Result res, String[] evN, int[] evV) { this.key = key; this.course = course; this.res = res; this.evN = evN; this.evV = evV; }
    }

    /** {@link Autopilot#planTo0} through the cache. */
    Autopilot.Result plan(Sim base, int a, int b, boolean pair, boolean measureMargin, boolean lookahead) {
        boolean tracing = GenTrace.GEN.get() != null;
        LOOKUPS.incrementAndGet();
        long k0 = GenTrace.now();
        int[] key; try { key = key(base, a, b, pair, measureMargin, lookahead, tracing); } catch (Uncacheable u) { key = null; UNCACHEABLE.incrementAndGet(); }
        long h = key == null ? 0L : hash(key);
        Entry e = key == null ? null : find(h, key, base.course);
        KEY_NANOS.addAndGet(GenTrace.now() - k0);
        if (e != null && mode == Mode.ON) {
            HITS.incrementAndGet();
            if (tracing) replay(e);
            return copy(e.res);
        }
        int[] before = e != null ? state(base) : null;          // shadow: the starting state must come back untouched
        GenTrace.Rec rec = null; boolean own = false; int r0 = 0;
        if (tracing) { rec = GenTrace.REC.get(); if (rec == null) { rec = new GenTrace.Rec(); GenTrace.REC.set(rec); own = true; } r0 = rec.size(); }
        long t0 = GenTrace.now();
        Autopilot.Result r; String[] evN = new String[0]; int[] evV = new int[0];
        try {
            r = Autopilot.planTo0(base, a, b, pair, measureMargin, lookahead);
            if (rec != null) { int n = rec.size() - r0; evN = rec.names.subList(r0, rec.size()).toArray(new String[0]); evV = Arrays.copyOfRange(rec.vals, r0, r0 + n); }
        } finally { if (own) GenTrace.REC.remove(); }
        long dt = GenTrace.now() - t0;
        if (e != null) {
            HITS.incrementAndGet(); HIT_PLAN_NANOS.addAndGet(dt);
            long c0 = GenTrace.now();
            String why = differs(e, r, evN, evV, base, before, a, pair ? b : -1);
            SHADOW_CHECK_NANOS.addAndGet(GenTrace.now() - c0);
            if (why != null) { MISMATCHES.incrementAndGet(); if (MISMATCH_LOG.size() < 50) MISMATCH_LOG.add(String.format("seed %d link %d->%d pair=%b mm=%b la=%b t=%.4f: %s", base.course.seed, a, b, pair, measureMargin, lookahead, base.time, why)); }
        } else if (key != null) {
            if (held + key.length > CAP_INTS) { map.clear(); held = 0; CLEARS.incrementAndGet(); }
            map.computeIfAbsent(h, x -> new ArrayList<>(1)).add(new Entry(key, base.course, copy(r), evN, evV));
            held += key.length; STORES.incrementAndGet();
        }
        return r;
    }

    private Entry find(long h, int[] key, Course c) {
        ArrayList<Entry> l = map.get(h);
        if (l != null) for (Entry e : l) if (e.course == c && Arrays.equals(e.key, key)) return e;
        return null;
    }

    private static void replay(Entry e) {
        GenTrace tr = GenTrace.GEN.get();
        for (int i = 0; i < e.evN.length; i++) { if (e.evV[i] == GenTrace.Rec.PHASE) tr.phase(e.evN[i], 0L); else GenTrace.tally(e.evN[i], e.evV[i]); }
    }

    // ---- shadow comparison ----

    private static String differs(Entry e, Autopilot.Result r, String[] evN, int[] evV, Sim base, int[] before, int a, int tgt) {
        Autopilot.Result c = e.res;
        for (Field f : fields(Autopilot.Result.class)) {
            if (f.getType() == Autopilot.PolicyFactory.class) continue;
            try { Object x = f.get(c), y = f.get(r); if (!java.util.Objects.equals(x, y)) return "result field " + f.getName() + ": cached " + x + " real " + y; }
            catch (IllegalAccessException ex) { return "unreadable result field " + f.getName(); }
        }
        String w = sameMove("factory", c.factory, r.factory, base, a, tgt); if (w != null) return w;
        w = sameMove("fallback", c.fallback, r.fallback, base, a, tgt); if (w != null) return w;
        if (!Arrays.equals(e.evN, evN) || !Arrays.equals(e.evV, evV)) return "tallies differ: cached " + Arrays.toString(e.evN) + Arrays.toString(e.evV) + " real " + Arrays.toString(evN) + Arrays.toString(evV);
        if (!Arrays.equals(before, state(base))) return "planning changed its starting state";
        return null;
    }

    /** Two chosen moves are the same move: the same policy, field for field, and the same trial played out to the same end state. */
    private static String sameMove(String what, Autopilot.PolicyFactory x, Autopilot.PolicyFactory y, Sim base, int a, int tgt) {
        if (x == null || y == null) return x == y ? null : what + ": one is null";
        try { if (!Arrays.equals(policy(x.create()), policy(y.create()))) return what + ": different policy " + x.create().getClass().getSimpleName() + " / " + y.create().getClass().getSimpleName(); }
        catch (Uncacheable u) { /* not encodable: the behaviour check below decides */ }
        Sim ex = Autopilot.trialEnd(base, a, tgt, x, 20f), ey = Autopilot.trialEnd(base, a, tgt, y, 20f);
        if (ex == null || ey == null) return ex == ey ? null : what + ": one trial arrives, the other does not";
        return Arrays.equals(state(ex), state(ey)) ? null : what + ": trials end in different states";
    }

    private static int[] policy(Object p) { Enc en = new Enc(); en.obj(p); return en.done(); }
    private static int[] state(Sim s) { try { Enc en = new Enc(); en.obj(s); return en.done(); } catch (Uncacheable u) { return new int[]{-1}; } }

    private static Autopilot.Result copy(Autopilot.Result r) {
        Autopilot.Result o = new Autopilot.Result();
        try { for (Field f : fields(Autopilot.Result.class)) f.set(o, f.get(r)); } catch (IllegalAccessException ex) { throw new IllegalStateException(ex); }
        return o;
    }

    // ---- the key ----

    static int[] key(Sim base, int a, int b, boolean pair, boolean measureMargin, boolean lookahead, boolean tracing) {
        Enc en = new Enc();
        en.put(a); en.put(b);
        en.put((pair ? 1 : 0) | (measureMargin ? 2 : 0) | (lookahead ? 4 : 0) | (tracing ? 8 : 0) | (Autopilot.HIT_STRICT ? 16 : 0) | (Autopilot.SLOW_APPROACH.get() ? 32 : 0)
                | (CourseGenerator.C1_OFF ? 64 : 0) | (CourseGenerator.R17_OFF_SPACING ? 128 : 0));
        en.obj(base);
        return en.done();
    }

    static long hash(int[] k) {
        long h = 0x9E3779B97F4A7C15L ^ k.length;
        for (int v : k) { h ^= v; h *= 0xBF58476D1CE4E5B9L; h ^= h >>> 31; }
        return h;
    }

    static final class Uncacheable extends RuntimeException {
        Uncacheable(String what) { super(what, null, false, false); }
    }

    private static final ConcurrentHashMap<Class<?>, Field[]> FIELDS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Integer> IDS = new ConcurrentHashMap<>();
    private static int id(Class<?> c) { return IDS.computeIfAbsent(c, x -> IDS.size() + 1); }

    /** Every instance field of {@code c} and its superclasses (minus {@link #EXCLUDED}), in a fixed order. */
    static Field[] fields(Class<?> c) {
        return FIELDS.computeIfAbsent(c, k -> {
            ArrayList<Field> out = new ArrayList<>();
            for (Class<?> x = k; x != null && x != Object.class; x = x.getSuperclass()) {
                Field[] fs = x.getDeclaredFields();
                Arrays.sort(fs, (p, q) -> p.getName().compareTo(q.getName()));
                for (Field f : fs) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    if (EXCLUDED.contains(x.getSimpleName() + "." + f.getName())) continue;
                    f.setAccessible(true); out.add(f);
                }
            }
            return out.toArray(new Field[0]);
        });
    }

    /** Value encoder: primitives by their exact bits, arrays and lists element by element, objects of this package field by field (shared objects by back-reference). */
    static final class Enc {
        int[] b = new int[8192]; int n;
        private final IdentityHashMap<Object, Integer> seen = new IdentityHashMap<>();
        void put(int v) { if (n == b.length) b = Arrays.copyOf(b, b.length * 2); b[n++] = v; }
        void putL(long v) { put((int) v); put((int) (v >>> 32)); }
        int[] done() { return Arrays.copyOf(b, n); }

        void obj(Object o) {
            if (o == null) { put(0); return; }
            Class<?> c = o.getClass();
            if (c.isArray()) { array(o, c.getComponentType()); return; }
            if (o instanceof Integer) { put(-2); put((Integer) o); return; }
            if (o instanceof Float) { put(-3); put(Float.floatToRawIntBits((Float) o)); return; }
            if (o instanceof Boolean) { put(-4); put((Boolean) o ? 1 : 0); return; }
            if (o instanceof Long) { put(-5); putL((Long) o); return; }
            if (o instanceof Double) { put(-6); putL(Double.doubleToRawLongBits((Double) o)); return; }
            if (o instanceof Enum) { put(-7); put(id(c)); put(((Enum<?>) o).ordinal()); return; }
            if (o instanceof String) { String s = (String) o; put(-8); put(s.length()); for (int i = 0; i < s.length(); i++) put(s.charAt(i)); return; }
            Integer back = seen.get(o);
            if (back != null) { put(-9); put(back); return; }
            if (o instanceof List) { seen.put(o, seen.size()); List<?> l = (List<?>) o; put(-10); put(l.size()); for (Object x : l) obj(x); return; }
            if (!c.getName().startsWith("com.hotatticgames.climbup.sim.") || c.isSynthetic() || c.getName().contains("$$Lambda")) throw new Uncacheable(c.getName());
            seen.put(o, seen.size());
            put(-11); put(id(c));
            try {
                for (Field f : fields(c)) {
                    Class<?> t = f.getType();
                    if (t == int.class) put(f.getInt(o));
                    else if (t == float.class) put(Float.floatToRawIntBits(f.getFloat(o)));
                    else if (t == boolean.class) put(f.getBoolean(o) ? 1 : 0);
                    else if (t == long.class) putL(f.getLong(o));
                    else if (t == double.class) putL(Double.doubleToRawLongBits(f.getDouble(o)));
                    else if (t == byte.class) put(f.getByte(o));
                    else if (t == short.class) put(f.getShort(o));
                    else if (t == char.class) put(f.getChar(o));
                    else obj(f.get(o));
                }
            } catch (IllegalAccessException ex) { throw new Uncacheable(c.getName() + ": " + ex.getMessage()); }
        }

        private void array(Object o, Class<?> ct) {
            if (ct == int.class) { int[] v = (int[]) o; put(-12); put(v.length); for (int x : v) put(x); }
            else if (ct == float.class) { float[] v = (float[]) o; put(-13); put(v.length); for (float x : v) put(Float.floatToRawIntBits(x)); }
            else if (ct == boolean.class) { boolean[] v = (boolean[]) o; put(-14); put(v.length); for (boolean x : v) put(x ? 1 : 0); }
            else if (ct == byte.class) { byte[] v = (byte[]) o; put(-15); put(v.length); for (byte x : v) put(x); }
            else if (ct == long.class) { long[] v = (long[]) o; put(-16); put(v.length); for (long x : v) putL(x); }
            else if (ct == double.class) { double[] v = (double[]) o; put(-17); put(v.length); for (double x : v) putL(Double.doubleToRawLongBits(x)); }
            else if (ct == short.class) { short[] v = (short[]) o; put(-18); put(v.length); for (short x : v) put(x); }
            else if (ct == char.class) { char[] v = (char[]) o; put(-19); put(v.length); for (char x : v) put(x); }
            else { Object[] v = (Object[]) o; put(-20); put(v.length); for (Object x : v) obj(x); }
        }
    }
}
