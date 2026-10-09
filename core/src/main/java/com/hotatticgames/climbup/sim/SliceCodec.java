package com.hotatticgames.climbup.sim;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/** Lossless compact binary form of a generated slice (every float stored as its exact bits, deflated): about 1-2 KB per slice, so the whole history of a very long climb stays cheap to keep and to save. */
public final class SliceCodec {
    private SliceCodec() {}
    private static final int FORMAT = 1;

    public static byte[] encode(CourseGenerator.Framed f) {
        try {
            ByteArrayOutputStream raw = new ByteArrayOutputStream(4096);
            DataOutputStream o = new DataOutputStream(new DeflaterOutputStream(raw));
            Course c = f.c;
            o.writeByte(FORMAT); o.writeLong(c.seed); o.writeFloat(c.circumference); o.writeInt(c.routeSize()); o.writeBoolean(c.gemCheckpoints);
            o.writeDouble(f.yBase); o.writeDouble(f.sBase);
            o.writeInt(c.size()); o.writeInt(c.hazards.size()); o.writeInt(c.keyRooms.size());
            for (int i = 0; i < c.size(); i++) put(o, c.get(i));
            for (Element h : c.hazards) put(o, h);
            for (int[] k : c.keyRooms) for (int v : k) o.writeInt(v);
            o.close();
            return raw.toByteArray();
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    public static CourseGenerator.Framed decode(byte[] data) {
        try {
            DataInputStream in = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data)));
            int fmt = in.readByte(); if (fmt != FORMAT) throw new IOException("unknown slice format " + fmt);
            long seed = in.readLong(); float circ = in.readFloat(); int route = in.readInt(); boolean gem = in.readBoolean();
            double yBase = in.readDouble(), sBase = in.readDouble();
            int n = in.readInt(), nh = in.readInt(), nk = in.readInt();
            Course c = new Course(seed, circ); c.gemCheckpoints = gem;
            for (int i = 0; i < n; i++) c.add(get(in));
            for (int i = 0; i < nh; i++) c.hazards.add(get(in));
            for (int i = 0; i < nk; i++) { int[] k = new int[6]; for (int j = 0; j < 6; j++) k[j] = in.readInt(); c.keyRooms.add(k); }
            c.routeCount = route; c.indexDecoys();
            return new CourseGenerator.Framed(c, yBase, sBase);
        } catch (IOException e) { throw new IllegalStateException("corrupt slice", e); }
    }

    private static void put(DataOutputStream o, Element e) throws IOException {
        o.writeByte(e.type.ordinal()); o.writeByte(e.zone);
        o.writeFloat(e.s); o.writeFloat(e.y); o.writeFloat(e.w); o.writeFloat(e.amp); o.writeFloat(e.period); o.writeFloat(e.phase); o.writeFloat(e.len);
        o.writeBoolean(e.checkpoint); o.writeShort(e.anchor); o.writeByte(e.dir); o.writeByte(e.color); o.writeByte(e.skin);
    }

    private static Element get(DataInputStream in) throws IOException {
        Element.Type t = Element.Type.values()[in.readByte()]; int zone = in.readByte();
        float s = in.readFloat(), y = in.readFloat(), w = in.readFloat();
        Element e = new Element(t, s, y, w); e.zone = zone;
        e.amp = in.readFloat(); e.period = in.readFloat(); e.phase = in.readFloat(); e.len = in.readFloat();
        e.checkpoint = in.readBoolean(); e.anchor = in.readShort(); e.dir = in.readByte(); e.color = in.readByte(); e.skin = in.readByte();
        return e;
    }

    /** True if two courses are identical in every stored field (bit-exact floats). */
    public static boolean same(Course a, Course b) {
        if (a.size() != b.size() || a.hazards.size() != b.hazards.size() || a.routeSize() != b.routeSize() || a.keyRooms.size() != b.keyRooms.size() || a.gemCheckpoints != b.gemCheckpoints) return false;
        for (int i = 0; i < a.size(); i++) if (!sameE(a.get(i), b.get(i))) return false;
        for (int i = 0; i < a.hazards.size(); i++) if (!sameE(a.hazards.get(i), b.hazards.get(i))) return false;
        for (int i = 0; i < a.keyRooms.size(); i++) if (!java.util.Arrays.equals(a.keyRooms.get(i), b.keyRooms.get(i))) return false;
        return true;
    }

    private static boolean sameE(Element a, Element b) {
        return a.type == b.type && a.zone == b.zone && Float.floatToRawIntBits(a.s) == Float.floatToRawIntBits(b.s) && Float.floatToRawIntBits(a.y) == Float.floatToRawIntBits(b.y)
            && Float.floatToRawIntBits(a.w) == Float.floatToRawIntBits(b.w) && Float.floatToRawIntBits(a.amp) == Float.floatToRawIntBits(b.amp) && Float.floatToRawIntBits(a.period) == Float.floatToRawIntBits(b.period)
            && Float.floatToRawIntBits(a.phase) == Float.floatToRawIntBits(b.phase) && Float.floatToRawIntBits(a.len) == Float.floatToRawIntBits(b.len)
            && a.checkpoint == b.checkpoint && a.anchor == b.anchor && a.dir == b.dir && a.color == b.color && a.skin == b.skin;
    }
}
