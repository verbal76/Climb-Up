package com.hotatticgames.climbup.module;

import com.badlogic.gdx.ApplicationListener;
import com.hotatticgames.climbup.ClimbGame;
import com.hotatticgames.climbup.spi.GameModule;
import com.hotatticgames.climbup.spi.HostEnv;

/**
 * The entry class of the code-update test release. It does what {@link ClimbModule} does, creating the same unchanged game, and additionally runs {@link CodeMarker} and reports it, so a
 * device can show that a signed release changed the executing code while the game itself (every :core class, byte for byte) stayed as it was.
 */
public final class ClimbModuleMarked implements GameModule {
    @Override public int interfaceVersion() { return INTERFACE_VERSION; }

    @Override public ApplicationListener create(HostEnv env) {
        env.diag("code-marker " + CodeMarker.value());
        ClimbGame.appBuild = env.appBuild();
        return new ModuleGame(env);
    }

    @Override public String selfTest(String request) { return "marker".equals(request) ? CodeMarker.value() : SelfTest.run(request); }
}
