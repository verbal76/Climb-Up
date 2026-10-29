package com.hotatticgames.climbup;

import com.hotatticgames.climbup.sim.CourseGenerator;
import com.hotatticgames.climbup.sim.Element;
import com.hotatticgames.climbup.sim.SliceCodec;
import com.hotatticgames.climbup.sim.Tuning;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * One demo stage's fixed course, baked ahead of time (tools: DemoBake) so the demo never generates a slice while it plays: every slice of the tower
 * from the bottom to a little past the stage's last castle, exactly as the generator made them, with the stamps that say which generator that was.
 * Loading checks every stamp against this build (the seed and difficulty the recipe names, the generation rules version, the tuning's CRC and the
 * file's SHA-256) and refuses a course that does not match: the demo never plays a course another generator made, and never quietly plays another one.
 *
 * File: gzip of  "UPWDEMO1", int format, long seed, int difficulty, int rules, int tuningStamp, int fromCastle, int toCastle, int slices, then each
 * slice as int length + its SliceCodec bytes. The SHA-256 in the recipe is of the uncompressed bytes.
 */
public final class DemoCourse {
    static final String MAGIC = "UPWDEMO1";
    static final int FORMAT = 1;

    public static final class Invalid extends RuntimeException { public Invalid(String m) { super(m); } }

    public final long seed; public final int difficulty, rules, tuningStamp, fromCastle, toCastle;
    public final List<byte[]> slices;
    /** The slice that holds castle fromCastle (the stage's start), found while loading. */
    public final int startSlice;
    public final String sha256;

    private DemoCourse(long seed, int difficulty, int rules, int tuningStamp, int fromCastle, int toCastle, List<byte[]> slices, int startSlice, String sha256) {
        this.seed = seed; this.difficulty = difficulty; this.rules = rules; this.tuningStamp = tuningStamp; this.fromCastle = fromCastle; this.toCastle = toCastle;
        this.slices = slices; this.startSlice = startSlice; this.sha256 = sha256;
    }

    /** The generator fingerprint shown in About and written to the build manifest: rules version, tuning CRC and the course file's hash. */
    public String fingerprint() { return "r" + rules + "-t" + String.format("%08x", tuningStamp) + "-" + sha256.substring(0, 12); }

    /** Writes a baked course (tools only). */
    public static byte[] encode(long seed, int difficulty, int rules, int tuningStamp, int fromCastle, int toCastle, List<byte[]> slices) {
        try {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(raw);
            o.writeBytes(MAGIC); o.writeInt(FORMAT); o.writeLong(seed); o.writeInt(difficulty); o.writeInt(rules); o.writeInt(tuningStamp); o.writeInt(fromCastle); o.writeInt(toCastle);
            o.writeInt(slices.size());
            for (byte[] b : slices) { o.writeInt(b.length); o.write(b); }
            o.flush();
            ByteArrayOutputStream gz = new ByteArrayOutputStream();
            try (GZIPOutputStream z = new GZIPOutputStream(gz)) { z.write(raw.toByteArray()); }
            return gz.toByteArray();
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    /** SHA-256 (hex) of a baked file's uncompressed content: what the recipe pins. */
    public static String contentSha256(byte[] file) { return sha256(gunzip(file)); }

    /**
     * Reads and checks a baked course for a recipe stage. {@code genTuning} is the tuning the stage's difficulty generates with in this build.
     * Throws {@link Invalid} naming the first thing that does not match.
     */
    public static DemoCourse load(byte[] file, DemoRecipe recipe, DemoRecipe.Stage stage, Tuning genTuning) {
        byte[] raw;
        try { raw = gunzip(file); } catch (RuntimeException e) { throw new Invalid("the course file of stage " + (stage.difficulty + 1) + " is damaged (" + e.getMessage() + ")"); }
        String sha = sha256(raw);
        if (!sha.equals(stage.sha256)) throw new Invalid("stage " + (stage.difficulty + 1) + " course file does not match the recipe (sha256 " + sha.substring(0, 12) + ", recipe " + stage.sha256.substring(0, Math.min(12, stage.sha256.length())) + ")");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
            byte[] m = new byte[MAGIC.length()]; in.readFully(m);
            if (!MAGIC.equals(new String(m, java.nio.charset.StandardCharsets.US_ASCII))) throw new Invalid("not a demo course file");
            int format = in.readInt(); if (format != FORMAT) throw new Invalid("demo course format " + format + " (this build reads " + FORMAT + ")");
            long seed = in.readLong(); int diff = in.readInt(), rules = in.readInt(), stamp = in.readInt(), from = in.readInt(), to = in.readInt(), n = in.readInt();
            if (seed != stage.seed) throw new Invalid("stage " + (stage.difficulty + 1) + " course is seed " + seed + ", the recipe names " + stage.seed);
            if (diff != stage.difficulty) throw new Invalid("stage " + (stage.difficulty + 1) + " course was made for difficulty " + diff);
            if (rules != CourseGenerator.RULES_VERSION) throw new Invalid("stage " + (stage.difficulty + 1) + " course was made by generation rules " + rules + ", this build has rules " + CourseGenerator.RULES_VERSION);
            int want = tuningStamp(genTuning);
            if (stamp != want) throw new Invalid("stage " + (stage.difficulty + 1) + " course was made with other tuning (" + Integer.toHexString(stamp) + ", this build " + Integer.toHexString(want) + ")");
            if (from != recipe.fromCastle || to != recipe.toCastle) throw new Invalid("stage " + (stage.difficulty + 1) + " course covers castles " + from + "-" + to + ", the recipe " + recipe.fromCastle + "-" + recipe.toCastle);
            if (n <= 0 || n > 4096) throw new Invalid("slice count " + n);
            List<byte[]> slices = new ArrayList<>(n);
            int startSlice = -1; boolean end = false; double top = Double.NEGATIVE_INFINITY;
            for (int k = 0; k < n; k++) {
                int len = in.readInt(); if (len <= 0 || len > (1 << 24)) throw new Invalid("slice " + k + " length " + len);
                byte[] b = new byte[len]; in.readFully(b); slices.add(b);
                CourseGenerator.Framed f;
                try { f = SliceCodec.decode(b); } catch (RuntimeException e) { throw new Invalid("slice " + k + " does not decode: " + e.getMessage()); }
                for (Element h : f.c.hazards) if (h.type == Element.Type.GATE && h.anchor >= 0) { if (h.skin == from) startSlice = k; if (h.skin == to) end = true; if (h.skin > to) throw new Invalid("slice " + k + " holds castle " + h.skin + ", past the stage"); }
                top = f.yBase + f.c.get(f.c.routeSize() - 1).y;
            }
            if (in.read() != -1) throw new Invalid("trailing bytes after the last slice");
            if (startSlice < 0 || !end) throw new Invalid("the course does not hold both castle " + from + " and castle " + to);
            if (top < to * (double) genTuning.castleSpacing + DemoFlow.HEADROOM) throw new Invalid("the course ends " + (int) top + " m up, short of the room the stage needs above castle " + to);
            return new DemoCourse(seed, diff, rules, stamp, from, to, java.util.Collections.unmodifiableList(slices), startSlice, sha);
        } catch (IOException e) { throw new Invalid("the course file is cut short (" + e.getMessage() + ")"); }
    }

    /**
     * CRC32 of every tuning number, by field name in sorted order and by exact bits: the same on every runtime (Android's reflection order and float
     * printing can differ from the desktop's, so the save's JSON-based stamp is not used here). Never 0.
     */
    public static int tuningStamp(Tuning t) {
        java.util.zip.CRC32 c = new java.util.zip.CRC32();
        java.lang.reflect.Field[] fs = Tuning.class.getFields();
        java.util.Arrays.sort(fs, java.util.Comparator.comparing(java.lang.reflect.Field::getName));
        try {
            for (java.lang.reflect.Field f : fs) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                c.update(f.getName().getBytes(java.nio.charset.StandardCharsets.US_ASCII)); long bits;
                Class<?> ty = f.getType();
                if (ty == float.class) bits = Float.floatToIntBits(f.getFloat(t));
                else if (ty == double.class) bits = Double.doubleToLongBits(f.getDouble(t));
                else if (ty == int.class) bits = f.getInt(t);
                else if (ty == long.class) bits = f.getLong(t);
                else if (ty == boolean.class) bits = f.getBoolean(t) ? 1 : 0;
                else throw new IllegalStateException("tuning field " + f.getName() + " has a type the stamp does not cover: " + ty);
                for (int i = 0; i < 8; i++) c.update((int) (bits >>> (8 * i)) & 0xff);
            }
        } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
        return (int) c.getValue() | 1;
    }

    static byte[] gunzip(byte[] file) {
        try (GZIPInputStream z = new GZIPInputStream(new ByteArrayInputStream(file))) {
            ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] buf = new byte[65536]; int n;
            while ((n = z.read(buf)) > 0) o.write(buf, 0, n);
            return o.toByteArray();
        } catch (IOException e) { throw new IllegalStateException("gzip: " + e.getMessage(), e); }
    }

    static String sha256(byte[] b) {
        try { byte[] d = MessageDigest.getInstance("SHA-256").digest(b); StringBuilder sb = new StringBuilder(); for (byte x : d) sb.append(String.format("%02x", x & 0xff)); return sb.toString(); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
