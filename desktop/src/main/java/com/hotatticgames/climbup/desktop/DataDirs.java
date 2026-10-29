package com.hotatticgames.climbup.desktop;

import java.io.File;
import java.util.Locale;

/**
 * Where saves live, never the working directory: %APPDATA%\HotAtticGames\Upwardly on Windows, $XDG_DATA_HOME (else ~/.local/share)/HotAtticGames/Upwardly
 * on Linux and the Steam Deck. The two use the same folder name on purpose: Steam Auto-Cloud maps the Windows root onto the Linux one with a
 * root override, which keeps the subdirectory as it is, so the names must match letter for letter. Builds before the Steam Cloud work used a
 * lower-case hotatticgames/upwardly on Linux; {@link #migrateLinuxLegacy} moves that folder's contents across once.
 */
public final class DataDirs {
    private DataDirs() { }

    public static File dataDir() {
        String o = System.getProperty("climb.data");
        if (o != null && !o.isEmpty()) return new File(o);
        String os = System.getProperty("os.name", ""), xdg = System.getenv("XDG_DATA_HOME"), home = System.getProperty("user.home", ".");
        File dir = resolve(os, System.getenv("APPDATA"), xdg, home);
        File old = com.hotatticgames.climbup.Edition.DEMO ? null : legacyLinux(os, xdg, home);          // (the demo never had a legacy folder: it never moves the full game's files)
        if (old != null) migrateLinuxLegacy(old, dir);
        return dir;
    }

    /** Pure: the lower-case Linux folder of the builds before Steam Cloud (null on Windows and macOS). */
    public static File legacyLinux(String os, String xdg, String home) {
        String n = os.toLowerCase(Locale.ROOT);
        if (n.contains("win") || n.contains("mac")) return null;
        String base = xdg != null && !xdg.isEmpty() ? xdg : new File(home, ".local/share").getPath();
        return new File(new File(base, "hotatticgames"), "upwardly");
    }

    /**
     * Moves the old Linux folder's contents into the new one, once. Each top-level entry (players.json, players/, settings.json, the desktop
     * config, ...) is moved only if the new folder does not have it yet, so nothing in the new folder is ever overwritten and running it again
     * does nothing. Anything that cannot be moved stays where it was (never deleted). Returns the number of entries moved.
     */
    public static int migrateLinuxLegacy(File oldDir, File newDir) {
        try {
            if (oldDir == null || !oldDir.isDirectory()) return 0;
            if (newDir.exists() && java.nio.file.Files.isSameFile(oldDir.toPath(), newDir.toPath())) return 0;     // a case-insensitive file system: one folder
            File[] items = oldDir.listFiles();
            if (items == null || items.length == 0) return 0;
            newDir.mkdirs();
            int moved = 0;
            for (File f : items) {
                File to = new File(newDir, f.getName());
                if (to.exists()) continue;
                try { java.nio.file.Files.move(f.toPath(), to.toPath()); moved++; } catch (Exception e) { /* left in the old folder, untouched */ }
            }
            String[] left = oldDir.list();
            if (left != null && left.length == 0) { oldDir.delete(); File parent = oldDir.getParentFile(); String[] p = parent == null ? null : parent.list(); if (p != null && p.length == 0) parent.delete(); }
            return moved;
        } catch (Exception e) { return 0; }
    }

    /** The player's screenshot folder (Build 65): Upwardly/Screenshots under the user's Pictures folder when that folder exists, else the data folder's Screenshots. */
    public static File screenshotDir(File dataDir) {
        String home = System.getProperty("user.home", ".");
        return screenshotDir(dataDir, resolvePictures(System.getProperty("os.name", ""), home, userDirsText(home)));
    }
    /** Pure: the folder for a known Pictures folder (null or missing = the data folder). */
    public static File screenshotDir(File dataDir, File pictures) {
        return pictures != null && pictures.isDirectory() ? new File(new File(pictures, FOLDER), "Screenshots") : new File(dataDir, "Screenshots");
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
    /** The folder name under HotAtticGames: the demo saves apart from the full game (docs/DEMO.md), so neither can ever read the other's files. */
    static final String FOLDER = com.hotatticgames.climbup.Edition.DEMO ? "Upwardly Demo" : "Upwardly";

    public static File resolve(String os, String appData, String xdg, String home) { return resolve(os, appData, xdg, home, FOLDER); }
    /** Pure: the data folder for a given folder name. */
    public static File resolve(String os, String appData, String xdg, String home, String folder) {
        String n = os.toLowerCase(Locale.ROOT);
        if (n.contains("win")) {
            String base = appData != null && !appData.isEmpty() ? appData : new File(home, "AppData/Roaming").getPath();
            return new File(new File(base, "HotAtticGames"), folder);
        }
        if (n.contains("mac")) return new File(home, "Library/Application Support/HotAtticGames/" + folder);
        String base = xdg != null && !xdg.isEmpty() ? xdg : new File(home, ".local/share").getPath();
        return new File(new File(base, "HotAtticGames"), folder);
    }
}
