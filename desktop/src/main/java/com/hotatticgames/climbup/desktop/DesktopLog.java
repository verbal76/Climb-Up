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

    public static void error(String what, Throwable t) { StringWriter w = new StringWriter(); t.printStackTrace(new PrintWriter(w)); append(what + "\n" + w); }
}
