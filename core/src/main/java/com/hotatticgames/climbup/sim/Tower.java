package com.hotatticgames.climbup.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The endless climb: a world {@link Course} that grows one generated slice at a time. Slice k is a pure function of
 * (seed, k, slice k-1), so the tower can be extended indefinitely in the background and resumed from a saved slice
 * without regenerating anything below it.
 */
public final class Tower {
    /** One slice as generated (local indices) plus where its elements landed in the world course. */
    public static final class Slice {
        public final int index; public final Course data; public final int base;      // world index of local element i>=1 is base + i - 1; local 0 maps to the previous slice's last route element
        public final int firstWorld, lastRouteWorld;
        Slice(int index, Course data, int base, int firstWorld, int lastRouteWorld) { this.index = index; this.data = data; this.base = base; this.firstWorld = firstWorld; this.lastRouteWorld = lastRouteWorld; }
        public int toWorld(int local) { return local == 0 ? firstWorld : base + local - 1; }
        public int toLocal(int world) { return world == firstWorld ? 0 : world - base + 1; }
    }

    public final long seed;
    public final Tuning T;
    public final Course world;
    public final List<Slice> slices = new ArrayList<>();
    private int lastRouteWorld;
    private Future<Course> pending;
    private int pendingIndex = -1;
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "tower-gen"); t.setDaemon(true); t.setPriority(Thread.MIN_PRIORITY); return t; });

    /** A fresh climb from the very bottom. */
    public Tower(long seed, Tuning t) { this(seed, t, 0, null); }

    /** Resumes at slice {@code index}; {@code data} is that slice exactly as it was generated (null = generate from the bottom). */
    public Tower(long seed, Tuning t, int index, Course data) {
        this.seed = seed; this.T = t;
        world = new Course(seed, t.circumference()); world.gemCheckpoints = t.castleSpacing > 0f;
        if (data == null) {
            if (index != 0) throw new IllegalArgumentException("slice data required to resume");
            data = CourseGenerator.chunk(seed, 0, null, t);
        }
        stitch(index, data);
    }

    private void stitch(int index, Course d) {
        int first, base = world.size();
        if (slices.isEmpty()) { first = base; base = base + 1; world.add(copy(d.get(0), d.get(0).anchor)); }
        else first = lastRouteWorld;
        Slice sl = new Slice(index, d, base, first, 0);
        for (int i = 1; i < d.size(); i++) {
            Element e = copy(d.get(i), -1);
            e.anchor = d.get(i).anchor < 0 ? -1 : sl.toWorld(d.get(i).anchor);
            world.add(e);
        }
        int hz0 = world.hazards.size();
        for (Element h : d.hazards) { Element e = copy(h, -1); e.anchor = sl.toWorld(h.anchor); world.hazards.add(e); }
        for (int[] k : d.keyRooms) world.keyRooms.add(new int[]{sl.toWorld(k[0]), sl.toWorld(k[1]), k[2], k[3], hz0 + k[4], hz0 + k[5]});
        int last = sl.toWorld(d.routeSize() - 1);
        Slice done = new Slice(index, d, sl.base, first, last);
        slices.add(done);
        lastRouteWorld = last;
    }

    private static Element copy(Element e, int anchor) {
        Element n = new Element(e.type, e.s, e.y, e.w);
        n.zone = e.zone; n.amp = e.amp; n.period = e.period; n.phase = e.phase; n.len = e.len; n.checkpoint = e.checkpoint; n.dir = e.dir; n.color = e.color; n.skin = e.skin; n.anchor = anchor;
        return n;
    }

    public Slice lastSlice() { return slices.get(slices.size() - 1); }
    public int topIndex() { return lastSlice().index; }
    public float topY() { return world.get(lastRouteWorld).y; }
    public int lastRouteIndex() { return lastRouteWorld; }

    /** Which slice a world element index belongs to (route and decoys alike). */
    public Slice sliceOf(int worldIdx) {
        for (int i = slices.size() - 1; i >= 0; i--) { Slice s = slices.get(i); if (worldIdx >= s.base || (worldIdx == s.firstWorld)) return s; }
        return slices.get(0);
    }

    /** Starts generating the next slice on the background thread if none is running. */
    public void requestNext() {
        if (pending != null) return;
        final Course prev = lastSlice().data; final int idx = topIndex() + 1;
        pendingIndex = idx;
        pending = POOL.submit((Callable<Course>) () -> CourseGenerator.chunk(seed, idx, prev, T));
    }

    /** Appends a finished slice, if any. Call from the thread that owns the world (the game loop). Returns true if the world grew. */
    public boolean poll() {
        if (pending == null || !pending.isDone()) return false;
        try { Course d = pending.get(); stitch(pendingIndex, d); } catch (Exception e) { throw new IllegalStateException("tower generation failed at slice " + pendingIndex, e); }
        finally { pending = null; }
        return true;
    }

    /** Blocking variant for tests and tools. */
    public void extend() {
        requestNext();
        try { pending.get(); } catch (Exception e) { throw new IllegalStateException(e); }
        poll();
    }

    /** Keeps at least {@code margin} metres of tower above {@code y}. */
    public void ensureAbove(float y, float margin) {
        poll();
        if (topY() < y + margin) requestNext();
    }
}
