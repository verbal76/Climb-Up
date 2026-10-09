package com.hotatticgames.climbup.sim;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import java.util.Locale;

/** Compact JSON (de)serialisation of generated courses so validated towers ship as data. */
public final class CourseIO {
    private CourseIO() {}

    public static String toJson(Course c) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"seed\":").append(c.seed).append(",\"circumference\":").append(f(c.circumference)).append(",\"routeCount\":").append(c.routeSize()).append(",\"elements\":[\n");
        for (int i = 0; i < c.size(); i++) { row(sb, c.get(i)); if (i < c.size() - 1) sb.append(",\n"); }
        sb.append("\n],\"hazards\":[\n");
        for (int i = 0; i < c.hazards.size(); i++) { row(sb, c.hazards.get(i)); if (i < c.hazards.size() - 1) sb.append(",\n"); }
        sb.append("\n],\"keyRooms\":[");
        for (int i = 0; i < c.keyRooms.size(); i++) { int[] k = c.keyRooms.get(i); sb.append(i > 0 ? "," : "").append('[').append(k[0]).append(',').append(k[1]).append(',').append(k[2]).append(',').append(k[3]).append(',').append(k[4]).append(',').append(k[5]).append(']'); }
        return sb.append("]}").toString();
    }

    private static void row(StringBuilder sb, Element e) {
        sb.append('[').append(e.type.ordinal()).append(',').append(e.zone).append(',').append(f(e.s)).append(',').append(f(e.y)).append(',')
          .append(f(e.w)).append(',').append(f(e.amp)).append(',').append(f(e.period)).append(',').append(f(e.phase)).append(',')
          .append(f(e.len)).append(',').append(e.checkpoint ? 1 : 0).append(',').append(e.anchor).append(',').append(e.dir).append(',').append(e.color).append(',').append(e.skin).append(']');
    }

    private static String f(float v) { return Float.toString(v); }       // exact round trip: a restored slice regenerates the very same tower above it

    private static Element read(JsonValue a) {
        Element.Type t = Element.Type.values()[a.getInt(0)];
        Element e = new Element(t, a.getFloat(2), a.getFloat(3), a.getFloat(4));
        e.zone = a.getInt(1); e.amp = a.getFloat(5); e.period = a.getFloat(6); e.phase = a.getFloat(7); e.len = a.getFloat(8);
        e.checkpoint = a.getInt(9) == 1; e.anchor = a.size > 10 ? a.getInt(10) : -1; e.dir = a.size > 11 ? a.getInt(11) : 1; e.color = a.size > 12 ? a.getInt(12) : 0; e.skin = a.size > 13 ? a.getInt(13) : 0;
        return e;
    }

    public static Course fromJson(String json) {
        JsonValue root = new JsonReader().parse(json);
        Course c = new Course(root.getLong("seed"), root.getFloat("circumference"));
        for (JsonValue a = root.get("elements").child; a != null; a = a.next) c.add(read(a));
        JsonValue hz = root.get("hazards");
        if (hz != null) for (JsonValue a = hz.child; a != null; a = a.next) c.hazards.add(read(a));
        JsonValue kr = root.get("keyRooms");
        if (kr != null) for (JsonValue a = kr.child; a != null; a = a.next) c.keyRooms.add(new int[]{a.getInt(0), a.getInt(1), a.getInt(2), a.getInt(3), a.getInt(4), a.getInt(5)});
        c.routeCount = root.getInt("routeCount", -1);
        c.indexDecoys();
        return c;
    }
}
