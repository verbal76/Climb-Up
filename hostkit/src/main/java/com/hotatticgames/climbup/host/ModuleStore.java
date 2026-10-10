package com.hotatticgames.climbup.host;

import com.badlogic.gdx.utils.Json;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * On-disk state of the downloadable game modules:
 * <pre>
 *   state.json          small, written atomically
 *   staging.tmp/        a download in progress (discarded at the next start)
 *   staged/             a complete, verified bundle waiting for the next cold start
 *   mod/v&lt;N&gt;/          installed modules (the active one and the last known good one)
 * </pre>
 * Everything that decides which module runs happens in {@link #boot}: a staged module is activated only there (cold start), it stays unproven until the game calls
 * {@link #confirm}, and an unproven module that fails to confirm within MAX_UNCONFIRMED_LAUNCHES launches (or fails verification, or fails to load) is dropped, blacklisted, and the last
 * known good module (or, if none, the host's built-in recovery) runs instead. The host never depends on module code to do any of this.
 */
public final class ModuleStore {
    public static final int MAX_UNCONFIRMED_LAUNCHES = 2;

    public static final class State {
        public int active, lastGood, pending, tries, highest, revokeFloor, staged;
        public boolean climbInProgress;
        public String lastResult = "", rollback = "";
        public List<Integer> bad = new ArrayList<>();
    }

    /** The outcome of a cold start: which verified module directory to load, or none (run the built-in recovery). */
    public static final class Boot {
        public final File dir; public final ModuleManifest manifest; public final String note;
        /** Files this module serves in place of the APK's (empty for a module without asset overrides). */
        public final java.util.Map<String, AssetManifest.Asset> overrides;
        Boot(File d, ModuleManifest m, String n) { this(d, m, n, java.util.Collections.<String, AssetManifest.Asset>emptyMap()); }
        Boot(File d, ModuleManifest m, String n, java.util.Map<String, AssetManifest.Asset> o) { dir = d; manifest = m; note = n; overrides = o; }
        public boolean recovery() { return dir == null; }
    }

    private final File root, modDir, stagedDir, tmpDir, stateFile;
    private final TrustedKeys keys; private final HostInfo host; private final AssetStore assets;
    public State st = new State();

    public ModuleStore(File root, TrustedKeys keys, HostInfo host) {
        this.root = root; this.keys = keys; this.host = host; modDir = new File(root, "mod"); stagedDir = new File(root, "staged");
        tmpDir = new File(root, "staging.tmp"); stateFile = new File(root, "state.json"); assets = new AssetStore(root);
    }

    public AssetStore assets() { return assets; }

    /** Verifies a module directory AND that every game file it serves is in the content store (so a module is never run, or activated, with its assets missing). */
    private ModuleVerifier.Result verifyWithAssets(File dir) {
        ModuleVerifier.Result r = ModuleVerifier.verify(dir, keys, host);
        if (!r.ok()) return r;
        try { for (AssetManifest.Asset a : AssetManifest.read(dir).all()) if (!assets.has(a)) return new ModuleVerifier.Result(null, "game file missing from the store: " + a.path); }
        catch (Exception e) { return new ModuleVerifier.Result(null, "assets.json: " + e.getMessage()); }
        return r;
    }

    private java.util.Map<String, AssetManifest.Asset> overridesOf(File dir) {
        try { return AssetManifest.read(dir).byPath; } catch (Exception e) { return java.util.Collections.emptyMap(); }
    }

    // ------------------------------------------------------------ cold start

    public synchronized Boot boot() {
        root.mkdirs(); modDir.mkdirs();
        try { loadState(); } catch (Exception e) { st = new State(); st.lastResult = "state unreadable: reset"; }
        Hashing.deleteTree(tmpDir);                                   // an interrupted download is discarded
        String note = "";
        try { note = activateStaged(); } catch (Exception e) { note = "staged module ignored: " + e.getMessage(); Hashing.deleteTree(stagedDir); }
        if (st.pending != 0) {
            st.tries++;
            if (st.tries > MAX_UNCONFIRMED_LAUNCHES) dropActive("not confirmed after " + (st.tries - 1) + " launches", true);
        }
        for (int guard = 0; guard < 3 && st.active != 0; guard++) {
            ModuleVerifier.Result r = verifyWithAssets(dirOf(st.active));
            if (r.ok() && r.manifest.moduleVersion >= st.revokeFloor) {
                saveState();
                return new Boot(dirOf(st.active), r.manifest, note, overridesOf(dirOf(st.active)));
            }
            dropActive(r.ok() ? "revoked (below floor " + st.revokeFloor + ")" : "verification failed: " + r.reason, false);
        }
        saveState();
        return new Boot(null, null, note.isEmpty() ? st.rollback : note);
    }

    private String activateStaged() throws IOException {
        if (!stagedDir.isDirectory()) return "";
        ModuleVerifier.Result r = verifyWithAssets(stagedDir);
        if (!r.ok()) { Hashing.deleteTree(stagedDir); st.staged = 0; return "staged module rejected: " + r.reason; }
        ModuleManifest m = r.manifest;
        if (m.moduleVersion <= st.highest || st.bad.contains(m.moduleVersion) || m.moduleVersion < st.revokeFloor) { Hashing.deleteTree(stagedDir); st.staged = 0; return "staged v" + m.moduleVersion + " rejected: not newer / blacklisted"; }
        if (st.active != 0) {
            ModuleVerifier.Result cur = ModuleVerifier.verify(dirOf(st.active), keys, host);
            if (cur.ok()) {
                String block = climbBlock(cur.manifest, m);
                if (block != null) { st.lastResult = "v" + m.moduleVersion + " waiting: " + block; return st.lastResult; }   // stays staged; the current module keeps running
            }
        }
        File target = dirOf(m.moduleVersion);
        Hashing.deleteTree(target);
        if (!stagedDir.renameTo(target)) throw new IOException("cannot install staged module");
        for (java.io.File f : target.listFiles()) f.setReadOnly();
        if (st.pending == 0 && st.active != 0) st.lastGood = st.active;      // an unproven module never becomes the safety net
        st.active = m.moduleVersion; st.pending = m.moduleVersion; st.tries = 0; st.staged = 0;
        st.highest = Math.max(st.highest, m.moduleVersion); st.revokeFloor = Math.max(st.revokeFloor, m.revokeFloor);
        st.lastResult = "activated v" + m.moduleVersion;
        pruneOld();
        return st.lastResult;
    }

    /**
     * Whether a climb in progress forbids switching from {@code cur} to {@code cand}: the candidate must be able to read the saves (saveMin..saveSchema covers the current schema)
     * and must generate the same world (same generator ruleset) for the not-yet-seen part of the climb. null = allowed.
     */
    public String climbBlock(ModuleManifest cur, ModuleManifest cand) {
        if (!st.climbInProgress) return null;
        if (cand.saveMin > cur.saveSchema || cand.saveSchema < cur.saveSchema) return "save schema " + cur.saveSchema + " not readable by v" + cand.moduleVersion + " (needs " + cand.saveMin + ".." + cand.saveSchema + ")";
        if (cand.generatorRuleset != cur.generatorRuleset) return "generator ruleset " + cur.generatorRuleset + " -> " + cand.generatorRuleset + " would change the climb in progress";
        return null;
    }

    /**
     * @param blame true when the release itself misbehaved (crash loop, could not load): it is blacklisted and never taken again. A verification failure or a revocation says
     *              nothing against the release (disk damage, a floor raised later), so it is not blacklisted and the signed baseline can still be restored.
     */
    private void dropActive(String why, boolean blame) {
        int dropped = st.active;
        if (blame && dropped != 0 && !st.bad.contains(dropped)) st.bad.add(dropped);
        int back = st.lastGood != dropped ? st.lastGood : 0;
        if (back != 0) {
            ModuleVerifier.Result r = verifyWithAssets(dirOf(back));
            if (!r.ok() || r.manifest.moduleVersion < st.revokeFloor) back = 0;
        }
        st.active = back; st.pending = 0; st.tries = 0; if (back == 0) st.lastGood = 0;
        st.rollback = "rolled back v" + dropped + (back != 0 ? " to v" + back : " to recovery") + ": " + why;
        st.lastResult = st.rollback;
        if (dropped != 0 && dropped != back) Hashing.deleteTree(dirOf(dropped));       // the broken one only; the safety net is never touched
    }

    private void pruneOld() {
        File[] kids = modDir.listFiles(); if (kids == null) return;
        for (File k : kids) { String n = k.getName(); if (!n.equals("v" + st.active) && !n.equals("v" + st.lastGood)) Hashing.deleteTree(k); }
    }

    /** The game reached live play with the active module: it is now the safety net. */
    public synchronized void confirm() {
        if (st.pending == 0) return;
        st.lastGood = st.active; st.pending = 0; st.tries = 0; st.lastResult = "confirmed v" + st.active; st.rollback = "";
        pruneOld(); collectAssets(); saveState();
    }

    /** Keeps only the store files the active module, the safety net and a waiting staged release name (an update re-downloads nothing it already has). */
    private void collectAssets() {
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (File d : new File[]{dirOf(st.active), dirOf(st.lastGood), stagedDir, tmpDir}) {
            if (d.getName().equals("v0")) continue;
            try { for (AssetManifest.Asset a : AssetManifest.read(d).all()) keep.add(a.sha256); } catch (Exception ignored) { }
        }
        assets.gc(keep);
    }

    /** The loader could not instantiate the module (class missing, wrong interface, constructor threw): drop it now rather than after two crashes. */
    public synchronized Boot loadFailed(String why) {
        dropActive("failed to load: " + why, true); saveState();
        if (st.active == 0) return new Boot(null, null, st.rollback);
        ModuleVerifier.Result r = verifyWithAssets(dirOf(st.active));
        return r.ok() ? new Boot(dirOf(st.active), r.manifest, st.rollback, overridesOf(dirOf(st.active))) : new Boot(null, null, st.rollback);
    }

    /**
     * Reinstalls the signed baseline module that ships inside the APK when nothing else is runnable (first install, or every installed module failed). It goes through the same
     * verification as any download, is refused if it was blacklisted or revoked, and starts out confirmed because it is the safety net itself. Returns null on success.
     */
    public synchronized String installBaseline(File bundleDir) {
        if (st.active != 0) return "not needed";
        try {
            ModuleVerifier.Result r = ModuleVerifier.verify(bundleDir, keys, host);
            if (!r.ok()) return r.reason;
            ModuleManifest m = r.manifest;
            if (st.bad.contains(m.moduleVersion) || m.moduleVersion < st.revokeFloor) return "baseline v" + m.moduleVersion + " is blacklisted or revoked";
            File target = dirOf(m.moduleVersion); Hashing.deleteTree(target); target.mkdirs();
            for (String n : bundleDir.list()) { File dst = new File(target, n); Files.copy(new File(bundleDir, n).toPath(), dst.toPath()); dst.setReadOnly(); }
            st.active = m.moduleVersion; st.lastGood = m.moduleVersion; st.pending = 0; st.tries = 0; st.highest = Math.max(st.highest, m.moduleVersion);
            st.revokeFloor = Math.max(st.revokeFloor, m.revokeFloor); st.lastResult = "installed baseline v" + m.moduleVersion;
            pruneOld(); saveState();
            return null;
        } catch (Exception e) { return "baseline install failed: " + e.getMessage(); }
    }

    public synchronized void setClimbInProgress(boolean v) { if (st.climbInProgress != v) { st.climbInProgress = v; saveState(); } }

    // ------------------------------------------------------------ staging (download side)

    /** Directory the downloader fills; discarded at the next start unless {@link #commitStaging} succeeds first. */
    public synchronized File openStaging() { Hashing.deleteTree(tmpDir); tmpDir.mkdirs(); return tmpDir; }

    /**
     * Verifies the complete bundle in staging.tmp and, only if it passes and is newer than everything this install has ever activated or staged, publishes it to staged/
     * with one rename. Returns null on success or the reason it was refused.
     */
    public synchronized String commitStaging() {
        try {
            ModuleVerifier.Result r = verifyWithAssets(tmpDir);
            if (!r.ok()) { Hashing.deleteTree(tmpDir); return r.reason; }
            ModuleManifest m = r.manifest;
            if (m.moduleVersion <= Math.max(st.highest, st.staged) || st.bad.contains(m.moduleVersion) || m.moduleVersion < st.revokeFloor) { Hashing.deleteTree(tmpDir); return "v" + m.moduleVersion + " is not newer than v" + Math.max(st.highest, st.staged); }
            Hashing.deleteTree(stagedDir);
            if (!tmpDir.renameTo(stagedDir)) return "cannot publish staged module";
            st.staged = m.moduleVersion; saveState();
            return null;
        } catch (Exception e) { return "commit failed: " + e.getMessage(); }
    }

    /**
     * Cheap pre-download gate on the (small) manifest: signature, identity, compatibility and anti-rollback are decided before a single module byte is fetched.
     * Returns null if the release is worth downloading, otherwise the reason. The full check still runs on the downloaded files in {@link #commitStaging}.
     */
    public synchronized String preflight(byte[] manifestBytes, byte[] sigBytes) {
        String kid = ModuleManifest.peekKeyId(manifestBytes);
        if (kid == null || !keys.verify(kid, manifestBytes, sigBytes)) return "manifest signature invalid";
        ModuleManifest m;
        try { m = ModuleManifest.parse(manifestBytes); } catch (IllegalArgumentException e) { return "manifest: " + e.getMessage(); }
        if (!m.app.equals(host.app)) return "built for another app";
        if (!m.channel.equals(host.channel)) return "wrong channel";
        if (m.interfaceVersion != host.interfaceVersion) return "needs a different interface (" + m.interfaceVersion + ")";
        if (host.hostLevel < m.hostMin || host.hostLevel > m.hostMax) return "needs a different host (" + m.hostMin + ".." + m.hostMax + ")";
        if (m.moduleVersion <= Math.max(st.highest, st.staged)) return "up to date (v" + Math.max(st.highest, st.staged) + ")";
        if (st.bad.contains(m.moduleVersion) || m.moduleVersion < st.revokeFloor) return "v" + m.moduleVersion + " was rolled back or revoked";
        return null;
    }

    public synchronized int stagedVersion() { return st.staged; }

    // ------------------------------------------------------------ helpers

    public File dirOf(int version) { return new File(modDir, "v" + version); }

    private void loadState() throws IOException {
        if (!stateFile.isFile()) { st = new State(); return; }
        st = new Json().fromJson(State.class, new String(Files.readAllBytes(stateFile.toPath()), StandardCharsets.UTF_8));
        if (st == null) st = new State();
        if (st.bad == null) st.bad = new ArrayList<>();
        if (st.lastResult == null) st.lastResult = "";
        if (st.rollback == null) st.rollback = "";
    }

    private void saveState() {
        try {
            root.mkdirs(); File tmp = new File(root, "state.json.tmp");
            Files.write(tmp.toPath(), new Json().toJson(st).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignored) { /* advisory; the next start re-derives a safe state from the verified directories */ }
    }
}
