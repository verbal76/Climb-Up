package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.ota.*;
import com.hotatticgames.climbup.sim.Tuning;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

/** Signed OTA: signature, compatibility gate, staging, apply-on-next-start, rollback, crash-loop protection, corrupt and interrupted downloads, offline start. */
public class OtaTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    static final KeyPair KEY = newKey(), OTHER_KEY = newKey();
    static KeyPair newKey() { try { KeyPairGenerator g = KeyPairGenerator.getInstance("EC"); g.initialize(256); return g.generateKeyPair(); } catch (Exception e) { throw new IllegalStateException(e); } }
    static final String BASE = OtaConfig.BASE;
    static final OtaClient.Endpoint EP = new OtaClient.Endpoint(BASE, OtaConfig.CHANNEL, KEY.getPublic(), OtaConfig.RUNTIME);

    static String bundledJson() throws Exception { return new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json")), StandardCharsets.UTF_8); }
    static Tuning bundled() throws Exception { return Tuning.parse(bundledJson()); }
    static String tuningJson(float runSpeed) throws Exception { return bundledJson().replaceFirst("\"runSpeed\"\\s*:\\s*[0-9.]+", "\"runSpeed\": " + runSpeed); }
    static byte[] zip(String name, String body) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(b)) { z.putNextEntry(new ZipEntry(name)); z.write(body.getBytes(StandardCharsets.UTF_8)); z.closeEntry(); }
        return b.toByteArray();
    }
    static byte[] sign(byte[] data, PrivateKey k) throws Exception { Signature s = Signature.getInstance("SHA256withECDSA"); s.initSign(k); s.update(data); return Base64.getEncoder().encode(s.sign()); }

    static String manifest(int version, byte[] payload, int runtime, int minCode, String sha, String channel, String keyId, String url) {
        return "{\"schema\":" + OtaConfig.SCHEMA + ",\"channel\":\"" + channel + "\",\"keyId\":\"" + keyId + "\",\"contentVersion\":" + version + ",\"runtime\":" + runtime + ",\"minVersionCode\":" + minCode
                + ",\"payloadUrl\":\"" + url + "\",\"sha256\":\"" + (sha != null ? sha : OtaStore.sha256(payload)) + "\",\"size\":" + payload.length + "}";
    }
    /** A correctly signed release: manifest.json, manifest.sig, payload.zip. */
    static Map<String, byte[]> publish(int version, String tuning, int runtime, int minCode) throws Exception {
        byte[] p = zip("tuning.json", tuning);
        return publishPayload(version, p, runtime, minCode, null);
    }
    static Map<String, byte[]> publishPayload(int version, byte[] p, int runtime, int minCode, String shaOverride) throws Exception {
        byte[] m = manifest(version, p, runtime, minCode, shaOverride, OtaConfig.CHANNEL, OtaVerifier.keyId(KEY.getPublic()), BASE + "payload.zip").getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> files = new HashMap<>();
        files.put(BASE + "manifest.json", m); files.put(BASE + "manifest.sig", sign(m, KEY.getPrivate())); files.put(BASE + "payload.zip", p);
        return files;
    }
    static Fetcher fake(Map<String, byte[]> files) {
        return (url, max) -> { byte[] d = files.get(url); if (d == null) throw new IOException("offline/404"); if (d.length > max) throw new IOException("big"); return d; };
    }
    OtaStore store() { return new OtaStore(new File(tmp.getRoot(), "ota")); }
    OtaClient client(OtaStore s, Map<String, byte[]> files) throws Exception { return new OtaClient(s, fake(files), 10, bundled(), EP); }

    @Test public void downloadedPayloadAppliesOnlyOnTheNextColdStart() throws Exception {
        OtaStore s = store(); assertNull(s.startup());
        String r = client(s, publish(2, tuningJson(7.25f), OtaConfig.RUNTIME, 0)).check(1000L, true);
        assertTrue(r, r.startsWith("downloaded v2"));
        assertEquals("staged, not applied mid-session", 2, s.stagedVersion());
        assertEquals(0, s.st.active); assertFalse(new File(tmp.getRoot(), "ota/active").exists());
        OtaStore next = store();                                  // simulated cold start
        String json = next.startup();
        assertNotNull(json); assertEquals(7.25f, Tuning.parse(json).runSpeed, 1e-4f);
        assertEquals(2, next.st.active); assertEquals(2, next.st.pending);
    }

    @Test public void aManifestThatIsNotSignedByThePinnedKeyIsIgnoredBeforeAnythingInItIsRead() throws Exception {
        OtaStore s = store(); s.startup();
        byte[] p = zip("tuning.json", tuningJson(7f));
        byte[] m = manifest(2, p, OtaConfig.RUNTIME, 0, null, OtaConfig.CHANNEL, OtaVerifier.keyId(KEY.getPublic()), BASE + "payload.zip").getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> wrongKey = new HashMap<>(); wrongKey.put(BASE + "manifest.json", m); wrongKey.put(BASE + "manifest.sig", sign(m, OTHER_KEY.getPrivate())); wrongKey.put(BASE + "payload.zip", p);
        assertTrue(client(s, wrongKey).check(1L, true).contains("signature invalid"));
        Map<String, byte[]> altered = publish(2, tuningJson(7f), OtaConfig.RUNTIME, 0);                  // validly signed, then changed after signing
        altered.put(BASE + "manifest.json", new String(altered.get(BASE + "manifest.json"), StandardCharsets.UTF_8).replace("\"contentVersion\":2", "\"contentVersion\":9").getBytes(StandardCharsets.UTF_8));
        assertTrue(client(s, altered).check(2L, true).contains("signature invalid"));
        Map<String, byte[]> noSig = publish(2, tuningJson(7f), OtaConfig.RUNTIME, 0); noSig.remove(BASE + "manifest.sig");
        assertTrue(client(s, noSig).check(3L, true).startsWith("offline"));
        Map<String, byte[]> junk = publish(2, tuningJson(7f), OtaConfig.RUNTIME, 0); junk.put(BASE + "manifest.sig", "AAAA".getBytes(StandardCharsets.UTF_8));
        assertTrue(client(s, junk).check(4L, true).contains("signature invalid"));
        Map<String, byte[]> other = publish(2, tuningJson(7f), OtaConfig.RUNTIME, 0); other.put(BASE + "manifest.sig", sign("something else".getBytes(StandardCharsets.UTF_8), KEY.getPrivate()));
        assertTrue("a genuine signature over other content", client(s, other).check(5L, true).contains("signature invalid"));
        assertEquals(0, s.stagedVersion()); assertNull(store().startup());
    }

    @Test public void aSwappedPayloadUnderASignedManifestIsRejectedByItsChecksum() throws Exception {
        OtaStore s = store(); s.startup();
        Map<String, byte[]> files = publish(2, tuningJson(7f), OtaConfig.RUNTIME, 0);
        files.put(BASE + "payload.zip", zip("tuning.json", tuningJson(9f)));                               // same size class, different content
        String r = client(s, files).check(1L, true);
        assertTrue(r, r.startsWith("checksum mismatch") || r.startsWith("size mismatch"));
        assertEquals(0, s.stagedVersion());
    }

    @Test public void corruptZipOddContentsInsaneNumbersAndLayoutChangesAreRejected() throws Exception {
        OtaStore s = store(); s.startup();
        byte[] bad = "not a zip at all".getBytes(StandardCharsets.UTF_8);
        assertTrue(client(s, publishPayload(2, bad, OtaConfig.RUNTIME, 0, null)).check(2L, true).startsWith("payload rejected"));
        for (String name : new String[]{"../evil.json", "assets/x.png", "sfx.json"}) {
            assertTrue(name, client(s, publishPayload(2, zip(name, "{}"), OtaConfig.RUNTIME, 0, null)).check(3L, true).startsWith("payload rejected"));
        }
        String insane = tuningJson(7f).replaceFirst("\"gravity\"\\s*:\\s*[0-9.]+", "\"gravity\": -5");
        assertTrue(client(s, publish(2, insane, OtaConfig.RUNTIME, 0)).check(4L, true).startsWith("payload rejected"));
        String layout = tuningJson(7f).replaceFirst("\"chunkHeight\"\\s*:\\s*[0-9.]+", "\"chunkHeight\": 80");
        String r = client(s, publish(2, layout, OtaConfig.RUNTIME, 0)).check(5L, true);
        assertTrue(r, r.contains("new app build") && r.contains("chunkHeight"));
        assertEquals(0, s.stagedVersion()); assertNull(store().startup());
    }

    @Test public void compatibilityGateRuntimeChannelKeyAndMinimumBuildAreEnforced() throws Exception {
        OtaStore s = store(); s.startup();
        assertTrue(client(s, publish(2, tuningJson(7f), OtaConfig.RUNTIME + 1, 0)).check(1L, true).contains("runtime"));
        assertTrue(client(s, publish(2, tuningJson(7f), OtaConfig.RUNTIME - 1, 0)).check(2L, true).contains("runtime"));
        assertTrue(client(s, publish(2, tuningJson(7f), OtaConfig.RUNTIME, 99)).check(3L, true).contains("newer app"));
        byte[] p = zip("tuning.json", tuningJson(7f));
        for (String[] variant : new String[][]{{"beta", OtaVerifier.keyId(KEY.getPublic())}, {OtaConfig.CHANNEL, "0000000000000000"}}) {
            byte[] m = manifest(2, p, OtaConfig.RUNTIME, 0, null, variant[0], variant[1], BASE + "payload.zip").getBytes(StandardCharsets.UTF_8);
            Map<String, byte[]> f = new HashMap<>(); f.put(BASE + "manifest.json", m); f.put(BASE + "manifest.sig", sign(m, KEY.getPrivate())); f.put(BASE + "payload.zip", p);
            String r = client(s, f).check(4L, true); assertTrue(r, r.contains("channel") || r.contains("another key"));
        }
        byte[] m = manifest(2, p, OtaConfig.RUNTIME, 0, null, OtaConfig.CHANNEL, OtaVerifier.keyId(KEY.getPublic()), "https://evil.example/payload.zip").getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> f = new HashMap<>(); f.put(BASE + "manifest.json", m); f.put(BASE + "manifest.sig", sign(m, KEY.getPrivate())); f.put(BASE + "payload.zip", p);
        assertTrue("payload must come from the release", client(s, f).check(5L, true).contains("manifest rejected"));
        assertEquals(0, s.stagedVersion());
    }

    @Test public void theBundledContentVersionIsAlreadyCurrent() throws Exception {
        OtaStore s = store(); s.startup();
        String r = client(s, publish(OtaConfig.BUNDLED_CONTENT_VERSION, bundledJson(), OtaConfig.RUNTIME, 0)).check(1L, true);
        assertTrue(r, r.startsWith("up to date (v" + OtaConfig.BUNDLED_CONTENT_VERSION));
        assertEquals(0, s.stagedVersion());
    }

    @Test public void offlineStartWorksAndFailureIsSilent() throws Exception {
        OtaStore s = store(); assertNull(s.startup());
        String r = client(s, new HashMap<>()).check(5L, true);
        assertTrue(r, r.startsWith("offline")); assertNull(store().startup());
    }

    @Test public void downgradeAndReplayOfAnOlderSignedManifestAreIgnored() throws Exception {
        OtaStore s = store(); s.startup();
        client(s, publish(3, tuningJson(7f), OtaConfig.RUNTIME, 0)).check(1L, true);
        OtaStore n = store(); assertNotNull(n.startup()); n.confirm();
        String r = client(n, publish(2, tuningJson(9f), OtaConfig.RUNTIME, 0)).check(2L, true);      // a genuine, older, signed release replayed by an attacker
        assertTrue(r, r.startsWith("up to date"));
        assertEquals(0, n.stagedVersion());
    }

    @Test public void anUnconfirmedUpdateIsRolledBackAfterTwoLaunchesAndNeverRetried() throws Exception {
        OtaStore s = store(); s.startup();
        client(s, publish(2, tuningJson(7f), OtaConfig.RUNTIME, 0)).check(1L, true);
        assertNotNull(store().startup());                         // launch 1: applied, pending (crashes before confirm)
        assertNotNull(store().startup());                         // launch 2: still on trial
        OtaStore third = store();
        assertNull("third launch falls back to the bundled content", third.startup());
        assertTrue(third.note, third.note.startsWith("rolled back v2"));
        assertTrue(third.st.bad.contains(2));
        assertEquals(0, third.st.active);
        String r = client(third, publish(2, tuningJson(7f), OtaConfig.RUNTIME, 0)).check(2L, true);
        assertTrue("a rolled-back version is not taken again: " + r, r.startsWith("up to date"));
    }

    @Test public void confirmedUpdateKeepsAndABetterOneReplacesIt() throws Exception {
        OtaStore s = store(); s.startup();
        client(s, publish(2, tuningJson(7f), OtaConfig.RUNTIME, 0)).check(1L, true);
        OtaStore a = store(); a.startup(); a.confirm();
        assertNotNull(store().startup());                          // stays applied across launches once confirmed
        OtaStore b0 = store(); b0.startup();
        client(b0, publish(3, tuningJson(8f), OtaConfig.RUNTIME, 0)).check(2L, true);
        OtaStore b = store(); String j = b.startup();
        assertEquals(8f, Tuning.parse(j).runSpeed, 1e-4f);
        assertEquals(2, b.st.previous);
        for (int i = 0; i < 3; i++) { b = store(); b.startup(); }   // v3 never confirmed -> back to v2, not to bundled
        assertEquals(2, b.st.active);
    }

    @Test public void tamperedActiveFileIsDetectedAndDropped() throws Exception {
        OtaStore s = store(); s.startup();
        client(s, publish(2, tuningJson(7f), OtaConfig.RUNTIME, 0)).check(1L, true);
        OtaStore a = store(); a.startup(); a.confirm();
        Files.write(new File(tmp.getRoot(), "ota/active/tuning.json").toPath(), tuningJson(30f).getBytes(StandardCharsets.UTF_8));
        assertNull(store().startup());
    }

    @Test public void interruptedDownloadsLeaveNothingBehindAndTheNextCheckRecovers() throws Exception {
        File tmpDir = new File(tmp.getRoot(), "ota/staging.tmp"); tmpDir.mkdirs();                   // a download killed mid-write
        Files.write(new File(tmpDir, "tuning.json").toPath(), "half".getBytes(StandardCharsets.UTF_8));
        OtaStore s = store(); assertNull(s.startup());
        assertFalse(tmpDir.exists());
        Map<String, byte[]> files = publish(2, tuningJson(7f), OtaConfig.RUNTIME, 0);
        Fetcher dropsOnPayload = (url, max) -> { if (url.endsWith("payload.zip")) throw new IOException("connection reset"); return fake(files).get(url, max); };
        assertTrue(new OtaClient(s, dropsOnPayload, 10, bundled(), EP).check(1L, true).startsWith("offline"));
        byte[] whole = files.get(BASE + "payload.zip"); Map<String, byte[]> cut = new HashMap<>(files); cut.put(BASE + "payload.zip", Arrays.copyOf(whole, whole.length / 2));
        assertTrue(client(s, cut).check(2L, true).startsWith("size mismatch"));                        // delivered short: discarded
        assertEquals(0, s.stagedVersion()); assertNull(store().startup());
        assertTrue("the next check simply succeeds", client(s, files).check(3L, true).startsWith("downloaded v2"));
    }

    @Test public void checkIsRateLimitedToOncePerDay() throws Exception {
        OtaStore s = store(); s.startup();
        OtaClient c = client(s, new HashMap<>());
        c.check(1000L, false); assertEquals("not due", c.check(1000L + 3600_000L, false));
        assertNotEquals("not due", c.check(1000L + OtaConfig.CHECK_INTERVAL_MS + 1, false));
    }

    @Test public void bundledTuningPassesTheSameValidationAndLayoutCheck() throws Exception {
        assertNull(OtaStore.validate(tuningJson(5.6f)));
        assertNull(OtaStore.frozenMismatch(bundled(), Tuning.parse(tuningJson(6.5f))));
    }
}
