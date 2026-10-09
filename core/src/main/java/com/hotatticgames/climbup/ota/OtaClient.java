package com.hotatticgames.climbup.ota;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The background check: manifest -> compatibility gate -> payload download -> SHA-256 -> allowlisted unzip -> validation -> atomic staging.
 * Every failure is silent (recorded as a short status string) and leaves the installed content untouched. TEST ONLY: see {@link OtaConfig}.
 */
public final class OtaClient {
    private final OtaStore store; private final Fetcher fetcher; private final int appVersionCode;
    public volatile String status = "";

    public OtaClient(OtaStore store, Fetcher fetcher, int appVersionCode) { this.store = store; this.fetcher = fetcher; this.appVersionCode = appVersionCode; }

    /** @param force true for the Settings "check now" button (ignores the 24 h interval). Returns the status string. */
    public String check(long now, boolean force) {
        if (!force && !store.shouldCheck(now)) return status = "not due";
        String r;
        try { r = run(); } catch (Exception e) { r = "offline or failed: " + e.getClass().getSimpleName(); }
        store.recordCheck(now, r);
        return status = r;
    }

    private String run() throws Exception {
        JsonValue m = new JsonReader().parse(new String(fetcher.get(OtaConfig.MANIFEST_URL, OtaConfig.MAX_MANIFEST_BYTES), StandardCharsets.UTF_8));
        if (m.getInt("schema", 0) != OtaConfig.SCHEMA) return "manifest schema not supported";
        if (!OtaConfig.CHANNEL.equals(m.getString("channel", ""))) return "wrong channel";
        if (m.getInt("runtime", -1) != OtaConfig.RUNTIME) return "payload needs a different app runtime";
        if (appVersionCode < m.getInt("minVersionCode", 0)) return "payload needs a newer app build";
        int version = m.getInt("contentVersion", 0);
        if (version <= 0) return "bad version";
        if (!store.acceptable(version)) return "up to date (v" + Math.max(store.st.highestApplied, store.stagedVersion()) + ")";
        String url = m.getString("payloadUrl", ""), sha = m.getString("sha256", "").toLowerCase();
        if (!url.startsWith(OtaConfig.BASE) || sha.length() != 64) return "manifest rejected";
        int size = m.getInt("size", -1);
        byte[] zip = fetcher.get(url, OtaConfig.MAX_PAYLOAD_BYTES);
        if (size >= 0 && zip.length != size) return "size mismatch, discarded";
        if (!OtaStore.sha256(zip).equals(sha)) return "checksum mismatch, discarded";
        String json = unzip(zip);
        if (json == null) return "payload rejected (contents)";
        String bad = OtaStore.validate(json);
        if (bad != null) return "payload rejected (" + bad + ")";
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
