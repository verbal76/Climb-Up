package com.hotatticgames.climbup.ota;

import com.hotatticgames.climbup.sim.Tuning;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * Release gate (run by CI through {@code ./gradlew :core:verifyOta}): takes the three files the publishing tool just produced and pushes them through the app's own client against the
 * PINNED public key, exactly as a phone would after downloading them: signature, key id, runtime, checksum, contents, validation, layout check, staging, and activation on a simulated next start.
 * Exits non-zero (and publishes nothing) if any step refuses. Usage: OtaVerifyCli DIR [--expect-bundled]
 */
public final class OtaVerifyCli {
    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "build/ota");
        boolean expectBundled = args.length > 1 && args[1].equals("--expect-bundled");
        Map<String, byte[]> files = new HashMap<>();
        for (String f : new String[]{"manifest.json", "manifest.sig", "payload.zip"}) files.put(OtaConfig.BASE + f, Files.readAllBytes(new File(dir, f).toPath()));
        Fetcher fetch = (url, max) -> { byte[] d = files.get(url); if (d == null) throw new IOException("missing " + url); if (d.length > max) throw new IOException("too large"); return d; };
        String bundledJson = new String(Files.readAllBytes(new File("assets/data/tuning.json").toPath()), StandardCharsets.UTF_8);
        File tmp = Files.createTempDirectory("ota-verify").toFile();
        try {
            OtaStore store = new OtaStore(new File(tmp, "ota"), 0);              // pretend nothing is applied yet so the version gate does not hide the check
            store.startup();
            String r = new OtaClient(store, fetch, Integer.MAX_VALUE, Tuning.parse(bundledJson)).check(1L, true);
            System.out.println("client verdict: " + r);
            if (!r.startsWith("downloaded v")) fail("the app would refuse this release: " + r);
            OtaStore next = new OtaStore(new File(tmp, "ota"), 0);
            String json = next.startup();
            if (json == null) fail("the release would not activate on the next start");
            if (Tuning.parse(json).validate() != null) fail("the activated tuning is not valid");
            if (expectBundled && !json.equals(bundledJson)) fail("the baseline payload differs from the tuning bundled in the APK");
            String m = new String(files.get(OtaConfig.BASE + "manifest.json"), StandardCharsets.UTF_8);
            System.out.println("OK: signature verifies with the pinned key " + OtaVerifier.keyId(OtaVerifier.parseKey(OtaConfig.PUBLIC_KEY_B64)) + ", runtime " + OtaConfig.RUNTIME + ", activates on the next start"
                    + (expectBundled ? ", identical to the bundled content" : "") + "\n" + m);
        } finally { delete(tmp); }
    }
    private static void fail(String why) { System.err.println("OTA VERIFY FAILED: " + why); System.exit(1); }
    private static void delete(File f) { File[] k = f.listFiles(); if (k != null) for (File c : k) delete(c); f.delete(); }
}
