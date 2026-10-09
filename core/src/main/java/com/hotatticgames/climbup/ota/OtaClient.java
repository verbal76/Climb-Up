package com.hotatticgames.climbup.ota;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.hotatticgames.climbup.sim.Tuning;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The background check: signed manifest -> compatibility gate -> payload download -> SHA-256 (from the signed manifest) -> allowlisted unzip -> validation -> atomic staging.
 * Nothing in a manifest is read before its signature verifies. Every failure is silent (recorded as a short status string) and leaves the installed content untouched.
 */
public final class OtaClient {
    /** Where updates come from and what they must be signed with; tests point this at a local server and a throw-away key. */
    public static final class Endpoint {
        public final String base, channel; public final PublicKey key; public final int runtime;
        public Endpoint(String base, String channel, PublicKey key, int runtime) { this.base = base; this.channel = channel; this.key = key; this.runtime = runtime; }
        public static Endpoint pinned() {
            try { return new Endpoint(OtaConfig.BASE, OtaConfig.CHANNEL, OtaVerifier.parseKey(OtaConfig.PUBLIC_KEY_B64), OtaConfig.RUNTIME); }
            catch (Exception e) { throw new IllegalStateException("pinned OTA key unreadable", e); }
        }
    }

    private final OtaStore store; private final Fetcher fetcher; private final int appVersionCode; private final Tuning bundled; private final Endpoint ep;
    public volatile String status = "";

    public OtaClient(OtaStore store, Fetcher fetcher, int appVersionCode, Tuning bundled) { this(store, fetcher, appVersionCode, bundled, Endpoint.pinned()); }
    public OtaClient(OtaStore store, Fetcher fetcher, int appVersionCode, Tuning bundled, Endpoint ep) { this.store = store; this.fetcher = fetcher; this.appVersionCode = appVersionCode; this.bundled = bundled; this.ep = ep; }

    /** @param force true for the Settings "check now" button (ignores the 24 h interval). Returns the status string. */
    public String check(long now, boolean force) {
        if (!force && !store.shouldCheck(now)) return status = "not due";
        String r;
        try { r = run(); } catch (Exception e) { r = "offline or failed: " + e.getClass().getSimpleName(); }
        store.recordCheck(now, r);
        return status = r;
    }

    private String run() throws Exception {
        byte[] manifest = fetcher.get(ep.base + "manifest.json", OtaConfig.MAX_MANIFEST_BYTES);
        byte[] signature = fetcher.get(ep.base + "manifest.sig", OtaConfig.MAX_SIGNATURE_BYTES);
        if (!OtaVerifier.verify(manifest, signature, ep.key)) return "manifest signature invalid, ignored";     // nothing below runs on an unsigned or altered manifest
        JsonValue m = new JsonReader().parse(new String(manifest, StandardCharsets.UTF_8));
        if (m.getInt("schema", 0) != OtaConfig.SCHEMA) return "manifest schema not supported";
        if (!ep.channel.equals(m.getString("channel", ""))) return "wrong channel";
        if (!OtaVerifier.keyId(ep.key).equals(m.getString("keyId", ""))) return "manifest names another key";
        if (m.getInt("runtime", -1) != ep.runtime) return "payload needs a different app runtime";
        if (appVersionCode < m.getInt("minVersionCode", 0)) return "payload needs a newer app build";
        int version = m.getInt("contentVersion", 0);
        if (version <= 0) return "bad version";
        if (!store.acceptable(version)) return "up to date (v" + store.currentVersion() + ")";
        String url = m.getString("payloadUrl", ""), sha = m.getString("sha256", "").toLowerCase();
        if (!url.startsWith(ep.base) || sha.length() != 64) return "manifest rejected";
        int size = m.getInt("size", -1);
        if (size <= 0 || size > OtaConfig.MAX_PAYLOAD_BYTES) return "manifest rejected (size)";
        byte[] zip = fetcher.get(url, OtaConfig.MAX_PAYLOAD_BYTES);
        if (zip.length != size) return "size mismatch, discarded";
        if (!OtaStore.sha256(zip).equals(sha)) return "checksum mismatch, discarded";
        String json = unzip(zip);
        if (json == null) return "payload rejected (contents)";
        String bad = OtaStore.validate(json);
        if (bad != null) return "payload rejected (" + bad + ")";
        String frozen = OtaStore.frozenMismatch(bundled, Tuning.parse(json));
        if (frozen != null) return "payload needs a new app build (" + frozen + ")";
        store.stage(version, json);
        return "downloaded v" + version + ", applies on next start";
    }

    /** The payload must be a zip holding exactly the allowlisted file(s), flat names, size capped. Returns tuning.json text or null. */
    static String unzip(byte[] zip) throws java.io.IOException {
        String tuning = null; int entries = 0;
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (++entries > 8 || e.isDirectory()) return null;
                String name = e.getName();
                boolean allowed = false; for (String a : OtaConfig.ALLOWED_FILES) if (a.equals(name)) allowed = true;
                if (!allowed) return null;           // also rejects "../x", "/x", "a/b" and anything unknown
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096]; int n;
                while ((n = z.read(buf)) > 0) { out.write(buf, 0, n); if (out.size() > OtaConfig.MAX_FILE_BYTES) return null; }
                if (name.equals("tuning.json")) tuning = new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        }
        return tuning;
    }
}
