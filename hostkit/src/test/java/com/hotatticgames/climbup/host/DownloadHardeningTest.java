package com.hotatticgames.climbup.host;

import static org.junit.Assert.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.security.KeyPair;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Download hardening against a real local HTTP server: the redirect policy (https only, never a downgrade, bounded hops), Range / Content-Range validation on resume, a wall-clock budget
 * (a slow drip must not hold the check forever), free-space handling, and no partial publish after any of them. Level: JVM integration.
 */
public class DownloadHardeningTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    KeyPair key; ModuleStore store; File root; HttpServer server; String base;
    final Map<String, byte[]> served = new HashMap<>();
    final Map<String, String> redirects = new HashMap<>();           // path -> Location
    final AtomicInteger hits = new AtomicInteger();
    volatile String rangeMode = "honest";                             // honest | wrongStart | noContentRange | always416 | ignore
    volatile int dripMillis = 0;
    volatile String lastRange;

    @Before public void setUp() throws Exception {
        key = Bundles.newKey(); root = tmp.newFolder("ota");
        store = new ModuleStore(root, new TrustedKeys().add(key.getPublic()), new HostInfo(Bundles.APP, 1, "internal"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            hits.incrementAndGet();
            String path = ex.getRequestURI().getPath().substring(1);
            if (redirects.containsKey(path)) { ex.getResponseHeaders().add("Location", redirects.get(path)); ex.sendResponseHeaders(302, -1); ex.close(); return; }
            byte[] b = served.get(path);
            if (b == null) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
            String range = ex.getRequestHeaders().getFirst("Range"); lastRange = range;
            if (range != null && !rangeMode.equals("ignore")) { serveRange(ex, b, range); return; }
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream o = ex.getResponseBody()) { if (dripMillis > 0) { for (byte x : b) { o.write(x); o.flush(); Thread.sleep(dripMillis); } } else o.write(b); } catch (Exception ignored) { }
        });
        server.start(); base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }
    @After public void tearDown() { server.stop(0); }

    void serveRange(HttpExchange ex, byte[] b, String range) throws IOException {
        int from = Integer.parseInt(range.substring("bytes=".length(), range.indexOf('-')));
        if (rangeMode.equals("always416")) { ex.sendResponseHeaders(416, -1); ex.close(); return; }
        int start = rangeMode.equals("wrongStart") ? Math.max(0, from - 7) : from;
        if (!rangeMode.equals("noContentRange")) ex.getResponseHeaders().add("Content-Range", "bytes " + start + "-" + (b.length - 1) + "/" + b.length);
        ex.sendResponseHeaders(206, b.length - start);
        try (OutputStream o = ex.getResponseBody()) { o.write(b, start, b.length - start); }
    }

    void serve(Bundles.Spec s) throws Exception {
        File d = tmp.newFolder(); java.util.Map<String, byte[]> blobs = Bundles.write(d, s, key); served.clear();
        for (String n : d.list()) served.put(n, Files.readAllBytes(new File(d, n).toPath()));
        for (java.util.Map.Entry<String, byte[]> e : blobs.entrySet()) served.put("assets/" + e.getKey(), e.getValue());
    }
    ModuleDownloader dl() { return new ModuleDownloader(store, new ModuleDownloader.Http(true), base); }

    // ---------------------------------------------------------------- redirect policy

    @Test public void theRedirectPolicyIsHttpsOnlyNeverADowngradeAndNeverCredentialsInTheUrl() throws Exception {
        assertEquals("https://cdn.example.org/a/b", ModuleDownloader.Http.redirectTarget("https://github.com/x", "https://cdn.example.org/a/b", false));
        assertEquals("a relative Location is resolved against the current URL", "https://github.com/rel/y", ModuleDownloader.Http.redirectTarget("https://github.com/x/z", "/rel/y", false));
        String[][] refused = {
            {"https://github.com/x", "http://evil.example.org/x", "false"},       // downgrade
            {"https://github.com/x", "http://evil.example.org/x", "true"},        // downgrade is refused even when cleartext is allowed for lab servers
            {"http://10.0.2.2/x", "http://evil.example.org/x", "false"},          // cleartext target without permission
            {"https://github.com/x", "ftp://example.org/x", "false"}, {"https://github.com/x", "file:///etc/passwd", "true"}, {"https://github.com/x", "jar:file:/x!/y", "true"},
            {"https://github.com/x", "https://user:pw@example.org/x", "false"},   // credentials in the URL
            {"https://github.com/x", "javascript:alert(1)", "false"}, {"https://github.com/x", "", "false"},
        };
        for (String[] r : refused) try { ModuleDownloader.Http.redirectTarget(r[0], r[1], Boolean.parseBoolean(r[2])); fail("followed " + r[1] + " from " + r[0] + " allowPlain=" + r[2]); } catch (IOException expected) { }
        assertEquals("a lab server may redirect within cleartext when explicitly allowed", "http://10.0.2.2/y", ModuleDownloader.Http.redirectTarget("http://10.0.2.2/x", "http://10.0.2.2/y", true));
    }

    @Test public void aBoundedNumberOfRedirectsIsFollowedAndALoopIsStopped() throws Exception {
        serve(new Bundles.Spec().v(2));
        redirects.put("manifest.json", "hop1"); redirects.put("hop1", "hop2"); redirects.put("hop2", "manifest.json2"); served.put("manifest.json2", served.get("manifest.json"));
        // three hops to a real file: allowed. (The manifest itself is then served from the final URL; files still come from the base.)
        String within = dl().check();
        assertTrue("redirects within the limit are followed: " + within, within.startsWith("staged v2"));
        for (int i = 0; i < 12; i++) redirects.put("loop" + i, "loop" + (i + 1)); redirects.put("loop12", "loop0");
        redirects.put("manifest.json", "loop0");
        int before = hits.get(), stagedBefore = store.stagedVersion();
        String r = dl().check();
        assertTrue("a long or looping chain is refused: " + r, r.startsWith("offline or failed") && r.contains("redirect"));
        assertTrue("and stops after a bounded number of requests (" + (hits.get() - before) + ")", hits.get() - before <= ModuleDownloader.Http.MAX_REDIRECTS + 2);
        assertEquals("a refused chain changes nothing", stagedBefore, store.stagedVersion()); assertFalse(new File(root, "staging.tmp").exists());
    }

    // ---------------------------------------------------------------- resume: Range / Content-Range

    byte[] asset() { byte[] b = new byte[200_000]; new java.util.Random(7).nextBytes(b); return b; }

    @Test public void aResumedDownloadContinuesWhereItStoppedAndOnlyWhenTheServerProvesIt() throws Exception {
        byte[] data = asset(); serve(new Bundles.Spec().v(2).asset("data/blob.bin", data));
        File part = store.assets().partFile(Hashing.sha256(data)); part.getParentFile().mkdirs(); Files.write(part.toPath(), java.util.Arrays.copyOf(data, data.length / 2));
        assertEquals("staged v2 (applies on next start)", dl().check()); assertEquals("bytes=" + data.length / 2 + "-", lastRange);
        assertEquals(2, store.stagedVersion());
    }

    @Test public void aPartialResponseThatStartsAtTheWrongOffsetIsRefusedAndNeverAppended() throws Exception {
        byte[] data = asset(); serve(new Bundles.Spec().v(2).asset("data/blob.bin", data)); rangeMode = "wrongStart";
        File part = store.assets().partFile(Hashing.sha256(data)); part.getParentFile().mkdirs(); Files.write(part.toPath(), java.util.Arrays.copyOf(data, data.length / 2));
        String r = dl().check();
        assertTrue(r, r.startsWith("offline or failed") && r.contains("Content-Range")); assertEquals(0, store.stagedVersion());
        assertFalse("no staging left behind", new File(root, "staging.tmp").exists());
        rangeMode = "honest"; assertEquals("and the next honest attempt completes", "staged v2 (applies on next start)", dl().check());
    }

    @Test public void aPartialResponseWithoutContentRangeIsRefused() throws Exception {
        byte[] data = asset(); serve(new Bundles.Spec().v(2).asset("data/blob.bin", data)); rangeMode = "noContentRange";
        File part = store.assets().partFile(Hashing.sha256(data)); part.getParentFile().mkdirs(); Files.write(part.toPath(), java.util.Arrays.copyOf(data, data.length / 2));
        assertTrue(dl().check().contains("Content-Range")); assertEquals(0, store.stagedVersion());
    }

    @Test public void aServerThatIgnoresRangeIsStillHandledAndARejectedRangeDiscardsThePartialFileSoItCannotLoopForever() throws Exception {
        byte[] data = asset(); serve(new Bundles.Spec().v(2).asset("data/blob.bin", data));
        File part = store.assets().partFile(Hashing.sha256(data)); part.getParentFile().mkdirs(); Files.write(part.toPath(), java.util.Arrays.copyOf(data, data.length / 2));
        rangeMode = "ignore"; assertEquals("a server that sends the whole file anyway", "staged v2 (applies on next start)", dl().check());
        // a partial file the server cannot satisfy (416) used to be kept and fail the same way on every future check
        ModuleStore s2 = new ModuleStore(tmp.newFolder("ota2"), new TrustedKeys().add(key.getPublic()), new HostInfo(Bundles.APP, 1, "internal"));
        File p2 = s2.assets().partFile(Hashing.sha256(data)); p2.getParentFile().mkdirs(); Files.write(p2.toPath(), java.util.Arrays.copyOf(data, data.length / 2));
        rangeMode = "always416";
        String r = new ModuleDownloader(s2, new ModuleDownloader.Http(true), base).check();
        assertTrue(r, r.startsWith("offline or failed")); assertFalse("the unusable partial file is discarded", p2.exists());
        rangeMode = "honest"; assertEquals("so the next check starts over and succeeds", "staged v2 (applies on next start)", new ModuleDownloader(s2, new ModuleDownloader.Http(true), base).check());
    }

    // ---------------------------------------------------------------- time and space

    @Test public void aSlowDripCannotHoldTheCheckPastItsWallClockBudget() throws Exception {
        serve(new Bundles.Spec().v(2)); dripMillis = 40;
        long t0 = System.nanoTime();
        String r = dl().withTimeBudgetMillis(1500).check();
        long took = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(r, r.startsWith("offline or failed") && r.contains("time budget")); assertTrue("took " + took + " ms", took < 6000);
        assertEquals(0, store.stagedVersion()); assertFalse(new File(root, "staging.tmp").exists());
    }

    @Test public void notEnoughFreeStorageIsDetectedBeforeAnythingIsWrittenAndNothingIsLeftBehind() throws Exception {
        serve(new Bundles.Spec().v(2));
        String r = dl().withFreeSpace(() -> 10_000L).check();
        assertTrue(r, r.contains("not enough storage")); assertEquals(0, store.stagedVersion());
        assertFalse(new File(root, "staging.tmp").exists()); assertFalse(new File(root, "staged").exists());
        assertEquals("with space it works", "staged v2 (applies on next start)", dl().withFreeSpace(() -> Long.MAX_VALUE / 2).check());
    }

    @Test public void aDiskFullErrorHalfWayThroughLeavesNoStagingAndTheInstallUntouched() throws Exception {
        serve(new Bundles.Spec().v(2));
        ModuleDownloader.Fetcher full = (url, from, max) -> new InputStream() {
            int n; @Override public int read() throws IOException { if (n++ > 100) throw new IOException("No space left on device"); return 'x'; }
            @Override public int read(byte[] b, int o, int l) throws IOException { if (n > 100) throw new IOException("No space left on device"); int k = Math.min(l, 100); for (int i = 0; i < k; i++) b[o + i] = 'x'; n += k; return k; } };
        ModuleDownloader.Fetcher mixed = (url, from, max) -> url.endsWith("module.jar") ? full.open(url, from, max) : new ModuleDownloader.Http(true).open(url, from, max);
        String r = new ModuleDownloader(store, mixed, base).check();
        assertTrue(r, r.startsWith("offline or failed")); assertEquals(0, store.stagedVersion()); assertEquals(0, store.st.active);
        assertFalse(new File(root, "staging.tmp").exists()); assertFalse(new File(root, "staged").exists());
    }
}
