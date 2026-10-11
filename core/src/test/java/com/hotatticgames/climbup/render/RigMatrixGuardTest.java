package com.hotatticgames.climbup.render;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import org.junit.Test;

/**
 * Regression guard for the rope/cable climb crash and the invisible-on-rope bug.
 *
 * <p>The desktop crash log reported {@code java.lang.RuntimeException: non-invertible matrix} from
 * {@code Matrix4.inv()} inside {@code HeroRig.rotateBone -> climbLegs}: while both hands grip the line the
 * arm/leg bone chain can collapse to a zero-determinant (zero-scale) local matrix. Inverting that parent
 * transform throws (the crash); decomposing it with {@code getRotation(normalizeAxes=true)} divides by a
 * zero-length axis and sprays NaN through the skeleton, which makes the skinned mesh vanish (the "invisible
 * on almost every rope" report). Both failures share one root: a degenerate bone matrix reaching the maths.
 *
 * <p>{@link HeroRig#safeInvert} is the guard that now stands in front of every {@code Matrix4.inv()} in the
 * rig. These tests construct the exact degenerate shapes and prove the guard refuses them without throwing,
 * while still inverting healthy transforms correctly. The helpers are static and touch no GL, so this runs
 * headlessly in the normal JUnit suite. (This test lives in the render package so it can reach the
 * package-private guard without widening its visibility.)
 */
public class RigMatrixGuardTest {

    /** A healthy bone transform (translate + rotate + uniform scale) inverts and round-trips to identity. */
    @Test
    public void healthyTransformInvertsAndRoundTrips() {
        Matrix4 m = new Matrix4().translate(1.3f, -2.1f, 0.7f)
                .rotate(new Quaternion().setFromAxis(Vector3.Y, 37f)).scale(0.9f, 0.9f, 0.9f);
        Matrix4 inv = new Matrix4();
        assertTrue("a healthy bone transform must invert", HeroRig.safeInvert(inv, m));

        Matrix4 round = new Matrix4(m).mul(inv);
        Matrix4 id = new Matrix4();
        for (int i = 0; i < 16; i++) {
            if (Math.abs(round.val[i] - id.val[i]) > 1e-4f) fail("m * inv(m) must be identity, element " + i);
        }
    }

    /**
     * The crash shape: a bone whose local matrix has a collapsed axis (zero scale) is singular. Raw
     * {@code Matrix4.inv()} throws exactly as the owner's log showed; {@link HeroRig#safeInvert} refuses it.
     */
    @Test
    public void collapsedBoneIsRefusedInsteadOfThrowing() {
        Matrix4 degenerate = new Matrix4().translate(0.4f, 1.1f, 0f).scale(1f, 1f, 0f);   // z axis collapsed

        boolean threw = false;
        try {
            new Matrix4(degenerate).inv();
        } catch (RuntimeException e) {
            threw = true;   // this is the original crash: "non-invertible matrix"
        }
        assertTrue("the degenerate transform must reproduce the raw inv() crash", threw);

        Matrix4 dst = new Matrix4();
        assertFalse("safeInvert must refuse the collapsed bone, not throw", HeroRig.safeInvert(dst, degenerate));
    }

    /** A NaN-poisoned transform (what a divide-by-zero decomposition produces) is refused and never inverted. */
    @Test
    public void nanTransformIsRefused() {
        Matrix4 poisoned = new Matrix4();
        poisoned.val[Matrix4.M00] = Float.NaN;
        assertFalse("non-finite element must be caught by finite()", HeroRig.finite(poisoned));
        assertFalse("safeInvert must refuse a NaN transform", HeroRig.safeInvert(new Matrix4(), poisoned));

        Matrix4 inf = new Matrix4();
        inf.val[Matrix4.M12] = Float.POSITIVE_INFINITY;
        assertFalse("safeInvert must refuse an infinite transform", HeroRig.safeInvert(new Matrix4(), inf));
    }

    /** A near-singular transform (determinant far below the tolerance) is treated as degenerate, not inverted into garbage. */
    @Test
    public void nearSingularTransformIsRefused() {
        Matrix4 tiny = new Matrix4().scale(1e-5f, 1e-5f, 1e-5f);   // det ~ 1e-15, well under the 1e-7 guard
        assertFalse("a near-singular transform must be refused", HeroRig.safeInvert(new Matrix4(), tiny));
    }
}
