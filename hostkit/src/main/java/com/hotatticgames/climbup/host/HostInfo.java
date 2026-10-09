package com.hotatticgames.climbup.host;

import com.hotatticgames.climbup.spi.GameModule;

/** What a verifier needs to know about the installed host. */
public final class HostInfo {
    /** Bumped only when the host changes in a way modules must know about (new SPI semantics, new loader rules). Rare by design. */
    public static final int HOST_LEVEL = 1;
    public final String app;
    public final int hostLevel, interfaceVersion;
    public final String channel;
    public HostInfo(String app, int hostLevel, String channel) { this(app, hostLevel, GameModule.INTERFACE_VERSION, channel); }
    public HostInfo(String app, int hostLevel, int interfaceVersion, String channel) { this.app = app; this.hostLevel = hostLevel; this.interfaceVersion = interfaceVersion; this.channel = channel; }
}
