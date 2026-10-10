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
