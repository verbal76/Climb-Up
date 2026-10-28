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
    private static final int HEADER = 12;             // magic + seed
    /**
     * Save repair H1 (2026-10-28): the byte offset just past the last whole record, -1 when not known (found by scanning the file before the next
     * append). A record cut short by a failed or interrupted write is cut off before anything is appended, so a new record never lands after broken
     * bytes (it used to, and the reader then took the next record's bytes as the broken record's body). The file format is unchanged.
     */
    private long goodEnd = -1;

    public HistoryStore(File dir) { this.file = new File(dir, "history.bin"); }

    /** Starts a new history for this seed (the old one is replaced). */
    public void reset(long seed) throws IOException {
        DiskWriter.drain();
        File tmp = new File(file.getPath() + ".tmp");
        try (DataOutputStream o = new DataOutputStream(new FileOutputStream(tmp))) { o.writeInt(MAGIC); o.writeLong(seed); }
        if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) throw new IOException("cannot replace history"); }
        this.seed = seed; count = 0; goodEnd = HEADER;
    }

    /**
     * Save repair H2 (2026-10-28): replaces the history with exactly these records (the readable start of a damaged one), written to a temporary file
     * and renamed over it, so a failure leaves the old file as it was.
     */
    public void rewrite(long seed, List<byte[]> records) throws IOException {
        DiskWriter.drain();
        File tmp = new File(file.getPath() + ".tmp");
        long end = HEADER;
        try (FileOutputStream f = new FileOutputStream(tmp); DataOutputStream o = new DataOutputStream(new java.io.BufferedOutputStream(f))) {
            o.writeInt(MAGIC); o.writeLong(seed);
            for (byte[] b : records) { o.writeInt(b.length); o.write(b); end += 4 + b.length; }
            o.flush(); f.getFD().sync();
        }
        if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) throw new IOException("cannot replace history"); }
        this.seed = seed; count = records.size(); goodEnd = end;
    }

    /**
     * Save repair H2 (2026-10-28): moves the history file aside (never deleted) under a name that says whose it was, so a climb that cannot be resumed
     * is kept for diagnosis or a later recovery. Returns the file it now lives in, or null when there was none or it could not be moved.
     */
    public File keepAside(long climbSeed, String why) {
        DiskWriter.drain();
        if (!file.exists()) return null;
        File aside = new File(file.getParentFile(), "history-" + why + "-" + Long.toHexString(climbSeed) + "-" + System.currentTimeMillis() + ".bin");
        if (!file.renameTo(aside)) return null;
        count = -1; goodEnd = -1;
        return aside;
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

    private synchronized void appendNow(byte[] blob) throws IOException {
        ByteArrayOutputStream rec = new ByteArrayOutputStream(blob.length + 4);
        new DataOutputStream(rec).writeInt(blob.length); rec.write(blob);
        if (goodEnd < 0) goodEnd = wholeRecordsEnd();
        if (goodEnd >= HEADER && file.length() > goodEnd) try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(file, "rw")) { r.setLength(goodEnd); }     // (H1: cut off a torn record first)
        try (FileOutputStream f = new FileOutputStream(file, true)) { f.write(rec.toByteArray()); f.getFD().sync(); }
        catch (IOException e) { goodEnd = -1; throw e; }          // what reached the disk is unknown: scan again before the next append
        goodEnd += rec.size();
    }

    /** The offset just past the last whole record on disk (the file's length when it has no valid header: nothing is cut then). */
    private long wholeRecordsEnd() throws IOException {
        long len = file.length();
        if (len < HEADER) return len;
        long pos;
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new FileInputStream(file)))) {
            if (in.readInt() != MAGIC) return len;
            in.readLong(); pos = HEADER;
            while (pos + 4 <= len) {
                int n = in.readInt();
                if (n <= 0 || n > 4_000_000 || pos + 4 + n > len) break;
                in.readFully(new byte[n]); pos += 4 + n;
            }
        }
        return pos;
    }

    /** The stored slices if the file belongs to this seed, otherwise null. */
    public List<byte[]> read(long wantSeed) {
        DiskWriter.drain();
        if (!file.exists()) return null;
        List<byte[]> out = new ArrayList<>();
        long end = HEADER;
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new FileInputStream(file)))) {
            if (in.readInt() != MAGIC) return null;
            long s = in.readLong(); if (s != wantSeed) return null;
            while (true) {
                int n; try { n = in.readInt(); } catch (java.io.EOFException e) { break; }
                if (n <= 0 || n > 4_000_000) break;
                byte[] b = new byte[n];
                try { in.readFully(b); } catch (java.io.EOFException e) { break; }          // a record cut short: ignore it
                out.add(b); end += 4 + n;
            }
        } catch (IOException e) { return null; }
        seed = wantSeed; count = out.size(); goodEnd = end;          // (H1: the next append starts right after the last whole record)
        return out;
    }

    public int count() {
        if (writeFailed) { DiskWriter.drain(); List<byte[]> on = read(seed); count = on == null ? 0 : on.size(); writeFailed = false; }       // a background append failed: once the queue is empty, trust the file
        return Math.max(0, count);
    }
    public void delete() { DiskWriter.drain(); file.delete(); count = -1; goodEnd = -1; }
}
