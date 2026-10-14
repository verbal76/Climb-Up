package com.hotatticgames.climbup.desktop;

import java.io.File;
import java.util.Locale;

/** Where saves live: %APPDATA%\HotAtticGames\Upwardly on Windows, never the working directory. */
public final class DataDirs {
    private DataDirs() { }

    public static File dataDir() {
        String o = System.getProperty("climb.data");
        if (o != null && !o.isEmpty()) return new File(o);
        return resolve(System.getProperty("os.name", ""), System.getenv("APPDATA"), System.getenv("XDG_DATA_HOME"), System.getProperty("user.home", "."));
    }

    /** The player's screenshot folder (Build 65): Upwardly/Screenshots under the user's Pictures folder when that folder exists, else the data folder's Screenshots. */
    public static File screenshotDir(File dataDir) {
        String home = System.getProperty("user.home", ".");
        return screenshotDir(dataDir, resolvePictures(System.getProperty("os.name", ""), home, userDirsText(home)));
    }
    /** Pure: the folder for a known Pictures folder (null or missing = the data folder). */
    public static File screenshotDir(File dataDir, File pictures) {
        return pictures != null && pictures.isDirectory() ? new File(new File(pictures, "Upwardly"), "Screenshots") : new File(dataDir, "Screenshots");
    }
    /** Pure: the operating system's Pictures folder - Windows and macOS: Pictures under the home; Linux: XDG_PICTURES_DIR from ~/.config/user-dirs.dirs (its text is passed in), else ~/Pictures. */
    public static File resolvePictures(String os, String home, String userDirsText) {
        String n = os.toLowerCase(Locale.ROOT);
        if (n.contains("win") || n.contains("mac")) return new File(home, "Pictures");
        if (userDirsText != null) for (String line : userDirsText.split("\n")) {
            String t = line.trim();
            if (!t.startsWith("XDG_PICTURES_DIR=")) continue;
            String v = t.substring("XDG_PICTURES_DIR=".length()).trim();
            if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
            return new File(v.replace("$HOME", home));
        }
        return new File(home, "Pictures");
    }
    private static String userDirsText(String home) {
        try { File f = new File(home, ".config/user-dirs.dirs"); return f.isFile() ? new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8) : null; }
        catch (Exception e) { return null; }
    }

    /** Pure so every platform's answer can be tested on any machine. */
    public static File resolve(String os, String appData, String xdg, String home) {
        String n = os.toLowerCase(Locale.ROOT);
        if (n.contains("win")) {
            String base = appData != null && !appData.isEmpty() ? appData : new File(home, "AppData/Roaming").getPath();
            return new File(new File(base, "HotAtticGames"), "Upwardly");
        }
        if (n.contains("mac")) return new File(home, "Library/Application Support/HotAtticGames/Upwardly");
        String base = xdg != null && !xdg.isEmpty() ? xdg : new File(home, ".local/share").getPath();
        return new File(new File(base, "hotatticgames"), "upwardly");
    }
}
