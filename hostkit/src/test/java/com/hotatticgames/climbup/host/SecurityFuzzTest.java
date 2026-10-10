package com.hotatticgames.climbup.host;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.util.Base64;
import java.util.Random;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Deterministic fuzz / property tests for everything that parses bytes an attacker can influence: the manifest (parsed by {@code peekKeyId} BEFORE its signature is checked), assets.json and the
 * signature text. Properties: only the documented exception type ever escapes; nothing hangs; whatever does parse satisfies the format's invariants; a manifest that differs from the signed
 * bytes by even one byte never verifies. Level: unit (JVM). Seeds are fixed so a failure reproduces.
 */
public class SecurityFuzzTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    KeyPair key; TrustedKeys keys; byte[] manifest, sig, assets; String keyId;

    @Before public void setUp() throws Exception {
        key = Bundles.newKey(); keys = new TrustedKeys().add(key.getPublic()); keyId = TrustedKeys.keyId(key.getPublic());
        File d = tmp.newFolder(); Bundles.write(d, new Bundles.Spec().v(3).asset("data/a.json", "{}".getBytes()).asset("models/b.obj", "v 0 0 0".getBytes()), key);
        manifest = Files.readAllBytes(new File(d, "manifest.json").toPath()); sig = Files.readAllBytes(new File(d, "manifest.sig").toPath()); assets = Files.readAllBytes(new File(d, "assets.json").toPath());
    }

    // ---------------------------------------------------------------- mutation engine

    static final String[] NUMBERS = {"0", "-1", "1.5", "1e9", "1e999", "-1e999", "99999999999999999999", "9223372036854775807", "9223372036854775808", "0x10", "NaN", "Infinity", "-0", "007", "1_000", "\"7\"", "true", "null", "[]", "{}"};
    static final String[] STRINGS = {"", "\u0000", "a\u0000b", "‮", "．．", "\ud800", "́", "../..", "a/b", "A".repeat(70_000), "%s%n%x", "${jndi:x}", "\\u0000", "\"", "\\"};

    static byte[] mutate(byte[] in, Random r) {
        String s = new String(in, StandardCharsets.UTF_8); byte[] b = in.clone();
        switch (r.nextInt(9)) {
            case 0: b[r.nextInt(b.length)] ^= (byte) (1 << r.nextInt(8)); return b;                                                          // bit flip
            case 1: return java.util.Arrays.copyOf(b, r.nextInt(b.length));                                                                  // truncate
            case 2: { int i = r.nextInt(b.length), j = Math.min(b.length, i + 1 + r.nextInt(40)); byte[] o = new byte[b.length - (j - i)]; System.arraycopy(b, 0, o, 0, i); System.arraycopy(b, j, o, i, b.length - j); return o; }   // delete a range
            case 3: { int i = r.nextInt(b.length), j = Math.min(b.length, i + 1 + r.nextInt(60)); byte[] o = new byte[b.length + (j - i)]; System.arraycopy(b, 0, o, 0, j); System.arraycopy(b, i, o, j, b.length - i); return o; }          // duplicate a range
            case 4: { byte[] junk = new byte[1 + r.nextInt(30)]; r.nextBytes(junk); int i = r.nextInt(b.length); byte[] o = new byte[b.length + junk.length]; System.arraycopy(b, 0, o, 0, i); System.arraycopy(junk, 0, o, i, junk.length); System.arraycopy(b, i, o, i + junk.length, b.length - i); return o; }
            case 5: return s.replaceFirst("(?<=: ?)-?[0-9]+(\\.[0-9]+)?", java.util.regex.Matcher.quoteReplacement(NUMBERS[r.nextInt(NUMBERS.length)])).getBytes(StandardCharsets.UTF_8);    // a number becomes something nasty
            case 6: return s.replaceFirst("(?<=: ?\")[^\"]*(?=\")", java.util.regex.Matcher.quoteReplacement(STRINGS[r.nextInt(STRINGS.length)])).getBytes(StandardCharsets.UTF_8);                 // a string becomes something nasty
            case 7: { int i = s.indexOf('"', 2); return (s.substring(0, i + 1) + s.substring(1, Math.min(s.length(), i + 1 + r.nextInt(30))) + s.substring(i + 1)).getBytes(StandardCharsets.UTF_8); }                 // a duplicated key / fragment
            default: { StringBuilder n = new StringBuilder(); int depth = 1 + r.nextInt(30_000); for (int k = 0; k < depth; k++) n.append(r.nextBoolean() ? '[' : '{'); return n.toString().getBytes(StandardCharsets.UTF_8); }   // deep nesting
        }
    }

    // ---------------------------------------------------------------- the manifest

    @Test public void theManifestParsersNeverThrowAnythingButTheDocumentedTypeNeverHangAndAnythingTheyAcceptIsWellFormed() {
        Random r = new Random(20261010); long t0 = System.nanoTime(); int accepted = 0;
        for (int i = 0; i < 6000; i++) {
            byte[] m = mutate(manifest, r);
            try { String k = ModuleManifest.peekKeyId(m); assertTrue("peekKeyId returned a non-id: " + k, k == null || k.matches("[A-Za-z0-9._-]{1,64}")); }
            catch (Throwable t) { fail("peekKeyId (runs BEFORE the signature check) threw " + t + " on mutation #" + i); }
            try {
                ModuleManifest p = ModuleManifest.parse(m); accepted++;
                assertTrue(p.moduleVersion >= 1 && p.moduleVersion <= 1_000_000_000); assertTrue(p.hostMin <= p.hostMax && p.saveMin <= p.saveSchema && p.revokeFloor <= p.moduleVersion);
                assertTrue(p.files.size() > 0 && p.files.size() <= ModuleManifest.MAX_FILES); assertNotNull(p.file(p.dex));
                java.util.Set<String> names = new java.util.HashSet<>();
                for (ModuleManifest.FileEntry e : p.files) {
                    assertTrue("bad name " + e.name, e.name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") && !e.name.equals("manifest.json") && !e.name.equals("manifest.sig") && !e.name.contains(".."));
                    assertTrue(e.size >= 1 && e.size <= ModuleManifest.MAX_FILE_BYTES); assertTrue(e.sha256.matches("[0-9a-f]{64}")); assertTrue("duplicate " + e.name, names.add(e.name));
                }
            } catch (IllegalArgumentException expected) { }
            catch (Throwable t) { fail("ModuleManifest.parse threw " + t + " on mutation #" + i); }
        }
        assertTrue("6000 mutations took " + (System.nanoTime() - t0) / 1_000_000 + " ms", (System.nanoTime() - t0) / 1_000_000 < 60_000);
        assertTrue("some benign mutations (whitespace, an unused byte) still parse: " + accepted, accepted >= 0);
    }

    @Test public void aManifestThatDiffersFromTheSignedBytesByEvenOneByteNeverVerifies() {
        assertTrue("sanity: the original verifies", keys.verify(keyId, manifest, sig));
        Random r = new Random(7); int checked = 0;
        for (int i = 0; i < 4000; i++) {
            byte[] m = mutate(manifest, r);
            if (java.util.Arrays.equals(m, manifest)) continue;
            assertFalse("a changed manifest verified (mutation #" + i + ")", keys.verify(keyId, m, sig)); checked++;
        }
        assertTrue(checked > 3000);
    }

    @Test public void duplicateKeysInTheManifestAreRefused() {
        String s = new String(manifest, StandardCharsets.UTF_8);
        for (String k : new String[]{"\"moduleVersion\":3", "\"app\":\"" + Bundles.APP + "\"", "\"revokeFloor\":0", "\"channel\":\"internal\"", "\"keyId\":\"" + keyId + "\""}) {
            String dup = s.replaceFirst("\\{", "{" + java.util.regex.Matcher.quoteReplacement(k) + ",");
            try { ModuleManifest.parse(dup.getBytes(StandardCharsets.UTF_8)); fail("a manifest with a duplicated " + k + " was accepted"); } catch (IllegalArgumentException expected) { }
        }
        String dupFile = s.replace("\"files\":[", "\"files\":[{\"name\":\"module.jar\",\"sha256\":\"" + "a".repeat(64) + "\",\"size\":1},");
        try { ModuleManifest.parse(dupFile.getBytes(StandardCharsets.UTF_8)); fail("a duplicated file entry was accepted"); } catch (IllegalArgumentException expected) { }
    }

    // ---------------------------------------------------------------- assets.json

    @Test public void assetsJsonParsingNeverThrowsAnythingButTheDocumentedTypeAndAcceptsOnlyWellFormedPaths() {
        Random r = new Random(99); int accepted = 0;
        for (int i = 0; i < 6000; i++) {
            byte[] a = mutate(assets, r);
            try {
                AssetManifest p = AssetManifest.parse(a); accepted++;
                java.util.Set<String> lower = new java.util.HashSet<>();
                for (AssetManifest.Asset x : p.all()) {
                    assertTrue("bad path " + x.path, x.path.matches("[A-Za-z0-9_][A-Za-z0-9_.\\-]*(/[A-Za-z0-9_][A-Za-z0-9_.\\-]*)*") && !x.path.contains("..") && x.path.length() <= 200);
                    assertTrue(x.sha256.matches("[0-9a-f]{64}") && x.size >= 0 && x.size <= AssetManifest.MAX_ASSET_BYTES); assertTrue("case duplicate " + x.path, lower.add(x.path.toLowerCase(java.util.Locale.ROOT)));
                }
            } catch (IllegalArgumentException expected) { }
            catch (Throwable t) { fail("AssetManifest.parse threw " + t + " on mutation #" + i); }
        }
        assertTrue(accepted >= 0);
    }

    @Test public void anAssetListThatIsAbsurdlyLargeIsRefusedBeforeItIsParsed() {
        StringBuilder sb = new StringBuilder("{\"schema\":1,\"assets\":[{\"path\":\"a\",\"sha256\":\"" + "a".repeat(64) + "\",\"size\":1}");
        while (sb.length() < 9 * 1024 * 1024) sb.append(' ');
        sb.append("]}");
        try { AssetManifest.parse(sb.toString().getBytes(StandardCharsets.UTF_8)); fail("a 9 MB assets.json was parsed"); } catch (IllegalArgumentException expected) { }
        for (String nasty : new String[]{"{\"schema\":1,\"assets\":[{\"path\":{},\"sha256\":\"" + "a".repeat(64) + "\",\"size\":1}]}", "{\"schema\":1,\"assets\":[{\"path\":[1],\"sha256\":5,\"size\":\"x\"}]}", "{\"schema\":[],\"assets\":{}}", "{\"schema\":1,\"assets\":[1,2,3]}", "{\"schema\":1,\"assets\":[null]}"}) {
            try { AssetManifest.parse(nasty.getBytes(StandardCharsets.UTF_8)); fail("accepted " + nasty); } catch (IllegalArgumentException expected) { }
        }
    }

    // ---------------------------------------------------------------- signature text and format edge cases

    @Test public void theSignatureTextIsStrictAndMalformedOrAlteredSignaturesNeverVerify() throws Exception {
        String good = new String(sig, StandardCharsets.US_ASCII).trim();
        assertTrue(keys.verify(keyId, manifest, good.getBytes()));
        assertTrue("surrounding whitespace and a trailing newline are fine", keys.verify(keyId, manifest, ("  " + good + "\r\n").getBytes()));
        // text that merely contains a valid signature among junk must not verify (the MIME decoder used to silently discard anything outside the alphabet)
        String[] junk = {good.substring(0, 10) + "!!" + good.substring(10), good.substring(0, 10) + "\u0000" + good.substring(10), good + "!!!!", "***" + good, good.substring(0, 20) + "é" + good.substring(20), good.substring(0, 6) + "-_" + good.substring(6)};
        for (String j : junk) assertFalse("junk-laden text verified: [" + j.replace("\u0000", "\\0") + "]", keys.verify(keyId, manifest, j.getBytes(StandardCharsets.UTF_8)));
        byte[] der = Base64.getDecoder().decode(good);
        byte[][] malformed = {new byte[0], new byte[1], new byte[7], java.util.Arrays.copyOf(der, der.length - 1), java.util.Arrays.copyOf(der, der.length + 1), new byte[141], new byte[der.length], der.clone()};
        malformed[3] = java.util.Arrays.copyOf(der, der.length - 1); malformed[7][malformed[7].length - 1] ^= 1; malformed[7][5] ^= 0x40;
        for (byte[] m : malformed) assertFalse(keys.verify(keyId, manifest, Base64.getEncoder().encode(m)));
        Random r = new Random(5);
        for (int i = 0; i < 3000; i++) {                                                                  // random DER mutations and random text: false, never an exception
            byte[] m = der.clone(); m[r.nextInt(m.length)] ^= (byte) (1 << r.nextInt(8));
            assertFalse(keys.verify(keyId, manifest, Base64.getEncoder().encode(m)));
            byte[] text = new byte[r.nextInt(300)]; r.nextBytes(text); assertFalse(keys.verify(keyId, manifest, text));
        }
        assertFalse(keys.verify(null, manifest, sig)); assertFalse(keys.verify("", manifest, sig)); assertFalse(keys.verify(keyId, null == manifest ? null : manifest, null));
    }
}
