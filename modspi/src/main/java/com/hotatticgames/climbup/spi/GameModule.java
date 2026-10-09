package com.hotatticgames.climbup.spi;

import com.badlogic.gdx.ApplicationListener;

/**
 * The whole contract between the stable host and an updatable game module (docs/ota-full/INTERFACE.md).
 * The host instantiates the manifest's entry class (public, no-arg constructor) and calls {@link #create}; the returned listener is handed
 * straight to the libGDX backend, so lifecycle, rendering, input and audio run exactly as they do in a packaged game: one render loop,
 * one clock, one set of native libraries, no proxying layer between the backend and the game.
 */
public interface GameModule {
    /** Version of THIS interface. A module built for another value is never activated (the manifest carries it and the host also checks the loaded class). */
    int INTERFACE_VERSION = 1;

    /** The interface version this module was compiled against; the host refuses a mismatch. */
    int interfaceVersion();

    /** Creates the game's ApplicationListener. Called once, on the host's UI thread, before the backend starts the render loop. */
    ApplicationListener create(HostEnv env);
}
