package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.ota.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.regex.*;
import org.junit.Test;

/** The things that must not drift apart: the pinned key, the runtime, the schema and the URLs in the app, the publishing tool and the repository. */
public class OtaConfigTest {
    private static String read(String p) throws Exception { return new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8); }

    @Test public void theKeyPinnedInTheAppIsTheOneInTheRepositoryAndIsAUsableP256Key() throws Exception {
        assertEquals(read("../assets/ota/ota_public_key.b64").trim(), OtaConfig.PUBLIC_KEY_B64);
        java.security.PublicKey k = OtaVerifier.parseKey(OtaConfig.PUBLIC_KEY_B64);
        assertEquals("EC", k.getAlgorithm());
        assertEquals(16, OtaVerifier.keyId(k).length());
    }

    @Test public void thePublishingToolAgreesWithTheApp() throws Exception {
        String py = read("../tools/ota/make_ota.py");
        assertEquals(String.valueOf(OtaConfig.RUNTIME), grab(py, "^RUNTIME = (\\d+)"));
        assertEquals(String.valueOf(OtaConfig.SCHEMA), grab(py, "^SCHEMA = (\\d+)"));
        assertEquals(OtaConfig.CHANNEL, grab(py, "^CHANNEL = \"([^\"]+)\""));
        assertEquals(OtaConfig.BASE, grab(py, "^BASE = \"([^\"]+)\""));
        Matcher m = Pattern.compile("^FROZEN = \\[(.*)\\]", Pattern.MULTILINE).matcher(py); assertTrue(m.find());
        java.util.List<String> frozen = new java.util.ArrayList<>(); for (String f : m.group(1).split(",")) frozen.add(f.trim().replace("\"", ""));
        assertEquals(java.util.Arrays.asList(OtaConfig.FROZEN_TUNING), frozen);
    }

    @Test public void theBundledContentVersionAndAnAndroidBuildAreConsistent() throws Exception {
        assertTrue(OtaConfig.BUNDLED_CONTENT_VERSION >= 1);
        assertTrue("OTA is https only", OtaConfig.BASE.startsWith("https://"));
        assertEquals(OtaConfig.BASE + "manifest.json", OtaConfig.MANIFEST_URL);
        assertEquals(OtaConfig.BASE + "manifest.sig", OtaConfig.SIGNATURE_URL);
        assertTrue(read("../android/src/main/AndroidManifest.xml").contains("android.permission.INTERNET"));
    }

    @Test public void theSigningIdentityEveryScriptAndWorkflowExpectsIsTheCommittedKeystoresAndTheInstalledBuild42s() throws Exception {
        String want = "CE:DD:D6:86:8B:F9:4C:30:B2:70:F3:59:01:88:66:95:EE:6A:E9:AB:95:51:19:6C:2C:A9:E2:99:50:04:1C:39";
        java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
        try (java.io.InputStream in = Files.newInputStream(Paths.get("../android/debug.keystore"))) { ks.load(in, "android".toCharArray()); }
        byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(ks.getCertificate("androiddebugkey").getEncoded());
        StringBuilder sb = new StringBuilder(); for (byte b : d) sb.append(sb.length() == 0 ? "" : ":").append(String.format("%02X", b));
        assertEquals("the committed keystore is the identity of the installed Build 42", want, sb.toString());
        assertEquals(want, grab(read("../.github/workflows/android.yml"), "EXPECTED_CERT_SHA256: \"([^\"]+)\""));
        assertEquals(want, grab(read("../tools/ci/verify_secrets.sh"), "WANT_CERT=\"([^\"]+)\""));
    }

    @Test public void theWorkflowsAndTheContentVersionFileAreWiredToTheGateAndTheRightBranch() throws Exception {
        String android = read("../.github/workflows/android.yml"), ota = read("../.github/workflows/ota-publish.yml");
        for (String w : new String[]{android, ota}) assertTrue(w.contains("branches: [ccr-cb458cd1-w4dh5c]"));
        assertTrue(android.contains("tools/ci/verify_secrets.sh") && android.contains(":core:verifyOta") && android.contains("--sign") && android.contains("apksigner"));
        assertTrue(ota.contains(":core:verifyOta") && ota.contains("tools/ci/verify_secrets.sh --ota-only") && ota.contains("tools/ota/content_version.txt"));
        int v = Integer.parseInt(read("../tools/ota/content_version.txt").trim());
        assertTrue("the published content version can never be below what the APK bundles", v >= OtaConfig.BUNDLED_CONTENT_VERSION);
        assertFalse("no secret value is ever echoed", android.contains("echo \"${{ secrets") || ota.contains("echo \"${{ secrets"));
    }

    private static String grab(String text, String re) { Matcher m = Pattern.compile(re, Pattern.MULTILINE).matcher(text); assertTrue(re, m.find()); return m.group(1); }
}
