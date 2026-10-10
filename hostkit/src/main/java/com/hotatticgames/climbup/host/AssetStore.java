package com.hotatticgames.climbup.host;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Set;

/**
 * Content-addressed store for downloaded game files: {@code cas/<sha256>} (complete, verified, read-only) and {@code cas.part/<sha256>} (an interrupted download, resumed by the next
 * check). A file is only ever published into cas/ after its size and SHA-256 match the signed manifest, so "present" means "verified when it arrived"; the overlay re-hashes on first use
 * to catch damage at rest. Identical content is stored once however many releases or paths use it, so an update downloads only what changed.
 */
public final class AssetStore {
    private final File cas, part;
    private final Set<String> verifiedThisRun = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    public AssetStore(File root) { cas = new File(root, "cas"); part = new File(root, "cas.part"); }

    public File file(String sha256) { return new File(cas, sha256); }
    public File partFile(String sha256) { part.mkdirs(); return new File(part, sha256); }

    /** Present with the right size. (Content was verified when it was published.) */
    public boolean has(AssetManifest.Asset a) { File f = file(a.sha256); return f.isFile() && f.length() == a.size; }

    /** Publishes a finished download: size and SHA-256 must match, then one atomic rename. Throws (and discards the part) otherwise. */
    public void publish(AssetManifest.Asset a, File finished) throws IOException {
        if (finished.length() != a.size) { finished.delete(); throw new IOException("asset size mismatch: " + a.path); }
        if (!Hashing.sha256(finished).equals(a.sha256)) { finished.delete(); throw new IOException("asset checksum mismatch: " + a.path); }
        cas.mkdirs(); File dst = file(a.sha256);
        Files.move(finished.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        dst.setReadOnly(); verifiedThisRun.add(a.sha256);
    }

    /** The file for an asset, re-hashed the first time it is used in this run; null (and the damaged copy removed) if it no longer matches. */
    public File openVerified(AssetManifest.Asset a) {
        File f = file(a.sha256);
        if (!f.isFile() || f.length() != a.size) return null;
        if (verifiedThisRun.contains(a.sha256)) return f;
        try { if (Hashing.sha256(f).equals(a.sha256)) { verifiedThisRun.add(a.sha256); return f; } } catch (IOException ignored) { }
        f.setWritable(true); f.delete(); return null;
    }

    /** Deletes every stored file and leftover partial download that none of the kept hashes names. Returns how many files were removed. */
    public int gc(Set<String> keep) {
        int n = 0;
        for (File dir : new File[]{cas, part}) {
            File[] kids = dir.listFiles(); if (kids == null) continue;
            for (File k : kids) if (!keep.contains(k.getName())) { k.setWritable(true); if (k.delete()) n++; }
        }
        return n;
    }
}
