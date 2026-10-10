package com.hotatticgames.climbup.host;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.spi.GameModule;
import com.hotatticgames.climbup.spi.HostEnv;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.security.KeyPair;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ModuleStoreTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    KeyPair key, other; TrustedKeys keys; HostInfo host; File root; ModuleStore store;

    @Before public void setUp() throws Exception {
        key = Bundles.newKey(); other = Bundles.newKey();
        keys = new TrustedKeys().add(key.getPublic()); host = new HostInfo(Bundles.APP, 1, "internal");
        root = tmp.newFolder("ota"); store = new ModuleStore(root, keys, host);
    }

    ModuleStore reopen() { store = new ModuleStore(root, keys, host); return store; }

    /** Downloads a bundle into staging and commits it, the way the real downloader does. */
    String stage(Bundles.Spec s) throws Exception { return stage(s, key); }
    String stage(Bundles.Spec s, KeyPair signer) throws Exception { File d = store.openStaging(); Bundles.write(d, s, signer); return store.commitStaging(); }

    ModuleStore.Boot bootNew() { return reopen().boot(); }
    int active() { return store.st.active; }

    /** Installs v1 as confirmed baseline. */
    void baseline() throws Exception { assertNull(stage(new Bundles.Spec())); ModuleStore.Boot b = bootNew(); assertEquals(1, b.manifest.moduleVersion); store.confirm(); assertEquals(1, store.st.lastGood); }

    // ---------------------------------------------------------------- verification

    @Test public void aValidBundleVerifies() throws Exception {
        File d = tmp.newFolder("v"); Bundles.write(d, new Bundles.Spec(), key);
        assertTrue(ModuleVerifier.verify(d, keys, host).toString(), ModuleVerifier.verify(d, keys, host).ok());
    }

    @Test public void aBadSignatureNeverRuns() throws Exception {
        File d = tmp.newFolder("v"); Bundles.write(d, new Bundles.Spec(), other);        // signed by a key the host does not pin
        assertFalse(ModuleVerifier.verify(d, keys, host).ok());
        File e = tmp.newFolder("w"); Bundles.write(e, new Bundles.Spec(), key);
        byte[] m = Files.readAllBytes(new File(e, "manifest.json").toPath()); m[m.length / 2] ^= 1;     // manifest altered after signing
        Files.write(new File(e, "manifest.json").toPath(), m);
        assertFalse(ModuleVerifier.verify(e, keys, host).ok());
        File f = tmp.newFolder("x"); Bundles.write(f, new Bundles.Spec(), key);
        Files.write(new File(f, "manifest.sig").toPath(), "AAAA".getBytes());
        assertFalse(ModuleVerifier.verify(f, keys, host).ok());
        File g = tmp.newFolder("y"); Bundles.write(g, new Bundles.Spec(), key); new File(g, "manifest.sig").delete();       // unsigned
        assertFalse(ModuleVerifier.verify(g, keys, host).ok());
    }

    @Test public void aKeyIdPointingAtAnotherKeyIsNotAnAttack() throws Exception {
        // an attacker names the trusted key id but signs with their own key
        File d = tmp.newFolder("v"); Bundles.write(d, new Bundles.Spec(), key);
        byte[] m = Files.readAllBytes(new File(d, "manifest.json").toPath());
        Bundles.sign(d, m, other);
        assertEquals("bad signature", ModuleVerifier.verify(d, keys, host).reason);
    }

    @Test public void aTamperedOrTruncatedFileIsRejected() throws Exception {
        File d = tmp.newFolder("v"); Bundles.write(d, new Bundles.Spec(), key);
        File jar = new File(d, "module.jar"); byte[] b = Files.readAllBytes(jar.toPath()); b[b.length / 2] ^= 0x55; Files.write(jar.toPath(), b);
        assertTrue(ModuleVerifier.verify(d, keys, host).reason.startsWith("hash mismatch"));
        File e = tmp.newFolder("w"); Bundles.write(e, new Bundles.Spec(), key);
        File j2 = new File(e, "module.jar"); byte[] c = Files.readAllBytes(j2.toPath()); Files.write(j2.toPath(), java.util.Arrays.copyOf(c, c.length - 10));
        assertTrue(ModuleVerifier.verify(e, keys, host).reason.startsWith("size mismatch"));
    }

    @Test public void extraOrMissingFilesAreRejected() throws Exception {
        File d = tmp.newFolder("v"); Bundles.write(d, new Bundles.Spec(), key); Files.write(new File(d, "evil.dex").toPath(), new byte[]{1});
        assertEquals("unexpected files in module directory", ModuleVerifier.verify(d, keys, host).reason);
        File e = tmp.newFolder("w"); Bundles.write(e, new Bundles.Spec(), key); new File(e, "hello.txt").delete();
        assertTrue(ModuleVerifier.verify(e, keys, host).reason.contains("unexpected") || ModuleVerifier.verify(e, keys, host).reason.contains("missing"));
    }

    @Test public void aModuleForAnotherAppChannelHostOrInterfaceNeverRuns() throws Exception {
        Bundles.Spec s = new Bundles.Spec(); s.app = "com.someone.else";
        File a = tmp.newFolder("a"); Bundles.write(a, s, key); assertTrue(ModuleVerifier.verify(a, keys, host).reason.startsWith("built for another app"));
        s = new Bundles.Spec(); s.channel = "release";
        File b = tmp.newFolder("b"); Bundles.write(b, s, key); assertTrue(ModuleVerifier.verify(b, keys, host).reason.startsWith("channel"));
        s = new Bundles.Spec(); s.iface = 2;
        File c = tmp.newFolder("c"); Bundles.write(c, s, key); assertTrue(ModuleVerifier.verify(c, keys, host).reason.startsWith("interface"));
        s = new Bundles.Spec(); s.hostMin = 2; s.hostMax = 3;
        File d = tmp.newFolder("d"); Bundles.write(d, s, key); assertTrue(ModuleVerifier.verify(d, keys, host).reason.startsWith("host level"));
    }

    @Test public void aRevokedKeyIsNotTrustedEvenIfPinned() throws Exception {
        File d = tmp.newFolder("v"); Bundles.write(d, new Bundles.Spec(), key);
        TrustedKeys revoked = new TrustedKeys().add(key.getPublic()).revoke(TrustedKeys.keyId(key.getPublic()));
        assertTrue(ModuleVerifier.verify(d, revoked, host).reason.contains("not trusted"));
        // rotation: the old key stays pinned for a while, the new one is added, new releases are signed by the new key
        TrustedKeys rotated = new TrustedKeys().add(key.getPublic()).add(other.getPublic());
        File e = tmp.newFolder("w"); Bundles.write(e, new Bundles.Spec(), other);
        assertTrue(ModuleVerifier.verify(e, rotated, host).ok());
    }

    @Test public void malformedManifestsAreRejectedWithoutThrowing() throws Exception {
        String good = new String(Files.readAllBytes(writeGood().toPath()), "UTF-8");
        String[] bad = {"", "[]", "{}", "not json", good.replace("\"schema\":1", "\"schema\":2"), good.replace("\"moduleVersion\":1,", "\"moduleVersion\":0,"),
                good.replace("\"dex\":\"module.jar\"", "\"dex\":\"nothere.jar\""), good.replace("\"name\":\"hello.txt\"", "\"name\":\"../hello.txt\""),
                good.replace("\"entry\":\"com.hotatticgames.climbup.synthetic.SyntheticModule\"", "\"entry\":\"bad entry!\""), good.replace("\"sha256\":\"", "\"sha256\":\"zz"),
                good.replace("\"hostMin\":1", "\"hostMin\":5"), good.replace("\"saveMin\":6", "\"saveMin\":9"), good.replace("\"revokeFloor\":0", "\"revokeFloor\":9"),
                good.replace("\"size\":", "\"size\":-")};
        for (String m : bad) {
            File d = tmp.newFolder(); Bundles.write(d, new Bundles.Spec(), key); Bundles.sign(d, m.getBytes("UTF-8"), key);     // correctly signed, still malformed
            ModuleVerifier.Result r = ModuleVerifier.verify(d, keys, host);
            assertFalse("accepted: " + m, r.ok());
        }
    }
    private File writeGood() throws Exception { File d = tmp.newFolder(); Bundles.write(d, new Bundles.Spec(), key); return new File(d, "manifest.json"); }

    // ---------------------------------------------------------------- staging and activation

    @Test public void aStagedModuleActivatesOnlyAtTheNextColdStart() throws Exception {
        baseline();
        assertNull(stage(new Bundles.Spec().v(2)));
        assertEquals("the running module is untouched by a download", 1, store.st.active);
        assertEquals(2, store.stagedVersion());
        ModuleStore.Boot b = bootNew();
        assertEquals(2, b.manifest.moduleVersion); assertEquals(2, store.st.pending); assertEquals("v1 stays the safety net until v2 proves itself", 1, store.st.lastGood);
        store.confirm();
        assertEquals(2, store.st.lastGood);
    }

    @Test public void corruptOrInterruptedDownloadsNeverReachStagedAndAreDiscarded() throws Exception {
        baseline();
        File d = store.openStaging(); Bundles.write(d, new Bundles.Spec().v(2), key);
        File jar = new File(d, "module.jar"); byte[] b = Files.readAllBytes(jar.toPath()); Files.write(jar.toPath(), java.util.Arrays.copyOf(b, b.length / 2));      // download cut short
        assertNotNull(store.commitStaging());
        assertEquals(0, store.stagedVersion()); assertFalse(new File(root, "staged").exists()); assertFalse(new File(root, "staging.tmp").exists());
        // process killed mid-download: the partial directory is still there at the next start and is thrown away
        File partial = store.openStaging(); Files.write(new File(partial, "module.jar").toPath(), new byte[]{1, 2, 3});
        ModuleStore.Boot boot = bootNew();
        assertEquals(1, boot.manifest.moduleVersion); assertFalse(new File(root, "staging.tmp").exists());
    }

    @Test public void antiRollbackRefusesOldAndSameVersions() throws Exception {
        baseline();
        assertNull(stage(new Bundles.Spec().v(3))); bootNew(); store.confirm();
        assertNotNull("older", stage(new Bundles.Spec().v(2)));
        assertNotNull("same", stage(new Bundles.Spec().v(3)));
        assertNull(stage(new Bundles.Spec().v(4)));
        assertNotNull("a second staged older than the staged one", stage(new Bundles.Spec().v(4)));
    }

    @Test public void aStagedBundleThatIsTamperedAtRestIsRejectedAtActivation() throws Exception {
        baseline(); assertNull(stage(new Bundles.Spec().v(2)));
        File jar = new File(root, "staged/module.jar"); byte[] b = Files.readAllBytes(jar.toPath()); b[10] ^= 1; Files.write(jar.toPath(), b);
        ModuleStore.Boot boot = bootNew();
        assertEquals(1, boot.manifest.moduleVersion); assertTrue(boot.note.contains("rejected")); assertFalse(new File(root, "staged").exists());
    }

    // ---------------------------------------------------------------- rollback and recovery

    @Test public void anUnconfirmedModuleIsRolledBackAfterRepeatedLaunchesAndNeverRetried() throws Exception {
        baseline(); assertNull(stage(new Bundles.Spec().v(2)));
        assertEquals(2, bootNew().manifest.moduleVersion);        // launch 1 of v2 (crashes before confirming)
        assertEquals(2, bootNew().manifest.moduleVersion);        // launch 2
        ModuleStore.Boot third = bootNew();                        // launch 3: the limit
        assertEquals(1, third.manifest.moduleVersion); assertTrue(store.st.rollback.contains("rolled back v2 to v1"));
        assertTrue(store.st.bad.contains(2));
        assertNotNull("the broken version cannot be re-staged", stage(new Bundles.Spec().v(2)));
        assertEquals("stable afterwards", 1, bootNew().manifest.moduleVersion);
        assertNull("a newer fix is accepted", stage(new Bundles.Spec().v(3))); assertEquals(3, bootNew().manifest.moduleVersion);
    }

    @Test public void aConfirmedModuleIsNotRolledBackNoMatterHowManyLaunches() throws Exception {
        baseline(); assertNull(stage(new Bundles.Spec().v(2))); bootNew(); store.confirm();
        for (int i = 0; i < 10; i++) assertEquals(2, bootNew().manifest.moduleVersion);
    }

    @Test public void noWorkingModuleMeansRecoveryNotACrash() throws Exception {
        assertTrue(bootNew().recovery());                                              // nothing installed
        assertNull(stage(new Bundles.Spec().v(1))); bootNew(); bootNew(); assertTrue("an unconfirmed only-module is dropped after the limit", bootNew().recovery());
        assertEquals(0, store.st.active);
    }

    @Test public void theLastKnownGoodModuleIsNeverDeleted() throws Exception {
        baseline(); assertNull(stage(new Bundles.Spec().v(2))); bootNew();
        assertTrue(new File(root, "mod/v1").isDirectory()); assertTrue(new File(root, "mod/v2").isDirectory());
        bootNew(); bootNew();
        assertTrue(new File(root, "mod/v1").isDirectory()); assertFalse("the broken one is removed", new File(root, "mod/v2").exists());
    }

    @Test public void aModuleCorruptedAtRestFallsToRecoveryAndTheApkBaselineRestoresPlay() throws Exception {
        File baseDir = tmp.newFolder("apkBaseline"); Bundles.write(baseDir, new Bundles.Spec(), key);            // the signed v1 that ships inside the APK
        assertNull(store.installBaseline(baseDir)); assertEquals(1, bootNew().manifest.moduleVersion);
        assertNull(stage(new Bundles.Spec().v(2))); bootNew(); store.confirm();
        File jar = new File(root, "mod/v2/module.jar"); jar.setWritable(true); byte[] b = Files.readAllBytes(jar.toPath()); b[20] ^= 1; Files.write(jar.toPath(), b);
        assertTrue("v2 is corrupt and v1 was pruned once v2 proved itself", bootNew().recovery());
        assertTrue(store.st.rollback.contains("verification failed"));
        assertNull("recovery is not a dead end: the baseline in the APK is reinstalled", store.installBaseline(baseDir));
        assertEquals(1, bootNew().manifest.moduleVersion);
        assertNotNull("not when something is already runnable", store.installBaseline(baseDir));
    }

    @Test public void aBlacklistedOrRevokedBaselineIsNotReinstalled() throws Exception {
        File baseDir = tmp.newFolder("apkBaseline"); Bundles.write(baseDir, new Bundles.Spec(), key);
        assertNull(store.installBaseline(baseDir)); bootNew(); store.st.bad.add(1); store.st.active = 0; store.st.lastGood = 0;
        assertTrue(store.installBaseline(baseDir).contains("blacklisted"));
    }

    @Test public void aCorruptStateFileDoesNotCrashTheHostAndCostsOnlyTheInstalledChoice() throws Exception {
        File baseDir = tmp.newFolder("apkBaseline"); Bundles.write(baseDir, new Bundles.Spec(), key);
        assertNull(store.installBaseline(baseDir)); bootNew();
        Files.write(new File(root, "state.json").toPath(), "{{{ not json".getBytes()); Files.write(new File(root, "state.json.bak").toPath(), "{{{ also not json".getBytes());      // every copy damaged
        assertTrue("no state => nothing is trusted to run", bootNew().recovery());
        assertEquals("but the anti-rollback counter is rebuilt from the installed, verified module", 1, store.st.highest);
        assertNull(store.installBaseline(baseDir)); assertEquals(1, bootNew().manifest.moduleVersion);
    }

    // ---------------------------------------------------------------- found by the first run on a real Android runtime

    @Test public void theRuntimesOwnCompiledCacheNextToTheDexIsToleratedAndNothingElseIs() throws Exception {
        baseline();
        File oat = new File(root, "mod/v1/oat/x86_64"); assertTrue(oat.mkdirs());                          // what ART writes after loading module.dex
        Files.write(new File(oat, "module.vdex").toPath(), new byte[]{1, 2, 3}); Files.write(new File(oat, "module.odex").toPath(), new byte[]{4});
        for (int i = 0; i < 5; i++) assertEquals("a restart after the runtime cached its output keeps the module", 1, bootNew().manifest.moduleVersion);
        File stray = new File(root, "mod/v1/classes2.dex"); Files.write(stray.toPath(), new byte[]{9});    // anything else is still refused
        assertTrue(bootNew().recovery()); assertTrue(store.st.rollback.contains("unexpected files"));
        stray.delete();
    }

    @Test public void aFileCalledOatOrASecondDirectoryIsNotTheRuntimeCache() throws Exception {
        File d = tmp.newFolder("a"); Bundles.write(d, new Bundles.Spec(), key); Files.write(new File(d, "oat").toPath(), new byte[]{1});
        assertEquals("unexpected files in module directory", ModuleVerifier.verify(d, keys, host).reason);
        File e = tmp.newFolder("b"); Bundles.write(e, new Bundles.Spec(), key); assertTrue(new File(e, "extra").mkdirs());
        assertEquals("unexpected files in module directory", ModuleVerifier.verify(e, keys, host).reason);
        File f = tmp.newFolder("c"); Bundles.write(f, new Bundles.Spec(), key); assertTrue(new File(f, "oat").mkdirs());
        assertTrue(ModuleVerifier.verify(f, keys, host).ok());
    }

    @Test public void onlyAMisbehavingReleaseIsBlacklistedNotOneThatWasMerelyDamagedOrRevoked() throws Exception {
        File baseDir = tmp.newFolder("apkBaseline"); Bundles.write(baseDir, new Bundles.Spec(), key);
        assertNull(store.installBaseline(baseDir)); bootNew();
        File jar = new File(root, "mod/v1/module.jar"); jar.setWritable(true); Files.write(jar.toPath(), new byte[]{1, 2, 3});
        assertTrue(bootNew().recovery()); assertFalse("damage is not the release's fault", store.st.bad.contains(1));
        assertNull("so the signed baseline can restore play", store.installBaseline(baseDir)); assertEquals(1, bootNew().manifest.moduleVersion);
        // a release that misbehaves IS blacklisted (anUnconfirmedModuleIsRolledBack..., aBlacklistedOrRevokedBaselineIsNotReinstalled)
        assertNull(stage(new Bundles.Spec().v(2))); bootNew(); bootNew(); bootNew(); assertTrue(bootNew().manifest != null); assertTrue(store.st.bad.contains(2));
    }

    // ---------------------------------------------------------------- climb / save compatibility, revocation

    @Test public void aGeneratorChangeWaitsWhileAClimbIsInProgress() throws Exception {
        baseline(); store.setClimbInProgress(true);
        assertNull(stage(new Bundles.Spec().v(2).ruleset(2)));
        ModuleStore.Boot b = bootNew();
        assertEquals("the climb keeps running on the module that generated it", 1, b.manifest.moduleVersion);
        assertEquals("still staged, not discarded", 2, store.stagedVersion()); assertTrue(store.st.lastResult.contains("waiting"));
        store.setClimbInProgress(false);                                               // the player finished or abandoned the climb
        assertEquals(2, bootNew().manifest.moduleVersion);
    }

    @Test public void aSameWorldUpdateAppliesEvenMidClimbButASaveItCannotReadDoesNot() throws Exception {
        baseline(); store.setClimbInProgress(true);
        assertNull(stage(new Bundles.Spec().v(2)));                                    // same ruleset, same save schema
        assertEquals(2, bootNew().manifest.moduleVersion); store.confirm();
        assertNull(stage(new Bundles.Spec().v(3).save(7, 7)));                         // reads only schema 7 saves
        assertEquals(2, bootNew().manifest.moduleVersion); assertTrue(store.st.lastResult.contains("not readable"));
        assertNull(stage(new Bundles.Spec().v(4).save(6, 7)));                         // migrates 6 -> 7: allowed
        assertEquals("a newer save schema that can read the old one is allowed", 4, bootNew().manifest.moduleVersion);
    }

    @Test public void revokeFloorInvalidatesOlderModulesAndFallsToRecovery() throws Exception {
        baseline(); assertNull(stage(new Bundles.Spec().v(2).floor(2)));
        assertEquals(2, bootNew().manifest.moduleVersion); assertEquals(2, store.st.revokeFloor);
        bootNew();
        ModuleStore.Boot b = bootNew();                                                // v2 never confirms: its safety net, v1, is below the floor and must not run
        assertTrue(b.recovery()); assertEquals(0, store.st.active);
    }

    // ---------------------------------------------------------------- real class loading (JVM stand-in for DexClassLoader)

    static final HostBoot.ClassLoading URL_LOADING = (f, parent) -> new URLClassLoader(new URL[]{f.toURI().toURL()}, parent);

    static HostEnv env(File moduleDir, java.util.List<String> log, ModuleStore store) {
        return new HostEnv() {
            public File dataDir() { return new File("data"); } public int appBuild() { return 1; } public int hostLevel() { return 1; }
            public File moduleDir() { return moduleDir; } public void confirmHealthy() { store.confirm(); } public void climbInProgress(boolean b) { store.setClimbInProgress(b); } public void diag(String l) { log.add(l); }
        };
    }

    @Test public void theHostLoadsAndInstantiatesTheSignedModule() throws Exception {
        baseline();
        HostBoot.Started s = HostBoot.start(reopen(), URL_LOADING, getClass().getClassLoader(), null);
        assertFalse(s.recovery()); assertEquals(1, s.manifest.moduleVersion);
        assertEquals(GameModule.INTERFACE_VERSION, s.module.interfaceVersion());
        assertNotNull(s.module.create(env(s.dir, new java.util.ArrayList<>(), store)));
    }

    @Test public void aModuleThatCannotStartIsReplacedByTheLastGoodOneInTheSameLaunch() throws Exception {
        baseline(); assertNull(stage(new Bundles.Spec().v(2).mode("throwCreate")));
        // class loading works (create() is called later by the backend); simulate the host catching a creation failure
        HostBoot.Started s = HostBoot.start(reopen(), URL_LOADING, getClass().getClassLoader(), null);
        assertEquals(2, s.manifest.moduleVersion);
        Throwable thrown = null;
        try { s.module.create(env(s.dir, new java.util.ArrayList<>(), store)); fail(); } catch (IllegalStateException expected) { thrown = expected; }
        HostBoot.Started again = HostBoot.failed(store, URL_LOADING, getClass().getClassLoader(), thrown);
        assertEquals("the same launch continues on v1", 1, again.manifest.moduleVersion); assertTrue(store.st.bad.contains(2));
        assertNotNull(again.module.create(env(again.dir, new java.util.ArrayList<>(), store)));
    }

    @Test public void aWrongEntryClassFallsBackWithoutCrashing() throws Exception {
        baseline();
        Bundles.Spec s = new Bundles.Spec().v(2); s.entry = "com.hotatticgames.climbup.synthetic.DoesNotExist";
        assertNull(stage(s));
        HostBoot.Started st = HostBoot.start(reopen(), URL_LOADING, getClass().getClassLoader(), null);
        assertEquals("fell back inside the same launch", 1, st.manifest.moduleVersion); assertTrue(store.st.rollback.contains("failed to load"));
    }

    @Test public void repeatedUpdatesLeaveOnlyTheProvenModule() throws Exception {
        baseline();
        for (int v = 2; v <= 6; v++) { assertNull(stage(new Bundles.Spec().v(v))); assertEquals(v, bootNew().manifest.moduleVersion); store.confirm(); }
        String[] dirs = new File(root, "mod").list(); java.util.Arrays.sort(dirs);
        assertArrayEquals("only the proven module remains", new String[]{"v6"}, dirs);
    }
}
