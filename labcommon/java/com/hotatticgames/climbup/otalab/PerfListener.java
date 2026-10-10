package com.hotatticgames.climbup.otalab;

import android.os.Debug;
import android.util.Log;
import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import java.io.File;
import java.io.FileWriter;
import java.util.Arrays;

/**
 * Lab-only measurement wrapper (never part of a shipped host). It forwards every call to the game unchanged and records, per frame, the time between consecutive render() calls (frame pacing,
 * what the player sees) and the time render() itself takes (work). After a warm-up it measures for a fixed number of seconds, then logs one PERF line and writes it to files/perf.txt.
 * It exists so that the packaged reference and the module-delivered game are measured by the SAME code, which isolates the delivery layer from the game. Enabled by the system property
 * climb.perf = measured seconds (launch extra prop.climb.perf); climb.perfWarm = warm-up seconds (default 10).
 */
public final class PerfListener implements ApplicationListener {
    private final ApplicationListener inner; private final String tag; private final float warmS, measureS;
    private float[] interval, work; private int n; private long createdNs, lastNs; private boolean done, started;

    private PerfListener(ApplicationListener inner, String tag, float warmS, float measureS) { this.inner = inner; this.tag = tag; this.warmS = warmS; this.measureS = measureS; }

    /** Wraps {@code game} when climb.perf is set, otherwise returns it untouched. */
    public static ApplicationListener maybeWrap(ApplicationListener game, String tag) {
        String p = System.getProperty("climb.perf");
        if (p == null) return game;
        float m = Float.parseFloat(p), w = Float.parseFloat(System.getProperty("climb.perfWarm", "10"));
        Log.i(LabCommon.TAG, "PERF measuring " + tag + ": " + w + " s warm-up, then " + m + " s");
        return new PerfListener(game, tag, w, m);
    }

    @Override public void create() { createdNs = System.nanoTime(); int cap = (int) (measureS * 240) + 64; interval = new float[cap]; work = new float[cap]; inner.create(); }
    @Override public void resize(int w, int h) { inner.resize(w, h); }
    @Override public void pause() { inner.pause(); }
    @Override public void resume() { inner.resume(); }
    @Override public void dispose() { inner.dispose(); }

    @Override public void render() {
        long t0 = System.nanoTime();
        inner.render();
        long t1 = System.nanoTime();
        if (!done) {
            float sinceCreate = (t0 - createdNs) / 1e9f;
            if (sinceCreate >= warmS) {
                if (!started) { started = true; lastNs = t0; }
                else if (n < interval.length) { interval[n] = (t0 - lastNs) / 1e6f; work[n] = (t1 - t0) / 1e6f; n++; lastNs = t0; }
                if (sinceCreate >= warmS + measureS || n >= interval.length) finish();
            }
        }
    }

    private static float pct(float[] sorted, double q) { return sorted[Math.min(sorted.length - 1, (int) Math.floor(q * (sorted.length - 1) + 0.5))]; }

    private void finish() {
        done = true;
        float[] iv = Arrays.copyOf(interval, n), wk = Arrays.copyOf(work, n); Arrays.sort(iv); Arrays.sort(wk);
        if (n < 10) { Log.i(LabCommon.TAG, "PERF " + tag + " frames=" + n + " (too few)"); return; }
        double sum = 0; int jank = 0, jank2 = 0; for (float v : iv) { sum += v; if (v > 25f) jank++; if (v > 50f) jank2++; }
        Runtime rt = Runtime.getRuntime();
        String line = String.format(java.util.Locale.US, "PERF %s frames=%d fps=%.1f interval_ms[p50=%.2f p90=%.2f p99=%.2f max=%.2f] work_ms[p50=%.2f p99=%.2f max=%.2f] slow25=%d slow50=%d javaUsedKb=%d nativeKb=%d",
                tag, n, 1000.0 * n / sum, pct(iv, .5), pct(iv, .9), pct(iv, .99), iv[n - 1], pct(wk, .5), pct(wk, .99), wk[n - 1], jank, jank2, (rt.totalMemory() - rt.freeMemory()) / 1024, Debug.getNativeHeapAllocatedSize() / 1024);
        Log.i(LabCommon.TAG, line);
        try { File f = new File(Gdx.files.getLocalStoragePath(), "perf.txt"); try (FileWriter w = new FileWriter(f)) { w.write(line + "\n"); } } catch (Exception e) { Log.i(LabCommon.TAG, "PERF write failed: " + e); }
    }
}
