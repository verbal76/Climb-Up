package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import java.nio.file.*;
import org.junit.Test;

/**
 * The rendered ramp (assets/space/ramp.g3dj placed by WorldRenderer.case RAMP: arc offset 2.98*sc, drop 0.62*sc, scale sc along the ramp and in height, yaw -90) against the surface the simulation
 * walks on (height = e.y + amp * x, x along the ramp from its middle, |x| <= L/2 with L = 2.2*sc, amp = 0.576). Measured from the real vertices along the walking line, at both ends and in between.
 */
public class RampFitTest {
    private static final float AMP = 0.576f, PLACE_DU = 2.98f, PLACE_DY = -0.62f;

    @Test public void theVisibleDeckSitsOnTheSimulationSurfaceAlongItsWholeLength() throws Exception {
        JsonValue m = new JsonReader().parse(new String(Files.readAllBytes(Paths.get("../assets/space/ramp.g3dj")))).get("meshes").get(0);
        float[] v = m.get("vertices").asFloatArray(); int stride = 10;
        for (float sc : new float[]{1.3f, 1.5f, 1.75f}) {
            float half = 1.1f * sc, worst = 0f;
            for (float x = -half; x <= half + 1e-4f; x += half / 4f) {
                // deck height at arc offset x: the centre-line vertices (|model x| < 0.05) at model z = PLACE_DU - x/sc, interpolated
                float z = PLACE_DU - x / sc, best = Float.NaN, lo = -1e9f, hi = 1e9f, yl = 0, yh = 0;
                for (int i = 0; i < v.length / stride; i++) {
                    if (Math.abs(v[i * stride]) > 0.05f) continue;
                    float vz = v[i * stride + 2], vy = v[i * stride + 1];
                    if (vz <= z && vz > lo) { lo = vz; yl = vy; }
                    if (vz >= z && vz < hi) { hi = vz; yh = vy; }
                }
                float ym = hi - lo < 1e-4f || lo < -1e8f || hi > 1e8f ? (lo > -1e8f ? yl : yh) : yl + (yh - yl) * (z - lo) / (hi - lo);
                float modelTop = PLACE_DY * sc + sc * ym, simTop = AMP * x;
                worst = Math.max(worst, Math.abs(modelTop - simTop));
            }
            assertTrue("sc " + sc + ": the deck is " + worst + " m off the walking surface", worst < 0.08f);
        }
    }
}
