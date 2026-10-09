package com.hotatticgames.climbup.host;

import static org.junit.Assert.*;

import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.security.KeyPair;
import java.util.HashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The download path end to end against a real local HTTP server: success, corruption, truncation, forged signature, rollback attempts, offline. */
public class DownloaderTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    KeyPair key, other; ModuleStore store; File root; HttpServer server; String base;
    final Map<String, byte[]> served = new HashMap<>(); volatile boolean truncateJar;

    @Before public void setUp() throws Exception {
        key = Bundles.newKey(); other = Bundles.newKey(); root = tmp.newFolder("ota");
        store = new ModuleStore(root, new TrustedKeys().add(key.getPublic()), new HostInfo(Bundles.APP, 1, "internal"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] b = served.get(ex.getRequestURI().getPath().substring(1));
            if (b == null) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream o = ex.getResponseBody()) { if (truncateJar && ex.getRequestURI().getPath().endsWith(".jar")) { o.write(b, 0, b.length / 2); o.flush(); ex.close(); return; } o.write(b); }
        });
        server.start(); base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }
    @After public void tearDown() { server.stop(0); }

    void serve(Bundles.Spec s, KeyPair signer) throws Exception {
        File d = tmp.newFolder(); Bundles.write(d, s, signer); served.clear();
        for (String n : d.list()) served.put(n, Files.readAllBytes(new File(d, n).toPath()));
    }
    String check() { return new ModuleDownloader(store, new ModuleDownloader.Http(true), base).check(); }

    @Test public void aSignedNewerModuleIsDownloadedVerifiedAndStagedButNotActivated() throws Exception {
        serve(new Bundles.Spec().v(2), key);
        assertEquals("staged v2 (applies on next start)", check());
        assertEquals(2, store.stagedVersion()); assertEquals("nothing was activated", 0, store.st.active);
        assertTrue(new File(root, "staged/module.jar").isFile());
        assertEquals("up to date (v2)", check());                                       // the same release is not downloaded again
    }

    @Test public void aFlippedByteIsCaughtWhileStreamingAndNothingIsStaged() throws Exception {
        serve(new Bundles.Spec().v(2), key);
        byte[] jar = served.get("module.jar"); jar[jar.length / 2] ^= 1;
        assertTrue(check(), check().contains("checksum mismatch"));
        assertEquals(0, store.stagedVersion()); assertFalse(new File(root, "staged").exists()); assertFalse(new File(root, "staging.tmp").exists());
    }

    @Test public void anInterruptedTransferIsDiscarded() throws Exception {
        serve(new Bundles.Spec().v(2), key); truncateJar = true;
        assertTrue(check().startsWith("offline or failed")); assertEquals(0, store.stagedVersion()); assertFalse(new File(root, "staging.tmp").exists());
        truncateJar = false;
        assertEquals("the next attempt starts clean and succeeds", "staged v2 (applies on next start)", check());
    }

    @Test public void aFileLargerThanTheSignedSizeIsRefused() throws Exception {
        serve(new Bundles.Spec().v(2), key);
        byte[] h = served.get("hello.txt"); byte[] bigger = java.util.Arrays.copyOf(h, h.length + 50); served.put("hello.txt", bigger);
        assertTrue(check().contains("larger than declared") || check().contains("offline or failed")); assertEquals(0, store.stagedVersion());
    }

    @Test public void aManifestSignedByAnUnpinnedKeyIsIgnoredBeforeAnyModuleByteIsFetched() throws Exception {
        serve(new Bundles.Spec().v(2), other);
        assertEquals("manifest signature invalid", check()); assertFalse(new File(root, "staging.tmp").exists());
    }

    @Test public void aManifestEditedAfterSigningIsIgnored() throws Exception {
        serve(new Bundles.Spec().v(2), key);
        byte[] m = served.get("manifest.json"); String s = new String(m, "UTF-8").replace("\"moduleVersion\":2", "\"moduleVersion\":9"); served.put("manifest.json", s.getBytes("UTF-8"));
        assertEquals("manifest signature invalid", check());
    }

    @Test public void anOlderReleaseServedLaterIsNotTaken() throws Exception {
        serve(new Bundles.Spec().v(3), key); assertTrue(check().startsWith("staged v3"));
        serve(new Bundles.Spec().v(2), key); assertEquals("up to date (v3)", check());
    }

    @Test public void aRolledBackVersionIsNeverDownloadedAgain() throws Exception {
        serve(new Bundles.Spec().v(1), key); assertTrue(check().startsWith("staged v1")); store.boot(); store.boot(); store.boot();     // v1 never confirms: dropped
        assertTrue(store.st.bad.contains(1));
        assertEquals("up to date (v1)", check());                                       // highest also blocks it; both guards agree
    }

    @Test public void offlineIsSilentAndHarmless() throws Exception {
        server.stop(0);
        assertTrue(check().startsWith("offline or failed")); assertEquals(0, store.stagedVersion());
    }

    @Test public void cleartextIsRefusedByTheReleaseFetcher() {
        assertTrue(new ModuleDownloader(store, new ModuleDownloader.Http(), base).check().contains("https only"));
    }
}
