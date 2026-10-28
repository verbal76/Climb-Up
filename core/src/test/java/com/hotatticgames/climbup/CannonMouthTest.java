package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import com.hotatticgames.climbup.render.WorldRenderer;
import com.hotatticgames.climbup.sim.Element;
import org.junit.Test;

/**
 * Build 67 (owner: "they need to spawn from the center of the mouth of the cannon"): every cannon is drawn so the centre of its mouth is where the
 * simulation launches the ball. The renderer places the model with the matrices replicated here (translate to the ring at radius - dz, turn by the
 * arc angle plus the yaw, scale PK; cradle node tilted about its axle), and the mouth ring's centre is at (2.258, 1.018) model units from the axle.
 */
public class CannonMouthTest {
    static final float R = 10f, PK = 0.37f;

    /** The world point of (arc, y, dz) as WorldRenderer.place computes it with the camera at arc 0. */
    static Vector3 world(float arc, float y, float dz) {
        float phi = arc / R, r = R - dz;
        return new Vector3(r * (float) Math.sin(phi), y, -R + r * (float) Math.cos(phi));
    }

    /** The mouth ring's centre once the engine has drawn the cannon with its axle at (arc, y, dz), turned yaw and its cradle tilted. */
    static Vector3 engineMouth(float arc, float y, float dz, float yaw, float tilt) {
        float phi = arc / R, r = R - dz;
        Matrix4 m = new Matrix4().idt().translate(r * (float) Math.sin(phi), y, -R + r * (float) Math.cos(phi))
                .rotate(0, 1, 0, phi * (180f / (float) Math.PI) + yaw).scale(PK, PK, PK);
        Matrix4 cradle = new Matrix4().set(new Vector3(), new Quaternion().setFromAxis(0f, 0f, 1f, tilt), new Vector3(1, 1, 1));
        return new Vector3(WorldRenderer.MOUTH_X, WorldRenderer.MOUTH_Y, 0f).mul(cradle).mul(m);
    }

    @Test public void theMouthOffsetsMatchTheModel() {
        float[] o = new float[2];
        WorldRenderer.mouthFromAxle(0f, 0f, o);
        assertEquals("side cannon toward +s: a barrel's length ahead of the axle", 2.258f * PK, o[0], 0.002f);
        assertEquals("...at the barrel axis height", 1.018f * PK, o[1], 0.002f);
        WorldRenderer.mouthFromAxle(180f, 0f, o);
        assertEquals("side cannon toward -s: mirrored", -2.258f * PK, o[0], 0.002f);
        WorldRenderer.mouthFromAxle(0f, 90f, o);
        assertEquals("mortar: the mouth stands a barrel-axis height to the LEFT of the axle", -1.018f * PK, o[0], 0.002f);
        assertEquals("...and a barrel's length above it", 2.258f * PK, o[1], 0.002f);
        assertEquals("no depth: lane units are metres", 0.835f, WorldRenderer.laneUnits(0.835f, 0f, R), 1e-6f);
        assertEquals("1.7 behind the lane the ring is drawn at radius 8.3, so 0.835 m spans 1.006 lane units", 0.835f * 10f / 8.3f, WorldRenderer.laneUnits(0.835f, 1.7f, R), 1e-4f);
    }

    @Test public void aSideCannonsMouthIsOnTheLaunchPointAtItsDepth() {
        float dz = WorldRenderer.AIM_DEPTH, s = 3.1f, y = 12f;
        for (int dir = -1; dir <= 1; dir += 2) {
            float yaw = dir > 0 ? 0f : 180f;
            float[] mz = WorldRenderer.mouthFromAxle(yaw, 0f, new float[2]);
            float body = s - WorldRenderer.laneUnits(mz[0], dz, R);                       // what the CANNON case draws
            Vector3 mouth = engineMouth(body, y - WorldRenderer.CANNON_AXIS_UP, dz, yaw, 0f), launch = world(s, y, dz);
            assertEquals("dir " + dir + ": the drawn mouth is on the simulation's launch point (a rigid barrel leaves the ring by its curvature only)", 0f, mouth.dst(launch), 0.06f);
            assertTrue("dir " + dir + ": the mouth is not short of the launch point along the lane", Math.abs(mouth.x - launch.x) < 0.03f);
        }
    }

    @Test public void theMortarsMouthIsOnItsLaunchArc() {
        float s = -2.2f, y = 30f;
        float[] mz = WorldRenderer.mouthFromAxle(0f, 90f, new float[2]);
        Vector3 mouth = engineMouth(s - mz[0], y - mz[1], 0f, 0f, 90f), launch = world(s, y, 0f);
        assertEquals("the mouth ring's centre is where the ball rises from", 0f, mouth.dst(launch), 0.01f);
    }

    @Test public void theTurretFormulaFindsTheDrawnMouthAtEveryAim() {
        float dz = WorldRenderer.AIM_DEPTH, s = 1.5f, y = 20f;
        float[] mz = new float[2];
        for (int deg = -80; deg <= 260; deg += 20) {
            float ang = deg * (float) Math.PI / 180f;
            boolean left = Math.cos(ang) < 0f;
            float tilt = left ? 180f - deg : deg; tilt = ((tilt + 180f) % 360f + 360f) % 360f - 180f;     // as the AIMED case tilts the cradle
            WorldRenderer.mouthFromAxle(left ? 180f : 0f, tilt, mz);
            float mouthS = s + WorldRenderer.laneUnits(mz[0], dz, R), mouthY = y - WorldRenderer.CANNON_AXIS_UP + mz[1];
            Vector3 formula = world(mouthS, mouthY, dz), engine = engineMouth(s, y - WorldRenderer.CANNON_AXIS_UP, dz, left ? 180f : 0f, tilt);
            assertEquals("aim " + deg + " deg: the formula's mouth is the engine's", 0f, formula.dst(engine), 0.06f);
        }
        // straight ahead the drawn mouth is further along the lane than the simulation's launch point (a rigid barrel behind the lane spans more arc): the ball is drawn from the mouth and glides onto its path
        WorldRenderer.mouthFromAxle(0f, 0f, mz);
        float offS = WorldRenderer.laneUnits(mz[0], dz, R) - Element.AIM_MUZZLE;
        assertTrue("the glide is a small correction, not a teleport", offS > 0.1f && offS < 0.25f);
        // aiming 60 degrees up the mouth is left of and below the launch point
        WorldRenderer.mouthFromAxle(0f, 60f, mz);
        float offS60 = WorldRenderer.laneUnits(mz[0], dz, R) - (float) Math.cos(Math.PI / 3) * Element.AIM_MUZZLE, offY60 = -WorldRenderer.CANNON_AXIS_UP + mz[1] - (float) Math.sin(Math.PI / 3) * Element.AIM_MUZZLE;
        assertTrue("60 degrees up: the mouth is about a third of a metre left of the old launch point", offS60 < -0.25f && offS60 > -0.4f);
        assertTrue("...and a little below it", offY60 < -0.1f && offY60 > -0.3f);
    }
}
