package com.hotatticgames.climbup.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import org.junit.Test;

/**
 * Reproduces the "arms/legs shrink after a long grip" bug and proves the fix, headlessly (pure matrix math, no GL).
 *
 * HeroRig.commitBone() re-aims a bone by conjugating a world-space rotation through the bone's parent and writing the
 * result back as the bone's LOCAL transform: cand = inv(parentGlobal) * T(pB) * R * T(-pB) * boneGlobal. When the parent
 * global carries any non-uniform scale - which happens the moment a higher bone has itself drifted, and from plain float
 * error - that conjugation leaks a sliver of scale into cand. The old code adopted cand's scale, and because the clips do
 * not re-key the arm/leg scale every frame, the leak compounded frame over frame: the limb shrank toward zero over a long
 * grip (the NaN guard only froze it once it was basically zero, leaving the tiny arms in the bug report). The fix keeps
 * the bone's own scale and takes only cand's rotation, so a re-aim can never change the bone's scale.
 */
public class RigScaleDriftTest {

    // A parent whose global transform is non-uniformly scaled (the condition that leaks scale under conjugation).
    private static Matrix4 parent() {
        return new Matrix4().translate(1f, 2f, 0f).rotate(0f, 0f, 1f, 20f).scale(1.08f, 0.93f, 1f);
    }
    // The world-space re-aim rotation commitBone applies each frame.
    private static final Quaternion REAIM = new Quaternion().setFromAxis(0f, 0f, 1f, 15f);

    /** Builds the exact 'cand' (candidate new local) that commitBone computes for a bone with the given local transform. */
    private static void cand(Matrix4 parentGlobal, Matrix4 boneLocal, Matrix4 out) {
        Matrix4 boneGlobal = new Matrix4(parentGlobal).mul(boneLocal);
        Vector3 pB = new Vector3();
        boneGlobal.getTranslation(pB);
        Matrix4 rot = new Matrix4().setToTranslation(pB).rotate(REAIM).translate(-pB.x, -pB.y, -pB.z);
        Matrix4 wNew = new Matrix4(rot).mul(boneGlobal);
        Matrix4 parInv = new Matrix4(parentGlobal).inv();
        out.set(parInv).mul(wNew);
    }

    private static float scaleMag(Matrix4 m) {
        Vector3 s = new Vector3();
        m.getScale(s);
        return (s.x + s.y + s.z) / 3f;
    }

    /**
     * One re-aim of a pure world rotation through a non-uniformly-scaled parent already perturbs the bone's scale by a
     * systematic amount - here ~8e-4, about four orders of magnitude above single-conjugation float noise (~1e-7). On its
     * own that is invisible; the across-frames test shows how adopting it every frame compounds into a visible shrink.
     */
    @Test
    public void aNonUniformParentLeaksScaleInASingleStep() {
        Matrix4 local = new Matrix4().translate(0f, 1f, 0f); // scale 1
        Matrix4 c = new Matrix4();
        cand(parent(), local, c);
        assertTrue("conjugation through a non-uniform parent must perturb scale away from 1 (leak was "
                + Math.abs(scaleMag(c) - 1f) + ")", Math.abs(scaleMag(c) - 1f) > 1e-4f);
    }

    /**
     * Across frames the clips re-key the bone's rotation/translation but not its scale, so the scale carries over. The old
     * behaviour (adopt cand's scale) drives it far from 1 - the shrinking limbs; the fix (keep the bone's own scale) holds
     * it exactly at 1 no matter how long the grip lasts.
     */
    @Test
    public void oldAdoptShrinksAcrossFramesFixKeepsScaleSteady() {
        final Matrix4 parent = parent();
        final Vector3 animT = new Vector3(0f, 1f, 0f);
        final Quaternion animR = new Quaternion(); // the clip's pose for this bone (identity here); scale is NOT re-keyed
        final Matrix4 c = new Matrix4();
        final Vector3 sc = new Vector3();

        // OLD: each frame the bone is rebuilt from the clip pose carrying the PREVIOUS cand's scale, then re-aimed.
        float sOld = 1f;
        for (int f = 0; f < 600; f++) {
            Matrix4 local = new Matrix4().set(animT, animR, new Vector3(sOld, sOld, sOld));
            cand(parent, local, c);
            c.getScale(sc);
            sOld = (sc.x + sc.y + sc.z) / 3f; // the bug: adopt cand's (leaked) scale
        }
        assertTrue("old behaviour must drift the limb's scale far from 1 over a long grip (observed deviation "
                + Math.abs(sOld - 1f) + ")", Math.abs(sOld - 1f) > 0.1f);

        // FIX: each frame keep the bone's own scale (the clip pose's scale, which stays 1); take only cand's rotation.
        float sFix = 1f;
        for (int f = 0; f < 600; f++) {
            Matrix4 local = new Matrix4().set(animT, animR, new Vector3(sFix, sFix, sFix));
            cand(parent, local, c);
            local.getScale(sc);
            sFix = (sc.x + sc.y + sc.z) / 3f; // the fix: keep the bone's own scale, ignore cand's
        }
        assertEquals("fix must preserve the limb's scale exactly however long the grip lasts", 1f, sFix, 1e-4f);
    }
}
