package com.hotatticgames.climbup.module;

import com.hotatticgames.climbup.ClimbGame;
import com.hotatticgames.climbup.PlayScreen;
import com.hotatticgames.climbup.spi.HostEnv;

/**
 * ClimbGame plus the host reporting the module contract needs, added by overriding public methods only (no gameplay code is touched or duplicated):
 *  - the module is declared healthy after 15 s of live play, the same moment the game itself confirms its data-OTA content;
 *  - the host is told when a climb exists (it holds back module switches that would change a climb in progress).
 */
class ModuleGame extends ClimbGame {
    private static final float HEALTHY_SECONDS = 15f;
    private final HostEnv env;
    private float playSeconds; private boolean healthy;

    ModuleGame(HostEnv env) { super(env.dataDir()); this.env = env; }

    @Override public void create() {
        super.create();
        env.climbInProgress(climbValid());
    }

    /** Settings > About > CHECK: the game's own check as before, plus the host's channel check for signed game updates (a host without it ignores the call). */
    @Override public void startOtaCheck(boolean force) {
        super.startOtaCheck(force);
        if (force) { try { env.checkForUpdates(); } catch (Throwable ignored) { } }
    }

    @Override public boolean canRestartApp() { return true; }

    @Override public void restartApp() { HostRestart.relaunch(); }

    private int statusTick;

    private String otaLabel;

    /** Title screen: the game version and the update (OTA) version that is running, which is what matters to the player; the build number stays in Settings > About. */
    @Override public String versionLabel() {
        if (otaLabel == null) {
            otaLabel = super.versionLabel();
            try {
                java.io.File d = env.moduleDir();
                if (d != null) {
                    String m = new String(java.nio.file.Files.readAllBytes(new java.io.File(d, "manifest.json").toPath()), java.nio.charset.StandardCharsets.UTF_8);
                    java.util.regex.Matcher x = java.util.regex.Pattern.compile("\"moduleVersion\"\\s*:\\s*(\\d+)").matcher(m);
                    if (x.find()) otaLabel = "V" + VERSION + "  OTA " + x.group(1);
                }
            } catch (Throwable ignored) { }
        }
        return otaLabel;
    }

    private UpdateGate gate;

    @Override public void render() {
        if (gate == null) gate = new UpdateGate(env, this);
        float dt = Math.min(com.badlogic.gdx.Gdx.graphics.getDeltaTime(), 0.1f);
        boolean onTitle = getScreen() instanceof com.hotatticgames.climbup.TitleScreen;
        try { gate.before(onTitle, dt); } catch (Throwable ignored) { }
        super.render();
        try { gate.after(); } catch (Throwable ignored) { }
        if (++statusTick % 30 == 0 && otaClient != null) { try { String s = env.updateStatus(); if (s != null && !s.isEmpty()) otaClient.status = s; } catch (Throwable ignored) { } }
        if (!healthy && getScreen() instanceof PlayScreen) { playSeconds += Math.min(com.badlogic.gdx.Gdx.graphics.getDeltaTime(), 0.25f); if (playSeconds >= HEALTHY_SECONDS) { healthy = true; env.confirmHealthy(); } }
    }

    @Override public Run openRun(boolean fresh) { Run r = super.openRun(fresh); env.climbInProgress(true); return r; }

    @Override public void forgetRun() { super.forgetRun(); env.climbInProgress(false); }
}
