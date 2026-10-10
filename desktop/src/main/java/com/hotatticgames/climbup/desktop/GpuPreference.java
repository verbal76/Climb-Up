package com.hotatticgames.climbup.desktop;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Windows "Graphics settings" per-app GPU preference for Upwardly.exe, kept in HKCU\Software\Microsoft\DirectX\UserGpuPreferences
 * (value name = full path of the program, data "GpuPreference=2;" = High performance). Current user only, so no administrator rights are needed.
 * The command builders are pure; {@link #apply} runs them (Windows only, every failure is swallowed and logged). Windows reads the preference when
 * the program starts, so a change needs a restart of the game.
 */
public final class GpuPreference {
    public static final String KEY = "HKCU\\Software\\Microsoft\\DirectX\\UserGpuPreferences";
    public static final String HIGH = "GpuPreference=2;";
    public enum Result { CHANGED, UNCHANGED, SKIPPED, FAILED }

    private GpuPreference() { }

    public static List<String> addCommand(String exePath) { return Arrays.asList("reg", "add", KEY, "/v", exePath, "/t", "REG_SZ", "/d", HIGH, "/f"); }
    public static List<String> deleteCommand(String exePath) { return Arrays.asList("reg", "delete", KEY, "/v", exePath, "/f"); }
    public static List<String> queryCommand(String exePath) { return Arrays.asList("reg", "query", KEY, "/v", exePath); }

    /** True when the output of {@link #queryCommand} shows the value set to High performance. */
    public static boolean isHigh(String queryOutput) { return queryOutput != null && queryOutput.replace(" ", "").toLowerCase(Locale.ROOT).contains("gpupreference=2;"); }

    /** Only the packaged game itself gets a preference, never java.exe or a development run. */
    public static boolean isGameExe(String path) {
        if (path == null) return false;
        String n = new File(path.replace('\\', '/')).getName().toLowerCase(Locale.ROOT);
        return n.equals("upwardly.exe");
    }

    /** Full path of the running Upwardly.exe (jpackage sets the first property; the process command is the fallback), or null when this is not the packaged game. */
    public static String currentExe() {
        try {
            String p = System.getProperty("jpackage.app-path");
            if (isGameExe(p)) return p;
            String c = ProcessHandle.current().info().command().orElse(null);
            return isGameExe(c) ? c : null;
        } catch (RuntimeException e) { return null; }
    }

    /** Sets (on) or removes (off) the preference. Blocking for up to a few seconds: call from a background thread. */
    public static Result apply(String exePath, boolean on, boolean windows) {
        if (!windows || exePath == null) return Result.SKIPPED;
        try {
            boolean already = isHigh(run(queryCommand(exePath)));
            if (on == already) return Result.UNCHANGED;
            String out = run(on ? addCommand(exePath) : deleteCommand(exePath));
            if (out == null) return Result.FAILED;
            return Result.CHANGED;
        } catch (Exception e) { DesktopLog.append("gpu preference: " + e); return Result.FAILED; }
    }

    /** Output of the command, or null if it did not finish cleanly with exit code 0 (a missing value on query is not a failure: it returns ""). */
    private static String run(List<String> cmd) throws Exception {
        Process p = new ProcessBuilder(new ArrayList<>(cmd)).redirectErrorStream(true).start();
        byte[] out = p.getInputStream().readAllBytes();
        if (!p.waitFor(8, TimeUnit.SECONDS)) { p.destroyForcibly(); return null; }
        if (p.exitValue() != 0) return cmd.get(1).equals("query") ? "" : null;
        return new String(out, java.nio.charset.StandardCharsets.UTF_8);
    }
}
