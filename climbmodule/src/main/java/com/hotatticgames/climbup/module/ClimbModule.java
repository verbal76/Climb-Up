package com.hotatticgames.climbup.module;

import com.badlogic.gdx.ApplicationListener;
import com.hotatticgames.climbup.ClimbGame;
import com.hotatticgames.climbup.spi.GameModule;
import com.hotatticgames.climbup.spi.HostEnv;

/**
 * Entry point of the Climb Up game module. It does exactly what the packaged game's AndroidLauncher does (set the build code, construct ClimbGame with the app data directory)
 * and nothing else; every line of gameplay, rendering, audio, UI and persistence is the unchanged :core code.
 */
public final class ClimbModule implements GameModule {
    @Override public int interfaceVersion() { return INTERFACE_VERSION; }

    @Override public ApplicationListener create(HostEnv env) {
        ClimbGame.appBuild = env.appBuild();
        return new ModuleGame(env);
    }

    @Override public String selfTest(String request) { return SelfTest.run(request); }
}
