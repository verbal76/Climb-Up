package com.hotatticgames.climbup;

import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.badlogic.gdx.utils.JsonWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Atomic, versioned JSON persistence with corrupt-file recovery. Pure java.io so it is unit-testable headlessly. */
public final class SaveStore {
    private final File dir;
    public boolean recoveredFromCorruption;

    public SaveStore(File dir) { this.dir = dir; dir.mkdirs(); }

    private Json json() { Json j = new Json(JsonWriter.OutputType.json); j.setIgnoreUnknownFields(true); return j; }

    public void writeAtomic(String name, String text) throws IOException {
        File tmp = new File(dir, name + ".tmp"), dst = new File(dir, name);
        Files.write(tmp.toPath(), text.getBytes(StandardCharsets.UTF_8));
        try { Files.move(tmp.toPath(), dst.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (IOException | UnsupportedOperationException e) { Files.move(tmp.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING); }
    }

    private String read(String name) {
        File f = new File(dir, name);
        if (!f.exists()) return null;
        try { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); } catch (IOException e) { return null; }
    }

    private void backupCorrupt(String name) {
        File f = new File(dir, name);
        try { Files.move(f.toPath(), new File(dir, name + ".corrupt").toPath(), StandardCopyOption.REPLACE_EXISTING); } catch (IOException ignored) { f.delete(); }
        recoveredFromCorruption = true;
    }

    public void saveGame(SaveData d) { try { writeAtomic("save.json", json().toJson(d)); } catch (IOException ignored) { } }
    public void saveSettings(Settings s) { try { writeAtomic("settings.json", json().toJson(s)); } catch (IOException ignored) { } }

    public SaveData loadGame() {
        String t = read("save.json");
        if (t == null) return new SaveData();
        try {
            JsonValue v = migrate(new JsonReader().parse(t));
            SaveData d = json().readValue(SaveData.class, v);
            if (d == null || d.shownTips == null) throw new IllegalStateException("empty");
            return sanitize(d);
        } catch (Exception e) { backupCorrupt("save.json"); return new SaveData(); }
    }

    public Settings loadSettings() {
        String t = read("settings.json");
        if (t == null) return new Settings();
        try { Settings s = json().fromJson(Settings.class, t); if (s == null) throw new IllegalStateException(); return s; }
        catch (Exception e) { backupCorrupt("settings.json"); return new Settings(); }
    }

    public void eraseAll() { new File(dir, "save.json").delete(); new File(dir, "settings.json").delete(); }

    private static SaveData sanitize(SaveData d) {
        d.courseIndex = Math.max(0, d.courseIndex); d.checkpoint = Math.max(0, d.checkpoint);
        if (!(d.bestHeight >= 0)) d.bestHeight = 0;
        if (!(d.playSeconds >= 0)) d.playSeconds = 0;
        return d;
    }

    /** Upgrades any older save layout in-place to {@link SaveData#CURRENT_VERSION}. */
    public static JsonValue migrate(JsonValue v) {
        int ver = v.getInt("version", 0);
        if (ver < 1) {                               // v0: {"best":h,"cp":n}
            if (v.has("best")) v.addChild("bestHeight", new JsonValue((double) v.getFloat("best")));
            if (v.has("cp")) v.addChild("checkpoint", new JsonValue((long) v.getInt("cp")));
        }
        if (ver < 2) {                               // v1 -> v2: added courseIndex, completions, bestTime, shownTips
            if (!v.has("courseIndex")) v.addChild("courseIndex", new JsonValue(0L));
            if (!v.has("completions")) v.addChild("completions", new JsonValue(0L));
        }
        // v2 -> v3: finite towers became the endless climb; there is no seed yet, so the next Play starts a fresh climb (best height and stats are kept)
        if (v.has("version")) v.remove("version");
        v.addChild("version", new JsonValue((long) SaveData.CURRENT_VERSION));
        return v;
    }
}
