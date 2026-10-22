package com.hotatticgames.climbup;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Build 71 (owner): the periodic autosave's disk writes run here, on one background thread, instead of on the render thread. The text and bytes to write are
 * made on the render thread first, so a write never sees the game state change under it. Writes run strictly in the order they were handed over, and
 * every write, delete or read made directly on the render thread first waits for the queued ones ({@link #drain}), so an older queued write can never land
 * after newer progress, or bring back a file that was deleted.
 */
final class DiskWriter {
    private DiskWriter() {}
    private static final ExecutorService W = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "save-writer"); t.setDaemon(true); return t; });
    private static volatile Future<?> last;

    /** Queues a write behind every write queued before it. Called from the thread that owns the game state (the render thread). */
    static void later(Runnable write) { last = W.submit(write); }

    /** Waits until every queued write has finished (the queue is first in, first out, so the newest one finishing means all have). */
    static void drain() {
        Future<?> f = last;
        if (f == null) return;
        try { f.get(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } catch (Exception ignored) { /* the write itself reports its failure */ }
    }
}
