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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The named players. Each player has a folder (players/p1, players/p2, ...) holding their own save.json, run.json and history.bin, so their runs never touch each other.
 * Settings are shared and stay in the data folder itself. players.json lists the players in order and remembers the one used last.
 * Pure java.io plus the JSON reader, so it is unit-testable headlessly. If players.json is missing or damaged it is rebuilt from the folders on disk; nothing is ever lost to a bad index.
 */
public final class Profiles {
    public static final int MAX_PLAYERS = 6, MAX_NAME = 10;
    public static final String FIRST_NAME = "PLAYER 1";
    /** The characters the name picker offers (also the only ones a name may contain). */
    public static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 ";
    private static final String INDEX = "players.json";

    public static final class Entry {
        public final int id; public String name;
        Entry(int id, String name) { this.id = id; this.name = name; }
    }

    private final File root;
    private final List<Entry> list = new ArrayList<>();
    private int lastId;
    /** True if players.json was damaged and the list was rebuilt from the folders (the damaged file is kept as players.json.corrupt). */
    public boolean rebuilt;
    /** True if the single pre-players save was moved into the first player's folder by this load. */
    public boolean migrated;

    private Profiles(File root) { this.root = root; }

    // ---- reading

    public List<Entry> players() { return Collections.unmodifiableList(list); }
    public int count() { return list.size(); }
    public boolean canAdd() { return list.size() < MAX_PLAYERS; }
    public int lastId() { return lastId; }
    public Entry get(int id) { for (Entry e : list) if (e.id == id) return e; return null; }
    public Entry last() { Entry e = get(lastId); return e != null ? e : list.get(0); }
    public File dirOf(int id) { return new File(new File(root, "players"), "p" + id); }

    /** The name as the picker would build it: capitals, digits and single spaces only, no leading or trailing space, at most {@value #MAX_NAME} characters. "" if nothing is left. */
    public static String cleanName(String raw) {
        if (raw == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < raw.length() && b.length() < MAX_NAME; i++) {
            char c = Character.toUpperCase(raw.charAt(i));
            if (LETTERS.indexOf(c) < 0) continue;
            if (c == ' ' && (b.length() == 0 || b.charAt(b.length() - 1) == ' ')) continue;
            b.append(c);
        }
        return b.toString().trim();
    }

    public boolean nameTaken(String name, int exceptId) {
        String n = cleanName(name);
        for (Entry e : list) if (e.id != exceptId && e.name.equals(n)) return true;
        return false;
    }

    // ---- changing

    /** Makes a new, empty player. Returns null if the name is empty or already used, or the list is full. The caller then switches to them. */
    public Entry create(String rawName) {
        String name = cleanName(rawName);
        if (name.isEmpty() || nameTaken(name, -1) || !canAdd()) return null;
        return add(name);
    }

    private Entry add(String name) {
        int id = 1; for (Entry e : list) id = Math.max(id, e.id + 1);
        File dir = dirOf(id); dir.mkdirs();
        SaveData d = new SaveData(); d.name = name;
        new SaveStore(dir).saveGame(d);
        Entry e = new Entry(id, name); list.add(e);
        if (lastId == 0) lastId = id;
        writeIndex();
        return e;
    }

    /** Remembers who played last (the next start opens them). Unknown ids are ignored. */
    public void select(int id) { if (get(id) != null && lastId != id) { lastId = id; writeIndex(); } }

    /** Removes the player and everything they saved. If that was the last player, a fresh empty one takes their place so there is always someone to play as. Returns false for an unknown id. */
    public boolean delete(int id) {
        Entry e = get(id); if (e == null) return false;
        list.remove(e);
        deleteTree(dirOf(id));
        if (list.isEmpty()) { lastId = 0; add(FIRST_NAME); }
        else if (lastId == id) { lastId = list.get(0).id; writeIndex(); }
        else writeIndex();
        return true;
    }

    /** Removes every player (ERASE) and starts again with an empty first player. */
    public void deleteAll() {
        deleteTree(new File(root, "players")); list.clear(); lastId = 0;
        add(FIRST_NAME);
    }

    // ---- loading (with migration of the pre-players single save)

    public static Profiles load(File root) {
        root.mkdirs();
        Profiles p = new Profiles(root);
        boolean indexFile = new File(root, INDEX).exists();
        if (indexFile && !p.readIndex()) { p.rebuilt = true; backupCorrupt(root); }
        if (p.list.isEmpty() && !indexFile && p.hasLegacy()) p.migrateLegacy();      // never had an index: the old files are the truth, even if an earlier move left a half-copied folder
        if (p.list.isEmpty()) p.scan();
        if (p.list.isEmpty()) {
            if (p.hasLegacy()) p.migrateLegacy(); else p.add(FIRST_NAME);
        } else if (!indexFile || p.rebuilt) p.writeIndex();
        if (p.get(p.lastId) == null) p.lastId = p.list.get(0).id;
        p.dropLeftoverLegacy();
        if (p.migratedFlag && !p.migrated) { p.migratedFlag = false; p.writeIndex(); }      // the clean-up has had its one chance after the move
        return p;
    }

