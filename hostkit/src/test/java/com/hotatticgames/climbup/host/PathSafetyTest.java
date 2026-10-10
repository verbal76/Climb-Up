package com.hotatticgames.climbup.host;

import static org.junit.Assert.*;

import com.badlogic.gdx.files.FileHandle;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Path safety for game-file overrides, as a matrix. Two sides: what a SIGNED manifest may name (a path is a key, never a filesystem location - store files are addressed by hash - but a
 * hostile or buggy manifest must still be refused before it is trusted), and how the game's own request spellings map onto those keys. Level: unit / JVM integration.
 */
public class PathSafetyTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    static final String H = "a".repeat(64);

    static byte[] manifest(String... paths) {
        List<String> e = new ArrayList<>(); for (String p : paths) e.add("{\"path\":\"" + p + "\",\"sha256\":\"" + H + "\",\"size\":1}");
        return ("{\"schema\":1,\"assets\":[" + String.join(",", e) + "]}").getBytes(StandardCharsets.UTF_8);
    }
    static boolean accepted(String... paths) { try { AssetManifest.parse(manifest(paths)); return true; } catch (IllegalArgumentException e) { return false; } }

    @Test public void theManifestAcceptsOnlyPlainRelativeNamesAndRefusesEveryTraversalOrLookalikeSpelling() {
        String[] good = {"data/tuning.json", "models/pack/a_b-c.g3dj", "audio/jump.ogg", "x", "A1.png", "_hidden", "branding/studio_splash.png"};
        for (String g : good) assertTrue("must accept " + g, accepted(g));
        String[] bad = {
            "../x", "a/../b", "..", ".", "./a", "a/./b", "/abs", "a//b", "a/", "a\\\\b", "C:\\\\win", "a\\\\..\\\\b", "",                                 // classic traversal / separators (JSON-escaped backslashes)
            "%2e%2e/x", "%2E%2E%2Fx", "a%2fb", "a%5cb", "a%00b", "%2e/a",                                                                         // percent-encoded dots and separators are NOT decoded, and are refused outright
            "a\\u0000b", "a\\u0009b", "a\\nb", "a b", "a:b", "a*b", "a?b", "a|b", "a<b", "a\"b".replace("\"", "\\\""),                           // control characters, spaces, Windows-forbidden characters
            "\\uff0e\\uff0e/x", "a\\u2215b", "a\\u2044b", "a\\uff0fb", "a\\u202eb", "a\\u200bb", "e\\u0301", "\\u0430dmin", "\\u212a", "caf\\u00e9",     // fullwidth dot/slash, division/fraction slash, RLO, zero-width, combining, Cyrillic, Kelvin sign, non-ASCII
            "a..b", "..a", "a.."                                                                                                                  // any ".." substring
        };
        for (String b : bad) assertFalse("must refuse [" + b + "]", accepted(b));
    }

    @Test public void duplicatesByCaseAreRefusedInEveryLocale() {
        Locale old = Locale.getDefault();
        try {
            for (Locale l : new Locale[]{Locale.ROOT, Locale.US, new Locale("tr", "TR"), new Locale("az", "AZ"), new Locale("lt", "LT")}) {
                Locale.setDefault(l);
                assertFalse("ID/id must collide in " + l, accepted("ID", "id"));
                assertFalse("Data/I.png must collide in " + l, accepted("data/I.png", "Data/i.PNG"));
                assertTrue("distinct names must not collide in " + l, accepted("id", "idx"));
            }
        } finally { Locale.setDefault(old); }
    }

    @Test public void theCountAndSizeLimitsHold() {
        List<String> many = new ArrayList<>(); for (int i = 0; i <= AssetManifest.MAX_ASSETS; i++) many.add("f" + i);
        assertFalse("more than MAX_ASSETS entries", accepted(many.toArray(new String[0])));
        byte[] huge = ("{\"schema\":1,\"assets\":[{\"path\":\"a\",\"sha256\":\"" + H + "\",\"size\":99999999999999999999}]}").getBytes(StandardCharsets.UTF_8);
        try { AssetManifest.parse(huge); fail("an absurd size was accepted"); } catch (IllegalArgumentException expected) { }
        byte[] dup = ("{\"schema\":1,\"schema\":2,\"assets\":[]}").getBytes(StandardCharsets.UTF_8);
        try { AssetManifest.parse(dup); } catch (IllegalArgumentException ignored) { }       // duplicate keys: must not crash (the last value wins in libGDX; the SIGNED bytes are what is parsed)
    }

    // ---------------------------------------------------------------- the request side

    AssetStore store;
    OverlayFiles overlay(String path, byte[] data) throws Exception {
        store = new AssetStore(tmp.newFolder());
        AssetManifest.Asset a = AssetManifest.parse(manifestFor(path, data)).byPath.get(path);
        java.nio.file.Files.write(store.partFile(a.sha256).toPath(), data); store.publish(a, store.partFile(a.sha256));
        Map<String, AssetManifest.Asset> m = new LinkedHashMap<>(); m.put(path, a);
        return new OverlayFiles(new AssetsTest.ApkFiles(), m, store, s -> { });
    }
    static byte[] manifestFor(String p, byte[] d) { return ("{\"schema\":1,\"assets\":[{\"path\":\"" + p + "\",\"sha256\":\"" + Hashing.sha256(d) + "\",\"size\":" + d.length + "}]}").getBytes(StandardCharsets.UTF_8); }

    @Test public void everyEquivalentSpellingOfAnOverriddenPathServesTheOverrideAndNothingElseDoes() throws Exception {
        byte[] data = "{\"delivered\":true}".getBytes();
        OverlayFiles o = overlay("data/tuning.json", data);
        String[] same = {"data/tuning.json", "./data/tuning.json", "/data/tuning.json", "data\\tuning.json", "data//tuning.json", "data/./tuning.json", "data/x/../tuning.json", "./data//./x/../tuning.json/", "\\data\\tuning.json"};
        for (String s : same) assertArrayEquals("spelling [" + s + "] must reach the override", data, o.internal(s).readBytes());
        String[] other = {"DATA/tuning.json", "data/Tuning.json", "data/tuning.json.bak", "data/tuning", "../data/tuning.json", "data/../../data/tuning.json", "data%2ftuning.json", "data/tuning.json\u0000"};
        for (String s : other) {
            FileHandle h = o.internal(s);
            try { assertFalse("[" + s + "] must NOT be served from the override", java.util.Arrays.equals(data, h.readBytes())); } catch (Exception notThere) { /* the APK has no such file: also fine */ }
        }
    }

    @Test public void aRequestCanNeverEscapeIntoTheContentStore() throws Exception {
        byte[] data = "SECRET-STORE-BYTES".getBytes();
        OverlayFiles o = overlay("data/tuning.json", data);
        File storeFile = store.file(Hashing.sha256(data));
        String[] probes = {storeFile.getAbsolutePath(), storeFile.getName(), "../" + storeFile.getName(), storeFile.getParentFile().getName() + "/" + storeFile.getName(), "cas/" + storeFile.getName()};
        for (String p : probes) {
            try { assertFalse("[" + p + "] reached a content-store file", java.util.Arrays.equals(data, o.internal(p).readBytes())); } catch (Exception notThere) { /* not found in the APK: fine */ }
        }
    }
}
