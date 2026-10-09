package com.hotatticgames.climbup;

import static org.junit.Assert.*;
import static org.junit.Assume.*;

import com.hotatticgames.climbup.ota.*;
import com.hotatticgames.climbup.sim.*;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

/**
 * The whole pipeline over real HTTP (a local server, the same Fetcher.Http the app uses, the same OtaClient and OtaStore): publish a signed release,
 * check, download, verify, stage, restart, activate, and watch the simulation behave differently. Plus the publishing tool's own output (python + openssl).
 */
public class OtaEndToEndTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private HttpServer server; private final Map<String, byte[]> served = new HashMap<>(); private volatile int truncateTo = -1;
    private String base;

    @Before public void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ota/", ex -> {
            byte[] d = served.get(ex.getRequestURI().getPath());
            if (d == null) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
            if (truncateTo >= 0 && ex.getRequestURI().getPath().endsWith("payload.zip")) {      // promise the whole file, deliver part of it, hang up: an interrupted transfer
                ex.sendResponseHeaders(200, d.length); ex.getResponseBody().write(d, 0, Math.min(truncateTo, d.length)); ex.getResponseBody().flush(); ex.close(); return;
            }
            ex.sendResponseHeaders(200, d.length); ex.getResponseBody().write(d); ex.close();
        });
        server.start(); base = "http://127.0.0.1:" + server.getAddress().getPort() + "/ota/";
    }
    @After public void stop() { if (server != null) server.stop(0); }

    private OtaClient.Endpoint ep() { return new OtaClient.Endpoint(base, OtaConfig.CHANNEL, OtaTest.KEY.getPublic(), OtaConfig.RUNTIME); }
    private OtaClient client(OtaStore s) throws Exception { return new OtaClient(s, new Fetcher.Http(true), 10, OtaTest.bundled(), ep()); }
    private OtaStore store() { return new OtaStore(new File(tmp.getRoot(), "ota")); }
    private void publish(int version, String tuning) throws Exception {
        byte[] p = OtaTest.zip("tuning.json", tuning);
        byte[] m = OtaTest.manifest(version, p, OtaConfig.RUNTIME, 0, null, OtaConfig.CHANNEL, OtaVerifier.keyId(OtaTest.KEY.getPublic()), base + "payload.zip").getBytes(StandardCharsets.UTF_8);
        served.put("/ota/manifest.json", m); served.put("/ota/manifest.sig", OtaTest.sign(m, OtaTest.KEY.getPrivate())); served.put("/ota/payload.zip", p);
    }

    /** Peak height of a full-hold jump from level ground and distance covered in one second of running, both measured in the real Sim. */
    private static float[] feel(Tuning t) {
        Course c = new Course(1L, t.circumference());          // one wide flat platform
        c.add(new Element(Element.Type.STATIC, 0f, 0f, 40f));
        Sim s = Sim.startOn(c, t, 0); InputState in = new InputState();
        for (int i = 0; i < 20; i++) { in.clear(); s.step(in); }
        float s0 = s.s; for (int i = 0; i < 60; i++) { in.clear(); in.moveX = 1f; s.step(in); }
        float run = Math.abs(c.dsWrap(s.s, s0));
        Sim j = Sim.startOn(c, t, 0); for (int i = 0; i < 20; i++) { in.clear(); j.step(in); }
        float y0 = j.y, apex = y0; in.clear(); in.jumpPressed = true; in.jumpHeld = true; j.step(in);
        for (int i = 0; i < 120; i++) { in.clear(); in.jumpHeld = true; j.step(in); apex = Math.max(apex, j.y); }
        return new float[]{apex - y0, run};
    }

    @Test public void aSignedGameLayerChangeIsDeliveredAndActivatesOnTheNextStartAndChangesHowTheGamePlays() throws Exception {
        Tuning bundled = OtaTest.bundled();
        String newNumbers = OtaTest.bundledJson().replaceFirst("\"jumpVel\"\\s*:\\s*[0-9.]+", "\"jumpVel\": 12.4").replaceFirst("\"runSpeed\"\\s*:\\s*[0-9.]+", "\"runSpeed\": 6.1");
        publish(2, newNumbers);
        OtaStore s = store(); assertNull(s.startup());                                    // first launch: bundled
        String r = client(s).check(1000L, true);
        assertTrue(r, r.startsWith("downloaded v2"));
        assertEquals("nothing is applied mid-session", 0, s.st.active); assertEquals(2, s.stagedVersion());
        float[] before = feel(bundled);
        OtaStore next = store(); String json = next.startup();                            // cold start: the staged payload becomes the content
        assertNotNull(json); Tuning updated = Tuning.parse(json);
        float[] after = feel(updated);
        System.out.printf("jump height %.3f -> %.3f m, run in 1 s %.3f -> %.3f m%n", before[0], after[0], before[1], after[1]);
        assertEquals("jump apex scales with the delivered take-off speed squared", before[0] * (12.4f * 12.4f) / (bundled.jumpVel * bundled.jumpVel), after[0], 0.05f);
        assertTrue("the jump really is higher", after[0] > before[0] + 0.3f);
        assertTrue("and running really is faster", after[1] > before[1] + 0.3f);
        assertEquals("the layout of the tower is untouched", OtaStore.frozenMismatch(bundled, updated), null);
        // generation still works under the delivered numbers: a tower is built and the whole-course solver completes slices of it
        Course prev = null;
        for (int k = 0; k < 3; k++) { Course c = CourseGenerator.chunk(77, k, prev, updated); assertTrue("slice " + k + " completable under v2", Autopilot.run(c, updated, 4000f).completed); prev = c; }
        next.confirm();
        OtaStore again = store(); assertNotNull("stays active after confirmation", again.startup()); assertEquals(2, again.st.active); assertEquals(0, again.st.pending);
        String r2 = client(again).check(2000L, true); assertTrue(r2, r2.startsWith("up to date (v2)"));
    }

    @Test public void aForgedOrAlteredReleaseOverHttpChangesNothing() throws Exception {
        publish(2, OtaTest.tuningJson(7f));
        byte[] m = served.get("/ota/manifest.json");
        served.put("/ota/manifest.sig", OtaTest.sign(m, OtaTest.OTHER_KEY.getPrivate()));
        OtaStore s = store(); s.startup();
        assertTrue(client(s).check(1L, true).contains("signature invalid"));
        publish(2, OtaTest.tuningJson(7f)); served.put("/ota/payload.zip", OtaTest.zip("tuning.json", OtaTest.tuningJson(31f)));
        assertTrue(client(s).check(2L, true).contains("mismatch"));
        assertNull(store().startup()); assertEquals(0, s.stagedVersion());
    }

    @Test public void anInterruptedTransferIsDiscardedAndTheNextAttemptSucceeds() throws Exception {
        publish(2, OtaTest.tuningJson(7f));
        OtaStore s = store(); s.startup();
        truncateTo = served.get("/ota/payload.zip").length / 2;
        String r = client(s).check(1L, true);
        assertTrue(r, r.startsWith("offline or failed"));
        assertEquals(0, s.stagedVersion()); assertFalse(new File(tmp.getRoot(), "ota/staging").exists());
        truncateTo = -1;
        assertTrue(client(s).check(2L, true).startsWith("downloaded v2"));
        served.clear(); assertTrue("and with the network gone the game still starts", store().startup() != null);
    }

    @Test public void theReleaseThatThePublishingToolProducesIsAcceptedByTheApp() throws Exception {
        boolean tools = false; try { tools = new ProcessBuilder("openssl", "version").redirectErrorStream(true).start().waitFor() == 0 && new ProcessBuilder("python3", "--version").start().waitFor() == 0; } catch (IOException ignored) { }
        assumeTrue("python3 and openssl needed", tools);
        File dir = tmp.newFolder("tool"); String pass = "correct horse battery staple 0123456789abcdef";
        run(dir, null, "sh", "-c", "openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 | openssl pkcs8 -topk8 -v2 aes-256-cbc -iter 10000 -passout env:PW -out key.pem", pass);
        run(dir, null, "sh", "-c", "openssl pkey -in key.pem -passin env:PW -pubout -outform DER | base64 -w0 > pub.b64", pass);
        String pem = new String(Files.readAllBytes(new File(dir, "key.pem").toPath()), StandardCharsets.UTF_8);
        File out = new File(dir, "out");
        ProcessBuilder pb = new ProcessBuilder("python3", new File("../tools/ota/make_ota.py").getAbsolutePath(), "--version", "5", "--sign", "--out", out.getAbsolutePath(), "--base", base, "--public-key-file", new File(dir, "pub.b64").getAbsolutePath());
        pb.environment().put("OTA_SIGNING_PRIVATE_KEY", pem); pb.environment().put("OTA_SIGNING_KEY_PASSPHRASE", pass); pb.redirectErrorStream(true);
        Process p = pb.start(); String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(log, 0, p.waitFor());
        assertFalse("the tool never prints the key or the passphrase", log.contains("BEGIN") || log.contains(pass));
        for (String f : new String[]{"manifest.json", "manifest.sig", "payload.zip"}) served.put("/ota/" + f, Files.readAllBytes(new File(out, f).toPath()));
        OtaClient.Endpoint e = new OtaClient.Endpoint(base, OtaConfig.CHANNEL, OtaVerifier.parseKey(new String(Files.readAllBytes(new File(dir, "pub.b64").toPath()))), OtaConfig.RUNTIME);
        OtaStore s = store(); s.startup();
        String r = new OtaClient(s, new Fetcher.Http(true), 10, OtaTest.bundled(), e).check(1L, true);
        assertTrue("python-signed release accepted by the Java verifier: " + r, r.startsWith("downloaded v5"));
        // and the tool refuses to sign with a key that is not the pair of the public key
        File out2 = new File(dir, "out2");
        ProcessBuilder bad = new ProcessBuilder("python3", new File("../tools/ota/make_ota.py").getAbsolutePath(), "--version", "6", "--sign", "--out", out2.getAbsolutePath(), "--base", base);   // default (pinned) public key
        bad.environment().put("OTA_SIGNING_PRIVATE_KEY", pem); bad.environment().put("OTA_SIGNING_KEY_PASSPHRASE", pass); bad.redirectErrorStream(true);
        Process bp = bad.start(); bp.getInputStream().readAllBytes();
        assertNotEquals("signing with a key that does not match the pinned public key must fail", 0, bp.waitFor());
        assertFalse(new File(out2, "manifest.sig").exists());
    }

    private static void run(File dir, Map<String, String> env, String... cmd) throws Exception {
        String pw = cmd[cmd.length - 1]; String[] c = Arrays.copyOf(cmd, cmd.length - 1);
        ProcessBuilder pb = new ProcessBuilder(c).directory(dir).redirectErrorStream(true); pb.environment().put("PW", pw);
        Process p = pb.start(); String o = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(o, 0, p.waitFor());
    }
}
