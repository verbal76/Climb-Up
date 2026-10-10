package com.hotatticgames.climbup.otalab;

import android.os.Bundle;
import android.util.Log;
import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.backends.android.AndroidApplication;
import com.hotatticgames.climbup.host.HostBoot;
import com.hotatticgames.climbup.host.HostInfo;
import com.hotatticgames.climbup.host.ModuleDownloader;
import com.hotatticgames.climbup.host.ModuleStore;
import com.hotatticgames.climbup.host.TrustedKeys;
import com.hotatticgames.climbup.spi.HostEnv;
import dalvik.system.DexClassLoader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Experimental host: verifies, loads and runs a signed game module, applies staged updates only at cold start, rolls back broken modules, and falls to a built-in recovery screen.
 * It owns libGDX, the native libraries and the render loop; the module supplies only an ApplicationListener, which the stock backend drives directly.
 */
public class LabLauncher extends AndroidApplication {
    static final String TAG = LabCommon.TAG;
    private ModuleStore store;

    private com.hotatticgames.climbup.host.OverlayFiles overlay;
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LabCommon.applyTestProps(this);
        long t0 = System.nanoTime();
        File root = new File(getFilesDir(), "host");
        HostInfo host = new HostInfo(getPackageName(), HostInfo.HOST_LEVEL, "internal");
        TrustedKeys keys = new TrustedKeys();
        try { keys.add(readAsset("lab_public_key.b64")); } catch (Exception e) { Log.e(TAG, "pinned key unreadable: " + e); }
        store = new ModuleStore(root, keys, host).withSaveGuard(new com.hotatticgames.climbup.host.DirSnapshots(getFilesDir(), new File(root, "snap"), java.util.Collections.singleton("host")));
        HostBoot.ClassLoading dex = (file, parent) -> {
            // Android 14+ refuses to load writable dex files; the store already marks installed files read-only, this makes it explicit for any other path.
            if (!file.setReadOnly() && file.canWrite()) throw new SecurityException("cannot make " + file.getName() + " read-only");
            return new DexClassLoader(file.getAbsolutePath(), null, null, parent);
        };
        ClassLoader parent = getClassLoader();
        HostBoot.Started s = HostBoot.start(store, dex, parent, this::extractBaseline);
        ApplicationListener listener = null;
        while (listener == null) {
            if (s.recovery()) { listener = new RecoveryGame(store.st.rollback.isEmpty() ? store.st.lastResult : store.st.rollback); break; }
            try { listener = s.module.create(env(s.dir)); }
            catch (Throwable t) { Log.e(TAG, "module create failed: " + t); s = HostBoot.failed(store, dex, parent, t); }
        }
        Log.i(TAG, (s.recovery() ? "RECOVERY screen" : "running module v" + s.manifest.moduleVersion) + " active=" + store.st.active + " lastGood=" + store.st.lastGood + " pending=" + store.st.pending
                + " tries=" + store.st.tries + " staged=" + store.st.staged + " note=[" + s.note + "] rollback=[" + store.st.rollback + "] bootMs=" + (System.nanoTime() - t0) / 1_000_000);
        initialize(s.recovery() ? listener : PerfListener.maybeWrap(listener, "module-host"), LabCommon.gameConfig());
        if (!s.overrides.isEmpty()) {                       // the module carries game files that replace the APK's: serve them through the overlay (nothing is installed otherwise)
            overlay = new com.hotatticgames.climbup.host.OverlayFiles(com.badlogic.gdx.Gdx.files, s.overrides, store.assets(), msg -> Log.i(TAG, msg));
            com.badlogic.gdx.Gdx.files = overlay;
            Log.i(TAG, "asset overrides active: " + s.overrides.size() + " file(s)");
        }
        LabCommon.startSelfTest(this, s.module);

        String base = getIntent() == null ? null : getIntent().getStringExtra("updateBase");      // lab only: where CI serves test bundles
        if (base != null) {
            final String b = base;
            Thread t = new Thread(() -> Log.i(TAG, "update check: " + new ModuleDownloader(store, new ModuleDownloader.Http(true), b).check()), "lab-update");
            t.setDaemon(true); t.start();
        }
    }

    private HostEnv env(File moduleDir) {
        return new HostEnv() {
            @Override public File dataDir() { return getFilesDir(); }
            @Override public int appBuild() { try { return (int) getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode(); } catch (Exception e) { return 0; } }
            @Override public int hostLevel() { return HostInfo.HOST_LEVEL; }
            @Override public File moduleDir() { return moduleDir; }
            @Override public void confirmHealthy() { store.confirm(); Log.i(TAG, "confirmed healthy: " + store.st.lastResult); }
            @Override public void climbInProgress(boolean v) { Log.i(TAG, "climbInProgress=" + v); store.setClimbInProgress(v); }
            @Override public void diag(String line) { Log.i(TAG, line); }
        };
    }

    private String readAsset(String name) throws Exception {
        try (InputStream in = getAssets().open(name)) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(); byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        }
    }

    /** Copies the signed baseline bundle shipped inside the APK (assets/baseline) to a fresh cache directory for the store to verify and install. */
    private File extractBaseline() {
        try {
            String[] names = getAssets().list("baseline");
            if (names == null || names.length == 0) return null;
            File dir = new File(getCacheDir(), "baseline"); deleteTree(dir); dir.mkdirs();
            for (String n : names) {
                try (InputStream in = getAssets().open("baseline/" + n); FileOutputStream out = new FileOutputStream(new File(dir, n))) {
                    byte[] buf = new byte[1 << 16]; int r;
                    while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                }
            }
            return dir;
        } catch (Exception e) { Log.e(TAG, "baseline extraction failed: " + e); return null; }
    }

    private static void deleteTree(File f) { File[] k = f.listFiles(); if (k != null) for (File c : k) deleteTree(c); f.delete(); }

    @Override protected void onPause() { Log.i(TAG, "host onPause"); super.onPause(); }
    /** libGDX re-publishes its own {@code Gdx.files} on every resume, which would silently drop the overlay before the game's {@code create()} runs; put it back. */
    @Override protected void onResume() {
        Log.i(TAG, "host onResume"); super.onResume();
        if (overlay != null && com.badlogic.gdx.Gdx.files != overlay) { com.badlogic.gdx.Gdx.files = overlay; Log.i(TAG, "asset overlay re-installed after resume"); }
    }
}
