package com.hotatticgames.climbup.host;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Thirty consecutive combined code + asset releases through the real downloader and store: every release is checked, downloaded, staged, activated at the next start and confirmed - except every
 * fifth, which never confirms and must be rolled back and blacklisted - and every seventh raises the save schema (its saves are migrated, or restored if it is rolled back). After EVERY step the
 * on-disk footprint must stay bounded (at most the active and the last-good module, only the content-store files they and a waiting release reference, no staging debris), nothing that
 * was rolled back may ever return, and the saves must be exactly right. Level: JVM integration, real filesystem, in-memory transport (no network).
 */
public class ReleaseStressTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    KeyPair key; TrustedKeys keys; HostInfo host; File data, root; DirSnapshots guard;
    final Map<String, byte[]> served = new HashMap<>(); final Map<Integer, byte[]> manifests = new HashMap<>(), sigs = new HashMap<>();
    final ModuleDownloader.Fetcher fetcher = (url, from, max) -> {
        byte[] b = served.get(url.replaceFirst("^https://x/", "")); if (b == null) throw new IOException("http 404");
        return new ByteArrayInputStream(b, (int) from, b.length - (int) from);
    };

    @Before public void setUp() throws Exception {
        key = Bundles.newKey(); keys = new TrustedKeys().add(key.getPublic()); host = new HostInfo(Bundles.APP, 1, "internal");
        data = tmp.newFolder("data"); root = new File(data, "host"); guard = new DirSnapshots(data, tmp.newFolder("snap"), new HashSet<>(Arrays.asList("host")));
    }
    ModuleStore open() { return new ModuleStore(root, keys, host).withSaveGuard(guard); }
    void write(String rel, String text) throws Exception { Files.write(new File(data, rel).toPath(), text.getBytes()); }
    String read(String rel) throws Exception { return new String(Files.readAllBytes(new File(data, rel).toPath())); }

    byte[] blob(int seed, int n) { byte[] b = new byte[n]; new java.util.Random(seed).nextBytes(b); return b; }

    void serve(Bundles.Spec s) throws Exception {
        File d = tmp.newFolder(); Map<String, byte[]> blobs = Bundles.write(d, s, key); served.clear();
        for (String n : d.list()) served.put(n, Files.readAllBytes(new File(d, n).toPath())); for (Map.Entry<String, byte[]> e : blobs.entrySet()) served.put("assets/" + e.getKey(), e.getValue());
    }

    /** Footprint invariants that must hold after every single step. */
    void footprint(ModuleStore s, String when) throws Exception {
        Set<String> mods = new HashSet<>(); String[] kids = new File(root, "mod").list(); if (kids != null) mods.addAll(Arrays.asList(kids));
        Set<String> allowed = new HashSet<>(); if (s.st.active != 0) allowed.add("v" + s.st.active); if (s.st.lastGood != 0) allowed.add("v" + s.st.lastGood);
        assertTrue(when + ": only the active and last-good modules may stay installed, found " + mods + " (active v" + s.st.active + ", lastGood v" + s.st.lastGood + ")", allowed.containsAll(mods));
        assertFalse(when + ": no staging debris", new File(root, "staging.tmp").exists());
        Set<String> referenced = new HashSet<>();
        for (File d : new File[]{s.dirOf(s.st.active), s.dirOf(s.st.lastGood), new File(root, "staged")}) {
            if (!d.isDirectory()) continue; for (AssetManifest.Asset a : AssetManifest.read(d).all()) referenced.add(a.sha256);
        }
        File cas = s.assets().file("0".repeat(64)).getParentFile(); String[] files = cas == null || !cas.isDirectory() ? new String[0] : cas.list();
        for (String f : files) { String base = f.endsWith(".part") ? f.substring(0, f.length() - 5) : f; assertTrue(when + ": content-store file " + f + " is referenced by nothing that is installed or waiting", referenced.contains(base)); }
        assertTrue(when + ": the state file stays small (" + new File(root, "state.json").length() + " bytes)", new File(root, "state.json").length() < 2048);
    }

    @Test public void thirtyCombinedReleasesWithRollbacksAndSchemaBumpsKeepAnExactBoundedStateAndNeverResurrectAnythingRolledBack() throws Exception {
        write("save.json", "{\"schema\":6}"); int schema = 6; Set<Integer> rolledBack = new HashSet<>(); int running = 0; long maxState = 0;
        for (int i = 1; i <= 30; i++) {
            ModuleStore s = open(); s.boot();
            boolean bump = i > 1 && i % 7 == 0, crash = i > 1 && i % 5 == 0;
            Bundles.Spec spec = new Bundles.Spec().v(i).save(schema, bump ? schema + 1 : schema)
                    .asset("data/common.json", "{\"shared\":true}".getBytes())                  // identical in every release: must be downloaded once and kept
                    .asset("data/release.json", ("{\"release\":" + i + "}").getBytes(StandardCharsets.UTF_8));
            if (i % 4 == 0) spec.asset("models/big_" + i + ".bin", blob(i, 40_000));
            serve(spec); manifests.put(i, served.get("manifest.json")); sigs.put(i, served.get("manifest.sig"));
            ModuleDownloader dl = new ModuleDownloader(s, fetcher, "https://x/");
            assertEquals("release " + i + " is delivered", "staged v" + i + " (applies on next start)", dl.check());
            footprint(s, "after staging v" + i);
            s = open(); ModuleStore.Boot b = s.boot();
            assertEquals("v" + i + " activates at the next start", i, b.manifest.moduleVersion);
            if (bump) write("save.json", "{\"schema\":" + (schema + 1) + "}");                      // the new module migrates the saves on its first run
            if (crash) {
                s = open(); s.boot(); ModuleStore last = open(); ModuleStore.Boot back = last.boot();     // never confirms: dropped on the third launch
                assertEquals("v" + i + " is rolled back to the last proven release", running, back.manifest.moduleVersion);
                assertTrue(last.st.bad.contains(i)); rolledBack.add(i); s = last;
                if (bump) assertEquals("rolled-back saves are exactly as before", "{\"schema\":" + schema + "}", read("save.json"));
            } else {
                s.confirm(); running = i; if (bump) { schema++; assertEquals("{\"schema\":" + schema + "}", read("save.json")); }
            }
            footprint(s, "after v" + i + (crash ? " (rolled back)" : " (confirmed)")); maxState = Math.max(maxState, new File(root, "state.json").length());
            ModuleStore again = open(); ModuleStore.Boot bt = again.boot();
            assertEquals("a restart keeps running the proven release", running, bt.manifest.moduleVersion);
            for (int dead : rolledBack) assertNotNull("rolled-back v" + dead + " must never be accepted again", again.preflight(manifests.get(dead), sigs.get(dead)));
            assertEquals(Math.max(i, again.st.highest), again.st.highest); assertEquals("every release so far was accepted once", i, again.st.highest);
        }
        assertEquals("the last proven release is v29 (v30 is a multiple of five and was rolled back)", 29, running);
        assertEquals(new HashSet<>(Arrays.asList(5, 10, 15, 20, 25, 30)), rolledBack);
        ModuleStore fin = open(); fin.boot(); footprint(fin, "end");
        assertTrue("the state file never grew past a few hundred bytes (" + maxState + ")", maxState < 1024);
    }

    @Test public void theSharedAssetIsDownloadedOnceAndNeverAgainWhileSeveralReleasesComeAndGo() throws Exception {
        ModuleStore s = open(); s.boot(); int downloadsOfCommon = 0; byte[] common = "{\"shared\":true}".getBytes(); String commonSha = Hashing.sha256(common);
        for (int i = 1; i <= 12; i++) {
            serve(new Bundles.Spec().v(i).asset("data/common.json", common).asset("data/release.json", ("{\"r\":" + i + "}").getBytes()));
            int[] hits = {0}; ModuleDownloader.Fetcher counting = (url, from, max) -> { if (url.endsWith("assets/" + commonSha)) hits[0]++; return fetcher.open(url, from, max); };
            assertEquals("staged v" + i + " (applies on next start)", new ModuleDownloader(s, counting, "https://x/").check()); downloadsOfCommon += hits[0];
            s = open(); s.boot(); s.confirm(); s = open(); s.boot();
        }
        assertEquals("the unchanged file crossed the wire exactly once in twelve releases", 1, downloadsOfCommon);
    }
}
