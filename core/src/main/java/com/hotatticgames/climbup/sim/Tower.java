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
    private CourseGenerator.Framed top;                  // the newest slice, expanded (the next one is generated from it)
    private final HashMap<Integer, CourseGenerator.Framed> cache = new HashMap<>();
    private long[] elemKey = new long[0], hazKey = new long[0];     // identity of each world element / hazard: slice * 65536 + local
    private Future<CourseGenerator.Framed> pending;
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "tower-gen"); t.setDaemon(true); t.setPriority(Thread.MIN_PRIORITY); return t; });
    private Ref cpRef; private int lastCp = -2;
    private final java.util.HashSet<Long> doneFeat = new java.util.HashSet<>();      // keys taken, gates opened, crabs knocked off: remembered across unloading a slice
    public int openedUpTo;                                // castles up to this number are open, wherever the player later falls to

    /** A fresh climb from the very bottom. */
    public Tower(long seed, Tuning t) {
        this.seed = seed; this.T = t;
        world = new Course(seed, t.circumference()); world.gemCheckpoints = t.castleSpacing > 0f;
        append(CourseGenerator.chunkFramed(seed, 0, null, t));
        rebuild(0, 0, 0.0, 0.0);
    }

    /** Resumes from the stored history (all slices, in order). The window opens around slice {@code at}. */
    public Tower(long seed, Tuning t, List<byte[]> history, int at) {
        this.seed = seed; this.T = t;
        world = new Course(seed, t.circumference()); world.gemCheckpoints = t.castleSpacing > 0f;
        if (history.isEmpty()) throw new IllegalArgumentException("empty history");
        for (byte[] b : history) { CourseGenerator.Framed f = SliceCodec.decode(b); record(f, b); top = f; }
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
        if (pending != null) return;
        final CourseGenerator.Framed prev = top; final int idx = blobs.size();
        pending = POOL.submit((Callable<CourseGenerator.Framed>) () -> CourseGenerator.chunkFramed(seed, idx, prev, T));
    }

    /** Appends a finished slice, if any. Call from the thread that owns the world. Returns true if the resident world grew. */
    public boolean poll() {
        if (pending == null || !pending.isDone()) return false;
        CourseGenerator.Framed f;
        try { f = pending.get(); } catch (Exception e) { throw new IllegalStateException("tower generation failed at slice " + blobs.size(), e); }
        finally { pending = null; }
        boolean wasTopResident = !res.isEmpty() && res.get(res.size() - 1).index == blobs.size() - 1;
        append(f);
        if (wasTopResident) { stitch(blobs.size() - 1, f); return true; }
        return false;
    }

    /** Blocking variant for tests and tools. */
    public void extend() {
        requestNext();
        try { pending.get(); } catch (Exception e) { throw new IllegalStateException(e); }
        poll();
    }

    /** Keeps at least {@code margin} metres of tower above absolute height {@code y}. */
    public void ensureAbove(double y, float margin) {
        poll();
        if (topAbsY() < y + margin) requestNext();
    }

    // ------------------------------------------------------------------ the resident window

    public int sliceAt(double abs) {                       // the slice whose route spans this height (clamped)
        int lo = 0, hi = blobs.size() - 1;
        while (lo < hi) { int mid = (lo + hi + 1) >>> 1; if (botAbs[mid] <= abs) lo = mid; else hi = mid - 1; }
        return lo;
    }

    private int[] rangeFor(double centreAbs) { return new int[]{sliceAt(centreAbs - REACH), sliceAt(centreAbs + REACH)}; }

    private double originFor(double abs) { double step = T.castleSpacing > 0f ? T.castleSpacing : 500.0; return step * Math.rint(abs / step); }

    /** Brings the resident window around the player (absolute height {@code abs}) if it is getting close to its edge. Returns true if the world was rebuilt (the sim was remapped). */
    public boolean ensureWindow(Sim sim) {
        double abs = originY + sim.y;
        int first = res.get(0).index, last = res.get(res.size() - 1).index;
        boolean lowOk = first == 0 || abs - MARGIN >= botAbs[first], highOk = last == blobs.size() - 1 || abs + MARGIN <= topAbs[last];
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
    public boolean topResident() { return res.get(res.size() - 1).index == blobs.size() - 1; }

    // ------------------------------------------------------------------ building the world course

    private Remap rebuild(int lo, int hi, double newOriginY, double newOriginS) {
        rebuilds++;
        long[] oldE = elemKey, oldH = hazKey; int oldN = world.size(), oldHn = world.hazards.size(); double oldOrigin = originY;
        world.elements.clear(); world.hazards.clear(); world.keyRooms.clear(); world.routeCount = -1;
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
