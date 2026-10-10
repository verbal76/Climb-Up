package com.hotatticgames.climbup.otalab;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import com.hotatticgames.climbup.spi.GameModule;

/** Test-lab plumbing shared by the experimental hosts (never part of a shipped host). */
public final class LabCommon {
    private LabCommon() {}
    public static final String TAG = "OTALAB";

    /** The same libGDX configuration the shipped game's AndroidLauncher uses; any difference would be a difference in the game's presentation. */
    public static AndroidApplicationConfiguration gameConfig() {
        AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
        config.useImmersiveMode = true; config.useAccelerometer = false; config.useCompass = false; config.useGyroscope = false; config.numSamples = 0; config.maxSimultaneousSounds = 24;
        return config;
    }

    /** Lab only: launch extras named {@code prop.X} become JVM system properties (the game's own demo / start-screen switches such as climb.demo, climb.start, climb.seed). */
    public static void applyTestProps(Activity a) {
        Bundle ex = a.getIntent() == null ? null : a.getIntent().getExtras();
        if (ex == null) return;
        for (String k : ex.keySet()) if (k.startsWith("prop.")) { System.setProperty(k.substring(5), String.valueOf(ex.get(k))); Log.i(TAG, "system property " + k.substring(5) + "=" + ex.get(k)); }
    }

    /** Lab only: extra {@code selftest}=request runs the module's headless self-check on a background thread and logs the one-line result. Call after initialize() so Gdx.files exists. */
    public static void startSelfTest(Activity a, final GameModule module) {
        final String req = a.getIntent() == null ? null : a.getIntent().getStringExtra("selftest");
        if (req == null || module == null) return;
        Thread t = new Thread(() -> {
            for (String one : req.split(",")) {                // several requests may be given, comma separated; they run one after the other
                long t0 = System.nanoTime();
                String r = module.selfTest(one);
                Log.i(TAG, "SELFTEST " + one + " -> " + r + " [" + (System.nanoTime() - t0) / 1_000_000 + " ms]");
            }
        }, "lab-selftest");
        t.setDaemon(true); t.start();
    }
}
