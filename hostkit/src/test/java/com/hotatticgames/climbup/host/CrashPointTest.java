package com.hotatticgames.climbup.host;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.file.Files;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Crash-point fault injection for {@link ModuleStore}. Each scenario is run once to DISCOVER the points where the process could die (every {@link ModuleStore.Fault#at} call), then re-run from a
 * clean directory once per point with the process "killed" exactly there (an Error nothing in the store catches). After every death a simulated host restarts - boot, install the baseline if
 * nothing runs, confirm if the module is healthy - and the invariants are checked. A second sweep also kills the FIRST recovery launch at every one of its own points (death during recovery).
 * Level: JVM integration test against the real filesystem; it models death BETWEEN steps, not inside a syscall or a power cut.
 *
 * Invariants after recovery, for every death point:
 *  I1 boot never throws and only ever returns a module that verifies (or recovery);
 *  I2 launches settle (no endless update/rollback loop);
 *  I3 the anti-rollback counter never drops below what was durably accepted before the scenario, so an old release is still refused;
 *  I4 an update that was interrupted is not lost forever: it is either applied or can be delivered again;
 *  I5 saves are exactly the old ones or exactly the migrated ones (never a mix), and a module that cannot read the migrated schema never sees it.
 */
public class CrashPointTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    KeyPair key; TrustedKeys keys; HostInfo host;

    static final class Death extends Error { Death(String where) { super("simulated process death at " + where); } }

    /** Records every point hit; dies at the armed index. */
    static final class Probe implements ModuleStore.Fault {
        final List<String> trace = new ArrayList<>(); int dieAt = -1;
        @Override public void at(String point) { int i = trace.size(); trace.add(point); if (i == dieAt) throw new Death(i + ":" + point); }
        void reset(int die) { trace.clear(); dieAt = die; }
    }

    static final String SAVE_OLD = "{\"version\":6,\"seed\":777}", SAVE_NEW = "{\"version\":7,\"seed\":777,\"new\":true}", HIST = "slices...";

    final class Env {
        final File data = mk("data"), root = new File(data, "host"), snap = mk("snap"), baseDir = mk("apk");
        final DirSnapshots guard = new DirSnapshots(data, snap, new HashSet<>(Arrays.asList("host")));
        final Probe probe = new Probe(); ModuleStore store;
        Env() throws Exception {
            Bundles.write(baseDir, new Bundles.Spec(), key);
            write("save.json", SAVE_OLD); write("history.bin", HIST);
        }
        File mk(String n) { try { return tmp.newFolder(); } catch (Exception e) { throw new IllegalStateException(e); } }
        ModuleStore open() { return store = new ModuleStore(root, keys, host).withSaveGuard(guard).withFault(probe); }
        void write(String rel, String text) throws Exception { File f = new File(data, rel); f.getParentFile().mkdirs(); Files.write(f.toPath(), text.getBytes()); }
        String read(String rel) throws Exception { File f = new File(data, rel); return f.isFile() ? new String(Files.readAllBytes(f.toPath())) : null; }
        /** Downloads into staging and commits, on the store the last launch booted (the downloader only ever runs after boot). */
        String stage(Bundles.Spec s) throws Exception { File d = store.openStaging(); Bundles.write(d, s, key); return store.commitStaging(); }
        /** One host launch. Returns the module version that runs (0 = built-in recovery). */
        int launch(boolean healthy) {
            ModuleStore.Boot b = open().boot();
            if (b.recovery() && store.installBaseline(baseDir) == null) b = store.boot();
            if (b.recovery()) return 0;
            ModuleVerifier.Result r = ModuleVerifier.verify(b.dir, keys, host);
            assertTrue("I1 a module that does not verify was handed to the loader: " + r.reason, r.ok());
            if (healthy) store.confirm();
            return b.manifest.moduleVersion;
        }
    }

    @Before public void setUp() throws Exception { key = Bundles.newKey(); keys = new TrustedKeys().add(key.getPublic()); host = new HostInfo(Bundles.APP, 1, "internal"); }

    interface Step { void run(Env e) throws Exception; }
    interface Check { void check(Env e, int running, String where) throws Exception; }

    static final class Scenario {
        final String name; final Step setup, run; final boolean healthy; final int target, minHighest; final Check extra;
        Scenario(String name, Step setup, Step run, boolean healthy, int target, int minHighest, Check extra) { this.name = name; this.setup = setup; this.run = run; this.healthy = healthy; this.target = target; this.minHighest = minHighest; this.extra = extra; }
    }

    Env fresh(Scenario s) throws Exception { Env e = new Env(); s.setup.run(e); e.probe.reset(-1); return e; }

    /** The sweep. Returns how many (death, recovery-death) runs were exercised. */
    int sweep(Scenario s) throws Exception {
        Env discover = fresh(s); s.run.run(discover); List<String> points = new ArrayList<>(discover.probe.trace);
        assertTrue(s.name + ": the scenario must pass the injection points", points.size() >= 4);
        int runs = 0;
        for (int i = 0; i < points.size(); i++) {
            // depth 1: die at point i, then a clean recovery
            Env e = fresh(s); e.probe.reset(i);
            try { s.run.run(e); fail(s.name + ": the armed death at " + i + ":" + points.get(i) + " never happened"); } catch (Death expected) { }
            recoverAndCheck(e, s, s.name + " / death at " + i + ":" + points.get(i), false); runs++;
            // depth 2: die at point i, then die again at every point j of the first recovery launch
            for (int j = 0; ; j++) {
                Env f = fresh(s); f.probe.reset(i);
                try { s.run.run(f); fail("no death"); } catch (Death expected) { }
                f.probe.reset(j); boolean died = false;
                try { f.launch(s.healthy); } catch (Death second) { died = true; }
                if (!died) break;                                              // j is past the end of the recovery launch
                recoverAndCheck(f, s, s.name + " / death at " + i + ":" + points.get(i) + " then during recovery at " + j + ":" + f.probe.trace.get(j), true); runs++;
            }
        }
        return runs;
    }

    /** @param twoDeaths the process died twice before any confirmation: with MAX_UNCONFIRMED_LAUNCHES=2 the crash-loop rule may then legitimately roll back and blacklist the release, so only single deaths must converge. */
    void recoverAndCheck(Env e, Scenario s, String where, boolean twoDeaths) throws Exception {
        e.probe.reset(-1);
        List<Integer> seq = new ArrayList<>();
        for (int i = 0; i < 8; i++) seq.add(e.launch(s.healthy));
        int n = seq.size(), running = seq.get(n - 1);
        assertTrue("I2 launches must settle, got " + seq + " after " + where, seq.get(n - 2) == running && seq.get(n - 3) == running);
        ModuleStore st = e.open(); st.boot();
        assertTrue("I3 the anti-rollback counter fell to " + st.st.highest + " (was >= " + s.minHighest + ") after " + where, st.st.highest >= s.minHighest);
        for (int old = 1; old <= s.minHighest; old++)
            assertNotNull("I3 release v" + old + " was accepted again after " + where, e.stage(new Bundles.Spec().v(old)));
        if (s.target > 0 && running != s.target && s.healthy && !(twoDeaths && e.store.st.bad.contains(s.target) && e.store.st.rollback.isEmpty() == false)) {
            String r = e.stage(new Bundles.Spec().v(s.target).save(6, s.target == 2 && s.name.contains("schema") ? 7 : 6));
            assertNull("I4 the interrupted update v" + s.target + " can neither run nor be delivered again (" + r + ") launches=" + seq + " state=" + new com.badlogic.gdx.utils.Json().toJson(e.store.st) + " files=" + Arrays.toString(e.root.list()) + "/" + Arrays.toString(new File(e.root, "mod").list()) + " after " + where, r);
            e.launch(true); assertEquals("I4 after re-delivery " + where, s.target, e.launch(true));
        }
        if (s.extra != null) s.extra.check(e, running, where);
    }

    // ---------------------------------------------------------------- scenarios

    final Step proven = new Step() { public void run(Env e) throws Exception { assertEquals(1, e.launch(true)); } };

    @Test public void upgradeToV2() throws Exception {
        int runs = sweep(new Scenario("upgrade v1->v2", proven, new Step() { public void run(Env e) throws Exception {
            assertNull(e.stage(new Bundles.Spec().v(2))); e.open().boot(); e.store.confirm();
        } }, true, 2, 1, null));
        System.out.println("CrashPoint upgrade: " + runs + " death scenarios");
    }

    @Test public void upgradeWithASaveSchemaBumpNeverMixesOrStrandsSaves() throws Exception {
        final Check saves = new Check() { public void check(Env e, int running, String where) throws Exception {
            boolean orig = SAVE_OLD.equals(e.read("save.json")) && HIST.equals(e.read("history.bin")) && e.read("save.v7") == null;
            boolean migr = SAVE_NEW.equals(e.read("save.json")) && e.read("history.bin") == null && "migrated".equals(e.read("save.v7"));
            assertTrue("I5 saves are neither the old set nor the migrated set after " + where, orig || migr);
            if (running == 1) assertTrue("I5 v1 runs on saves it cannot read after " + where, orig);
            if (running == 2 && migr) assertTrue("I5 v2 confirmed but its snapshot leaked after " + where, !new File(e.snap, "v2").exists());
        } };
        int runs = sweep(new Scenario("upgrade v1->v2 (schema bump)", proven, new Step() { public void run(Env e) throws Exception {
            assertNull(e.stage(new Bundles.Spec().v(2).save(6, 7))); e.open().boot();
            e.write("save.json", SAVE_NEW); e.write("save.v7", "migrated"); new File(e.data, "history.bin").delete();           // what the new module does on its first run
            e.store.confirm();
        } }, true, 2, 1, saves));
        System.out.println("CrashPoint schema bump: " + runs + " death scenarios");
    }

    @Test public void aCrashLoopRollbackRestoresTheSavesWhereverTheProcessDies() throws Exception {
        final Check saves = new Check() { public void check(Env e, int running, String where) throws Exception {
            boolean orig = SAVE_OLD.equals(e.read("save.json")) && HIST.equals(e.read("history.bin")) && e.read("save.v7") == null;
            assertEquals("the unproven v2 must be gone after " + where, 1, running);
            assertTrue("I5 the saves are not exactly the pre-update ones after " + where, orig);
            assertFalse("the snapshot leaked after " + where, new File(e.snap, "v2").exists());
            e.open().boot();                                          // v2 was either never accepted (died before it was published) or it can never be taken again
            boolean neverArrived = !new File(e.root, "staged").exists() && !new File(e.root, "mod/v2").exists();
            assertTrue("v2 may never be taken again after " + where, e.store.st.highest >= 2 || e.store.st.bad.contains(2) || neverArrived);
        } };
        int runs = sweep(new Scenario("crash-loop rollback (schema bump)", proven, new Step() { public void run(Env e) throws Exception {
            assertNull(e.stage(new Bundles.Spec().v(2).save(6, 7))); e.open().boot();
            e.write("save.json", SAVE_NEW); e.write("save.v7", "migrated"); new File(e.data, "history.bin").delete();
            e.open().boot(); e.open().boot(); e.open().boot();                                                         // v2 never confirms: three launches, the third drops it
        } }, false, 0, 1, saves));
        System.out.println("CrashPoint crash-loop: " + runs + " death scenarios");
    }

    @Test public void firstInstallOfTheBaseline() throws Exception {
        int runs = sweep(new Scenario("baseline install", new Step() { public void run(Env e) { } }, new Step() { public void run(Env e) throws Exception {
            ModuleStore s = e.open(); s.boot(); assertNull(s.installBaseline(e.baseDir)); s.boot(); s.confirm();
        } }, true, 1, 0, null));
        System.out.println("CrashPoint baseline: " + runs + " death scenarios");
    }

    // ---------------------------------------------------------------- clean back-outs are forgiven; hangs and crashes are not

    @Test public void cleanBackOutsOfAnUnprovenModuleNeverTriggerARollbackButCrashesStillDo() throws Exception {
        Env e = new Env(); assertEquals(1, e.launch(true));
        assertNull(e.stage(new Bundles.Spec().v(2)));
        for (int i = 0; i < 6; i++) {                                                       // the player opens the game and backs out, six times, before it ever confirms
            ModuleStore.Boot b = e.open().boot(); assertEquals("launch " + i, 2, b.manifest.moduleVersion);
            assertTrue("a clean back-out is forgiven (launch " + i + ")", e.store.forgiveCleanPause());
            assertFalse("a second call in the same launch must not forgive twice", e.store.forgiveCleanPause());
        }
        assertEquals("then it confirms normally", 2, e.launch(true)); assertEquals(2, e.store.st.lastGood);
        assertFalse("nothing to forgive once confirmed", e.open().boot() == null || e.store.forgiveCleanPause());

        Env c = new Env(); assertEquals(1, c.launch(true)); assertNull(c.stage(new Bundles.Spec().v(2)));
        c.open().boot(); c.open().boot();                                                   // two launches that never reach onPause (hang / crash): they count
        assertEquals("a third unconfirmed launch is rolled back as before", 1, c.open().boot().manifest.moduleVersion);
        assertTrue(c.store.st.bad.contains(2));
        c.open().boot(); assertFalse("never below zero, nothing pending", c.store.forgiveCleanPause());
    }

    @Test public void forgivingACleanPauseIsCrashSafeWhereverTheProcessDies() throws Exception {
        int runs = sweep(new Scenario("forgive clean pause", proven, new Step() { public void run(Env e) throws Exception {
            assertNull(e.stage(new Bundles.Spec().v(2)));
            for (int i = 0; i < 4; i++) { e.open().boot(); e.store.forgiveCleanPause(); }       // back out four times
            e.open().boot(); e.store.confirm();
        } }, true, 2, 1, null));
        System.out.println("CrashPoint forgive: " + runs + " death scenarios");
    }

    // ---------------------------------------------------------------- the state file itself

    @Test public void theAntiRollbackCounterSurvivesAnyDamageToTheStateFiles() throws Exception {
        String[] damage = {"missing", "garbage", "empty", "truncated", "bit-flip", "main-garbage-backup-ok", "both-garbage", "both-missing"};
        for (String d : damage) {
            Env e = new Env(); assertEquals(1, e.launch(true));
            assertNull(e.stage(new Bundles.Spec().v(2))); assertEquals(2, e.launch(true)); assertEquals(2, e.launch(true));        // v2 confirmed, highest = 2, several state writes behind us
            File main = new File(e.root, "state.json"), bak = new File(e.root, "state.json.bak");
            byte[] cur = Files.readAllBytes(main.toPath());
            switch (d) {
                case "missing": main.delete(); break;
                case "garbage": Files.write(main.toPath(), "{{{ not json".getBytes()); break;
                case "empty": Files.write(main.toPath(), new byte[0]); break;
                case "truncated": Files.write(main.toPath(), Arrays.copyOf(cur, cur.length / 2)); break;
                case "bit-flip": { byte[] c = cur.clone(); c[c.length - 3] ^= 1; Files.write(main.toPath(), c); break; }
                case "main-garbage-backup-ok": Files.write(main.toPath(), "garbage".getBytes()); break;
                case "both-garbage": Files.write(main.toPath(), "x".getBytes()); Files.write(bak.toPath(), "y".getBytes()); break;
                case "both-missing": main.delete(); bak.delete(); break;
            }
            for (int i = 0; i < 4; i++) e.launch(true);                                                                  // the host keeps starting
            ModuleStore s = e.open(); s.boot();
            assertTrue("the anti-rollback counter was lost with damage '" + d + "': highest=" + s.st.highest, s.st.highest >= 2);
            assertNotNull("v1 was accepted again after '" + d + "'", e.stage(new Bundles.Spec().v(1))); assertNotNull("v2 was accepted again after '" + d + "'", e.stage(new Bundles.Spec().v(2)));
            assertNull("a newer release must still be deliverable after '" + d + "'", e.stage(new Bundles.Spec().v(3)));
            e.launch(true); assertEquals("and activate after '" + d + "'", 3, e.launch(true));
        }
    }
}
