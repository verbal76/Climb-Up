package com.hotatticgames.climbup.host;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.security.MessageDigest;

/**
 * Background check + download of a signed module release into the store's staging area. Order: small manifest + signature, pre-download gate (signature, identity,
 * compatibility, anti-rollback), then each listed file streamed to disk with its size and SHA-256 enforced as it arrives, then the store's complete verification and one
 * atomic rename. Any failure discards the partial download and leaves the installed modules untouched; nothing here ever activates a module.
 */
public final class ModuleDownloader {
    /** Opens a URL; tests use a local server, the app uses {@link Http}. */
    public interface Fetcher { InputStream open(String url, long maxBytes) throws IOException; }

    public static final class Http implements Fetcher {
        private final boolean allowPlainHttp;
        public Http() { this(false); }
        /** Debug/lab builds only: allow cleartext to a LAN or loopback test server. The release form is https-only. */
        public Http(boolean allowPlainHttp) { this.allowPlainHttp = allowPlainHttp; }
        @Override public InputStream open(String url, long maxBytes) throws IOException {
            if (!url.startsWith("https://") && !(allowPlainHttp && url.startsWith("http://"))) throw new IOException("https only");
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000); c.setReadTimeout(15000); c.setInstanceFollowRedirects(true); c.setUseCaches(false);
            c.setRequestProperty("Accept", "application/octet-stream");
            if (c.getResponseCode() != 200) { c.disconnect(); throw new IOException("http " + c.getResponseCode()); }
            if (c.getContentLengthLong() > maxBytes) { c.disconnect(); throw new IOException("too large"); }
            return c.getInputStream();
        }
    }

    private final ModuleStore store; private final Fetcher fetcher; private final String base;
    public volatile String status = "";

    public ModuleDownloader(ModuleStore store, Fetcher fetcher, String baseUrl) { this.store = store; this.fetcher = fetcher; this.base = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/"; }

    /** Runs one check. Returns a short status; "staged vN" means a verified module now waits for the next cold start. */
    public String check() {
        try { return status = run(); } catch (Exception e) { return status = "offline or failed: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " " + e.getMessage()); }
    }

    private String run() throws Exception {
        byte[] mb = readAll(fetcher.open(base + "manifest.json", ModuleManifest.MAX_BYTES), ModuleManifest.MAX_BYTES), sb = readAll(fetcher.open(base + "manifest.sig", 1024), 1024);
        String no = store.preflight(mb, sb);
        if (no != null) return no;
        ModuleManifest m = ModuleManifest.parse(mb);
        File dir = store.openStaging();
        try {
            Files.write(new File(dir, "manifest.json").toPath(), mb); Files.write(new File(dir, "manifest.sig").toPath(), sb);
            for (ModuleManifest.FileEntry e : m.files) fetchFile(e, new File(dir, e.name));
        } catch (Exception e) { deleteQuietly(dir); throw e; }
        String refused = store.commitStaging();
        return refused == null ? "staged v" + m.moduleVersion + " (applies on next start)" : "refused: " + refused;
    }

    private void fetchFile(ModuleManifest.FileEntry e, File out) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256"); long total = 0;
        try (InputStream in = fetcher.open(base + e.name, e.size); OutputStream o = new FileOutputStream(out)) {
            byte[] buf = new byte[1 << 16]; int n;
            while ((n = in.read(buf)) > 0) {
                total += n; if (total > e.size) throw new IOException("larger than declared: " + e.name);
                md.update(buf, 0, n); o.write(buf, 0, n);
            }
        }
        if (total != e.size) throw new IOException("truncated download: " + e.name);                  // an interrupted transfer is never used
        if (!Hashing.hex(md.digest()).equals(e.sha256)) throw new IOException("checksum mismatch: " + e.name);
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        try (InputStream i = in) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(); byte[] buf = new byte[4096]; int n;
            while ((n = i.read(buf)) > 0) { bo.write(buf, 0, n); if (bo.size() > max) throw new IOException("too large"); }
            return bo.toByteArray();
        }
    }
    private static void deleteQuietly(File d) { Hashing.deleteTree(d); }
}
