package com.hotatticgames.climbup.otaref;

import android.os.Bundle;
import android.util.Log;
import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.backends.android.AndroidApplication;
import com.hotatticgames.climbup.module.ClimbModule;
import com.hotatticgames.climbup.otalab.LabCommon;
import com.hotatticgames.climbup.spi.GameModule;
import com.hotatticgames.climbup.spi.HostEnv;
import java.io.File;

/**
 * Reference for the equivalence tests: the SAME game classes and the SAME module entry, compiled directly into the APK (no loader, no store, no signature), the way the store edition
 * will be packaged. Anything that differs between this app and the module-loading host is a difference introduced by the delivery mechanism.
 */
public class PackagedLauncher extends AndroidApplication {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LabCommon.applyTestProps(this);
        final GameModule module = new ClimbModule();
        ApplicationListener game = module.create(new HostEnv() {
            @Override public File dataDir() { return getFilesDir(); }
            @Override public int appBuild() { try { return (int) getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode(); } catch (Exception e) { return 0; } }
            @Override public int hostLevel() { return 1; }
            @Override public File moduleDir() { return null; }
            @Override public void confirmHealthy() { Log.i(LabCommon.TAG, "confirmed healthy (packaged build)"); }
            @Override public void climbInProgress(boolean v) { }
            @Override public void diag(String line) { Log.i(LabCommon.TAG, line); }
        });
        Log.i(LabCommon.TAG, "running PACKAGED game (no loader)");
        initialize(com.hotatticgames.climbup.otalab.PerfListener.maybeWrap(game, "packaged-ref"), LabCommon.gameConfig());
        LabCommon.startSelfTest(this, module);
    }
}
