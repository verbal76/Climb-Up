package com.hotatticgames.climbup.ota;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Downloads a small public file. Tests use an in-memory implementation. */
public interface Fetcher {
    byte[] get(String url, int maxBytes) throws IOException;

    /** Plain HTTPS GET (follows the release-asset redirect), short timeouts, hard size cap, no cookies or identifiers sent. */
    final class Http implements Fetcher {
        @Override public byte[] get(String url, int maxBytes) throws IOException {
            if (!url.startsWith("https://")) throw new IOException("https only");
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(OtaConfig.TIMEOUT_MS); c.setReadTimeout(OtaConfig.TIMEOUT_MS);
            c.setInstanceFollowRedirects(true); c.setUseCaches(false);
            c.setRequestProperty("Accept", "application/octet-stream");
            try {
                if (c.getResponseCode() != 200) throw new IOException("http " + c.getResponseCode());
                if (c.getContentLength() > maxBytes) throw new IOException("too large");
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192]; int n;
                    while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); if (out.size() > maxBytes) throw new IOException("too large"); }
                    return out.toByteArray();
                }
            } finally { c.disconnect(); }
        }
    }
}
