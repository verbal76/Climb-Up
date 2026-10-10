package com.hotatticgames.climbup.host;

import static org.junit.Assert.*;

import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Signed game files end to end: download into the content store (resumable), gate activation on completeness, serve, and clean up. */
public class AssetDeliveryTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    KeyPair key; ModuleStore store; File root; HttpServer server; String base; TrustedKeys keys; HostInfo host;
    final Map<String, byte[]> served = new HashMap<>(); final List<String> requests = new ArrayList<>();
    volatile String cutAfterBytesFor = null; volatile int cutAt = 0; volatile boolean ignoreRange = false;

    static byte[] bytes(int n, int seed) { byte[] b = new byte[n]; new java.util.Random(seed).nextBytes(b); return b; }

    @Before public void setUp() throws Exception {
        key = Bundles.newKey(); root = tmp.newFolder("ota"); keys = new TrustedKeys().add(key.getPublic()); host = new HostInfo(Bundles.APP, 1, "internal");
        store = new ModuleStore(root, keys, host);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath().substring(1); String range = ex.getRequestHeaders().getFirst("Range");
            requests.add(path + (range == null ? "" : " [" + range + "]"));
            byte[] b = served.get(path);
            if (b == null) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
            int from = 0; boolean partial = false;
            if (range != null && !ignoreRange) { from = Integer.parseInt(range.replaceAll("[^0-9-]", "").split("-")[0]); partial = true; }
            int len = b.length - from;
            if (partial) ex.getResponseHeaders().add("Content-Range", "bytes " + from + "-" + (b.length - 1) + "/" + b.length);      // RFC 9110: a 206 for one range always carries it
            ex.sendResponseHeaders(partial ? 206 : 200, len);
            try (OutputStream o = ex.getResponseBody()) {
                if (cutAfterBytesFor != null && path.endsWith(cutAfterBytesFor)) { o.write(b, from, Math.min(cutAt, len)); o.flush(); ex.close(); return; }
                o.write(b, from, len);
            }
        });
        server.start(); base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }
    @After public void tearDown() { server.stop(0); }

    void publish(Bundles.Spec s) throws Exception {
        File d = tmp.newFolder(); Map<String, byte[]> blobs = Bundles.write(d, s, key); served.clear();
        for (String n : d.list()) served.put(n, Files.readAllBytes(new File(d, n).toPath()));
        for (Map.Entry<String, byte[]> e : blobs.entrySet()) served.put("assets/" + e.getKey(), e.getValue());
    }
    String check() { requests.clear(); return new ModuleDownloader(store, new ModuleDownloader.Http(true), base).check(); }
    ModuleStore.Boot boot() { store = new ModuleStore(root, keys, host); return store.boot(); }
    int blobRequests() { int n = 0; for (String r : requests) if (r.startsWith("assets/")) n++; return n; }

    @Test public void gameFilesAreDownloadedIntoTheStoreAndServedOnlyAfterTheNextColdStart() throws Exception {
        byte[] a = bytes(5000, 1), b = bytes(70000, 2);
        publish(new Bundles.Spec().v(1).asset("data/tuning.json", a).asset("models/x.bin", b));
        assertEquals("staged v1 (applies on next start)", check());
        assertEquals("nothing is active before a cold start", 0, store.st.active);
        ModuleStore.Boot bt = boot();
        assertEquals(2, bt.overrides.size());
        File f = store.assets().openVerified(bt.overrides.get("models/x.bin")); assertArrayEquals(b, Files.readAllBytes(f.toPath()));
    }

    @Test public void anInterruptedDownloadResumesFromWhereItStoppedAndFinishesVerified() throws Exception {
        byte[] big = bytes(300000, 3);
        publish(new Bundles.Spec().v(1).asset("models/big.bin", big));
        cutAfterBytesFor = Hashing.sha256(big); cutAt = 120000;
        assertTrue(check().startsWith("offline or failed")); assertEquals("nothing staged", 0, store.stagedVersion());
        File part = store.assets().partFile(Hashing.sha256(big)); assertEquals("the partial download is kept", 120000, part.length());
        cutAfterBytesFor = null;
        assertEquals("staged v1 (applies on next start)", check());
        assertTrue("the second attempt asked for the rest only: " + requests, requests.contains("assets/" + Hashing.sha256(big) + " [bytes=120000-]"));
        assertArrayEquals(big, Files.readAllBytes(store.assets().file(Hashing.sha256(big)).toPath())); assertFalse(part.exists());
    }

    @Test public void aServerThatIgnoresRangeStillYieldsACorrectFile() throws Exception {
        byte[] big = bytes(200000, 4); publish(new Bundles.Spec().v(1).asset("models/big.bin", big));
        cutAfterBytesFor = Hashing.sha256(big); cutAt = 50000; check(); cutAfterBytesFor = null; ignoreRange = true;
        assertEquals("staged v1 (applies on next start)", check()); assertArrayEquals(big, Files.readAllBytes(store.assets().file(Hashing.sha256(big)).toPath()));
    }

    @Test public void aCorruptedFileIsDiscardedAndTheNextAttemptStartsOver() throws Exception {
        byte[] good = bytes(40000, 5); publish(new Bundles.Spec().v(1).asset("models/x.bin", good));
        String sha = Hashing.sha256(good); byte[] bad = good.clone(); bad[1234] ^= 1; served.put("assets/" + sha, bad);
        String r = check(); assertTrue(r, r.contains("checksum mismatch")); assertEquals(0, store.stagedVersion());
        assertFalse("nothing unverified is kept", store.assets().partFile(sha).exists()); assertFalse(store.assets().file(sha).exists());
        served.put("assets/" + sha, good); assertEquals("staged v1 (applies on next start)", check());
    }

    @Test public void aReleaseWhoseFileIsMissingOnTheServerNeverStagesAndNeverActivates() throws Exception {
        publish(new Bundles.Spec().v(1).asset("models/x.bin", bytes(1000, 6)).asset("models/y.bin", bytes(1000, 7)));
        String some = served.keySet().stream().filter(k -> k.startsWith("assets/")).findFirst().get(); served.remove(some);
        String r = check(); assertTrue(r, r.startsWith("offline or failed")); assertEquals(0, store.stagedVersion()); assertTrue(boot().recovery());
    }

    @Test public void onlyChangedFilesAreDownloadedForTheNextRelease() throws Exception {
        byte[] a = bytes(9000, 8), b = bytes(9000, 9), b2 = bytes(9000, 10), c = bytes(9000, 11);
        publish(new Bundles.Spec().v(1).asset("a.bin", a).asset("b.bin", b)); check(); boot(); store.confirm();
        publish(new Bundles.Spec().v(2).asset("a.bin", a).asset("b.bin", b2).asset("c.bin", c));
        assertEquals("staged v2 (applies on next start)", check());
        assertEquals("only b and c are fetched; a is already in the store", 2, blobRequests());
        assertFalse(requests.toString(), requests.contains("assets/" + Hashing.sha256(a)));
    }

    @Test public void aModuleThatLostAFileFromTheStoreIsNotRunAndTheSafetyNetTakesOver() throws Exception {
        publish(new Bundles.Spec().v(1).asset("a.bin", bytes(3000, 12))); check(); boot(); store.confirm();
        publish(new Bundles.Spec().v(2).asset("a.bin", bytes(3000, 13))); check(); assertEquals(2, boot().manifest.moduleVersion); store.confirm();
        // v2 is proven and v1 was pruned; damage the store now
        for (File f : new File(root, "cas").listFiles()) { f.setWritable(true); f.delete(); }
        ModuleStore.Boot b = boot(); assertTrue(b.recovery()); assertFalse("a lost file is not the release's fault", store.st.bad.contains(2));
        assertTrue(store.st.rollback.contains("game file missing"));
    }

    @Test public void theStoreKeepsOnlyWhatTheProvenModuleNeeds() throws Exception {
        byte[] a = bytes(2000, 14), b = bytes(2000, 15);
        publish(new Bundles.Spec().v(1).asset("a.bin", a)); check(); boot(); store.confirm();
        publish(new Bundles.Spec().v(2).asset("b.bin", b)); check(); boot();
        assertTrue("v1's file is kept while v2 is unproven (it is the safety net)", store.assets().file(Hashing.sha256(a)).exists());
        store.confirm();
        assertFalse("once v2 has proven itself v1's file is released", store.assets().file(Hashing.sha256(a)).exists()); assertTrue(store.assets().file(Hashing.sha256(b)).exists());
    }

    @Test public void aReleaseWithoutGameFilesChangesNothingAboutHowFilesAreRead() throws Exception {
        publish(new Bundles.Spec().v(1)); check(); ModuleStore.Boot b = boot();
        assertTrue("no overrides: the host installs no overlay at all", b.overrides.isEmpty());
    }
}
