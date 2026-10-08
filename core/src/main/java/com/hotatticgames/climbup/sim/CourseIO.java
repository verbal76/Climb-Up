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
        for (int i = 0; i < c.size(); i++) {
            Element e = c.get(i);
            sb.append('[').append(e.type.ordinal()).append(',').append(e.zone).append(',').append(f(e.s)).append(',').append(f(e.y)).append(',')
              .append(f(e.w)).append(',').append(f(e.amp)).append(',').append(f(e.period)).append(',').append(f(e.phase)).append(',')
              .append(f(e.len)).append(',').append(e.checkpoint ? 1 : 0).append(',').append(e.anchor).append(']');
            if (i < c.size() - 1) sb.append(",\n");
        }
        return sb.append("\n]}").toString();
    }

    private static String f(float v) { return String.format(Locale.ROOT, "%.4f", v); }

    public static Course fromJson(String json) {
        JsonValue root = new JsonReader().parse(json);
        Course c = new Course(root.getLong("seed"), root.getFloat("circumference"));
        for (JsonValue a = root.get("elements").child; a != null; a = a.next) {
            Element.Type t = Element.Type.values()[a.getInt(0)];
            Element e = new Element(t, a.getFloat(2), a.getFloat(3), a.getFloat(4));
            e.zone = a.getInt(1); e.amp = a.getFloat(5); e.period = a.getFloat(6); e.phase = a.getFloat(7); e.len = a.getFloat(8);
            e.checkpoint = a.getInt(9) == 1; e.anchor = a.size > 10 ? a.getInt(10) : -1;
            c.add(e);
        }
        c.routeCount = root.getInt("routeCount", -1);
        c.indexDecoys();
        return c;
    }
}
