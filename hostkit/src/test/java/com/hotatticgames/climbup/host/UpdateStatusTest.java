package com.hotatticgames.climbup.host;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.KeyPair;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The status model's hard rule - never "up to date" unless it is true for everything the claim could cover - checked exhaustively over every combination of its inputs, then against a real store
 * and downloader (including the case that motivated it: a rolled-back v8 makes the store answer "up to date (v8)" while v1 runs). Level: unit + JVM integration.
 */
public class UpdateStatusTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    KeyPair key; HostInfo host; File root;

    @Before public void setUp() throws Exception { key = Bundles.newKey(); host = new HostInfo(Bundles.APP, 1, "internal"); root = tmp.newFolder("ota"); }

    static UpdateStatus st(UpdateStatus.Check c, Set<UpdateStatus.Component> checked, int active, int staged, String rollback, int checkVersion) {
        return new UpdateStatus("Climb up", "internal", 1, active, staged, Math.max(active, checkVersion), 0, "abc", 2, false, "", rollback, c, checkVersion, 1_000_000L, checked);
    }

    // ---------------------------------------------------------------- the rule, exhaustively

    @Test public void upToDateIsClaimedIfAndOnlyIfItIsTrueForEverythingItCouldCover() {
        int claims = 0, total = 0;
        for (UpdateStatus.Check c : UpdateStatus.Check.values())
            for (int mask = 0; mask < 8; mask++) {
                Set<UpdateStatus.Component> checked = EnumSet.noneOf(UpdateStatus.Component.class);
                for (UpdateStatus.Component k : UpdateStatus.Component.values()) if ((mask & (1 << k.ordinal())) != 0) checked.add(k);
                for (int active : new int[]{0, 4, 5}) for (int staged : new int[]{0, 5}) for (String rb : new String[]{"", "rolled back v5"}) for (int ver : new int[]{0, 4, 5, 8}) for (long now : new long[]{0, 1_000_000L, 5_000_000L, 900_000_000L}) {
                    UpdateStatus s = st(c, checked, active, staged, rb, ver); String h = s.headline(now); total++;
                    boolean claimed = h.toLowerCase().contains("up to date");
                    boolean truth = c == UpdateStatus.Check.NO_NEWER_RELEASE && checked.contains(UpdateStatus.Component.MODULE) && checked.contains(UpdateStatus.Component.ASSETS) && staged == 0 && rb.isEmpty() && active != 0 && ver == active;
                    assertEquals("claim vs truth for " + c + " " + checked + " active=" + active + " staged=" + staged + " rb=[" + rb + "] ver=" + ver + ": " + h, truth, claimed);
                    assertEquals(truth, s.isUpToDate());
                    if (claimed) { claims++; assertTrue("the claim must say what it does not cover: " + h, h.contains("app itself is not checked")); }
                    for (String line : s.lines(now)) assertFalse("diagnostics lines must not make the claim: " + line, line.toLowerCase().contains("up to date"));
                }
            }
        assertTrue("the claim is reachable (" + claims + " of " + total + ")", claims > 0 && claims < total);
    }

    @Test public void theHeadlineAlwaysSaysSomethingAndNeverThrows() {
        for (UpdateStatus.Check c : UpdateStatus.Check.values()) for (long now : new long[]{-5, 0, Long.MAX_VALUE})
            assertFalse(new UpdateStatus(null, null, 0, 0, 0, 0, 0, null, 0, false, null, null, c, -1, -1, null).headline(now).isEmpty());
        assertFalse(new UpdateStatus(null, null, 0, 0, 0, 0, 0, null, 0, false, null, null, null, 0, 0, null).lines(0).isEmpty());
    }

    // ---------------------------------------------------------------- against a real store and downloader

    final Map<String, byte[]> served = new HashMap<>(); volatile boolean offline;
    ModuleDownloader.Fetcher fetcher = (url, from, max) -> {
        if (offline) throw new IOException("offline");
        byte[] b = served.get(url.substring(url.lastIndexOf("//") + 2).replaceFirst("^[^/]*/", "")); if (b == null) throw new IOException("http 404");
        return new ByteArrayInputStream(b, (int) from, b.length - (int) from);
    };
    void serve(Bundles.Spec s) throws Exception { File d = tmp.newFolder(); Map<String, byte[]> blobs = Bundles.write(d, s, key); served.clear(); for (String n : d.list()) served.put(n, Files.readAllBytes(new File(d, n).toPath())); for (Map.Entry<String, byte[]> e : blobs.entrySet()) served.put("assets/" + e.getKey(), e.getValue()); }
    ModuleStore open() { return new ModuleStore(root, new TrustedKeys().add(key.getPublic()), host); }

    @Test public void aRealLifecycleNeverOverclaimsAtAnyStep() throws Exception {
        ModuleStore s = open(); s.boot(); ModuleDownloader dl = new ModuleDownloader(s, fetcher, "https://x/");
        assertEquals("fresh install: nothing known", "Content updates: not checked yet", UpdateStatus.capture(s, host, "Climb up", null).headline(0));
        serve(new Bundles.Spec().v(1)); assertTrue(dl.check().startsWith("staged v1"));
        UpdateStatus staged = UpdateStatus.capture(s, host, "Climb up", dl); assertTrue(staged.headline(0), staged.headline(0).contains("applies at the next start")); assertFalse(staged.isUpToDate());
        s = open(); s.boot(); s.confirm(); dl = new ModuleDownloader(s, fetcher, "https://x/");
        UpdateStatus before = UpdateStatus.capture(s, host, "Climb up", dl); assertFalse("a running module with no check in this process is not 'up to date'", before.isUpToDate());
        assertEquals("up to date (v1)", dl.check());
        UpdateStatus ok = UpdateStatus.capture(s, host, "Climb up", dl); assertTrue(ok.headline(dl.checkedAtMillis + 120_000), ok.isUpToDate()); assertTrue(ok.headline(dl.checkedAtMillis + 120_000).contains("v1"));
        offline = true; dl.check(); offline = false;
        UpdateStatus failed = UpdateStatus.capture(s, host, "Climb up", dl); assertFalse("a failed check is not 'up to date'", failed.isUpToDate()); assertTrue(failed.headline(0), failed.headline(0).contains("Could not check"));
    }

    @Test public void aRolledBackReleaseMakesThePreflightSayUpToDateButTheStatusModelDoesNot() throws Exception {
        ModuleStore s = open(); s.boot(); ModuleDownloader dl = new ModuleDownloader(s, fetcher, "https://x/");
        serve(new Bundles.Spec().v(1)); dl.check(); s = open(); s.boot(); s.confirm();                       // v1 is running and proven
        dl = new ModuleDownloader(s, fetcher, "https://x/"); serve(new Bundles.Spec().v(8)); assertTrue(dl.check().startsWith("staged v8"));
        s = open(); s.boot(); s = open(); s.boot(); s = open(); ModuleStore.Boot b = s.boot();                  // v8 never confirms: rolled back and blacklisted
        assertEquals(1, b.manifest.moduleVersion); assertTrue(s.st.bad.contains(8));
        dl = new ModuleDownloader(s, fetcher, "https://x/");
        assertEquals("the store's own text is the misleading one", "up to date (v8)", dl.check());
        UpdateStatus u = UpdateStatus.capture(s, host, "Climb up", dl);
        assertFalse(u.isUpToDate()); String h = u.headline(dl.checkedAtMillis); assertFalse(h, h.toLowerCase().contains("up to date"));
        assertTrue(h, h.contains("Rolled back") && h.contains("v1"));
    }

    @Test public void theAssetSetIdIsStableOrderIndependentAndChangesWithAnyFile() throws Exception {
        AssetManifest a = AssetManifest.parse(("{\"schema\":1,\"assets\":[{\"path\":\"a\",\"sha256\":\"" + "1".repeat(64) + "\",\"size\":1},{\"path\":\"b\",\"sha256\":\"" + "2".repeat(64) + "\",\"size\":1}]}").getBytes());
        AssetManifest b = AssetManifest.parse(("{\"schema\":1,\"assets\":[{\"path\":\"b\",\"sha256\":\"" + "2".repeat(64) + "\",\"size\":1},{\"path\":\"a\",\"sha256\":\"" + "1".repeat(64) + "\",\"size\":1}]}").getBytes());
        AssetManifest c = AssetManifest.parse(("{\"schema\":1,\"assets\":[{\"path\":\"a\",\"sha256\":\"" + "1".repeat(64) + "\",\"size\":1},{\"path\":\"b\",\"sha256\":\"" + "3".repeat(64) + "\",\"size\":1}]}").getBytes());
        assertEquals(UpdateStatus.assetSetId(a), UpdateStatus.assetSetId(b)); assertNotEquals(UpdateStatus.assetSetId(a), UpdateStatus.assetSetId(c)); assertEquals(12, UpdateStatus.assetSetId(a).length());
        ModuleStore s = open(); s.boot(); ModuleDownloader dl = new ModuleDownloader(s, fetcher, "https://x/");
        serve(new Bundles.Spec().v(1).asset("data/x.json", "{}".getBytes())); dl.check(); s = open(); s.boot(); s.confirm();
        UpdateStatus u = UpdateStatus.capture(s, host, "Climb up", null);
        assertEquals(1, u.assetCount); assertEquals(12, u.assetSetId.length()); assertTrue(String.join("\n", u.lines(0)), u.lines(0).toString().contains(u.assetSetId));
        assertEquals("a module that serves no files says so", "none", UpdateStatus.capture(open(), host, "Climb up", null).assetSetId);
    }
}
