package com.hotatticgames.climbup.desktop.input;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Everything the Windows build persists on its own (separate from the shared save and settings, which stay byte-compatible with Android): bindings, input policy, dead zone, display. */
public final class DesktopConfig {
    public enum InputMode { AUTO, KEYBOARD, CONTROLLER;
        public String label() { return this == AUTO ? "AUTO DETECT" : this == KEYBOARD ? "KEYBOARD + MOUSE" : "CONTROLLER"; } }
    public enum DisplayMode { WINDOWED, FULLSCREEN }

    public static final int VERSION = 1;
    public static final float DEADZONE_MIN = 0.05f, DEADZONE_MAX = 0.60f, DEADZONE_DEFAULT = 0.25f;

    public InputMode inputMode = InputMode.AUTO;
    public float deadzone = DEADZONE_DEFAULT;
    public DisplayMode display = DisplayMode.WINDOWED;
    public int width = 1280, height = 720;
    public final Bindings<Integer> keyboard = Defaults.keyboard();
    /** Used for any controller without a profile of its own. */
    public final Bindings<Ctl> padDefault = Defaults.pad();
    /** Profiles of controllers the player customised, by sanitized stable id. */
    public final Map<String, Bindings<Ctl>> padProfiles = new LinkedHashMap<>();

    public static String padKey(String uniqueId) {
        String s = uniqueId == null ? "" : uniqueId.replaceAll("[^A-Za-z0-9_-]", "_");
        return s.length() > 64 ? s.substring(0, 64) : s;
    }

    /** The bindings in force for this controller (its own profile, else the shared default). */
    public Bindings<Ctl> padFor(String uniqueId) { Bindings<Ctl> p = padProfiles.get(padKey(uniqueId)); return p != null ? p : padDefault; }
    /** A copy to edit for this controller; call {@link #setPadProfile} to commit. */
    public Bindings<Ctl> editablePad(String uniqueId) { return padFor(uniqueId).copy(); }
    public void setPadProfile(String uniqueId, Bindings<Ctl> b) { if (b.valid()) padProfiles.put(padKey(uniqueId), b.copy()); }
    public void restorePad(String uniqueId) { padProfiles.remove(padKey(uniqueId)); }
    public void restoreKeyboard() { keyboard.copyFrom(Defaults.keyboard()); }
    public void setDeadzone(float v) { deadzone = Math.max(DEADZONE_MIN, Math.min(DEADZONE_MAX, Math.round(v * 20f) / 20f)); }

    // ------------------------------------------------------------------ text format (key=value lines)

    public String serialize() {
        StringBuilder sb = new StringBuilder("# Upwardly desktop settings\nversion=").append(VERSION).append('\n');
        sb.append("inputMode=").append(inputMode).append("\ndeadzone=").append(String.format(Locale.ROOT, "%.2f", deadzone)).append('\n');
        sb.append("display=").append(display).append("\nresolution=").append(width).append('x').append(height).append('\n');
        for (Act a : Act.values()) sb.append("kb.").append(a).append('=').append(joinKeys(keyboard.get(a))).append('\n');
        for (Act a : Act.values()) sb.append("pad.default.").append(a).append('=').append(joinCtl(padDefault.get(a))).append('\n');
        for (Map.Entry<String, Bindings<Ctl>> e : padProfiles.entrySet())
            for (Act a : Act.values()) sb.append("pad.id.").append(e.getKey()).append('.').append(a).append('=').append(joinCtl(e.getValue().get(a))).append('\n');
        return sb.toString();
    }

    private static String joinKeys(List<Integer> l) { StringBuilder sb = new StringBuilder(); for (int k : l) { String t = KeyCodes.token(k); if (t == null) continue; if (sb.length() > 0) sb.append(','); sb.append(t); } return sb.toString(); }
    private static String joinCtl(List<Ctl> l) { StringBuilder sb = new StringBuilder(); for (Ctl c : l) { if (sb.length() > 0) sb.append(','); sb.append(c.name()); } return sb.toString(); }

