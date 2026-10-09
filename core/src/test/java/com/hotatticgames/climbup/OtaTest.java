package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.ota.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

/** Family-test OTA: checksum, compatibility gate, staging, apply-on-next-start, rollback and crash-loop protection, offline start. */
public class OtaTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static String tuningJson(float runSpeed) throws Exception {
        String base = new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json")), StandardCharsets.UTF_8);
        return base.replaceFirst("\"runSpeed\"\\s*:\\s*[0-9.]+", "\"runSpeed\": " + runSpeed);
    }
    private static byte[] zip(String name, String body) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(b)) { z.putNextEntry(new ZipEntry(name)); z.write(body.getBytes(StandardCharsets.UTF_8)); z.closeEntry(); }
        return b.toByteArray();
    }
    private static final String PAYLOAD_URL = OtaConfig.BASE + "payload.zip";
    private static String manifest(int version, byte[] payload, int runtime, int minCode, String sha) {
        return "{\"schema\":1,\"channel\":\"dev\",\"contentVersion\":" + version + ",\"runtime\":" + runtime + ",\"minVersionCode\":" + minCode
                + ",\"payloadUrl\":\"" + PAYLOAD_URL + "\",\"sha256\":\"" + (sha != null ? sha : OtaStore.sha256(payload)) + "\",\"size\":" + payload.length + "}";
    }
    private static Fetcher fake(Map<String, byte[]> files) {
        return (url, max) -> { byte[] d = files.get(url); if (d == null) throw new IOException("offline/404"); if (d.length > max) throw new IOException("big"); return d; };
    }
    private OtaStore store() { return new OtaStore(new File(tmp.getRoot(), "ota")); }
    private Map<String, byte[]> publish(int version, float runSpeed, int runtime, int minCode, String shaOverride) throws Exception {
        byte[] p = zip("tuning.json", tuningJson(runSpeed));
        Map<String, byte[]> m = new HashMap<>();
        m.put(OtaConfig.MANIFEST_URL, manifest(version, p, runtime, minCode, shaOverride).getBytes(StandardCharsets.UTF_8));
        m.put(PAYLOAD_URL, p);
        return m;
    }

    @Test public void downloadedPayloadAppliesOnlyOnTheNextColdStart() throws Exception {
        OtaStore s = store(); assertNull(s.startup());
        OtaClient c = new OtaClient(s, fake(publish(1, 7.25f, OtaConfig.RUNTIME, 0, null)), 10);
        String r = c.check(1000L, true);
        assertTrue(r, r.startsWith("downloaded v1"));
        assertEquals("staged, not applied mid-session", 1, s.stagedVersion());
        assertEquals(0, s.st.active); assertFalse(new File(tmp.getRoot(), "ota/active").exists());
        OtaStore next = store();                                  // simulated cold start
        String json = next.startup();
        assertNotNull(json); assertEquals(7.25f, com.hotatticgames.climbup.sim.Tuning.parse(json).runSpeed, 1e-4f);
        assertEquals(1, next.st.active); assertEquals(1, next.st.pending);
    }

    @Test public void badChecksumCorruptZipAndOddContentsAreRejected() throws Exception {
        OtaStore s = store(); s.startup();
        assertTrue(new OtaClient(s, fake(publish(1, 7f, OtaConfig.RUNTIME, 0, "0".repeat(64))), 10).check(1L, true).startsWith("checksum mismatch"));
        Map<String, byte[]> corrupt = publish(1, 7f, OtaConfig.RUNTIME, 0, null);
        byte[] bad = "not a zip at all".getBytes(StandardCharsets.UTF_8);
        corrupt.put(PAYLOAD_URL, bad); corrupt.put(OtaConfig.MANIFEST_URL, manifest(1, bad, OtaConfig.RUNTIME, 0, null).getBytes(StandardCharsets.UTF_8));
        assertTrue(new OtaClient(s, fake(corrupt), 10).check(2L, true).startsWith("payload rejected"));
        for (String name : new String[]{"../evil.json", "assets/x.png", "sfx.json"}) {
            byte[] p = zip(name, "{}"); Map<String, byte[]> m = new HashMap<>();
            m.put(OtaConfig.MANIFEST_URL, manifest(2, p, OtaConfig.RUNTIME, 0, null).getBytes(StandardCharsets.UTF_8)); m.put(PAYLOAD_URL, p);
            assertTrue(name, new OtaClient(s, fake(m), 10).check(3L, true).startsWith("payload rejected"));
        }
        byte[] insane = zip("tuning.json", tuningJson(7f).replaceFirst("\"gravity\"\\s*:\\s*[0-9.]+", "\"gravity\": -5"));
        Map<String, byte[]> m = new HashMap<>(); m.put(OtaConfig.MANIFEST_URL, manifest(2, insane, OtaConfig.RUNTIME, 0, null).getBytes(StandardCharsets.UTF_8)); m.put(PAYLOAD_URL, insane);
        assertTrue(new OtaClient(s, fake(m), 10).check(4L, true).startsWith("payload rejected"));
        assertEquals(0, s.stagedVersion()); assertNull(store().startup());
    }

    @Test public void versionGateAndWrongRuntimeHold() throws Exception {
        OtaStore s = store(); s.startup();
        assertTrue(new OtaClient(s, fake(publish(1, 7f, OtaConfig.RUNTIME + 1, 0, null)), 10).check(1L, true).contains("runtime"));
        assertTrue(new OtaClient(s, fake(publish(1, 7f, OtaConfig.RUNTIME, 99, null)), 10).check(2L, true).contains("newer app"));
        assertEquals(0, s.stagedVersion());
    }

    @Test public void offlineStartWorksAndFailureIsSilent() throws Exception {
        OtaStore s = store(); assertNull(s.startup());
        String r = new OtaClient(s, fake(new HashMap<>()), 10).check(5L, true);
        assertTrue(r, r.startsWith("offline")); assertNull(store().startup());
    }

    @Test public void downgradeAndReplayAreIgnored() throws Exception {
        OtaStore s = store(); s.startup();
        new OtaClient(s, fake(publish(3, 7f, OtaConfig.RUNTIME, 0, null)), 10).check(1L, true);
        OtaStore n = store(); assertNotNull(n.startup()); n.confirm();
        String r = new OtaClient(n, fake(publish(2, 9f, OtaConfig.RUNTIME, 0, null)), 10).check(2L, true);
        assertTrue(r, r.startsWith("up to date"));
        assertEquals(0, n.stagedVersion());
    }

    @Test public void anUnconfirmedUpdateIsRolledBackAfterTwoLaunchesAndNeverRetried() throws Exception {
        OtaStore s = store(); s.startup();
        new OtaClient(s, fake(publish(1, 7f, OtaConfig.RUNTIME, 0, null)), 10).check(1L, true);
        assertNotNull(store().startup());                         // launch 1: applied, pending (crashes before confirm)
        assertNotNull(store().startup());                         // launch 2: still on trial
        OtaStore third = store();
        assertNull("third launch falls back to the bundled content", third.startup());
        assertTrue(third.note, third.note.startsWith("rolled back v1"));
        assertTrue(third.st.bad.contains(1));
        assertEquals(0, third.st.active);
        String r = new OtaClient(third, fake(publish(1, 7f, OtaConfig.RUNTIME, 0, null)), 10).check(2L, true);
        assertTrue("a rolled-back version is not taken again: " + r, r.startsWith("up to date"));
    }

    @Test public void confirmedUpdateKeepsAndABetterOneReplacesIt() throws Exception {
        OtaStore s = store(); s.startup();
        new OtaClient(s, fake(publish(1, 7f, OtaConfig.RUNTIME, 0, null)), 10).check(1L, true);
        OtaStore a = store(); a.startup(); a.confirm();
        assertNotNull(store().startup());                          // stays applied across launches once confirmed
        OtaStore b0 = store(); b0.startup();
        new OtaClient(b0, fake(publish(2, 8f, OtaConfig.RUNTIME, 0, null)), 10).check(2L, true);
        OtaStore b = store(); String j = b.startup();
        assertEquals(8f, com.hotatticgames.climbup.sim.Tuning.parse(j).runSpeed, 1e-4f);
        assertEquals(1, b.st.previous);
        for (int i = 0; i < 3; i++) { b = store(); b.startup(); }   // v2 never confirmed -> back to v1, not to bundled
        assertEquals(1, b.st.active);
    }

    @Test public void tamperedActiveFileIsDetectedAndDropped() throws Exception {
        OtaStore s = store(); s.startup();
        new OtaClient(s, fake(publish(1, 7f, OtaConfig.RUNTIME, 0, null)), 10).check(1L, true);
        OtaStore a = store(); a.startup(); a.confirm();
        Files.write(new File(tmp.getRoot(), "ota/active/tuning.json").toPath(), tuningJson(30f).getBytes(StandardCharsets.UTF_8));
        assertNull(store().startup());
    }

    @Test public void interruptedDownloadLeavesNothingBehind() throws Exception {
        File tmpDir = new File(tmp.getRoot(), "ota/staging.tmp"); tmpDir.mkdirs();
        Files.write(new File(tmpDir, "tuning.json").toPath(), "half".getBytes(StandardCharsets.UTF_8));
        OtaStore s = store(); assertNull(s.startup());
        assertFalse(tmpDir.exists());
    }

    @Test public void checkIsRateLimitedToOncePerDay() throws Exception {
        OtaStore s = store(); s.startup();
        OtaClient c = new OtaClient(s, fake(new HashMap<>()), 10);
        c.check(1000L, false); assertEquals("not due", c.check(1000L + 3600_000L, false));
        assertNotEquals("not due", c.check(1000L + OtaConfig.CHECK_INTERVAL_MS + 1, false));
    }

    @Test public void bundledTuningPassesTheSameValidation() throws Exception {
        assertNull(OtaStore.validate(tuningJson(5.6f)));
    }
}
