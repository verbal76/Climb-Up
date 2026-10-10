package com.hotatticgames.climbup.host;

import static org.junit.Assert.*;

import java.io.File;
import java.security.KeyPair;
import java.util.Random;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Key rotation, key revocation, revokeFloor and anti-rollback under repeated / adversarial staging, as full store scenarios (staging -> activation -> confirmation -> rollback). Each scenario
 * restarts the "host" with a different set of pinned keys the way a host update would. Level: JVM integration, real filesystem.
 */
public class KeyRotationTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    KeyPair a, b; HostInfo host; File root;

    @Before public void setUp() throws Exception { a = Bundles.newKey(); b = Bundles.newKey(); host = new HostInfo(Bundles.APP, 1, "internal"); root = tmp.newFolder("ota"); }

    ModuleStore host(TrustedKeys keys) { return new ModuleStore(root, keys, host); }
    static TrustedKeys pins(KeyPair... ks) { TrustedKeys t = new TrustedKeys(); for (KeyPair k : ks) t.add(k.getPublic()); return t; }
    String stage(ModuleStore s, Bundles.Spec spec, KeyPair signer) throws Exception { File d = s.openStaging(); Bundles.write(d, spec, signer); return s.commitStaging(); }
    ModuleStore.Boot launch(ModuleStore s, boolean confirm) { ModuleStore.Boot bt = s.boot(); if (confirm && !bt.recovery()) s.confirm(); return bt; }

    @Test public void twoPinnedKeysBothWorkAndAManifestNamingOneKeyButSignedByTheOtherIsRefused() throws Exception {
        ModuleStore s = host(pins(a, b)); s.boot();
        File d = tmp.newFolder(); Bundles.write(d, new Bundles.Spec().v(1), a);
        byte[] m = java.nio.file.Files.readAllBytes(new File(d, "manifest.json").toPath());
        Bundles.sign(d, m, b);                                                               // manifest names key A (keyId inside) but is signed with B
        assertEquals("manifest signature invalid", s.preflight(m, java.nio.file.Files.readAllBytes(new File(d, "manifest.sig").toPath())));
        assertNull("signed by A as it says", stage(s, new Bundles.Spec().v(1), a));
        ModuleStore s2 = host(pins(a, b)); s2.boot(); assertNull("signed by B", stage(s2, new Bundles.Spec().v(2), b));
    }

    @Test public void aKeyRotationAcrossHostUpdatesNeverStrandsAnInstallOrReopensTheRetiredKey() throws Exception {
        ModuleStore h1 = host(pins(a)); h1.boot(); assertNull(stage(h1, new Bundles.Spec().v(1), a)); assertEquals(1, launch(host(pins(a)), true).manifest.moduleVersion);
        ModuleStore old = host(pins(a)); old.boot();
        assertNotNull("a host that has not heard of key B refuses a B-signed release", stage(old, new Bundles.Spec().v(2), b));
        ModuleStore h2 = host(pins(a, b)); h2.boot();                                       // host update: pins old + new
        assertNull(stage(h2, new Bundles.Spec().v(2), b)); assertEquals("B-signed v2 activates", 2, launch(host(pins(a, b)), true).manifest.moduleVersion);
        ModuleStore h3 = host(pins(b)); ModuleStore.Boot bt = h3.boot();                    // a later host drops the old key
        assertEquals("the installed B-signed module keeps running", 2, bt.manifest.moduleVersion); h3.confirm();
        assertNotNull("a release signed by the retired key is refused", stage(h3, new Bundles.Spec().v(3), a));
        assertNull("a release signed by the current key is taken", stage(h3, new Bundles.Spec().v(3), b));
        assertEquals(3, launch(host(pins(b)), true).manifest.moduleVersion);
    }

    @Test public void aRevokedKeyInvalidatesWhatItSignedWithoutASoftLockAndTheBaselineSignedByTheNewKeyRestoresPlay() throws Exception {
        ModuleStore h1 = host(pins(a, b)); h1.boot(); assertNull(stage(h1, new Bundles.Spec().v(1), a)); assertEquals(1, launch(host(pins(a, b)), true).manifest.moduleVersion);
        ModuleStore compromised = host(pins(a, b).revoke(TrustedKeys.keyId(a.getPublic())));      // a host update cuts key A off (it is pinned, but revoked)
        ModuleStore.Boot bt = compromised.boot();
        assertTrue("the A-signed install must not run: nothing else is installed", bt.recovery()); assertTrue(compromised.st.rollback, compromised.st.rollback.contains("verification failed"));
        File aBase = tmp.newFolder("aBase"); Bundles.write(aBase, new Bundles.Spec().v(1), a);
        assertNotNull("the baseline signed by the revoked key is refused", compromised.installBaseline(aBase));
        File bBase = tmp.newFolder("bBase"); Bundles.write(bBase, new Bundles.Spec().v(1), b);
        assertNull("the baseline signed by the new key is accepted", compromised.installBaseline(bBase));
        ModuleStore again = host(pins(a, b).revoke(TrustedKeys.keyId(a.getPublic()))); ModuleStore.Boot ok = again.boot();
        assertFalse("play is restored, no soft lock", ok.recovery()); assertEquals(1, ok.manifest.moduleVersion); again.confirm();
        for (int i = 0; i < 5; i++) assertEquals("and it stays that way across restarts", 1, host(pins(a, b).revoke(TrustedKeys.keyId(a.getPublic()))).boot().manifest.moduleVersion);
        assertNotNull(stage(again, new Bundles.Spec().v(2), a)); assertNull(stage(again, new Bundles.Spec().v(2), b));
    }

    @Test public void aRevokeFloorInvalidatesTheRollbackTargetSoARolledBackBadReleaseCannotFallBackToAKnownVulnerableOne() throws Exception {
        TrustedKeys k = pins(a);
        ModuleStore s = host(k); s.boot(); assertNull(stage(s, new Bundles.Spec().v(1), a)); launch(host(k), true);
        s = host(k); s.boot(); assertNull(stage(s, new Bundles.Spec().v(2), a)); assertEquals(2, launch(host(k), true).manifest.moduleVersion);
        s = host(k); s.boot(); assertNull(stage(s, new Bundles.Spec().v(3).floor(3), a));       // v3 declares that nothing below v3 may run any more
        assertEquals(3, launch(host(k), false).manifest.moduleVersion);                      // activated, unproven
        host(k).boot(); ModuleStore last = host(k); ModuleStore.Boot bt = last.boot();       // v3 never confirms
        assertTrue("v3 is dropped, and v2 (below the floor) must NOT be the fallback", bt.recovery()); assertEquals(3, last.st.revokeFloor); assertTrue(last.st.bad.contains(3));
        File base = tmp.newFolder("base"); Bundles.write(base, new Bundles.Spec().v(1), a);
        assertTrue("the old baseline is below the floor", last.installBaseline(base).contains("blacklisted or revoked"));
        assertNull("a fixed release at or above the floor is accepted", stage(last, new Bundles.Spec().v(4).floor(3), a));
        assertEquals(4, launch(host(k), true).manifest.moduleVersion);
    }

    @Test public void repeatedAndAdversarialStagingNeverAcceptsAnythingThatIsNotStrictlyNewerAndLeavesNoDebris() throws Exception {
        TrustedKeys k = pins(a); ModuleStore s = host(k); s.boot(); Random r = new Random(1234); int maxAccepted = 0, staged = 0;
        for (int i = 0; i < 120; i++) {
            int v = 1 + r.nextInt(40); String res = stage(s, new Bundles.Spec().v(v), a);
            if (v > maxAccepted) { assertNull("v" + v + " is newer than v" + maxAccepted + " and must be taken: " + res, res); maxAccepted = v; staged = v; }
            else assertNotNull("v" + v + " is not newer than v" + maxAccepted + " but was accepted", res);
            assertEquals("what waits for the next start is the newest accepted release", staged, s.stagedVersion());
            assertFalse("no debris from a refused release", new File(root, "staging.tmp").exists() && v <= maxAccepted && res != null && new File(root, "staging.tmp").list().length > 0);
            if (i % 9 == 8) { ModuleStore.Boot bt = launch(host(k), true); assertFalse(bt.recovery()); assertEquals("the highest staged one is the one that runs", maxAccepted, bt.manifest.moduleVersion); s = host(k); s.boot(); staged = 0; }
        }
        ModuleStore fin = host(k); fin.boot(); assertEquals(maxAccepted, fin.st.highest);
        for (int old = 1; old <= maxAccepted; old++) assertNotNull("v" + old + " must stay refused", stage(fin, new Bundles.Spec().v(old), a));
    }
}