    private static final String[] LEGACY_FILES = {"save.json", "run.json", "history.bin"};

    private boolean hasLegacy() { for (String n : LEGACY_FILES) if (new File(root, n).exists()) return true; return new File(root, "save.json.corrupt").exists(); }

    /** The single save becomes player 1: copy first, write the index, delete the originals last, so a crash at any point leaves either the old layout or the new one complete. */
    private void migrateLegacy() {
        File dir = dirOf(1); dir.mkdirs();
        for (String n : LEGACY_FILES) copy(new File(root, n), new File(dir, n));
        copy(new File(root, "save.json.corrupt"), new File(dir, "save.json.corrupt"));
        // the player's name goes into the save so a rebuilt index can find it again
        SaveStore st = new SaveStore(dir);
        SaveData d = st.loadGame(); d.name = FIRST_NAME; st.saveGame(d);
        list.add(new Entry(1, FIRST_NAME)); lastId = 1; migrated = true;
        writeIndex();
        dropLeftoverLegacy();
    }

    /** Originals left behind by a migration that was interrupted after the index was written. Only ever removed when the index says the migration happened. */
    private void dropLeftoverLegacy() {
        if (!migratedFlag) return;
        for (String n : LEGACY_FILES) new File(root, n).delete();
        new File(root, "save.json.corrupt").delete();
        new File(root, "save.json.tmp").delete(); new File(root, "run.json.tmp").delete(); new File(root, "history.bin.tmp").delete();
    }
    private boolean migratedFlag;

    private static void copy(File from, File to) {
        if (!from.exists()) return;
        try { Files.copy(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING); } catch (IOException ignored) { }
    }

    // ---- the index file

    private boolean readIndex() {
        try {
            String t = new String(Files.readAllBytes(new File(root, INDEX).toPath()), StandardCharsets.UTF_8);
            JsonValue v = new JsonReader().parse(t);
            if (v == null || !v.has("players") || v.get("players").type() != JsonValue.ValueType.array) return false;
            for (JsonValue e = v.get("players").child; e != null; e = e.next) {
                int id = e.getInt("id", 0); String name = cleanName(e.getString("name", ""));
                if (id <= 0 || get(id) != null || name.isEmpty() || list.size() >= MAX_PLAYERS) continue;
                list.add(new Entry(id, name));
                File dir = dirOf(id);
                if (!dir.isDirectory()) { dir.mkdirs(); SaveData d = new SaveData(); d.name = name; new SaveStore(dir).saveGame(d); }   // the folder is gone: an empty player, not a crash
            }
            lastId = v.getInt("lastId", 0);
            migratedFlag = v.getBoolean("migrated", false);
            return !list.isEmpty();
        } catch (Exception e) { list.clear(); return false; }
    }

    /** Rebuilds the list from the player folders (the names come from their saves). */
    private void scan() {
        File[] dirs = new File(root, "players").listFiles();
        if (dirs == null) return;
        List<Integer> ids = new ArrayList<>();
        for (File f : dirs) {
            String n = f.getName();
            if (f.isDirectory() && n.matches("p[0-9]{1,6}")) { int id = Integer.parseInt(n.substring(1)); if (id > 0) ids.add(id); }
        }
        Collections.sort(ids);
        for (int id : ids) {
            if (list.size() >= MAX_PLAYERS) break;
            String name = "";
            try { name = cleanName(new SaveStore(dirOf(id)).loadGame().name); } catch (Exception ignored) { }
            if (name.isEmpty() || nameTaken(name, -1)) name = "PLAYER " + id;
            list.add(new Entry(id, name));
        }
        if (!list.isEmpty() && lastId == 0) lastId = list.get(0).id;
    }

    private static void backupCorrupt(File root) {
        try { Files.move(new File(root, INDEX).toPath(), new File(root, INDEX + ".corrupt").toPath(), StandardCopyOption.REPLACE_EXISTING); } catch (IOException ignored) { new File(root, INDEX).delete(); }
    }

    private void writeIndex() {
        if (migrated) migratedFlag = true;
        Json j = new Json(JsonWriter.OutputType.json);
        StringBuilder b = new StringBuilder("{\"version\":1,\"lastId\":" + lastId + ",\"migrated\":" + migratedFlag + ",\"players\":[");
        for (int i = 0; i < list.size(); i++) b.append(i > 0 ? "," : "").append("{\"id\":").append(list.get(i).id).append(",\"name\":").append(j.toJson(list.get(i).name)).append("}");
        b.append("]}");
        try { new SaveStore(root).writeAtomic(INDEX, b.toString()); } catch (IOException ignored) { }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
