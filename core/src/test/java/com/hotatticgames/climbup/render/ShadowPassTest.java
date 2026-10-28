package com.hotatticgames.climbup.render;

import static org.junit.Assert.*;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import org.junit.Test;

/** Build 72 (owner): the dynamic shadow's projection puts every point of a caster on the platform plane, along the light, and the shader carries it. */
public class ShadowPassTest {
    private static Vector3 project(Matrix4 m, float x, float y, float z) {
        float[] v = m.val;
        float px = v[0] * x + v[4] * y + v[8] * z + v[12], py = v[1] * x + v[5] * y + v[9] * z + v[13];
        float pz = v[2] * x + v[6] * y + v[10] * z + v[14], pw = v[3] * x + v[7] * y + v[11] * z + v[15];
        return new Vector3(px / pw, py / pw, pz / pw);
    }

    @Test public void aPointAboveAFlatPlatformLandsOnItAlongTheLight() {
        Matrix4 m = new Matrix4();
        float lx = 0.1f, ly = 0.95f, lz = -0.3f;           // toward the light
        ShadowPass.planarShadow(m, 0f, 1f, 0f, -2f, lx, ly, lz);    // the plane y = 2
        Vector3 p = project(m, 1f, 5f, 0.5f);
        assertEquals("on the plane", 2f, p.y, 1e-4f);
        float t = 3f / ly;                                   // 3 above the plane, moved away from the light
        assertEquals(1f - lx * t, p.x, 1e-4f);
        assertEquals(0.5f - lz * t, p.z, 1e-4f);
        Vector3 on = project(m, 4f, 2f, -1f);
        assertEquals("a point already on the plane stays put", 4f, on.x, 1e-4f);
        assertEquals(-1f, on.z, 1e-4f);
    }

    @Test public void aPointAboveARampLandsOnTheSlope() {
        Matrix4 m = new Matrix4();
        float slope = 0.576f;                                // y = 1 + slope * x  ->  -slope*x + y - 1 = 0
        ShadowPass.planarShadow(m, -slope, 1f, 0f, -1f, 0f, 1f, 0f);
        Vector3 p = project(m, 2f, 6f, 0f);
        assertEquals("straight down onto the slope", 2f, p.x, 1e-4f);
        assertEquals(1f + slope * 2f, p.y, 1e-4f);
    }

    @Test public void theShadowShaderFlattensBeforeTheCameraAndClipsInTheFragment() {
        String v = ShadowPass.inject("void main() {\n\tvec4 pos = u_worldTrans * vec4(a_position, 1.0);\n\tgl_Position = u_projViewTrans * pos;\n}\n");
        assertTrue(v.contains("uniform mat4 u_shadowProj;"));
        assertTrue("projected right before the camera transform", v.indexOf("pos = u_shadowProj * pos;") < v.indexOf("gl_Position = u_projViewTrans * pos;"));
        assertTrue(ShadowPass.FRAGMENT.contains("discard"));
    }
}
