package com.hotatticgames.climbup.host;

import static org.junit.Assert.*;

import com.badlogic.gdx.Files;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.g3d.loader.G3dModelLoader;
import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.graphics.g3d.model.data.ModelMaterial;
import com.badlogic.gdx.graphics.g3d.model.data.ModelTexture;
import com.badlogic.gdx.utils.JsonReader;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Game-file overrides: manifest rules, the content store, and the overlay against the game's real asset tree. */
public class AssetsTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    static final File APK = new File("../assets");                    // stands in for the APK's asset directory

    // ------------------------------------------------------------ manifest

    static String json(String... entries) { return "{\"schema\":1,\"assets\":[" + String.join(",", entries) + "]}"; }
    static String entry(String path, String sha, long size) { return "{\"path\":\"" + path + "\",\"sha256\":\"" + sha + "\",\"size\":" + size + "}"; }
    static final String H = "a".repeat(64);

    @Test public void aWellFormedManifestParsesAndMalformedOnesAreRefused() {
        AssetManifest m = AssetManifest.parse(json(entry("data/tuning.json", H, 10), entry("models/a.obj", H, 5)).getBytes(StandardCharsets.UTF_8));
        assertEquals(2, m.byPath.size()); assertEquals(10, m.byPath.get("data/tuning.json").size);
        String[] bad = {json(entry("../x", H, 1)), json(entry("/abs/x", H, 1)), json(entry("a//b", H, 1)), json(entry("a\\b", H, 1)), json(entry("a/../b", H, 1)), json(entry("", H, 1)),
                json(entry("ok", "short", 1)), json(entry("ok", H.toUpperCase(), 1)), json(entry("ok", H, -1)), json(entry("ok", H, 1), entry("OK", H, 1)),
                json(entry("sp ace", H, 1)), json(entry("x".repeat(201), H, 1)), "{\"schema\":2,\"assets\":[]}", "{}", "[]", "nope", json(entry("ok", H, 300L * 1024 * 1024))};
        for (String b : bad) try { AssetManifest.parse(b.getBytes(StandardCharsets.UTF_8)); fail("accepted: " + b); } catch (IllegalArgumentException expected) { }
    }

    // ------------------------------------------------------------ store

    AssetManifest.Asset asset(String path, byte[] data) { return AssetManifest.parse(json(entry(path, Hashing.sha256(data), data.length)).getBytes(StandardCharsets.UTF_8)).byPath.get(path); }

    @Test public void theStorePublishesOnlyVerifiedBytesAndDetectsDamageAtRest() throws Exception {
        AssetStore st = new AssetStore(tmp.newFolder("root")); byte[] data = "hello world".getBytes(); AssetManifest.Asset a = asset("x/y.txt", data);
        java.nio.file.Files.write(st.partFile(a.sha256).toPath(), "hello worlD".getBytes());                  // same size, wrong content
        try { st.publish(a, st.partFile(a.sha256)); fail(); } catch (java.io.IOException expected) { }
        assertFalse(st.has(a)); assertFalse("the bad part is gone", st.partFile(a.sha256).exists());
        java.nio.file.Files.write(st.partFile(a.sha256).toPath(), data); st.publish(a, st.partFile(a.sha256));
        assertTrue(st.has(a)); assertNotNull(st.openVerified(a));
        AssetStore later = new AssetStore(tmp.getRoot().toPath().resolve("root").toFile());                  // a new run: first use re-hashes
        File f = later.file(a.sha256); f.setWritable(true); java.nio.file.Files.write(f.toPath(), "hello worlD".getBytes());
        assertNull("damage at rest is caught on first use", later.openVerified(a)); assertFalse("and the damaged copy is removed", f.exists());
    }

    @Test public void garbageCollectionKeepsExactlyWhatIsNamed() throws Exception {
        AssetStore st = new AssetStore(tmp.newFolder("root")); AssetManifest.Asset a = asset("a", "AAA".getBytes()), b = asset("b", "BBB".getBytes());
        for (AssetManifest.Asset x : new AssetManifest.Asset[]{a, b}) { java.nio.file.Files.write(st.partFile(x.sha256).toPath(), x == a ? "AAA".getBytes() : "BBB".getBytes()); st.publish(x, st.partFile(x.sha256)); }
        java.nio.file.Files.write(st.partFile("c".repeat(64)).toPath(), new byte[]{1});
        assertEquals(2, st.gc(new java.util.HashSet<>(java.util.Arrays.asList(a.sha256)))); assertTrue(st.has(a)); assertFalse(st.has(b));
    }

    // ------------------------------------------------------------ overlay over the real asset tree

    /** The APK stand-in: internal(path) reads ../assets/path. */
    static final class ApkFiles implements Files {
        /** Behaves like the backend's internal handle: the path is the LOGICAL asset path, content comes from the asset tree. */
        static final class ApkHandle extends FileHandle {
            final String logical;
            ApkHandle(String logical) { super(new File(APK, logical)); this.logical = logical; }
            @Override public String path() { return logical; }
            @Override public String name() { return logical.substring(logical.lastIndexOf('/') + 1); }
            @Override public FileType type() { return FileType.Internal; }
            @Override public FileHandle parent() { int i = logical.lastIndexOf('/'); return new ApkHandle(i < 0 ? "" : logical.substring(0, i)); }
            @Override public FileHandle child(String n) { return new ApkHandle(logical.isEmpty() ? n : logical + "/" + n); }
            @Override public FileHandle sibling(String n) { return parent().child(n); }
        }
        public FileHandle internal(String p) { return new ApkHandle(OverlayFiles.norm(p)); }
        public FileHandle absolute(String p) { return new AbsHandle(new File(p)); }
        public FileHandle getFileHandle(String p, FileType t) { return t == FileType.Internal ? internal(p) : absolute(p); }
        public FileHandle classpath(String p) { throw new UnsupportedOperationException(); } public FileHandle external(String p) { throw new UnsupportedOperationException(); } public FileHandle local(String p) { throw new UnsupportedOperationException(); }
        public String getExternalStoragePath() { return ""; } public boolean isExternalStorageAvailable() { return false; } public String getLocalStoragePath() { return ""; } public boolean isLocalStorageAvailable() { return false; }
    }
    static final class AbsHandle extends FileHandle { AbsHandle(File f) { super(f); } }

    AssetStore store; List<String> diag = new ArrayList<>();
    @Before public void setUp() throws Exception { store = new AssetStore(tmp.newFolder("cas-root")); }

    AssetManifest.Asset put(String path, byte[] data) throws Exception {
        AssetManifest.Asset a = asset(path, data); java.nio.file.Files.write(store.partFile(a.sha256).toPath(), data); store.publish(a, store.partFile(a.sha256)); return a;
    }
    OverlayFiles overlay(AssetManifest.Asset... as) { Map<String, AssetManifest.Asset> m = new LinkedHashMap<>(); for (AssetManifest.Asset a : as) m.put(a.path, a); return new OverlayFiles(new ApkFiles(), m, store, diag::add); }

    @Test public void everyApkFileReadsIdenticallyThroughTheOverlay() throws Exception {
        OverlayFiles o = overlay(put("data/tuning.json", "{}".getBytes()));                     // some override, so every path goes through OverlayHandle
        ApkFiles base = new ApkFiles(); int n = 0;
        try (java.util.stream.Stream<java.nio.file.Path> w = java.nio.file.Files.walk(APK.toPath())) {
            for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) w.filter(java.nio.file.Files::isRegularFile)::iterator) {
                String rel = APK.toPath().relativize(p).toString().replace(File.separatorChar, '/'); if (rel.equals("data/tuning.json")) continue;
                FileHandle a = base.internal(rel), b = o.internal(rel); n++;
                assertEquals(rel, a.length(), b.length()); assertTrue(rel, b.exists()); assertFalse(rel, b.isDirectory());
                assertArrayEquals(rel, a.readBytes(), b.readBytes()); assertEquals(rel, a.name(), b.name()); assertEquals(rel, a.extension(), b.extension()); assertEquals(rel, a.path(), b.path());
                assertEquals(rel, Files.FileType.Internal, b.type());
            }
        }
        assertTrue("walked the real tree (" + n + " files)", n > 200);
    }

    @Test public void anOverriddenFileIsServedFromTheStoreAndEverythingElseFromTheApk() throws Exception {
        byte[] custom = "{\"custom\":true}".getBytes();
        OverlayFiles o = overlay(put("data/tuning.json", custom));
        FileHandle h = o.internal("data/tuning.json");
        assertArrayEquals(custom, h.readBytes()); assertEquals(custom.length, h.length()); assertEquals("{\"custom\":true}", h.readString("UTF-8"));
        assertEquals("the path stays the logical one", "data/tuning.json", h.path());
        assertArrayEquals(new ApkFiles().internal("data/sfx.json").readBytes(), o.internal("data/sfx.json").readBytes());
        assertFalse(o.internal("data/missing.json").exists());
        assertTrue(o.internal("./data//tuning.json".replace("//", "/")).exists());
    }

    @Test public void navigationGoesBackThroughTheOverlayInBothDirections() throws Exception {
        String tex = firstFileUnder("models/Textures"); byte[] custom = "TEXTURE-OVERRIDE".getBytes();
        OverlayFiles o = overlay(put(tex, custom));
        FileHandle model = o.internal(firstFileUnder("models", ".obj"));                       // not overridden
        assertArrayEquals("a sibling lookup from an APK file finds the override", custom, model.parent().child(tex.substring("models/".length())).readBytes());
        assertArrayEquals(custom, o.internal("models").child(tex.substring("models/".length())).readBytes());
        assertEquals("models", model.parent().path()); assertEquals("", o.internal("models").parent().path());
        FileHandle overridden = o.internal(tex);
        String objName = firstFileUnder("models", ".obj").substring("models/".length());
        assertEquals("an overridden file's parent still resolves to the APK directory's other files", new ApkFiles().internal("models/" + objName).length(), overridden.parent().parent().child(objName).length());
    }

    @Test public void aModelServedFromTheStoreStillFindsItsTexturesWhereTheLoaderLooksForThem() throws Exception {
        String g3dj = firstFileUnder("hero", ".g3dj"); byte[] bytes = new ApkFiles().internal(g3dj).readBytes();
        OverlayFiles o = overlay(put(g3dj, bytes));                                             // the same model, now delivered
        ModelData viaApk = new G3dModelLoader(new JsonReader()).loadModelData(new ApkFiles().internal(g3dj)), viaOverlay = new G3dModelLoader(new JsonReader()).loadModelData(o.internal(g3dj));
        assertEquals(viaApk.meshes.size, viaOverlay.meshes.size); assertEquals(viaApk.nodes.size, viaOverlay.nodes.size); assertEquals(viaApk.materials.size, viaOverlay.materials.size);
        for (int i = 0; i < viaApk.materials.size; i++) {
            ModelMaterial a = viaApk.materials.get(i), b = viaOverlay.materials.get(i);
            int na = a.textures == null ? 0 : a.textures.size, nb = b.textures == null ? 0 : b.textures.size; assertEquals(na, nb);
            for (int t = 0; t < na; t++) { ModelTexture ta = a.textures.get(t), tb = b.textures.get(t); assertEquals("texture path as the loader derives it", ta.fileName, tb.fileName); }
        }
    }

    @Test public void audioOverridesAreTheBackendsOwnAbsoluteHandleTypeAndOthersAreNot() throws Exception {
        OverlayFiles o = overlay(put("audio/jump.wav", new byte[]{1, 2, 3, 4}), put("data/sfx.json", "{}".getBytes()));
        assertTrue("the Android audio classes cast to their own handle", o.internal("audio/jump.wav") instanceof AbsHandle);
        assertFalse(o.internal("data/sfx.json") instanceof AbsHandle);
        assertTrue("an audio file that is not overridden still reads from the APK", o.internal("audio/pack/" + new File(APK, "audio/pack").list()[0]).exists());
    }

    @Test public void aDamagedOverrideFallsBackToTheApkCopyAndSaysSo() throws Exception {
        byte[] good = "the real content".getBytes(); AssetManifest.Asset a = put("data/sfx.json", good);
        File f = store.file(a.sha256); f.setWritable(true); java.nio.file.Files.write(f.toPath(), "tampered content".getBytes());
        AssetStore later = new AssetStore(tmp.getRoot().toPath().resolve("cas-root").toFile());
        Map<String, AssetManifest.Asset> m = new LinkedHashMap<>(); m.put(a.path, a);
        OverlayFiles o = new OverlayFiles(new ApkFiles(), m, later, diag::add);
        assertArrayEquals("never the damaged bytes", new ApkFiles().internal("data/sfx.json").readBytes(), o.internal("data/sfx.json").readBytes());
        assertTrue(diag.get(0).contains("using the APK copy"));
    }

    static String firstFileUnder(String dir, String... suffix) {
        File[] kids = new File(APK, dir).listFiles(); java.util.Arrays.sort(kids);
        for (File k : kids) if (k.isFile() && (suffix.length == 0 || k.getName().endsWith(suffix[0]))) return dir + "/" + k.getName();
        for (File k : kids) if (k.isDirectory()) { try { return firstFileUnder(dir + "/" + k.getName(), suffix); } catch (RuntimeException ignored) { } }
        throw new IllegalStateException("none under " + dir);
    }
}
