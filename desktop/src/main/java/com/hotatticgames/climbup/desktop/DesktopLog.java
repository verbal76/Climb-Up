package com.hotatticgames.climbup.desktop;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

/** desktop.log in the save folder: what this run did at startup (and why it died, if it did). Truncated at every launch; never contains personal data. */
public final class DesktopLog {
    private static File file;
    private DesktopLog() { }

    public static synchronized void start(File dataDir) {
        file = new File(dataDir, "desktop.log");
        try { dataDir.mkdirs(); Files.write(file.toPath(), new byte[0]); } catch (Exception ignored) { }
        append("launcher started; java " + System.getProperty("java.version") + " at " + System.getProperty("java.home") + "; " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
    }

    public static synchronized void append(String line) {
        if (file == null) return;
        try { Files.write(file.toPath(), (line + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND); } catch (Exception ignored) { }
    }

    public static void error(String what, Throwable t) {
        StringWriter w = new StringWriter();
        t.printStackTrace(new PrintWriter(w));
        append(what + "\ngame state: " + gameState() + "\n" + graphicsNow() + "\n" + w);
    }

    /** The last per-frame game-state line the play screen recorded (screen, sim mode, height, element, zone, character...). */
    private static String gameState() {
        try { return com.hotatticgames.climbup.CrashContext.get(); } catch (Throwable e) { return "(unavailable)"; }
    }

    /** GPU and frame state at the moment of the crash (the startup line has the configured GPU; this is the live state). */
    private static String graphicsNow() {
        try {
            com.badlogic.gdx.Graphics g = com.badlogic.gdx.Gdx.graphics;
            if (g == null) return "gpu now: (no graphics context)";
            com.badlogic.gdx.graphics.glutils.GLVersion v = g.getGLVersion();
            String gpu = v != null ? v.getRendererString() + " (" + v.getVendorString() + ") GL " + v.getMajorVersion() + "." + v.getMinorVersion() : "(no GL version)";
            return "gpu now: " + gpu + " | fps " + g.getFramesPerSecond() + " | backbuffer " + g.getBackBufferWidth() + "x" + g.getBackBufferHeight();
        } catch (Throwable e) { return "gpu now: (unavailable: " + e + ")"; }
    }
}
