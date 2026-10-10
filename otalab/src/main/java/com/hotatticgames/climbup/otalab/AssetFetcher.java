package com.hotatticgames.climbup.otalab;

import android.content.res.AssetManager;
import com.hotatticgames.climbup.host.ModuleDownloader;
import java.io.IOException;
import java.io.InputStream;

/**
 * Lab only: a transport for {@link ModuleDownloader} that reads release files from the lab APK's own assets ({@code asset://updates/NAME/file}) instead of the network. Everything after the
 * transport is the real path: the same preflight, hashing, signature verification, staging and activation rules as for a downloaded release. It lets a phone with no server and no adb run
 * the update scenarios.
 */
public final class AssetFetcher implements ModuleDownloader.Fetcher {
    private final AssetManager assets;
    public AssetFetcher(AssetManager assets) { this.assets = assets; }

    @Override public InputStream open(String url, long from, long maxBytes) throws IOException {
        if (!url.startsWith("asset://")) throw new IOException("not an asset url");
        InputStream in = assets.open(url.substring("asset://".length()));
        long skipped = 0;
        while (skipped < from) { long n = in.skip(from - skipped); if (n <= 0) { if (in.read() < 0) break; n = 1; } skipped += n; }
        return in;
    }
}
