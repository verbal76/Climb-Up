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
        public int active, lastGood, pending, tries, highest, revokeFloor, staged, snapshotFor;
        public String restoring = "";
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

    /**
     * Test seam for crash-point fault injection: {@link #at} is called between the durable steps of boot / activation / drop / confirm / staging / state writes. Production never sets one;
     * a test throws an {@link Error} from it to model the process dying exactly there (nothing the store catches is an Error), then "restarts" with a new store on the same directory.
     */
    public interface Fault { void at(String point); }
    private Fault fault = new Fault() { @Override public void at(String point) { } };
    public ModuleStore withFault(Fault f) { this.fault = f; return this; }
    private void at(String point) { fault.at(point); }

    private final File root, modDir, stagedDir, tmpDir, stateFile, stateBak, floorFile;
    private final TrustedKeys keys; private final HostInfo host; private final AssetStore assets; private SaveGuard guard;
    public State st = new State();

    public ModuleStore(File root, TrustedKeys keys, HostInfo host) {
        this.root = root; this.keys = keys; this.host = host; modDir = new File(root, "mod"); stagedDir = new File(root, "staged");
        tmpDir = new File(root, "staging.tmp"); stateFile = new File(root, "state.json"); stateBak = new File(root, "state.json.bak"); floorFile = new File(root, "floor"); assets = new AssetStore(root);
    }

    public AssetStore assets() { return assets; }

    /** Optional: protects saves across a module that raises the save-schema version (see {@link SaveGuard}). */
    public ModuleStore withSaveGuard(SaveGuard g) { this.guard = g; return this; }

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
        forgave = false;
        root.mkdirs(); modDir.mkdirs();
        try { loadState(); } catch (Exception e) { st = derive(); }
        int[] floor = readFloor(); st.highest = Math.max(st.highest, floor[0]); st.revokeFloor = Math.max(st.revokeFloor, floor[1]);
        reconcileStaged();
        at("boot.afterLoad");
        Hashing.deleteTree(tmpDir);                                   // an interrupted download is discarded
        String note = "";
        if (!st.restoring.isEmpty() && guard != null) { if (guard.restore(st.restoring)) { guard.discard(st.restoring); st.restoring = ""; } }      // the process died half-way through putting saves back: do it again
        at("boot.afterRestore");
        try { note = activateStaged(); } catch (Exception e) { note = "staged module ignored: " + e.getMessage(); Hashing.deleteTree(stagedDir); }
        at("boot.afterActivate");
        if (st.pending != 0) {
            st.tries++;
            if (st.tries > MAX_UNCONFIRMED_LAUNCHES) dropActive("not confirmed after " + (st.tries - 1) + " launches", true);
        }
        for (int guard = 0; guard < 3 && st.active != 0; guard++) {
            ModuleVerifier.Result r = verifyWithAssets(dirOf(st.active));
            if (r.ok() && r.manifest.moduleVersion >= st.revokeFloor) {
                if (this.guard != null && st.pending == 0 && st.snapshotFor == 0 && st.restoring.isEmpty()) this.guard.discard("v" + st.active);      // a confirmed module's pre-update snapshot is obsolete (the process may have died before discarding it)
                saveState();
                return new Boot(dirOf(st.active), r.manifest, note, overridesOf(dirOf(st.active)));
            }
            dropActive(r.ok() ? "revoked (below floor " + st.revokeFloor + ")" : "verification failed: " + r.reason, false);
        }
        saveState();
        return new Boot(null, null, note.isEmpty() ? st.rollback : note);
    }

    /**
     * The state says a release is staged but staged/ is gone: the process died after activation published it as mod/vN and before the state recording that was written. Put it back so it goes
     * through the normal activation checks again; otherwise "staged" would forever claim a version that exists nowhere and the update could never be delivered again.
     */
    private void reconcileStaged() {
        if (stagedDir.exists()) return;
        int cand = st.staged;
        if (cand == 0) {                                         // the state never recorded it either: any module directory newer than everything ever accepted can only be an interrupted activation
            File[] kids = modDir.listFiles();
            if (kids != null) for (File k : kids) {
                String n = k.getName();
                if (!n.matches("v[0-9]{1,9}")) continue;
                int v = Integer.parseInt(n.substring(1));
                if (v > st.highest && v != st.active && v != st.lastGood && !st.bad.contains(v) && v > cand) cand = v;
            }
        }
        File orphan = dirOf(cand);
        if (cand != 0 && cand != st.active && cand != st.lastGood && orphan.isDirectory() && orphan.renameTo(stagedDir)) { at("reconcile.afterRename"); return; }
        st.staged = 0;
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
        int bumpFrom = 0;
        if (st.active != 0 && guard != null) {
            ModuleVerifier.Result cur = ModuleVerifier.verify(dirOf(st.active), keys, host);
            if (cur.ok() && m.saveSchema > cur.manifest.saveSchema) {
                if (!guard.snapshot("v" + m.moduleVersion)) { st.lastResult = "v" + m.moduleVersion + " waiting: could not snapshot the saves before a save-schema change"; return st.lastResult; }
                bumpFrom = m.moduleVersion;
            }
        }
        at("activate.beforeRename");
        File target = dirOf(m.moduleVersion);
        Hashing.deleteTree(target);
        if (!stagedDir.renameTo(target)) throw new IOException("cannot install staged module");
        at("activate.afterRename");
        st.snapshotFor = bumpFrom;
        for (java.io.File f : target.listFiles()) f.setReadOnly();
        if (st.pending == 0 && st.active != 0) st.lastGood = st.active;      // an unproven module never becomes the safety net
        st.active = m.moduleVersion; st.pending = m.moduleVersion; st.tries = 0; st.staged = 0;
        st.highest = Math.max(st.highest, m.moduleVersion); st.revokeFloor = Math.max(st.revokeFloor, m.revokeFloor);
        st.lastResult = "activated v" + m.moduleVersion;
        saveState(); at("activate.afterSave");                       // record the new truth BEFORE anything is deleted: pruning first could remove a module the old state still names
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
        boolean restoreSaves = dropped != 0 && st.snapshotFor == dropped && st.pending == dropped && guard != null;
        st.active = back; st.pending = 0; st.tries = 0; if (back == 0) st.lastGood = 0;
        st.rollback = "rolled back v" + dropped + (back != 0 ? " to v" + back : " to recovery") + ": " + why;
        at("drop.afterMutate");
        if (restoreSaves) {
            st.restoring = "v" + dropped; st.snapshotFor = 0; saveState();                  // recorded first so a crash mid-restore is finished at the next start
            at("drop.restoreRecorded");
            if (guard.restore(st.restoring)) { guard.discard(st.restoring); st.restoring = ""; st.rollback += "; saves restored to how they were before v" + dropped; }
            else st.rollback += "; WARNING: saves could not be restored";
            at("drop.afterRestore");
        }
        st.lastResult = st.rollback;
        saveState(); at("drop.afterSave");                                             // the verdict (and the cleared restore marker) is durable before anything is deleted
        if (dropped != 0 && dropped != back) Hashing.deleteTree(dirOf(dropped));       // the broken one only; the safety net is never touched
        at("drop.afterDelete");
    }

    private void pruneOld() {
        File[] kids = modDir.listFiles(); if (kids == null) return;
        for (File k : kids) { String n = k.getName(); if (!n.equals("v" + st.active) && !n.equals("v" + st.lastGood)) Hashing.deleteTree(k); }
    }

    /** The game reached live play with the active module: it is now the safety net. */
    public synchronized void confirm() {
        if (st.pending == 0) return;
        boolean dropSnapshot = st.snapshotFor == st.active && guard != null; String snapshot = "v" + st.active;
        forgave = false; st.snapshotFor = 0;
        st.lastGood = st.active; st.pending = 0; st.tries = 0; st.lastResult = "confirmed v" + st.active; st.rollback = "";
        boolean saved = saveState(); at("confirm.afterSave");
        if (saved && dropSnapshot) guard.discard(snapshot);          // only after the confirmation is durable: a snapshot is the only way back until then
        at("confirm.afterDiscard");
        pruneOld(); at("confirm.afterPrune"); collectAssets(); at("confirm.afterGc");
    }

    /**
     * A clean back-out of an unconfirmed module that had visibly been running (the launcher calls this from onPause after the first rendered frame): this launch is not counted against
     * MAX_UNCONFIRMED_LAUNCHES, so a player who leaves during the first seconds cannot get a healthy release rolled back and blacklisted. A hang or crash never reaches onPause, so it still
     * counts. Idempotent per launch (this store instance is one launch): a second call does nothing. Durable before it returns. Returns true if it forgave a launch.
     */
    public synchronized boolean forgiveCleanPause() {
        if (forgave || st.pending == 0 || st.tries <= 0) return false;
        forgave = true; st.tries--;
        boolean saved = saveState(); at("forgive.afterSave");
        return saved;
    }
    private boolean forgave;

    /**
     * The reverse of {@link #forgiveCleanPause}: the player came back (onResume) to a module that is still unconfirmed, so this launch counts again. Without it a module that is paused
     * once and then crashes after the resume would die with {@code tries} already forgiven and never roll back. No-op unless this launch was forgiven; idempotent; durable before it returns.
     */
    public synchronized boolean recountAfterResume() {
        if (!forgave || st.pending == 0) { forgave = false; return false; }
        forgave = false; st.tries++;
        boolean saved = saveState(); at("recount.afterSave");
        return saved;
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
            at("baseline.afterCopy");
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
            at("commit.afterVerify");
            Hashing.deleteTree(stagedDir);
            if (!tmpDir.renameTo(stagedDir)) return "cannot publish staged module";
            at("commit.afterRename");
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

    /** Free bytes where the store lives (nearest existing parent while the store directory is not created yet). */
    public long usableBytes() {
        File f = root; while (f != null && !f.exists()) f = f.getParentFile();
        return f == null ? 0 : f.getUsableSpace();
    }

    // ------------------------------------------------------------ helpers

    public File dirOf(int version) { return new File(modDir, "v" + version); }

    // The state file is "S1:<sha256 of the JSON>\n<JSON>" (a legacy bare-JSON file is still read). Two copies are kept: state.json and state.json.bak (the previous good one).
    private static final String MAGIC = "S1:";

    private static String envelope(String json) { return MAGIC + Hashing.sha256(json.getBytes(StandardCharsets.UTF_8)) + "\n" + json; }

    /** The state in {@code f}, or null if it is missing, damaged (checksum, JSON, impossible numbers). */
    private static State tryRead(File f) {
        try {
            if (!f.isFile()) return null;
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8), json = s;
            if (s.startsWith(MAGIC)) {
                int nl = s.indexOf('\n');
                if (nl != MAGIC.length() + 64) return null;
                json = s.substring(nl + 1);
                if (!Hashing.sha256(json.getBytes(StandardCharsets.UTF_8)).equals(s.substring(MAGIC.length(), nl))) return null;
            }
            State x = new Json().fromJson(State.class, json);
            if (x == null || x.active < 0 || x.lastGood < 0 || x.pending < 0 || x.tries < 0 || x.highest < 0 || x.revokeFloor < 0 || x.staged < 0 || x.snapshotFor < 0) return null;
            if (x.bad == null) x.bad = new ArrayList<>();
            if (x.lastResult == null) x.lastResult = "";
            if (x.rollback == null) x.rollback = "";
            if (x.restoring == null) x.restoring = "";
            return x;
        } catch (Exception e) { return null; }
    }

    private void loadState() {
        State s = tryRead(stateFile);
        if (s != null) { st = s; return; }
        s = tryRead(stateBak);
        if (s != null) { st = s; st.lastResult = "state restored from its backup copy"; return; }
        st = derive();
    }

    /**
     * Neither state copy is usable (or none exists). Nothing about WHICH module should run is trusted, but the anti-rollback counter must not reset to zero while signed modules are
     * installed: it is rebuilt from the highest version (and revoke floor) of every installed module that still verifies, so an old release cannot be fed to a wiped state.
     * A fresh install (no modules) correctly starts from zero.
     */
    private State derive() {
        State d = new State();
        File[] kids = modDir.listFiles();
        if (kids != null) for (File k : kids) {
            ModuleVerifier.Result r = ModuleVerifier.verify(k, keys, host);
            if (r.ok()) { d.highest = Math.max(d.highest, r.manifest.moduleVersion); d.revokeFloor = Math.max(d.revokeFloor, r.manifest.revokeFloor); }
        }
        if (stateFile.exists() || stateBak.exists() || d.highest != 0) d.lastResult = "state unreadable: reset (anti-rollback floor kept at v" + d.highest + ")";
        return d;
    }

    // A tiny separate monotonic record of the two numbers anti-rollback depends on ("F1:<sha256 of body>\n<highest>,<revokeFloor>"). It is written durably, before the state, only when a number
    // rises, and applied at every boot, so a release that was rolled back (its directory deleted) stays refused even if state.json AND its backup are both lost.
    private static final String FLOOR_MAGIC = "F1:";

    private int[] readFloor() {
        int[] none = new int[2];
        try {
            if (!floorFile.isFile()) return none;
            String s = new String(Files.readAllBytes(floorFile.toPath()), StandardCharsets.UTF_8);
            int nl = s.indexOf('\n');
            if (!s.startsWith(FLOOR_MAGIC) || nl != FLOOR_MAGIC.length() + 64) return none;
            String body = s.substring(nl + 1);
            if (!Hashing.sha256(body.getBytes(StandardCharsets.UTF_8)).equals(s.substring(FLOOR_MAGIC.length(), nl))) return none;
            String[] p = body.split(",");
            if (p.length != 2) return none;
            int h = Integer.parseInt(p[0]), r = Integer.parseInt(p[1]);
            return h < 0 || r < 0 ? none : new int[]{h, r};
        } catch (Exception e) { return none; }
    }

    private void raiseFloor() throws IOException {
        int[] f = readFloor();
        if (st.highest <= f[0] && st.revokeFloor <= f[1]) return;
        String body = Math.max(f[0], st.highest) + "," + Math.max(f[1], st.revokeFloor);
        File tmp = new File(root, "floor.tmp");
        writeDurably(tmp, (FLOOR_MAGIC + Hashing.sha256(body.getBytes(StandardCharsets.UTF_8)) + "\n" + body).getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), floorFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        at("floor.afterWrite");
    }

    private static void writeDurably(File f, byte[] data) throws IOException {
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) { o.write(data); o.getFD().sync(); }
    }

    private static void syncDir(File d) {
        try (java.nio.channels.FileChannel c = java.nio.channels.FileChannel.open(d.toPath(), java.nio.file.StandardOpenOption.READ)) { c.force(true); } catch (Exception ignored) { /* not supported everywhere */ }
    }

    /** Write-ahead, atomic, durable: new copy fsynced beside the old, the old (if valid) kept as the backup, then one rename. Returns false if it could not be written. */
    private boolean saveState() {
        try {
            root.mkdirs(); File tmp = new File(root, "state.json.tmp"), bakTmp = new File(root, "state.json.bak.tmp");
            writeDurably(tmp, envelope(new Json().toJson(st)).getBytes(StandardCharsets.UTF_8));
            at("state.afterTmp");
            if (tryRead(stateFile) != null) {                         // only a copy that itself verifies may replace the backup
                writeDurably(bakTmp, Files.readAllBytes(stateFile.toPath()));
                Files.move(bakTmp.toPath(), stateBak.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            at("state.afterBackup");
            Files.move(tmp.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            at("state.afterMove");
            syncDir(root);
            // The floor follows the state, never leads it: if it ran ahead, a death in between would leave it claiming a version whose activation the state never recorded, and that update
            // could then neither be adopted nor delivered again. A floor that lags by one save is harmless (it catches up on the next save) and only matters if the state is lost.
            try { raiseFloor(); } catch (Exception ignored) { /* redundant record: the state itself was saved */ }
            return true;
        } catch (Exception e) { return false; }      // e.g. storage full: the in-memory state stays authoritative for this run and the next save retries
    }
}
