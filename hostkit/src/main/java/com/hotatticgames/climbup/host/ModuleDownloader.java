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
    public interface Fetcher {
        /** Opens {@code url} positioned at byte {@code from} (a resumed download); at most {@code maxBytes} more bytes are expected. */
        InputStream open(String url, long from, long maxBytes) throws IOException;
        default InputStream open(String url, long maxBytes) throws IOException { return open(url, 0, maxBytes); }
    }

    /** The server cannot serve the byte range asked for (416): the partial file it was meant to continue is unusable and must be discarded. */
    public static final class RangeRejected extends IOException { public RangeRejected(String m) { super(m); } }

    public static final class Http implements Fetcher {
        public static final int MAX_REDIRECTS = 5;
        private static final java.util.regex.Pattern CONTENT_RANGE = java.util.regex.Pattern.compile("bytes ([0-9]{1,18})-([0-9]{1,18})/([0-9]{1,18}|\\*)");
        private final boolean allowPlainHttp;
        public Http() { this(false); }
        /** Debug/lab builds only: allow cleartext to a LAN or loopback test server. The release form is https-only. */
        public Http(boolean allowPlainHttp) { this.allowPlainHttp = allowPlainHttp; }

        /**
         * The URL a redirect may lead to, or an IOException. Only https (http too when cleartext is allowed for lab servers); NEVER http from an https URL; no credentials in the URL; a
         * relative Location is resolved against the current URL. Redirects are followed by hand so each hop passes through here and the hop count is bounded.
         */
        static String redirectTarget(String current, String location, boolean allowPlain) throws IOException {
            if (location == null || location.isEmpty()) throw new IOException("redirect without a Location");
            URL from = new URL(current), next;
            try { next = new URL(from, location); } catch (java.net.MalformedURLException e) { throw new IOException("bad redirect target"); }
            String p = next.getProtocol();
            if (!p.equals("https") && !(allowPlain && p.equals("http"))) throw new IOException("redirect to a non-https URL refused");
            if (from.getProtocol().equals("https") && !p.equals("https")) throw new IOException("redirect downgrade refused");
            if (next.getUserInfo() != null) throw new IOException("redirect with credentials refused");
            return next.toString();
        }

        @Override public InputStream open(String url, long from, long maxBytes) throws IOException {
            String u = url; HttpURLConnection c; int code;
            for (int hop = 0; ; hop++) {
                if (!u.startsWith("https://") && !(allowPlainHttp && u.startsWith("http://"))) throw new IOException("https only");
                c = (HttpURLConnection) new URL(u).openConnection();
                c.setConnectTimeout(8000); c.setReadTimeout(15000); c.setInstanceFollowRedirects(false); c.setUseCaches(false);
                c.setRequestProperty("Accept", "application/octet-stream");
                if (from > 0) c.setRequestProperty("Range", "bytes=" + from + "-");                // sent again on every hop
                code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = c.getHeaderField("Location"); c.disconnect();
                    if (hop >= MAX_REDIRECTS) throw new IOException("too many redirects");
                    u = redirectTarget(u, loc, allowPlainHttp);
                    continue;
                }
                break;
            }
            if (code == 416) { c.disconnect(); throw new RangeRejected("http 416"); }
            if (code != 200 && code != 206) { c.disconnect(); throw new IOException("http " + code); }
            if (code == 206) {                                                                   // the server must PROVE it continues at the byte we asked for
                java.util.regex.Matcher m = CONTENT_RANGE.matcher(String.valueOf(c.getHeaderField("Content-Range")));
                if (from <= 0 || !m.matches() || Long.parseLong(m.group(1)) != from || Long.parseLong(m.group(2)) < from) { c.disconnect(); throw new IOException("bad Content-Range on a partial response"); }
                if (Long.parseLong(m.group(2)) - from + 1 > maxBytes) { c.disconnect(); throw new IOException("too large"); }
            }
            long allowed = (from > 0 && code == 200) ? from + maxBytes : maxBytes;            // a server that ignores Range sends the whole file
            if (c.getContentLengthLong() > allowed) { c.disconnect(); throw new IOException("too large"); }
            InputStream in = c.getInputStream();
            if (from > 0 && code == 200) { long skipped = 0; while (skipped < from) { long n = in.skip(from - skipped); if (n <= 0) { if (in.read() < 0) break; n = 1; } skipped += n; } }     // a server that ignores Range sends everything: skip what we already have
            return in;
        }
    }

    /** Headroom kept free on top of what a release needs (the unpack/verify/rename and the platform's own writes). */
    static final long SPACE_MARGIN = 4L * 1024 * 1024;

    private final ModuleStore store; private final Fetcher fetcher; private final String base;
    private long budgetMillis = 15L * 60 * 1000, deadlineNanos; private java.util.function.LongSupplier freeSpace;
    public volatile String status = "";

    public ModuleDownloader(ModuleStore store, Fetcher fetcher, String baseUrl) { this.store = store; this.fetcher = fetcher; this.base = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/"; this.freeSpace = store::usableBytes; }

    /** Wall-clock limit for one whole check (the 15 s socket timeout is per read, so a slow drip would otherwise never end). Partial asset downloads are kept and resumed by the next check. */
    public ModuleDownloader withTimeBudgetMillis(long ms) { this.budgetMillis = ms; return this; }
    /** Test seam for the free-space probe. */
    public ModuleDownloader withFreeSpace(java.util.function.LongSupplier s) { this.freeSpace = s; return this; }

    private void tick() throws IOException { if (System.nanoTime() - deadlineNanos > 0) throw new IOException("time budget exceeded"); }

    private void requireSpace(long need) throws IOException {
        long have = freeSpace.getAsLong();
        if (have < need + SPACE_MARGIN) throw new IOException("not enough storage (need " + (need + SPACE_MARGIN) / 1024 + " KiB, have " + have / 1024 + " KiB)");
    }

    /** Runs one check. Returns a short status; "staged vN" means a verified module now waits for the next cold start. */
    public String check() {
        deadlineNanos = System.nanoTime() + budgetMillis * 1_000_000L;
        try { return status = run(); } catch (Exception e) { return status = "offline or failed: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " " + e.getMessage()); }
    }

    private String run() throws Exception {
        byte[] mb = readAll(fetcher.open(base + "manifest.json", ModuleManifest.MAX_BYTES), ModuleManifest.MAX_BYTES), sb = readAll(fetcher.open(base + "manifest.sig", 1024), 1024);
        String no = store.preflight(mb, sb);
        if (no != null) return no;
        ModuleManifest m = ModuleManifest.parse(mb);
        long need = 0; for (ModuleManifest.FileEntry e : m.files) need += e.size;
        requireSpace(need);                                                                           // before anything is created: a full disk leaves no staging behind
        File dir = store.openStaging();
        try {
            Files.write(new File(dir, "manifest.json").toPath(), mb); Files.write(new File(dir, "manifest.sig").toPath(), sb);
            for (ModuleManifest.FileEntry e : m.files) fetchFile(e, new File(dir, e.name));
            fetchAssets(AssetManifest.read(dir));
        } catch (Exception e) { deleteQuietly(dir); throw e; }
        String refused = store.commitStaging();
        return refused == null ? "staged v" + m.moduleVersion + " (applies on next start)" : "refused: " + refused;
    }

    private void fetchFile(ModuleManifest.FileEntry e, File out) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256"); long total = 0;
        try (InputStream in = fetcher.open(base + e.name, e.size); OutputStream o = new FileOutputStream(out)) {
            byte[] buf = new byte[1 << 16]; int n;
            while ((n = in.read(buf)) > 0) {
                tick();
                total += n; if (total > e.size) throw new IOException("larger than declared: " + e.name);
                md.update(buf, 0, n); o.write(buf, 0, n);
            }
        }
        if (total != e.size) throw new IOException("truncated download: " + e.name);                  // an interrupted transfer is never used
        if (!Hashing.hex(md.digest()).equals(e.sha256)) throw new IOException("checksum mismatch: " + e.name);
    }

    /** Downloads the game files this release needs and the store does not have yet. A partial file from an interrupted attempt is resumed; the finished file must match its signed size and SHA-256. */
    private void fetchAssets(AssetManifest am) throws Exception {
        AssetStore cas = store.assets();
        long need = 0;
        for (AssetManifest.Asset a : am.all()) if (!cas.has(a)) { File p = cas.partFile(a.sha256); long h = p.isFile() ? Math.min(p.length(), a.size) : 0; need += a.size - h; }
        if (need > 0) requireSpace(need);
        for (AssetManifest.Asset a : am.all()) {
            if (cas.has(a)) continue;
            File part = cas.partFile(a.sha256); long have = part.isFile() ? part.length() : 0;
            if (have > a.size) { part.delete(); have = 0; }
            if (have < a.size) {
                try (InputStream in = fetcher.open(base + "assets/" + a.sha256, have, a.size - have); java.io.FileOutputStream o = new java.io.FileOutputStream(part, have > 0)) {
                    byte[] buf = new byte[1 << 16]; int n; long total = have;
                    while ((n = in.read(buf)) > 0) { tick(); total += n; if (total > a.size) { part.delete(); throw new IOException("larger than declared: " + a.path); } o.write(buf, 0, n); }
                } catch (RangeRejected r) { part.delete(); throw r; }                           // the server cannot continue from there: starting over beats failing the same way forever
            }
            if (part.length() != a.size) throw new IOException("incomplete download kept for resume: " + a.path);        // the partial file stays; the next check continues it
            try { cas.publish(a, part); } catch (IOException e) { part.delete(); throw e; }                              // corrupt: discarded, the next check starts it over
        }
    }

    private byte[] readAll(InputStream in, int max) throws IOException {
        try (InputStream i = in) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(); byte[] buf = new byte[4096]; int n;
            while ((n = i.read(buf)) > 0) { tick(); bo.write(buf, 0, n); if (bo.size() > max) throw new IOException("too large"); }
            return bo.toByteArray();
        }
    }
    private static void deleteQuietly(File d) { Hashing.deleteTree(d); }
}
