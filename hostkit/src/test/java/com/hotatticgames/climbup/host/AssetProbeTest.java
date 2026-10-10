package com.hotatticgames.climbup.host;

import static org.junit.Assert.*;

import com.badlogic.gdx.files.FileHandle;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The asset probe over the game's real asset tree: what it counts, that the overlay without overrides is byte-identical to the APK, that an override changes exactly one path, and that failures are reported. Level: JVM integration. */
public class AssetProbeTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    AssetStore store; final List<String> diag = new ArrayList<>();

    @Before public void setUp() throws Exception { store = new AssetStore(tmp.newFolder("cas")); }

    AssetManifest.Asset put(String path, byte[] data) throws Exception {
        AssetManifest.Asset a = AssetManifest.parse(("{\"schema\":1,\"assets\":[{\"path\":\"" + path + "\",\"sha256\":\"" + Hashing.sha256(data) + "\",\"size\":" + data.length + "}]}").getBytes(StandardCharsets.UTF_8)).byPath.get(path);
        java.nio.file.Files.write(store.partFile(a.sha256).toPath(), data); store.publish(a, store.partFile(a.sha256)); return a;
    }
    OverlayFiles overlay(AssetManifest.Asset... as) { Map<String, AssetManifest.Asset> m = new LinkedHashMap<>(); for (AssetManifest.Asset a : as) m.put(a.path, a); return new OverlayFiles(new AssetsTest.ApkFiles(), m, store, diag::add); }

    @Test public void itReadsEveryFileOfTheRealAssetTreeAndCountsByType() throws Exception {
        AssetProbe.Result r = AssetProbe.run(new AssetsTest.ApkFiles(), null, null);
        long expected = 0; int n = 0;
        try (java.util.stream.Stream<java.nio.file.Path> w = java.nio.file.Files.walk(AssetsTest.APK.toPath())) {
            for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) w.filter(java.nio.file.Files::isRegularFile)::iterator) { n++; expected += java.nio.file.Files.size(p); }
        }
        assertTrue(r.line(), r.ok()); assertEquals("every file", n, r.files); assertEquals("every byte", expected, r.bytes); assertTrue("the real tree is big (" + r.files + ")", r.files > 200);
        assertEquals(r.files, r.byExtension.values().stream().mapToInt(Integer::intValue).sum());
        for (String type : new String[]{"png", "ogg", "json"}) assertTrue("the tree has " + type + " files: " + r.line(), r.byExtension.containsKey(type));
        assertTrue(r.line().startsWith("ASSETPROBE files=" + n + " bytes=" + expected + " digest=") && r.line().contains(" fail=0 ext="));
        assertEquals(16, r.digest.length());
    }

    @Test public void anOverlayWithNoOverrideReadsIdenticallyToThePackagedFilesAndOneOverrideChangesExactlyOnePath() throws Exception {
        AssetProbe.Result apk = AssetProbe.run(new AssetsTest.ApkFiles(), null, null);
        AssetProbe.Result same = AssetProbe.run(overlay(put("data/__unused_marker__.json", "{}".getBytes())), null, null);       // an override for a path the tree does not have changes nothing the walk finds
        assertEquals("the overlay is transparent for every untouched file", apk.digest, same.digest); assertEquals(apk.files, same.files); assertEquals(apk.bytes, same.bytes);
        String target = apk.sha256ByPath.keySet().stream().filter(p -> p.endsWith(".json")).findFirst().get();
        byte[] delivered = ("{\"delivered\":\"" + target + "\"}").getBytes();
        AssetProbe.Result over = AssetProbe.run(overlay(put(target, delivered)), null, null);
        assertNotEquals("the digest moves when a file is delivered", apk.digest, over.digest); assertEquals(apk.files, over.files);
        int differing = 0; for (Map.Entry<String, String> e : apk.sha256ByPath.entrySet()) if (!e.getValue().equals(over.sha256ByPath.get(e.getKey()))) { differing++; assertEquals(target, e.getKey()); }
        assertEquals("exactly the delivered path differs", 1, differing); assertEquals(Hashing.sha256(delivered), over.sha256ByPath.get(target));
        assertEquals(apk.bytes - AssetsTest.APK.toPath().resolve(target).toFile().length() + delivered.length, over.bytes);
    }

    @Test public void aFileThatOnlyExistsAsAnOverrideIsFoundThroughExtraPaths() throws Exception {
        AssetManifest.Asset added = put("data/brand_new.json", "{\"new\":true}".getBytes());
        AssetProbe.Result without = AssetProbe.run(overlay(added), null, null), with = AssetProbe.run(overlay(added), Arrays.asList("data/brand_new.json"), null);
        assertFalse("a directory listing cannot know it", without.sha256ByPath.containsKey("data/brand_new.json")); assertEquals(without.files + 1, with.files);
        assertEquals(Hashing.sha256("{\"new\":true}".getBytes()), with.sha256ByPath.get("data/brand_new.json"));
    }

    @Test public void loadersRunPerExtensionAndEveryFailureIsReportedWithoutStoppingTheWalk() throws Exception {
        Map<String, Integer> seen = new HashMap<>(); Map<String, AssetProbe.Loader> loaders = new HashMap<>();
        loaders.put("png", (p, h) -> { seen.merge("png", 1, Integer::sum); if (h.length() <= 0) throw new IllegalStateException("empty"); });
        loaders.put("ogg", (p, h) -> { seen.merge("ogg", 1, Integer::sum); throw new ClassCastException("simulated: not the backend's own handle"); });
        AssetProbe.Result r = AssetProbe.run(new AssetsTest.ApkFiles(), null, loaders);
        assertEquals("a loader ran for every png", r.byExtension.get("png"), seen.get("png")); assertEquals(r.byExtension.get("ogg"), seen.get("ogg"));
        assertEquals("every ogg failed, nothing else did", (int) r.byExtension.get("ogg"), r.failures); assertFalse(r.ok());
        assertTrue(r.line(), r.line().contains(" fail=" + r.failures + " ") && r.line().contains("first=") && r.line().contains("ClassCastException"));
        assertTrue("the walk went on past the failures: files were still counted", r.files > r.failures);
    }

    @Test public void anUnreadableFileAndAnEmptyTreeAreFailuresNotCrashes() throws Exception {
        AssetProbe.Result empty = AssetProbe.run(new MissingTree(), null, null);
        assertEquals(0, empty.files); assertFalse("an empty tree is not a pass", empty.ok());
        AssetProbe.Result ghost = AssetProbe.run(new AssetsTest.ApkFiles(), Arrays.asList("data/does_not_exist.png"), null);
        assertEquals(1, ghost.failures); assertTrue(ghost.failed.get(0).startsWith("data/does_not_exist.png:"));
        assertEquals("png", AssetProbe.extensionOf("a/b/c.PNG")); assertEquals("", AssetProbe.extensionOf("a.b/c")); assertEquals("", AssetProbe.extensionOf(".hidden"));
    }

    /** A Files whose internal tree does not exist at all. */
    static final class MissingTree implements com.badlogic.gdx.Files {
        public FileHandle internal(String p) { return new AssetsTest.ApkFiles.ApkHandle("__no_such_dir__/" + p); }
        public FileHandle getFileHandle(String p, FileType t) { return internal(p); }
        public FileHandle classpath(String p) { throw new UnsupportedOperationException(); } public FileHandle external(String p) { throw new UnsupportedOperationException(); }
        public FileHandle absolute(String p) { throw new UnsupportedOperationException(); } public FileHandle local(String p) { throw new UnsupportedOperationException(); }
        public String getExternalStoragePath() { return ""; } public boolean isExternalStorageAvailable() { return false; } public String getLocalStoragePath() { return ""; } public boolean isLocalStorageAvailable() { return false; }
    }
}
