package com.hotatticgames.climbup.module;

import com.hotatticgames.climbup.ClimbGame;
import com.hotatticgames.climbup.PlayScreen;
import com.hotatticgames.climbup.spi.HostEnv;

/**
 * ClimbGame plus the host reporting the module contract needs, added by overriding public methods only (no gameplay code is touched or duplicated):
 *  - the module is declared healthy after 15 s of live play, the same moment the game itself confirms its data-OTA content;
 *  - the host is told when a climb exists (it holds back module switches that would change a climb in progress).
 */
final class ModuleGame extends ClimbGame {
    private static final float HEALTHY_SECONDS = 15f;
    private final HostEnv env;
    private float playSeconds; private boolean healthy;

    ModuleGame(HostEnv env) { super(env.dataDir()); this.env = env; }

    @Override public void create() {
        super.create();
        env.climbInProgress(climbValid());
    }

    @Override public void render() {
        super.render();
        if (!healthy && getScreen() instanceof PlayScreen) { playSeconds += Math.min(com.badlogic.gdx.Gdx.graphics.getDeltaTime(), 0.25f); if (playSeconds >= HEALTHY_SECONDS) { healthy = true; env.confirmHealthy(); } }
    }

    @Override public Run openRun(boolean fresh) { Run r = super.openRun(fresh); env.climbInProgress(true); return r; }

    @Override public void forgetRun() { super.forgetRun(); env.climbInProgress(false); }
}
