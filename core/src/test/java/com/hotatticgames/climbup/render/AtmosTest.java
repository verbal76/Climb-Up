package com.hotatticgames.climbup.render;

import static org.junit.Assert.*;

import com.badlogic.gdx.graphics.Color;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/** The atmosphere prototype: off by default, its shader additions land where intended (and only under fog), and its data stays subtle and in range. */
public class AtmosTest {
    private static String res(String name) throws Exception {
        try (InputStream in = com.badlogic.gdx.graphics.g3d.shaders.DefaultShader.class.getResourceAsStream(name)) {
            assertNotNull("libGDX ships " + name, in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test public void theMasterSwitchIsOffByDefault() {
        assertFalse("the compile-time default stays off", Atmos.DEFAULT_ON);
        if (System.getProperty("climb.atmos") == null) assertFalse("no property, no atmosphere", Atmos.ENABLED);
    }

    @Test public void theShaderAdditionsFindTheirAnchorsAndStayInsideTheFogBranch() throws Exception {
        String v = res("default.vertex.glsl"), f = res("default.fragment.glsl");
        String av = Atmos.vertexShader(v), af = Atmos.fragmentShader(f, v);
        assertNotEquals("the vertex shader got its varying", v, av);
        assertNotEquals("the fragment shader got the haze", f, af);
        assertTrue(av.contains("v_atmos = vec2(pos.y - u_cameraPosition.y, dot(pos.xyz - u_cameraPosition.xyz, u_atmosView.xyz) - u_atmosView.w);"));
        assertTrue(af.contains("u_atmosHaze") && af.contains("u_atmosMist") && af.contains("u_atmosK"));
        int body = af.indexOf("float atmC"), fog = af.indexOf("mix(gl_FragColor.rgb, u_fogColor.rgb, v_fog)"), main = af.indexOf("void main() {");
        assertTrue("before the distance fog", body > 0 && body < fog);
        assertTrue("declarations before main", af.indexOf("uniform vec4 u_atmosK;") < main);
        String head = af.substring(0, body), ifdef = "#ifdef fogFlag";
        assertTrue("the body opens its own fog branch", head.lastIndexOf(ifdef) > main);
        // an unrecognised shader is left exactly as it was, on both sides
        assertEquals("x", Atmos.fragmentShader("x", v));
        assertEquals(f, Atmos.fragmentShader(f, "no anchor here"));
        assertEquals("y", Atmos.vertexShader("y"));
    }

    @Test public void theHazeNeverReachesThePlayPlane() {
        assertTrue("the haze begins behind the lane (depth measured from the play plane, so a pulled-back camera changes nothing)", Atmos.HAZE_NEAR >= 1f);
        assertTrue(Atmos.HAZE_FAR > Atmos.HAZE_NEAR + 3f);
        assertTrue("the mist starts below the bottom of the screen (the camera sits ~2.5 m above the hero)", Atmos.MIST_BELOW >= 5f && Atmos.MIST_SPAN >= 8f);
    }

    @Test public void perWorldTablesAreCompleteAndModest() {
        int z = Palette.ZONES;
        assertEquals(z, Atmos.HAZE.length); assertEquals(z, Atmos.HAZE_A.length); assertEquals(z, Atmos.MIST.length); assertEquals(z, Atmos.MIST_A.length);
        assertEquals(z, Atmos.HORIZON_A.length); assertEquals(z, Atmos.ZENITH_A.length); assertEquals(z, AtmosMotes.COL.length); assertEquals(z, AtmosMotes.RATE.length);
        for (float[] a : new float[][]{Atmos.HAZE_A, Atmos.MIST_A, Atmos.HORIZON_A, Atmos.ZENITH_A}) for (float x : a) assertTrue("never washes the picture out: " + x, x >= 0f && x <= 0.4f);
        assertTrue(Atmos.GLOW_GAIN >= 0f && Atmos.GLOW_GAIN <= 0.6f);
    }

    @Test public void everyCastleHasItsOwnSubtleTintAndTheStartIsUntouched() {
        assertEquals("one per climb between castles (10 castles a cycle)", 10, Atmos.CASTLE.length);
        float[] c = new float[3];
        Atmos.castleTint(10, 500f, 5000f, c);
        assertArrayEquals("the start is as approved", new float[]{1f, 1f, 1f}, c, 1e-6f);
        for (float[] k : Atmos.CASTLE) { assertEquals(3, k.length); for (float x : k) assertTrue("subtle: " + x, x >= 0.9f && x <= 1.1f); }
        for (int i = 2; i < 10; i++) for (int j = i + 1; j < 10; j++) assertFalse("castle " + i + " and " + j + " differ", java.util.Arrays.equals(Atmos.CASTLE[i], Atmos.CASTLE[j]));
        Atmos.castleTint(2 * 500 + 250, 500f, 5000f, c); assertArrayEquals(Atmos.CASTLE[2], c, 1e-6f);
        Atmos.castleTint(5000 + 1250, 500f, 5000f, c); assertArrayEquals("the cycle repeats", Atmos.CASTLE[2], c, 1e-6f);
        Atmos.castleTint(-50, 500f, 5000f, c); assertArrayEquals("below the start is the start", Atmos.CASTLE[0], c, 1e-6f);
        // continuous across a castle: no visible step from one metre to the next
        float[] a = new float[3], b = new float[3];
        for (double h = 900; h < 1100; h += 0.5) {
            Atmos.castleTint(h, 500f, 5000f, a); Atmos.castleTint(h + 0.5, 500f, 5000f, b);
            for (int i = 0; i < 3; i++) assertEquals("smooth at " + h, a[i], b[i], 0.004f);
        }
        Atmos.castleTint(4999.9, 500f, 5000f, a); Atmos.castleTint(5000.1, 500f, 5000f, b);
        for (int i = 0; i < 3; i++) assertEquals("the wrap from castle 10 back to the start is smooth too", a[i], b[i], 0.01f);
        Atmos.castleTint(1000, 0f, 5000f, c); assertArrayEquals("bad tuning: neutral", new float[]{1f, 1f, 1f}, c, 0f);
    }

    @Test public void colourblindSafeColoursKeepTheLightsNeutralAndLowQualityAddsNothing() {
        Atmos at = new Atmos();
        Color top = new Color(0.4f, 0.6f, 1f, 1f), mid = new Color(0.6f, 0.6f, 0.8f, 1f), bot = new Color(0.6f, 0.5f, 0.7f, 1f);
        at.frame(2.6f, 2700, 500f, 5000f, 2, true, 0f, top, mid, bot);
        assertArrayEquals("colourblind: the lights the keys and gates are seen in are untouched", new float[]{1f, 1f, 1f}, at.light, 0f);
        at.frame(2.6f, 2700, 500f, 5000f, 2, false, 0f, top, mid, bot);
        assertFalse("otherwise the castle reaches the lights", java.util.Arrays.equals(new float[]{1f, 1f, 1f}, at.light));
        assertTrue(at.haze[3] > 0f && at.mist[3] > 0f && at.params[3] > 0f);
        at.frame(2.6f, 2700, 500f, 5000f, 0, false, 0f, top, mid, bot);
        assertEquals("LOW quality: no haze", 0f, at.haze[3], 0f); assertEquals(0f, at.mist[3], 0f); assertEquals("no glow", 0f, at.params[3], 0f);
        for (float x : at.haze) assertTrue(x >= 0f && x <= 1f);
    }
}
