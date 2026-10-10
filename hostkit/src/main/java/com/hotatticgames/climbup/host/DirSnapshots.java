package com.hotatticgames.climbup.host;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Set;

/** A {@link SaveGuard} over the app's data directory: copies everything except the named top-level entries (the host's own state, caches) into a snapshot folder and back. */
public final class DirSnapshots implements SaveGuard {
    private final File dataDir, snapRoot; private final Set<String> exclude;

    public DirSnapshots(File dataDir, File snapRoot, Set<String> exclude) { this.dataDir = dataDir; this.snapRoot = snapRoot; this.exclude = exclude; }

    @Override public boolean snapshot(String id) {
        File dst = new File(snapRoot, id), tmp = new File(snapRoot, id + ".tmp");
        try {
            Hashing.deleteTree(tmp); Hashing.deleteTree(dst); tmp.mkdirs();
            copyContents(dataDir, tmp, exclude);
            return tmp.renameTo(dst);
        } catch (IOException e) { Hashing.deleteTree(tmp); return false; }
    }

    @Override public boolean restore(String id) {
        File src = new File(snapRoot, id);
        if (!src.isDirectory()) return false;
        try {
            File[] now = dataDir.listFiles();
            if (now != null) for (File f : now) if (!exclude.contains(f.getName())) Hashing.deleteTree(f);
            copyContents(src, dataDir, java.util.Collections.<String>emptySet());
            return true;
        } catch (IOException e) { return false; }
    }

    @Override public void discard(String id) { Hashing.deleteTree(new File(snapRoot, id)); Hashing.deleteTree(new File(snapRoot, id + ".tmp")); }

    private static void copyContents(File from, File to, Set<String> skipTop) throws IOException {
        File[] kids = from.listFiles(); if (kids == null) return;
        for (File k : kids) {
            if (skipTop.contains(k.getName())) continue;
            File dst = new File(to, k.getName());
            if (k.isDirectory()) { dst.mkdirs(); copyContents(k, dst, java.util.Collections.<String>emptySet()); }
            else Files.copy(k.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
