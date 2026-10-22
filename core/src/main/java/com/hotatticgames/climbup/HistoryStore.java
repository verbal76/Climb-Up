package com.hotatticgames.climbup;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Every slice of the climb in progress, in order, in one append-only file (history.bin): a header (magic, seed) and then [length][compressed slice] records.
 * A slice is a few hundred to a thousand bytes, so a 100 km climb is about 1.5 MB, written incrementally (never rewritten as a whole), and the whole tower can be rebuilt exactly from it.
 * A record cut short by a crash is simply ignored when reading.
 */
public final class HistoryStore {
    private static final int MAGIC = 0x434C4D48;      // "CLMH"
    private final File file;
    private int count = -1; private long seed;
    private volatile boolean writeFailed;             // Build 71: a background append failed; count() then re-reads what is really on disk

    public HistoryStore(File dir) { this.file = new File(dir, "history.bin"); }

    /** Starts a new history for this seed (the old one is replaced). */
    public void reset(long seed) throws IOException {
        DiskWriter.drain();
        File tmp = new File(file.getPath() + ".tmp");
        try (DataOutputStream o = new DataOutputStream(new FileOutputStream(tmp))) { o.writeInt(MAGIC); o.writeLong(seed); }
        if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) throw new IOException("cannot replace history"); }
        this.seed = seed; count = 0;
    }

    public void append(byte[] blob) throws IOException { DiskWriter.drain(); appendNow(blob); if (count >= 0) count++; }

    /**
     * Build 71: {@link #append} for the periodic autosave, on the background writer, in order behind every write before it. The slice counts as stored at
     * once (so the next autosave does not hand it over again); if the write fails, {@link #count} finds out from the file and the slice is handed over again.
     */
    public void appendLater(byte[] blob) {
        if (count >= 0) count++;
        DiskWriter.later(() -> { if (writeFailed) return; try { appendNow(blob); } catch (IOException e) { writeFailed = true; } });      // after a failure nothing more is appended until the slices are handed over again, so they stay in order
    }

    private void appendNow(byte[] blob) throws IOException {
        ByteArrayOutputStream rec = new ByteArrayOutputStream(blob.length + 4);
        new DataOutputStream(rec).writeInt(blob.length); rec.write(blob);
        try (FileOutputStream f = new FileOutputStream(file, true)) { f.write(rec.toByteArray()); f.getFD().sync(); }
    }

    /** The stored slices if the file belongs to this seed, otherwise null. */
    public List<byte[]> read(long wantSeed) {
        DiskWriter.drain();
        if (!file.exists()) return null;
        List<byte[]> out = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new FileInputStream(file)))) {
            if (in.readInt() != MAGIC) return null;
            long s = in.readLong(); if (s != wantSeed) return null;
            while (true) {
                int n; try { n = in.readInt(); } catch (java.io.EOFException e) { break; }
                if (n <= 0 || n > 4_000_000) break;
                byte[] b = new byte[n];
                try { in.readFully(b); } catch (java.io.EOFException e) { break; }          // a record cut short: ignore it
                out.add(b);
            }
        } catch (IOException e) { return null; }
        seed = wantSeed; count = out.size();
        return out;
    }

    public int count() {
        if (writeFailed) { DiskWriter.drain(); List<byte[]> on = read(seed); count = on == null ? 0 : on.size(); writeFailed = false; }       // a background append failed: once the queue is empty, trust the file
        return Math.max(0, count);
    }
    public void delete() { DiskWriter.drain(); file.delete(); count = -1; }
}
