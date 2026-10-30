package com.hotatticgames.climbup.sim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The endless climb. Every slice ever generated is kept as a few hundred compressed bytes ({@link SliceCodec}); only the slices around the player are expanded into the
 * {@link #world} course that the simulation and renderer work on. The world is expressed relative to a floating origin ({@link #originY}), so heights, arcs and every
 * float the physics touches stay small however high the climb goes.
 *
 * Slice k is a pure function of (seed, k, slice k-1) in its own frame ({@link CourseGenerator#chunkFramed}), and a stored slice decodes bit for bit, so unloading and
 * reloading a section always gives back exactly the same platforms.
 */
public final class Tower {
    /** How far (metres) the world reaches below / above the player when it is rebuilt, and how close to its edge the player may get before it is. */
    public static final float REACH = 300f, MARGIN = 120f;

    /** One resident slice and where its elements landed in the world course. */
    public static final class Res {
        public final int index; public final Course data; public final double yBase, sBase;
        public int firstWorld, base, hzBase, lastRouteWorld;      // world index of local 0 / of local 1 / of local hazard 0 / of the last route element
        Res(int index, CourseGenerator.Framed f) { this.index = index; this.data = f.c; this.yBase = f.yBase; this.sBase = f.sBase; }
        public int toWorld(int local) { return local == 0 ? firstWorld : base + local - 1; }
        public int toLocal(int world) { return world == firstWorld ? 0 : world - base + 1; }
    }

    /** A place in the tower that survives unloading: slice index and local element index. */
    public static final class Ref {
        public final int slice, local;
        public Ref(int slice, int local) { this.slice = slice; this.local = local; }
    }

    /** How element and hazard indices moved when the world was rebuilt (-1 = no longer resident), and how far the origin moved. */
    public static final class Remap {
        public int[] elem, haz; public float dy; public int newN, newH;
    }

    public final long seed;
    public final Tuning T;
    public final Course world;
    public double originY, originS;                     // world local = absolute - origin
    public final List<Res> res = new ArrayList<>();      // resident slices, ascending
    public int rebuilds;                                  // number of times the world was rebuilt (the renderer compares it to know when to remap)
    public Remap lastRemap;

    // ---- every slice ever generated, compressed, plus what is needed to find it again
    private final List<byte[]> blobs = new ArrayList<>();
    private double[] yBase = new double[64], sBase = new double[64], botAbs = new double[64], topAbs = new double[64];
    private int[] routeLen = new int[64];
    private double globalMinPlatAbs = Double.POSITIVE_INFINITY;
    private CourseGenerator.Framed top;                  // the newest final slice, expanded (the next one is generated from it)
    /**
     * Generation recovery R2 (2026-10-28): the slice after the last final one, built but not final. A slice is final once the slice after it has been
     * built from it; until then it is provisional: never counted, stored, admitted or saved, and if the slice after it cannot be built (all its attempts
     * refused) it is built again from its own next attempt (a bounded backtrack, at most {@link #BACKTRACKS} times). The rule is part of what defines
     * the course, so every device builds the same one; when nothing has to be undone (every course that built before) it is exactly the old course.
     */
    private CourseGenerator.Framed prov; private int provAttempt = -1;
    public static final int BACKTRACKS = 8;
    /** R1: set when the tower cannot grow any further (every recovery refused); the climb keeps everything already final. Never thrown at the game. */
    private volatile String failure;
    /** Diagnostics: backtracks made so far (R2). */
    public int backtracks;
    /**
     * R3 (2026-10-28): when false (the game), a slice that has to join the window before the background thread has made it is not made on the
     * calling thread; {@link #waiting()} says so and the game holds the simulation (no step, no clock) until it is there - the slice still joins at
     * the same step on every device. True (tests and tools): made on the spot, as before.
     */
    public boolean blockingAdmit = true;
    private boolean waiting;
    private final HashMap<Integer, CourseGenerator.Framed> cache = new HashMap<>();
    private long[] elemKey = new long[0], hazKey = new long[0];     // identity of each world element / hazard: slice * 65536 + local
    private Future<Step> pending;
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "tower-gen"); t.setDaemon(true); t.setPriority(Thread.MIN_PRIORITY); return t; });
    private Ref cpRef; private int lastCp = -2;

    // ---- the first slice of the next climb, made ahead of time (Build 71 owner decision: no freeze when a climb begins)
    /** One slice 0 being made in the background for a known seed and tuning (one at a time: a newer request replaces it). */
    private static final class Prepared { final long seed; final Tuning t; final Future<Step> slice; Prepared(long s, Tuning t, Future<Step> f) { seed = s; this.t = t; slice = f; } }
    private static volatile Prepared prepared;
    private static final ExecutorService PREP = Executors.newSingleThreadExecutor(r -> { Thread th = new Thread(r, "tower-prep"); th.setDaemon(true); th.setPriority(Thread.MIN_PRIORITY); return th; });

    /**
     * Starts making slice 0 of a climb on {@code seed} in the background, so that {@code new Tower(seed, t)} does not have to make it on the frame that
     * begins the climb (about 1.2 s on desktop). Generation is a pure function of seed, slice and tuning, so the slice is exactly the one the constructor
     * would make itself. Repeating the request for the same seed and tuning does nothing.
     */
    public static void prepare(long seed, Tuning t) {
        Prepared p = prepared;
        if (p != null && p.seed == seed && p.t == t) return;
        prepared = new Prepared(seed, t, PREP.submit((Callable<Step>) () -> grow(seed, t, -1, null, null, -1)));
    }

    /** True once the slices {@link #prepare} started for this seed and tuning are made (built or refused): {@code new Tower(seed, t)} then does not generate. */
    public static boolean ready(long seed, Tuning t) {
        Prepared p = prepared;
        return p != null && p.seed == seed && p.t == t && p.slice.isDone();
    }

    /** Slice 0 for this climb (final: slice 1 was built from it) and the provisional slice 1: the prepared ones when they are for this seed and tuning, else made now. */
    private static Step firstSlice(long seed, Tuning t) {
        Prepared p = prepared;
        if (p != null && p.seed == seed && p.t == t) {
            prepared = null;
            try { return p.slice.get(); } catch (Exception e) { /* a fault of the thread itself: made here instead */ }
        }
        return grow(seed, t, -1, null, null, -1);
    }

    /** What one growth step produced: the slice that became final, the new provisional one after it, or why the tower cannot grow (R1). */
    static final class Step {
        CourseGenerator.Framed fin, prov; int finAttempt, provAttempt, backtracks; String failure;
    }

    /**
     * One growth step (R2), a pure function of its inputs: with the last final slice {@code f} (index {@code fi}, null before slice 0) and the provisional
     * slice after it ({@code p}, made here when null), builds the slice after {@code p}; if that is refused on every attempt, {@code p} is built again from
     * its next attempt and the slice after it tried again, at most {@link #BACKTRACKS} times. Returns {@code p} as final and the new provisional slice,
     * or the failure. Only slices the player has not been shown are ever built again.
     */
    static Step grow(long seed, Tuning t, int fi, CourseGenerator.Framed f, CourseGenerator.Framed p, int pAttempt) {
        Step st = new Step();
        int[] acc = new int[1];
        try {
            if (p == null) { p = CourseGenerator.chunkFramedFrom(seed, fi + 1, f, t, 0, acc); pAttempt = acc[0]; }
            for (int b = 0; ; b++) {
                try {
                    CourseGenerator.Framed q = CourseGenerator.chunkFramedFrom(seed, fi + 2, p, t, 0, acc);
                    st.fin = p; st.finAttempt = pAttempt; st.prov = q; st.provAttempt = acc[0]; st.backtracks = b;
                    return st;
                } catch (IllegalStateException e) {
                    if (b >= BACKTRACKS || pAttempt + 1 >= CourseGenerator.ATTEMPTS) { st.failure = "slice " + (fi + 2) + " cannot be built on slice " + (fi + 1) + " (attempt " + pAttempt + ", " + b + " backtracks): " + e.getMessage(); return st; }
                    p = CourseGenerator.chunkFramedFrom(seed, fi + 1, f, t, pAttempt + 1, acc); pAttempt = acc[0];          // backtrack: the unseen slice, its next attempt
                }
            }
        } catch (IllegalStateException e) {
            st.failure = "slice " + (fi + 1) + " cannot be built" + (f == null ? " (first slice)" : "") + ": " + e.getMessage(); return st;
        } catch (RuntimeException | Error e) {          // R1: whatever goes wrong in the generator is a failure to grow, never a crash or a lost climb
            st.failure = "generator fault at slice " + (fi + 1) + ": " + e; return st;
        }
    }

    /** R6: the first slice of a climb could not be built (the seed is never changed to hide it). */
    public static final class FirstSliceFailed extends IllegalStateException { public FirstSliceFailed(String m) { super(m); } }
    private final java.util.HashSet<Long> doneFeat = new java.util.HashSet<>();      // keys taken, gates opened, crabs knocked off: remembered across unloading a slice
    public int openedUpTo;                                // castles up to this number are open, wherever the player later falls to

    /**
     * Build 74 (owner: the same inputs must give the same run on every device): the resident window only ever uses slices whose bottom is at or below this
     * absolute height (the game sets it from the highest point climbed, before every step). Generation still runs ahead on a background thread, but how
     * far ahead it happens to be (device speed, thread timing) no longer changes which slices the window holds or when it is rebuilt around a new floating
     * origin: a slice joins at the same step on every device, and if it is needed before the thread has made it, it is made on the spot. Unset (infinite),
     * every generated slice joins at once, as before (tests and tools).
     */
    public double admitAbs = Double.POSITIVE_INFINITY;
    private int admitted;                                 // slices that have joined: the window logic sees only these

    /** A fresh climb from the very bottom. */
    public Tower(long seed, Tuning t) {
        this.seed = seed; this.T = t;
        world = new Course(seed, t.circumference()); world.gemCheckpoints = t.castleSpacing > 0f;
        Step st = firstSlice(seed, t);
        if (st.failure != null) throw new FirstSliceFailed(st.failure);
        apply(st); admitted = 1;
        rebuild(0, 0, 0.0, 0.0);
    }

    /** Resumes from the stored history (all slices, in order). The window opens around slice {@code at}. */
    public Tower(long seed, Tuning t, List<byte[]> history, int at) {
        this.seed = seed; this.T = t;
        world = new Course(seed, t.circumference()); world.gemCheckpoints = t.castleSpacing > 0f;
        if (history.isEmpty()) throw new IllegalArgumentException("empty history");
        for (byte[] b : history) { CourseGenerator.Framed f = SliceCodec.decode(b); record(f, b); top = f; }
        admitted = blobs.size();
        int k = Math.max(0, Math.min(at, blobs.size() - 1));
        double mid = 0.5 * (botAbs[k] + topAbs[k]);
        int[] r = rangeFor(mid);
        rebuild(r[0], r[1], originFor(mid), sBase[r[0]]);
    }

    // ------------------------------------------------------------------ history

    private void record(CourseGenerator.Framed f, byte[] blob) {
        int k = blobs.size();
        if (k == yBase.length) { int m = k * 2; yBase = java.util.Arrays.copyOf(yBase, m); sBase = java.util.Arrays.copyOf(sBase, m); botAbs = java.util.Arrays.copyOf(botAbs, m); topAbs = java.util.Arrays.copyOf(topAbs, m); routeLen = java.util.Arrays.copyOf(routeLen, m); }
        blobs.add(blob);
        Course c = f.c; int rs = c.routeSize();
        yBase[k] = f.yBase; sBase[k] = f.sBase; routeLen[k] = rs;
        botAbs[k] = f.yBase + c.get(0).y; topAbs[k] = f.yBase + c.get(rs - 1).y;
        for (int i = 0; i < c.size(); i++) { Element e = c.get(i); if (e.isPlatform()) globalMinPlatAbs = Math.min(globalMinPlatAbs, f.yBase + e.y - Math.abs(e.amp) - 1.0); }
    }

    private void append(CourseGenerator.Framed f) {
        record(f, SliceCodec.encode(f)); top = f;
    }

    public int sliceCount() { return blobs.size(); }
    public byte[] blob(int k) { return blobs.get(k); }
    public double topAbsY() { return topAbs[blobs.size() - 1]; }
    public double botAbsY(int k) { return botAbs[k]; }
    public double topAbsY(int k) { return topAbs[k]; }
    public double absY(float local) { return originY + local; }
    public float local(double abs) { return (float) (abs - originY); }
    /** Local height of the lowest platform of the whole tower (a fall past it, with nothing to land on, sends the player back to the checkpoint). */
    public float floorLocal() { return globalMinPlatAbs == Double.POSITIVE_INFINITY ? Float.NEGATIVE_INFINITY : (float) (globalMinPlatAbs - originY); }
    public int bytesStored() { long n = 0; for (byte[] b : blobs) n += b.length; return (int) Math.min(Integer.MAX_VALUE, n); }

    private CourseGenerator.Framed slice(int k) {
        if (k == blobs.size() - 1 && top != null) return top;
        CourseGenerator.Framed f = cache.get(k);
        if (f == null) { f = SliceCodec.decode(blobs.get(k)); cache.put(k, f); }
        return f;
    }

    // ------------------------------------------------------------------ generation (background) and growth of the resident world

    public void requestNext() {
        if (pending != null || failure != null) return;
        final CourseGenerator.Framed f = top, p = prov; final int fi = blobs.size() - 1, pa = provAttempt;
        pending = POOL.submit((Callable<Step>) () -> grow(seed, T, fi, f, p, pa));
    }

    private int[] accepted = new int[0];
    /** The attempt slice {@code k} was accepted on (R2 diagnostics and tests); -1 when it came from stored history. */
    public int acceptedAttempt(int k) { return k >= 0 && k < accepted.length && k < blobs.size() ? accepted[k] : -1; }
    /** Test-only: told of every slice as it joins the resident world (its index and stored bytes). Null in the game. */
    public java.util.function.BiConsumer<Integer, byte[]> onStitch;

    /** R1: why the tower cannot grow any further, or null while it can. */
    public String failure() { return failure; }
    /** R3: true when the window needs a slice that is not final yet (or never will be: {@link #failure()}); the game then holds the simulation. */
    public boolean waiting() { return waiting; }

    private void apply(Step st) {
        if (st.failure != null) { failure = st.failure; return; }
        backtracks += st.backtracks;
        int k = blobs.size(); if (accepted.length <= k) { int was = accepted.length; accepted = java.util.Arrays.copyOf(accepted, Math.max(k + 1, accepted.length * 2 + 8)); java.util.Arrays.fill(accepted, was, accepted.length, -1); }
        accepted[k] = st.finAttempt;
        append(st.fin); prov = st.prov; provAttempt = st.provAttempt;
    }

    /** Takes a finished slice, if any, and lets slices join the window up to {@link #admitAbs}. Call from the thread that owns the world. Returns true if the resident world grew. */
    public boolean poll() {
        if (pending != null && pending.isDone()) take();
        return admit();
    }

    private void take() {
        Step st;
        try { st = pending.get(); } catch (Exception e) { st = new Step(); st.failure = "tower generation thread failed at slice " + blobs.size() + ": " + e; }
        finally { pending = null; }
        apply(st);
    }

    /** Lets every slice whose bottom is at or below {@link #admitAbs} join, in order, making it now if the background thread has not yet; a slice joins the resident world at once when the top slice is resident. */
    private boolean admit() {
        boolean grew = false; waiting = false;
        while (admitted >= blobs.size() ? topAbs[blobs.size() - 1] <= admitAbs : botAbs[admitted] <= admitAbs) {
            if (admitted >= blobs.size()) {
                if (admitAbs == Double.POSITIVE_INFINITY) break;
                if (failure != null || !blockingAdmit) { requestNext(); if (pending != null && pending.isDone()) { take(); continue; } waiting = true; break; }          // (R1/R3: hold, never block or throw)
                requestNext(); try { pending.get(); } catch (Exception e) { /* take() records it */ } take(); if (failure != null) { waiting = true; break; } continue;
            }
            boolean topResident = !res.isEmpty() && res.get(res.size() - 1).index == admitted - 1;
            int k = admitted++;
            if (topResident) { stitch(k, slice(k)); grew = true; }
        }
        return grew;
    }

    /** Blocking variant for tests and tools. */
    /** Whether a slice is being generated on the background thread right now (diagnostics). */
    public boolean generating() { return pending != null && !pending.isDone(); }

    /** Build 77 (owner, hidden world picker): the route platform right after castle {@code n}'s deck - where you stand once through its door, the normal way into the
     *  section above it (its key lies ahead, in that section). -1 if castle n is not in the window. Found by the castle's number, not by a height, so the floating origin
     *  and the order of the elements (side platforms come after the route) do not matter. */
    public int afterCastle(int n) {
        for (Element h : world.hazards) if (h.type == Element.Type.GATE && h.skin == n && h.anchor >= 0 && h.anchor + 1 < world.routeSize()) return h.anchor + 1;
        return -1;
    }

    /** Blocking growth by one final slice (tests and tools). Throws if the tower cannot grow (R1: the game itself never calls this during play). */
    public void extend() {
        if (failure != null) throw new IllegalStateException("tower cannot grow: " + failure);
        requestNext();
        try { pending.get(); } catch (Exception e) { /* take() records it */ }
        poll();
        if (failure != null) throw new IllegalStateException("tower cannot grow: " + failure);
    }

    /** Keeps at least {@code margin} metres of tower above absolute height {@code y}. */
    public void ensureAbove(double y, float margin) {
        poll();
        if (topAbsY() < y + margin) requestNext();
    }

    // ------------------------------------------------------------------ the resident window

    public int sliceAt(double abs) {                       // the slice whose route spans this height (clamped)
        int lo = 0, hi = admitted - 1;
        while (lo < hi) { int mid = (lo + hi + 1) >>> 1; if (botAbs[mid] <= abs) lo = mid; else hi = mid - 1; }
        return lo;
    }

    private int[] rangeFor(double centreAbs) { return new int[]{sliceAt(centreAbs - REACH), sliceAt(centreAbs + REACH)}; }

    private double originFor(double abs) { double step = T.castleSpacing > 0f ? T.castleSpacing : 500.0; return step * Math.rint(abs / step); }

    /** Brings the resident window around the player (absolute height {@code abs}) if it is getting close to its edge. Returns true if the world was rebuilt (the sim was remapped). */
    public boolean ensureWindow(Sim sim) {
        double abs = originY + sim.y;
        int first = res.get(0).index, last = res.get(res.size() - 1).index;
        boolean lowOk = first == 0 || abs - MARGIN >= botAbs[first], highOk = last == admitted - 1 || abs + MARGIN <= topAbs[last];
        boolean tooBig = abs - botAbs[first] > 2 * REACH + 60f || topAbs[last] - abs > 2 * REACH + 60f;          // trim what the player has left far behind (keeps memory bounded however high the climb goes)
        if (lowOk && highOk && !tooBig && Math.abs(sim.y) < 1500f) return false;
        int[] r = rangeFor(abs);
        r[1] = Math.max(r[1], sliceAt(abs));
        rebuildFor(sim, r[0], r[1], originFor(abs), sBase[r[0]]);
        return true;
    }

    /** Keeps the tower and the simulation in step: new slices, window moves, and the checkpoint respawn (which may need slices that are not resident). Call once per frame before stepping. */
    public void maintain(Sim sim) {
        if (poll()) sim.ensureCapacity();
        if (sim.checkpoint != lastCp && sim.checkpoint >= 0 && sim.checkpoint < world.size()) { cpRef = refOf(sim.checkpoint); lastCp = sim.checkpoint; }
        if (sim.respawnPending) {
            if (cpRef == null) cpRef = new Ref(0, 0);
            int k = cpRef.slice; double mid = botAbs[k] + 0.5 * (topAbs[k] - botAbs[k]);
            int[] r = rangeFor(mid); r[0] = Math.min(r[0], k); r[1] = Math.max(r[1], k);
            rebuildFor(sim, r[0], r[1], originFor(mid), sBase[r[0]]);
            int idx = worldIndex(cpRef); sim.checkpoint = idx; lastCp = idx;
            sim.completeRespawn(idx);
            return;
        }
        ensureWindow(sim);
        sim.floorOverride = floorLocal();
    }

    /** Rebuilds the world for a managed simulation: remembers what has been done to the features that are about to be unloaded, remaps the sim, and re-applies it to whatever comes back. */
    private void rebuildFor(Sim sim, int lo, int hi, double newOriginY, double newOriginS) {
        sim.deferRespawn = true;
        for (int i = 0; i < hazKey.length && i < sim.featDone.length; i++) if (sim.featDone[i]) doneFeat.add(hazKey[i]);
        Remap m = rebuild(lo, hi, newOriginY, newOriginS);
        sim.rebase(m);
        applyDone(sim);
        sim.floorOverride = floorLocal(); sim.setRange(0, world.size() - 1);
    }

    /** Re-applies remembered feature state to the resident world (gates of opened castles stay open, taken keys stay taken). */
    public void applyDone(Sim sim) {
        for (int i = 0; i < world.hazards.size() && i < sim.featDone.length; i++) {
            Element h = world.hazards.get(i);
            if (doneFeat.contains(hazKey[i]) || (h.type == Element.Type.GATE && h.skin > 0 && h.skin <= openedUpTo)) sim.featDone[i] = true;
        }
    }

    /** Rebuilds the window around an absolute height (used by tests and by anything that must place the player somewhere else). */
    public void windowAround(Sim sim, double centreAbs) {
        int[] r = rangeFor(centreAbs);
        rebuildFor(sim, r[0], r[1], originFor(centreAbs), sBase[r[0]]);
    }

    /** Rebuilds with an explicit slice range and origin (tests: a window that covers the whole tower is the reference for what falls and jumps should do). */
    public void rebuildExplicit(Sim sim, int lo, int hi, double originYNew) { rebuildFor(sim, lo, hi, originYNew, sBase[lo]); }

    /** Stable identity of a resident world element / hazard (slice * 65536 + local); it survives unloading and rebuilding the window. */
    public long elemKeyAt(int worldIdx) { return worldIdx >= 0 && worldIdx < elemKey.length ? elemKey[worldIdx] : -1L; }
    public long hazKeyAt(int hazIdx) { return hazIdx >= 0 && hazIdx < hazKey.length ? hazKey[hazIdx] : -1L; }
    /** World index of the element with this identity, or -1 if its slice is not resident. */
    public int indexOfHazKey(long key) { for (int i = 0; i < world.hazards.size() && i < hazKey.length; i++) if (hazKey[i] == key) return i; return -1; }
    public int indexOfElemKey(long key) { for (int i = 0; i < world.size() && i < elemKey.length; i++) if (elemKey[i] == key) return i; return -1; }
    public int sliceCountResident() { return res.size(); }

    /** Every feature (key taken, gate opened, crab knocked off) that has been done in this climb so far, resident or not. */
    public java.util.List<Long> doneKeys(Sim sim) {
        java.util.HashSet<Long> out = new java.util.HashSet<>(doneFeat);
        for (int i = 0; i < hazKey.length && i < sim.featDone.length; i++) if (sim.featDone[i]) out.add(hazKey[i]);
        java.util.ArrayList<Long> l = new java.util.ArrayList<>(out); java.util.Collections.sort(l); return l;
    }
    public void addDone(long[] keys) { for (long k : keys) doneFeat.add(k); }

    /** Moves the resident window (and the floating origin) around an absolute height without a simulation to remap: for a tower that is about to get its sim, as when a saved climb is restored. */
    public void windowAroundAbs(double centreAbs) {
        int[] r = rangeFor(centreAbs);
        rebuild(r[0], r[1], originFor(centreAbs), sBase[r[0]]);
    }
    /**
     * As {@link #windowAroundAbs(double)}, but with the floating origin a saved climb was captured with (rules 14 fix): arcs are held as floats relative
     * to the origin, and a window built around another slice sits a few micrometres out of phase with the one the climb was saved in (seed 7: 1.1e-5 m),
     * which a creature chasing the climber grew to 2e-4 m (PlayDeterminismTest). With the saved origin every resident element is placed exactly as it was.
     */
    public void windowAroundAbs(double centreAbs, double originYAt, double originSAt) {
        int[] r = rangeFor(centreAbs);
        rebuild(r[0], r[1], originYAt, originSAt);
    }
    public int sliceAtAbs(double abs) { return sliceAt(abs); }

    public Ref checkpointRef() { return cpRef; }
    public void setCheckpointRef(Ref r) { cpRef = r; lastCp = -2; }

    /** The slice and local index of a resident world element (shared start platforms are reported as the previous slice's last route element). */
    public Ref refOf(int worldIdx) {
        Res r = resOf(worldIdx);
        int local = r.toLocal(worldIdx);
        if (local == 0 && r.index > 0) return new Ref(r.index - 1, routeLen[r.index - 1] - 1);
        return new Ref(r.index, local);
    }

    /** World index of a place in the tower, or -1 if its slice is not resident. */
    public int worldIndex(Ref ref) {
        for (Res r : res) if (r.index == ref.slice) return r.toWorld(ref.local);
        if (ref.local == routeLen[ref.slice] - 1) for (Res r : res) if (r.index == ref.slice + 1) return r.firstWorld;
        return -1;
    }

    public Res resOf(int worldIdx) {
        for (int i = res.size() - 1; i >= 0; i--) { Res r = res.get(i); if (worldIdx >= r.base || worldIdx == r.firstWorld) return r; }
        return res.get(0);
    }

    public int lastRouteIndex() { return res.get(res.size() - 1).lastRouteWorld; }
    public boolean topResident() { return res.get(res.size() - 1).index == admitted - 1; }

    // ------------------------------------------------------------------ building the world course

    private Remap rebuild(int lo, int hi, double newOriginY, double newOriginS) {
        rebuilds++;
        long[] oldE = elemKey, oldH = hazKey; int oldN = world.size(), oldHn = world.hazards.size(); double oldOrigin = originY;
        world.elements.clear(); world.hazards.clear(); world.keyRooms.clear(); world.forks.clear(); world.routeCount = -1;
        res.clear(); originY = newOriginY; originS = newOriginS;
        elemKey = new long[0]; hazKey = new long[0];
        for (int k = lo; k <= hi; k++) stitch(k, slice(k));
        // drop cached expansions far from the window
        cache.keySet().removeIf(k -> k < lo - 2 || k > hi + 2);
        HashMap<Long, Integer> eIdx = new HashMap<>(), hIdx = new HashMap<>();
        for (int i = 0; i < world.size(); i++) eIdx.put(elemKey[i], i);
        for (int i = 0; i < world.hazards.size(); i++) hIdx.put(hazKey[i], i);
        Remap m = new Remap(); m.dy = (float) (newOriginY - oldOrigin); m.newN = world.size(); m.newH = world.hazards.size();
        m.elem = new int[oldN]; for (int i = 0; i < oldN; i++) { Integer j = eIdx.get(oldE[i]); m.elem[i] = j == null ? -1 : j; }
        m.haz = new int[oldHn]; for (int i = 0; i < oldHn; i++) { Integer j = hIdx.get(oldH[i]); m.haz[i] = j == null ? -1 : j; }
        lastRemap = m;
        return m;
    }

    private void stitch(int k, CourseGenerator.Framed f) {
        if (onStitch != null) onStitch.accept(k, blobs.get(k));
        Course d = f.c; Res r = new Res(k, f);
        boolean first = res.isEmpty();
        double dy = f.yBase - originY, ds = f.sBase - originS;
        if (first) { r.firstWorld = world.size(); addElem(d.get(0), -1, dy, ds, r, k, 0); r.base = world.size(); }
        else { r.firstWorld = res.get(res.size() - 1).lastRouteWorld; r.base = world.size(); }
        for (int i = 1; i < d.size(); i++) addElem(d.get(i), d.get(i).anchor < 0 ? -1 : Integer.MIN_VALUE, dy, ds, r, k, i);
        r.hzBase = world.hazards.size();
        int hn = world.hazards.size();
        hazKey = java.util.Arrays.copyOf(hazKey, hn + d.hazards.size());
        for (int i = 0; i < d.hazards.size(); i++) {
            Element h = d.hazards.get(i), e = copy(h, r.toWorld(h.anchor), dy, ds);
            world.hazards.add(e); hazKey[hn + i] = (long) k * 65536L + i;
        }
        for (int[] fk : d.forks) world.forks.add(new int[]{r.toWorld(fk[0]), r.toWorld(fk[1]), fk[2], r.toWorld(fk[3])});          // (DC6: the live tower knows its forks, as each slice does)
        for (int[] kr : d.keyRooms) world.keyRooms.add(new int[]{r.toWorld(kr[0]), kr[1] < 0 ? -1 : r.toWorld(kr[1]), kr[2], kr[3], r.hzBase + kr[4], kr[5] < 0 ? -1 : r.hzBase + kr[5]});
        r.lastRouteWorld = r.toWorld(d.routeSize() - 1);
        res.add(r);
    }

    private void addElem(Element e, int anchor, double dy, double ds, Res r, int k, int local) {
        Element n = copy(e, anchor == Integer.MIN_VALUE ? r.toWorld(e.anchor) : anchor, dy, ds);
        int idx = world.add(n);
        if (elemKey.length <= idx) elemKey = java.util.Arrays.copyOf(elemKey, Math.max(idx + 1, elemKey.length * 3 / 2 + 16));
        elemKey[idx] = local == 0 && k > 0 ? (long) (k - 1) * 65536L + (routeLen[k - 1] - 1) : (long) k * 65536L + local;        // a shared start platform is the previous slice's last route element
    }

    private static Element copy(Element e, int anchor, double dy, double ds) {
        Element n = new Element(e.type, (float) (e.s + ds), (float) (e.y + dy), e.w);
        n.zone = e.zone; n.amp = e.amp; n.period = e.period; n.phase = e.phase; n.len = e.len; n.checkpoint = e.checkpoint; n.dir = e.dir; n.color = e.color; n.skin = e.skin; n.anchor = anchor;
        return n;
    }
}