    /** Parses leniently: unknown keys and bad tokens are skipped, an action that ends with no valid binding keeps its default. Returns null only if the text is not a config at all. */
    public static DesktopConfig parse(String text) {
        if (text == null || !text.contains("version=")) return null;
        DesktopConfig c = new DesktopConfig();
        Map<String, Map<Act, List<Ctl>>> profiles = new LinkedHashMap<>();
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq < 1) continue;
            String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
            try {
                if (k.equals("inputMode")) c.inputMode = InputMode.valueOf(v);
                else if (k.equals("deadzone")) c.setDeadzone(Float.parseFloat(v));
                else if (k.equals("display")) c.display = DisplayMode.valueOf(v);
                else if (k.equals("resolution")) { String[] wh = v.split("x"); int w = Integer.parseInt(wh[0]), h = Integer.parseInt(wh[1]); if (w >= 640 && h >= 360 && w <= 16384 && h <= 16384) { c.width = w; c.height = h; } }
                else if (k.startsWith("kb.")) { Act a = Act.valueOf(k.substring(3)); List<Integer> l = new ArrayList<>(); for (String t : v.split(",")) { int code = KeyCodes.parse(t.trim()); if (code > 0 && !KeyCodes.reserved(code) && !l.contains(code)) l.add(code); } if (!l.isEmpty()) c.keyboard.set(a, l); }
                else if (k.startsWith("pad.default.")) { Act a = Act.valueOf(k.substring(12)); List<Ctl> l = ctls(v); if (!l.isEmpty()) c.padDefault.set(a, l); }
                else if (k.startsWith("pad.id.")) { String rest = k.substring(7); int dot = rest.lastIndexOf('.'); Act a = Act.valueOf(rest.substring(dot + 1)); List<Ctl> l = ctls(v); if (!l.isEmpty()) profiles.computeIfAbsent(rest.substring(0, dot), x -> new LinkedHashMap<>()).put(a, l); }
            } catch (RuntimeException ignored) { /* a bad line never spoils the rest */ }
        }
        for (Map.Entry<String, Map<Act, List<Ctl>>> e : profiles.entrySet()) {
            Bindings<Ctl> b = Defaults.pad();
            for (Map.Entry<Act, List<Ctl>> a : e.getValue().entrySet()) b.set(a.getKey(), a.getValue());
            c.padProfiles.put(e.getKey(), b);
        }
        c.repairConflicts();
        return c;
    }

    private static List<Ctl> ctls(String v) { List<Ctl> l = new ArrayList<>(); for (String t : v.split(",")) { try { Ctl c = Ctl.valueOf(t.trim()); if (!l.contains(c)) l.add(c); } catch (IllegalArgumentException ignored) { } } return l; }

    /** A hand-edited or damaged file may leave the same input on two actions of one context; the later action then falls back to its default so the file always behaves. */
    private void repairConflicts() {
        Bindings<Integer> d = Defaults.keyboard();
        for (Act a : Act.values()) for (Act o : Act.values()) if (o.ordinal() > a.ordinal() && o.sameContext(a)) for (int k : new ArrayList<>(keyboard.get(a))) if (keyboard.get(o).contains(k) && keyboard.get(o).size() > 1) keyboard.get(o).remove((Integer) k);
        for (Act a : Act.values()) if (keyboard.get(a).isEmpty()) keyboard.set(a, d.get(a));
    }

    // ------------------------------------------------------------------ files

    /** Writes next to the real file, keeps the previous good file as .bak, then renames over it. A failure leaves the previous file untouched. Returns false on failure. */
    public boolean save(File file) {
        File tmp = new File(file.getPath() + ".tmp"), bak = new File(file.getPath() + ".bak");
        try {
            File dir = file.getAbsoluteFile().getParentFile(); if (dir != null) dir.mkdirs();
            Files.write(tmp.toPath(), serialize().getBytes(StandardCharsets.UTF_8));
            if (file.exists()) Files.copy(file.toPath(), bak.toPath(), StandardCopyOption.REPLACE_EXISTING);
            try { Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (IOException | UnsupportedOperationException e) { Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING); }
            return true;
        } catch (IOException e) { tmp.delete(); return false; }
    }

    /** Loads the file, else its backup, else defaults. A corrupt file is kept as .corrupt. */
    public static DesktopConfig load(File file) {
        DesktopConfig c = read(file);
        if (c == null && file.exists()) { try { Files.move(file.toPath(), new File(file.getPath() + ".corrupt").toPath(), StandardCopyOption.REPLACE_EXISTING); } catch (IOException ignored) { } }
        if (c == null) c = read(new File(file.getPath() + ".bak"));
        return c != null ? c : new DesktopConfig();
    }

    private static DesktopConfig read(File f) {
        if (!f.isFile()) return null;
        try { return parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)); } catch (IOException e) { return null; }
    }
}
