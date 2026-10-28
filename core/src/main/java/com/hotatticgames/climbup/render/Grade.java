package com.hotatticgames.climbup.render;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;

/**
 * Build 72 (owner: "a little washed out ... I want some beautiful looks"): colour grading. The 3D shader is libGDX's own default shader with one step
 * added just before the distance fog: a saturation and contrast curve. Grading inside the shader keeps the window's anti-aliasing, the hero's skinning and
 * the fog (an off-screen post pass would lose the multisampling). The 2D sky gets the same saturation in {@link #saturate}.
 */
public final class Grade {
    private Grade() { }

    /** Colour saturation (1 = unchanged) and contrast around mid-grey. */
    public static final float SATURATION = 1.22f, CONTRAST = 1.10f;
    /** Lighting balance: less flat ambient light and a slightly stronger key light, so the blocks show their shape. */
    public static final float AMBIENT = 0.84f, KEY = 1.08f;

    private static final String FOG = "\t#ifdef fogFlag\n\t\tgl_FragColor.rgb = mix(gl_FragColor.rgb, u_fogColor.rgb, v_fog);";

    /** The default shader config with the graded fragment shader. */
    public static DefaultShader.Config config(DefaultShader.Config c) {
        c.fragmentShader = fragmentShader();
        return c;
    }

    static String fragmentShader() {
        String src = DefaultShader.getDefaultFragmentShader();
        int main = src.indexOf("void main() {"), fog = src.indexOf(FOG);
        if (main < 0 || fog < 0) return src;          // a libGDX whose shader we do not recognise: draw ungraded rather than break
        String fn = "vec3 upwGrade(vec3 c) {\n"
            + "\tfloat l = dot(c, vec3(0.2126, 0.7152, 0.0722));\n"
            + "\tc = mix(vec3(l), c, " + glsl(SATURATION) + ");\n"
            + "\tc = (c - 0.5) * " + glsl(CONTRAST) + " + 0.5;\n"
            + "\treturn clamp(c, 0.0, 1.0);\n}\n\n";
        String graded = src.substring(0, fog) + "\tgl_FragColor.rgb = upwGrade(gl_FragColor.rgb);\n" + src.substring(fog);
        return graded.substring(0, main) + fn + graded.substring(main);
    }

    private static String glsl(float v) { return String.format(java.util.Locale.ROOT, "%.4f", v); }

    /** The same saturation for colours drawn outside the 3D shader (the sky gradient). */
    public static Color saturate(Color c) {
        float l = 0.2126f * c.r + 0.7152f * c.g + 0.0722f * c.b;
        c.r = clamp(l + (c.r - l) * SATURATION); c.g = clamp(l + (c.g - l) * SATURATION); c.b = clamp(l + (c.b - l) * SATURATION);
        return c;
    }

    private static float clamp(float v) { return v < 0f ? 0f : v > 1f ? 1f : v; }
}
