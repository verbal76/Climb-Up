package com.hotatticgames.climbup.android;

import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import com.hotatticgames.climbup.ClimbGame;
import com.hotatticgames.climbup.CrashContext;
import java.io.File;
import java.io.PrintWriter;

public class AndroidLauncher extends AndroidApplication {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installCrashLog();
        AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
        config.useImmersiveMode = true;
        config.useAccelerometer = false;
        config.useCompass = false;
        config.useGyroscope = false;
        config.numSamples = 0;
        config.stencil = 8;           // the shadows draw each pixel once (ShadowPass)
        config.maxSimultaneousSounds = 24;
        try { ClimbGame.appBuild = (int) getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode(); } catch (Exception ignored) { }
        initialize(new ClimbGame(getFilesDir(), new AndroidPlatform(this)), config);      // the original touch platform plus the gallery screenshot sink (Build 65)
    }

    /**
     * Mirrors the desktop crash log on the phone so a crash can be sent in: on any uncaught exception, write the
     * device, the last game-state line ({@link CrashContext}) and the stack trace to logcat and to crash.log in the
     * app's external files folder (Android/data/&lt;package&gt;/files/crash.log, reachable over USB or a file manager).
     * Defensive throughout and chained to the platform handler so Android still shows its own crash dialog.
     */
    private void installCrashLog() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                String head = "Upwardly crashed on " + Build.MANUFACTURER + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ", api " + Build.VERSION.SDK_INT + ")";
                String state = "game state: " + safeState();
                Log.e("Upwardly", head + "\n" + state, ex);
                File dir = getExternalFilesDir(null);
                if (dir == null) dir = getFilesDir();
                try (PrintWriter w = new PrintWriter(new File(dir, "crash.log"))) {
                    w.println(head);
                    w.println("build " + ClimbGame.appBuild + "; thread " + (thread != null ? thread.getName() : "?"));
                    w.println(state);
                    w.println();
                    ex.printStackTrace(w);
                }
            } catch (Throwable ignored) { /* never let the crash logger hide the real crash */ }
            if (prev != null) prev.uncaughtException(thread, ex);
        });
    }

    private static String safeState() {
        try { return CrashContext.get(); } catch (Throwable e) { return "(unavailable)"; }
    }
}
