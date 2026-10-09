package com.hotatticgames.climbup.ota;

import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.hotatticgames.climbup.sim.Tuning;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * On-disk OTA state: ota/active, ota/previous, ota/staging plus ota/state.json.
 * A staged payload is applied only at the next cold start ({@link #startup()}); an applied payload stays "pending" until the game calls
 * {@link #confirm()} (live play reached). A pending payload that is not confirmed within MAX_UNCONFIRMED_LAUNCHES launches (crash or hang loop),
 * or whose files fail verification, is rolled back to the previous payload or the bundled content and its version is blacklisted.
 */
public final class OtaStore {
    public static final class State {
        public int active, previous, pending, tries, highestApplied;
        public String activeSha = "", previousSha = "";
        public long lastCheck;
        public String lastResult = "";
        public List<Integer> bad = new ArrayList<>();
    }

    private final File root, activeDir, previousDir, stagingDir, tmpDir, stateFile;
    private final int bundledVersion;
    public State st = new State();
    public String note = "";

    public OtaStore(File root) { this(root, OtaConfig.BUNDLED_CONTENT_VERSION); }
    /** @param bundledVersion the content version compiled into this build; manifests at or below it are already current. */
    public OtaStore(File root, int bundledVersion) {
        this.root = root; this.bundledVersion = bundledVersion; activeDir = new File(root, "active"); previousDir = new File(root, "previous");
        stagingDir = new File(root, "staging"); tmpDir = new File(root, "staging.tmp"); stateFile = new File(root, "state.json");
    }

    // ------------------------------------------------------------------ cold start

    /** Applies a staged payload (if any), enforces rollback, and returns the tuning JSON to use, or null for the bundled content. */
    public synchronized String startup() {
        try { root.mkdirs(); loadState(); } catch (Exception e) { resetAll("state unreadable"); }
        try {
            if (stagingDir.isDirectory() && new File(stagingDir, "READY").isFile()) applyStaged();
        } catch (Exception e) { note = "apply failed: " + e.getMessage(); deleteTree(stagingDir); }
        deleteTree(tmpDir);      // an interrupted download is discarded
        if (st.pending != 0) {
            st.tries++;
            if (st.tries > OtaConfig.MAX_UNCONFIRMED_LAUNCHES) rollback("not confirmed after " + (st.tries - 1) + " launches");
        }
        String json = null;
        if (st.active != 0) {
            json = readVerified(activeDir, st.activeSha);
            if (json == null) { rollback("active payload failed verification"); if (st.active != 0) json = readVerified(activeDir, st.activeSha); }
        }
        saveStateQuiet();
        return json;
    }

    private void applyStaged() throws IOException {
        String ready = new String(Files.readAllBytes(new File(stagingDir, "READY").toPath()), StandardCharsets.UTF_8).trim();
        String[] p = ready.split(" ");
        int v = Integer.parseInt(p[0]); String sha = p[1];
        if (v <= st.highestApplied || st.bad.contains(v) || !sha.equals(sha256(new File(stagingDir, "tuning.json")))) { deleteTree(stagingDir); return; }
        deleteTree(previousDir);
        boolean hadActive = activeDir.isDirectory();
        if (hadActive && !activeDir.renameTo(previousDir)) throw new IOException("cannot move active");
        if (!stagingDir.renameTo(activeDir)) { if (hadActive) previousDir.renameTo(activeDir); throw new IOException("cannot move staging"); }
        new File(activeDir, "READY").delete();
        st.previous = st.active; st.previousSha = st.activeSha;
        st.active = v; st.activeSha = sha; st.pending = v; st.tries = 0; st.highestApplied = Math.max(st.highestApplied, v);
        note = "applied v" + v;
    }

    /** Drops the pending payload: back to the previous one (if any) or the bundled content; the dropped version is never tried again. */
    private void rollback(String why) {
        if (st.pending == 0 && st.active == 0) return;
        int dropped = st.active;
        if (!st.bad.contains(dropped)) st.bad.add(dropped);
        deleteTree(activeDir);
        if (st.previous != 0 && previousDir.isDirectory() && previousDir.renameTo(activeDir)) { st.active = st.previous; st.activeSha = st.previousSha; }
        else { st.active = 0; st.activeSha = ""; }
        st.previous = 0; st.previousSha = ""; st.pending = 0; st.tries = 0;
        note = "rolled back v" + dropped + ": " + why;
        st.lastResult = note;
    }

    /** The game reached live play with the current content: the pending payload is now trusted. */
    public synchronized void confirm() {
        if (st.pending == 0) return;
        st.pending = 0; st.tries = 0; st.previous = 0; st.previousSha = ""; deleteTree(previousDir);
        saveStateQuiet();
    }

    // ------------------------------------------------------------------ staging (called from the background check)

    public synchronized boolean shouldCheck(long now) { return now - st.lastCheck >= OtaConfig.CHECK_INTERVAL_MS || st.lastCheck > now; }

    public synchronized void recordCheck(long now, String result) { st.lastCheck = now; st.lastResult = result; saveStateQuiet(); }

    /** Anti-rollback: a version is taken only if it is newer than everything ever applied or already staged, and was not rolled back before. */
    public synchronized boolean acceptable(int version) { return version > Math.max(st.highestApplied, bundledVersion) && version > stagedVersion() && !st.bad.contains(version); }

    /** The newest content version this install has, applied or downloaded. */
    public synchronized int currentVersion() { return Math.max(Math.max(st.highestApplied, bundledVersion), stagedVersion()); }

    /** Names the first layout constant (OtaConfig.FROZEN_TUNING) in which a payload differs from the bundled tuning, or null. */
    public static String frozenMismatch(Tuning bundled, Tuning candidate) {
        for (String f : OtaConfig.FROZEN_TUNING) {
            try {
                java.lang.reflect.Field fd = Tuning.class.getField(f);
                if (!String.valueOf(fd.get(bundled)).equals(String.valueOf(fd.get(candidate)))) return f;
            } catch (Exception e) { return f; }
        }
        return null;
    }

    /** Writes the verified payload into staging atomically (temp dir, then rename). Replaces any older staged payload. */
    public synchronized void stage(int version, String tuningJson) throws IOException {
        deleteTree(tmpDir); tmpDir.mkdirs();
        File f = new File(tmpDir, "tuning.json");
        Files.write(f.toPath(), tuningJson.getBytes(StandardCharsets.UTF_8));
        String sha = sha256(f);
        Files.write(new File(tmpDir, "READY").toPath(), (version + " " + sha).getBytes(StandardCharsets.UTF_8));
        deleteTree(stagingDir);
        if (!tmpDir.renameTo(stagingDir)) throw new IOException("cannot publish staging");
    }

    public synchronized int stagedVersion() {
        try { return Integer.parseInt(new String(Files.readAllBytes(new File(stagingDir, "READY").toPath()), StandardCharsets.UTF_8).trim().split(" ")[0]); } catch (Exception e) { return 0; }
    }

    public synchronized String describe() {
        String s = (st.active == 0 ? "V" + bundledVersion + " (BUNDLED)" : "V" + st.active + (st.pending != 0 ? " (NEW, UNCONFIRMED)" : "")) + ", RUNTIME " + OtaConfig.RUNTIME;
        int sv = stagedVersion();
        if (sv != 0) s += ", V" + sv + " DOWNLOADED - APPLIES ON NEXT START";
        return s;
    }

    // ------------------------------------------------------------------ helpers

    private String readVerified(File dir, String sha) {
        try {
            File f = new File(dir, "tuning.json");
            if (!f.isFile() || !sha.equals(sha256(f))) return null;
            String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            return validate(json) == null ? json : null;
        } catch (Exception e) { return null; }
    }

    /** Returns null when the JSON parses into sane tuning numbers, otherwise a reason. */
    public static String validate(String json) {
        try { new JsonReader().parse(json); return Tuning.parse(json).validate(); } catch (Exception e) { return "unparseable: " + e.getMessage(); }
    }

    private void loadState() throws IOException {
        if (!stateFile.isFile()) { st = new State(); return; }
        st = new Json().fromJson(State.class, new String(Files.readAllBytes(stateFile.toPath()), StandardCharsets.UTF_8));
        if (st == null) st = new State();
        if (st.bad == null) st.bad = new ArrayList<>();
    }

    private void saveStateQuiet() {
        try {
            root.mkdirs();
            File tmp = new File(root, "state.json.tmp");
            Files.write(tmp.toPath(), new Json().toJson(st).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignored) { /* state is advisory; the next start re-derives a safe one */ }
    }

    private void resetAll(String why) { deleteTree(activeDir); deleteTree(previousDir); deleteTree(stagingDir); deleteTree(tmpDir); stateFile.delete(); st = new State(); note = "reset: " + why; }

    public static String sha256(File f) throws IOException { return sha256(Files.readAllBytes(f.toPath())); }

    public static String sha256(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(); for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
