package com.hotatticgames.climbup.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.loader.G3dModelLoader;
import com.badlogic.gdx.graphics.g3d.utils.AnimationController;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.graphics.g3d.model.NodePart;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.JsonReader;
import com.hotatticgames.climbup.sim.Sim;

/**
 * The player: Quaternius' "Character" (CC0), a skinned 3D model with a ready-made animation set (converted from glTF to g3dj by tools/gltf_to_g3dj.py).
 * Idle/Walk/Run/Jump/Jump_Idle/Jump_Land are played from the simulation state; hangs and ropes reuse the airborne pose.
 */
public final class HeroRig implements Disposable {
    private static final float HANG_YAW = Float.parseFloat(System.getProperty("climb.hangYaw", "84"));
    private final float SCALE;    // model units -> world units (bunny/hamster ~3.7 tall, astronauts ~2.85)

    private final Model model;
    private final ModelInstance inst;
    private final AnimationController ac;
    private final boolean ham;                       // the chibi hamster: same skeleton and clips as the bunny, textured instead of vertex-coloured
    /**
     * A hamster whose arms are the old stubs (the rig shipped up to Build 62: upper + lower arm under 0.7 bone units, the bunny's are 0.95): only that
     * rig takes the stretch, tuck, root-pivot and hamster grip-height paths below. Build 63 re-rigged the hamster onto the bunny's skeleton
     * (tools/hamster/retarget_to_bunny.py), so it poses exactly like the bunny and this is false; measured, not assumed, so either asset works.
     */
    private final boolean stubArms;
    private final boolean astro;                     // an astronaut (index >= 2): a much deeper, larger helmet than the bunny's head, so it sits back further off the rope
    // Ball hands: the astronauts' finger-gloves splay out and never curl (no finger articulation), and the hamster's paws
    // read messy. Replace each hand with a simple coloured ball, proportioned from the bunny, matching the hand's colour.
    private final boolean handBall;
    private float ballRad;                           // ball radius in world units (proportional to this character's size)
    private Model ballModel; private ModelInstance ballL, ballR;
    private final Matrix4 ballM = new Matrix4(); private final Vector3 ballP = new Vector3();
    private float ballFront;                          // this frame's forward (toward-camera) nudge for the hand balls: >0 on a vertical rope so they render in front of the line
    private float ballPullX = 1f;                      // this frame's sideways pull of the hand balls toward the rope line (1 = none); <1 draws the hamster's balls in onto the line while keeping them on the arm (no forward push)
    // How big each ball hand is (fraction scaled by the character's size). The astronauts have small hands on big bodies,
    // so their ball is smaller than a strict bunny-proportion; the hamster's covers its chunky wrist. Both tunable.
    private static final float BALL_RATIO = Float.parseFloat(System.getProperty("climb.handBall", "0.10"));
    private static final float BALL_ASTRO = Float.parseFloat(System.getProperty("climb.handBallAstro", "0.09"));
    // On a vertical rope the ball hands are nudged toward the camera (world +z) so they draw IN FRONT of the line, reading
    // as hands clasping the rope instead of the rope drawing over them. Cosmetic depth only: the fists, arms, reach, grip
    // and all mechanics are untouched; the game camera looks straight down -z, so this is genuine 3D in-front, not a trick.
    private static final float BALL_FRONT = Float.parseFloat(System.getProperty("climb.ballFront", "0.17"));
    // On a rope, how much of the hand's own sideways offset is kept (0 = both balls dead on the rope's centre line, stacked;
    // 1 = left where the fists are). A little keeps the two hands from fully overlapping while they still clasp the line.
    private static final float ROPE_BALL_X = Float.parseFloat(System.getProperty("climb.ropeBallX", "0.3"));
    private String current = "";
    public float yaw, landT;

    // squash & stretch (spring) driven by gameplay events fed from the play screen
    private float sq, sqV, landSpd; private int pendingEv;
    public void events(int ev, float landSpeed) {
        pendingEv |= ev; landSpd = landSpeed;
        if ((ev & Sim.EV_JUMP) != 0) sqV += 5f;
        if ((ev & Sim.EV_LAND) != 0) sqV -= 4f + Math.min(16f, landSpeed) * 0.45f;
        if ((ev & Sim.EV_BOUNCE) != 0) sqV += 9f;
        if ((ev & Sim.EV_GRAB) != 0) sqV -= 5f;
    }

    // ---- idle director: after a few still seconds he turns to the camera and does a bit (sometimes with a speech bubble)
    private static final class Beat { final String anim; final boolean toCamera; Beat(String a, boolean c) { anim = a; toCamera = c; } }
    private static final Beat[] BEATS = {new Beat("Wave", true), new Beat("No", true), new Beat("Yes", true), new Beat("Duck", false), new Beat("Punch", false), new Beat("No", true)};
    private static final String[] LINES = {"COME ON!", "HELLO? STILL THERE?", "PSST. YOU. THE BUTTON.", "I CAN DO THIS ALL DAY.", "WHAT ARE WE WAITING FOR?",
            "READY WHEN YOU ARE.", "NICE VIEW. NOW JUMP.", "ANY DAY NOW...", "I'M NOT GETTING ANY YOUNGER.", "YOU'RE DOING GREAT. (STANDING.)", "HI! YES, YOU."};
    private final java.util.Random rnd = new java.util.Random(7);   // cosmetic only; never touches the simulation
    private float idleT, beatT, beatDur, nextBeatAt = 3.5f, bubbleT;
    private Beat beat; private int lastBeat = -1, lastLine = -1; private String bubble;

    /** Current speech-bubble text, or null. */
    public String bubble() { return bubbleT > 0 ? bubble : null; }
    public float bubbleAlpha() { return Math.min(1f, Math.min(bubbleT * 4f, (beatDur - beatT) * 4f + 0.2f)); }

    private void startBeat() {
        int i; do { i = rnd.nextInt(BEATS.length); } while (i == lastBeat);
        lastBeat = i; beat = BEATS[i]; beatT = 0;
        com.badlogic.gdx.graphics.g3d.model.Animation a = model.getAnimation(beat.anim);
        beatDur = a != null ? a.duration : 2f;
        if (beat.toCamera && rnd.nextInt(10) < 7) {
            int l; do { l = rnd.nextInt(LINES.length); } while (l == lastLine);
            lastLine = l; bubble = LINES[l]; bubbleT = beatDur;
        }
    }

    // ---- hanging: both arms are posed in code to reach up and grip (the pack has no hang/climb clips)
    private Node upL, loL, fiL, upR, loR, fiR, ulL, ulR, llL, llR, ftL, ftR;
    private Vector3 footLocalL, footLocalR;           // where each foot sits inside its lower-leg bone (bind pose) - the Foot node is a static Root helper, so we track the foot mesh (skinned to the lower leg) this way
    private final Vector3 footEst = new Vector3();    // scratch: a foot's live world position, estimated from the lower leg + its bind offset
    private Node[] limbs = new Node[0];               // the arm and leg bones the animation/grip posing touches (filled by findNodes)
    private float hangT, lean;
    private final Vector3 pB = new Vector3(), pC = new Vector3(), dir = new Vector3(), tgt = new Vector3(), keepScale = new Vector3(), rcT = new Vector3();
    private final Quaternion arc = new Quaternion(), part = new Quaternion(), ident = new Quaternion(), rcR = new Quaternion();
    private final Matrix4 rot = new Matrix4(), wNew = new Matrix4(), parInv = new Matrix4(), cand = new Matrix4();
    private final java.util.IdentityHashMap<Node, Vector3> bindScale = new java.util.IdentityHashMap<>();   // each limb bone's rest scale, captured before any animation runs
    private final java.util.IdentityHashMap<Node, Vector3> bindT = new java.util.IdentityHashMap<>();       // each limb bone's rest translation (the clips' constant translation tracks repeat it every frame)
    /**
     * How far each arm bone's own mesh reaches BEHIND its joint along the bone (bone-local units; absent or 0 = nothing).
     * Measured from the skin weights at load, hamster only. Its stub arms are modelled as capsules around the joint, so the
     * rope stretch, which scaled them about the joint, pushed the inner half of each stub out through the shoulder: the pale
     * "hands" at both shoulders in the owner's Build 62 report. The stretch and the upper-arm aim pivot at this root instead,
     * so the arm only ever lengthens and swings toward the line. Empty when nothing could be measured: the joint stays the pivot.
     */
    private final java.util.IdentityHashMap<Node, Float> armRoot = new java.util.IdentityHashMap<>();
    private final Vector3 pivotV = new Vector3(), rootP = new Vector3();

    private void findNodes() {
        for (Node n : inst.nodes) collect(n);
        limbs = new Node[]{upL, loL, fiL, upR, loR, fiR, ulL, llL, ulR, llR};
    }

    /** Records each limb bone's rest scale while the skeleton is still in its bind pose (before any animation has run). */
    private void captureBind() {
        for (Node n : limbs) {
            if (n != null && !bindScale.containsKey(n)) { Vector3 v = new Vector3(); n.localTransform.getScale(v); bindScale.put(n, v); }
            if (n != null && !bindT.containsKey(n)) { Vector3 t = new Vector3(); n.localTransform.getTranslation(t); bindT.put(n, t); }
        }
        footLocalL = footInShin(llL, ftL); footLocalR = footInShin(llR, ftR);   // where the foot mesh sits inside the lower leg, captured in bind pose
    }

    /** Measures armRoot (see there) from the skinned mesh in its bind pose; any mesh layout this does not understand leaves it empty. */
    private void measureArmRoots() {
        if (upL == null || upR == null) return;
        Node[] arms = {upL, upR, loL, loR};
        try { for (Node n : inst.nodes) measureArmRoots(n, arms); } catch (RuntimeException e) { armRoot.clear(); }
    }

    private void measureArmRoots(Node node, Node[] arms) {
        for (NodePart np : node.parts) {
            if (np.invBoneBindTransforms == null || np.meshPart == null || np.meshPart.mesh == null) continue;
            Mesh mesh = np.meshPart.mesh;
            VertexAttributes attrs = mesh.getVertexAttributes();
            VertexAttribute pos = attrs.findByUsage(VertexAttributes.Usage.Position);
            if (pos == null) continue;
            int[] bw = new int[8]; int nbw = 0;                                   // the (bone index, weight) pairs of each vertex
            for (VertexAttribute a : attrs) if (a.usage == VertexAttributes.Usage.BoneWeight && nbw < bw.length) bw[nbw++] = a.offset / 4;
            if (nbw == 0) continue;
            int stride = mesh.getVertexSize() / 4, nv = mesh.getNumVertices(), po = pos.offset / 4, nb = np.invBoneBindTransforms.size;
            float[] v = new float[nv * stride]; mesh.getVertices(v);
            for (int i = 0; i < nv; i++) {
                int base = i * stride, best = -1; float bestW = 0f;
                for (int k = 0; k < nbw; k++) { int bi = (int) v[base + bw[k]]; float w = v[base + bw[k] + 1]; if (w > bestW && bi >= 0 && bi < nb) { bestW = w; best = bi; } }
                if (best < 0) continue;
                Node b = np.invBoneBindTransforms.keys[best];                 // the bone this vertex mostly follows
                boolean arm = false; for (Node a : arms) if (a == b) arm = true;
                if (!arm) continue;
                rootP.set(v[base + po], v[base + po + 1], v[base + po + 2]).mul(np.invBoneBindTransforms.values[best]);   // mesh (bind) -> this bone's own frame
                float behind = -rootP.y;                                      // the bone runs along its local +y (its child sits at (0, length, 0))
                Float cur = armRoot.get(b);
                if (cur == null || behind > cur) armRoot.put(b, behind);
            }
        }
        for (Node c : node.getChildren()) measureArmRoots(c, arms);
    }

    /** The pivot of this bone's stretch and aim: its mesh root behind the joint (the hamster's arms), else 0 = the joint itself. */
    private float rootDepth(Node n) { Float r = armRoot.get(n); return r == null || !(r > 0f) ? 0f : r; }
    /** Upper + lower arm length in bone units, from the rest pose (the bunny's is 0.95; the stub-armed hamster rig's was 0.45). */
    float armLength() { return upL == null || loL == null || fiL == null ? 0f : loL.translation.len() + fiL.translation.len(); }

    /** Test hook: the measured arm roots, "up L/R lo L/R" in bone units, or "" when the joint is the pivot (every character but the hamster). */
    public String armRoots() {
        if (armRoot.isEmpty() || upL == null) return "";
        return String.format(java.util.Locale.ROOT, "up %.2f/%.2f lo %.2f/%.2f", rootDepth(upL), rootDepth(upR), loL == null ? 0f : rootDepth(loL), loR == null ? 0f : rootDepth(loR));
    }

    /** The foot node's bind position expressed in its lower-leg bone's local space, so the foot mesh (skinned to the lower
     *  leg) can be located and aimed at runtime even though the Foot node is a static, Root-parented helper. Null if absent. */
    private Vector3 footInShin(Node shin, Node foot) {
        if (shin == null || foot == null) return null;
        Matrix4 inv = new Matrix4();
        if (!safeInvert(inv, shin.globalTransform)) return null;
        Vector3 fw = new Vector3(); foot.globalTransform.getTranslation(fw);
        return fw.mul(inv);                 // foot world (bind) -> lower-leg-local
    }

    /**
     * Supplies the scale "track" the animation clips lack, so the procedurally re-posed limbs never waste away.
     *
     * Root cause (proven by isolation - see GripProbe and climb.noPose/noBlend/noReclamp): the arm and leg bones are
     * re-posed every frame by the grip/climb/pull-up code (aim/rotateBone -> commitBone reads a bone's transform and
     * writes it back). The character clips carry rotation and translation tracks for these bones but NO scale track
     * (verified: 0 scale keyframes in hero.g3dj), so nothing ever re-asserts their rest scale. Any scale error introduced
     * into a bone therefore persists and feeds into the next frame's re-pose, compounding geometrically toward zero over
     * a run. The error comes from the re-pose's own matrix/quaternion round-trip (dominant: collapses even with blending
     * off) and, on top of it, AnimationController's cross-fade, which averages pose matrices with an element-wise lerp
     * that shrinks rotations (an amplifier). Without the re-posing neither matters - the clips' clean per-frame set keeps
     * restoring the bones. Resetting each limb bone to its captured bind scale (with a re-orthonormalised rotation) right
     * after ac.update, before anything reads it, is the missing scale reset and breaks the feedback loop. The real
     * animation is untouched (the clips never scale these bones); the hamster's deliberate stretch re-applies from here.
     */
    private void reclampLimbScales() {
        for (Node n : limbs) {
            if (n == null) continue;
            Vector3 bind = bindScale.get(n);
            if (bind == null) continue;
            n.localTransform.getTranslation(rcT); n.localTransform.getRotation(rcR, true);
            Vector3 bt = armRoot.containsKey(n) ? bindT.get(n) : null;      // a root-pivoted arm: the joint shift its pivot makes within a frame is never carried into the next (the clips' constant translation track says the same)
            n.localTransform.set(bt != null ? bt : rcT, rcR, bind);
            n.scale.set(bind);
        }
        inst.calculateTransforms();
    }
    private void collect(Node n) {
        String id = n.id;
        if (id.endsWith("_UpperArm.L")) upL = n; else if (id.endsWith("_LowerArm.L")) loL = n; else if (id.endsWith("_Fist.L") || id.endsWith("_Middle1.L")) fiL = n;
        else if (id.endsWith("_UpperLeg.L")) ulL = n; else if (id.endsWith("_UpperLeg.R")) ulR = n; else if (id.endsWith("_LowerLeg.L")) llL = n; else if (id.endsWith("_LowerLeg.R")) llR = n;
        else if (id.endsWith("_Foot.L")) ftL = n; else if (id.endsWith("_Foot.R")) ftR = n;
        else if (id.endsWith("_UpperArm.R")) upR = n; else if (id.endsWith("_LowerArm.R")) loR = n; else if (id.endsWith("_Fist.R") || id.endsWith("_Middle1.R")) fiR = n;
        for (Node c : n.getChildren()) collect(c);
    }

    /** True only if every element of the matrix is a finite number (no NaN, no infinity). */
    static boolean finite(Matrix4 m) {
        for (float v : m.val) if (Float.isNaN(v) || Float.isInfinite(v)) return false;
        return true;
    }

    /**
     * Inverts src into dst and returns true; returns false and leaves dst untouched when src is non-finite or
     * (near-)singular, so Matrix4.inv() - which throws "non-invertible matrix" on a zero determinant - is never
     * reached with a degenerate bone transform. Package-private and static so it is unit-testable without a GL context.
     */
    static boolean safeInvert(Matrix4 dst, Matrix4 src) {
        if (!finite(src) || !(Math.abs(src.det()) > 1e-7f)) return false;
        dst.set(src).inv();
        return finite(dst);
    }

    /** Sets parInv to the inverse of the bone's parent global transform (identity if root); false leaves the bone untouched. */
    private boolean parentInverse(Node bone) {
        Node par = bone.getParent();
        if (par == null) { parInv.idt(); return true; }
        return safeInvert(parInv, par.globalTransform);
    }

    /**
     * Commits the world matrix in wNew onto the bone through its parent. Everything is computed into a scratch
     * matrix first and the bone is only written if the result is finite and invertible: a zero-determinant local
     * matrix would make getRotation(normalizeAxes) divide by a zero-length axis and spray NaN through the skeleton
     * (the hero vanishes, then the next bone's inverse crashes). A rejected frame simply keeps the animation pose.
     */
    private void commitBone(Node bone) {
        if (!finite(wNew) || !parentInverse(bone)) return;
        cand.set(parInv).mul(wNew);
        if (!finite(cand) || !(Math.abs(cand.det()) > 1e-7f)) return;
        // Re-pose with ROTATION ONLY: keep the bone's own scale (its bind scale, or the hamster's deliberate stretch) instead
        // of adopting cand's, which carries scale/shear when the parent is non-uniformly scaled. Scale hygiene, not the limb-
        // shrink fix on its own (reclampLimbScales, run every frame before this, is what breaks the no-scale-track feedback loop).
        bone.localTransform.getScale(keepScale);
        cand.getTranslation(bone.translation); cand.getRotation(bone.rotation, true); bone.scale.set(keepScale);
        bone.localTransform.set(bone.translation, bone.rotation, bone.scale);
        inst.calculateTransforms();
    }

    /** Rotates bone about its own joint so that the bone->child direction points along target (blended by weight). */
    private void aim(Node bone, Node child, Vector3 target, float weight) {
        if (target.isZero(1e-5f)) return;
        bone.globalTransform.getTranslation(pB); child.globalTransform.getTranslation(pC);
        dir.set(pC).sub(pB);
        if (dir.isZero(1e-5f)) return;         // shoulder and hand coincide this frame: no direction to aim along, leave the pose
        dir.nor();
        arc.setFromCross(dir, target);
        part.set(ident).slerp(arc, weight);
        rot.setToTranslation(pB).rotate(part).translate(-pB.x, -pB.y, -pB.z);
        wNew.set(rot).mul(bone.globalTransform);
        commitBone(bone);
    }

    /** aim(), but rotating about the bone's mesh root (rootBehind along the bone behind the joint) instead of the joint: the root
     *  stays put and the joint swings with the arm. The root is a fixed point of the root-pivoted stretch, so reading it off the
     *  stretched global transform gives the same model-space point the stretch kept still. */
    private void aimAbout(Node bone, Node child, Vector3 target, float weight, float rootBehind) {
        if (target.isZero(1e-5f)) return;
        bone.globalTransform.getTranslation(pB); child.globalTransform.getTranslation(pC);
        dir.set(pC).sub(pB);
        if (dir.isZero(1e-5f)) return;
        dir.nor();
        arc.setFromCross(dir, target);
        part.set(ident).slerp(arc, weight);
        pivotV.set(0f, -rootBehind, 0f).mul(bone.globalTransform);
        rot.setToTranslation(pivotV).rotate(part).translate(-pivotV.x, -pivotV.y, -pivotV.z);
        wNew.set(rot).mul(bone.globalTransform);
        commitBone(bone);
    }

    /** Like aim(), but the child reference is an explicit world (model-space) position instead of a child node - used to aim
     *  the lower leg at the foot mesh, whose position we estimate (the Foot node is a static helper, not under the leg). */
    private void aimTo(Node bone, Vector3 childWorld, Vector3 target, float weight) {
        if (target.isZero(1e-5f)) return;
        bone.globalTransform.getTranslation(pB);
        dir.set(childWorld).sub(pB);
        if (dir.isZero(1e-5f)) return;
        dir.nor();
        arc.setFromCross(dir, target);
        part.set(ident).slerp(arc, weight);
        rot.setToTranslation(pB).rotate(part).translate(-pB.x, -pB.y, -pB.z);
        wNew.set(rot).mul(bone.globalTransform);
        commitBone(bone);
    }

    /** Rotates a bone about its own joint (model-space axis). */
    private void rotateBone(Node bone, float ax, float ay, float az, float rad) {
        bone.globalTransform.getTranslation(pB);
        part.setFromAxisRad(ax, ay, az, rad);
        rot.setToTranslation(pB).rotate(part).translate(-pB.x, -pB.y, -pB.z);
        wNew.set(rot).mul(bone.globalTransform);
        commitBone(bone);
    }

    /** Frantic leg kicks while dangling from a ledge; intensity grows the longer he hangs. */
    private void flail(float t) {
        if (ulL == null || ulR == null) return;
        float amp = 0.18f + Math.min(0.20f, hangT * 0.08f), w = 7f + Math.min(3f, hangT);       // a nervous dangle, not a seizure
        rotateBone(ulL, 1f, 0f, 0f, MathUtils.sin(t * w) * amp);
        rotateBone(ulR, 1f, 0f, 0f, MathUtils.sin(t * w + 3.1f) * amp);
        if (llL != null) rotateBone(llL, 1f, 0f, 0f, 0.30f + 0.18f * MathUtils.sin(t * w + 1.2f));
        if (llR != null) rotateBone(llR, 1f, 0f, 0f, 0.30f + 0.18f * MathUtils.sin(t * w + 4.3f));
    }

    private float slip;
    /** Sliding on a wet platform: arms thrown up and flailing, legs kicking (reduced motion: the same startled pose, held still). */
    private void slipFlail(float t, float k, boolean reduced) {
        if (upL == null) findNodes();
        if (upL == null || upR == null) return;
        raiseArms(k * 0.7f, 0.25f);
        if (!reduced) {
            float w = 13f;
            rotateBone(upL, 0f, 0f, 1f, MathUtils.sin(t * w) * 0.7f * k); rotateBone(upR, 0f, 0f, 1f, MathUtils.sin(t * w + 2.4f) * 0.7f * k);
            rotateBone(upL, 1f, 0f, 0f, MathUtils.sin(t * w * 0.8f + 1f) * 0.5f * k); rotateBone(upR, 1f, 0f, 0f, MathUtils.sin(t * w * 0.8f + 3.6f) * 0.5f * k);
            if (ulL != null && ulR != null) { rotateBone(ulL, 1f, 0f, 0f, MathUtils.sin(t * 11f) * 0.45f * k); rotateBone(ulR, 1f, 0f, 0f, MathUtils.sin(t * 11f + 3.1f) * 0.45f * k); }
        }
    }

    private void raiseArms(float weight, float forward) {
        if (upL == null) findNodes();
        if (upL == null || upR == null) return;
        if (DEBUG) { upL.globalTransform.getTranslation(pB); fiL.globalTransform.getTranslation(pC); System.out.printf("BEFORE upL=(%.2f,%.2f,%.2f) fistL=(%.2f,%.2f,%.2f)%n", pB.x, pB.y, pB.z, pC.x, pC.y, pC.z); }
        for (int side = 0; side < 2; side++) {
            Node up = side == 0 ? upL : upR, lo = side == 0 ? loL : loR, fi = side == 0 ? fiL : fiR;
            up.globalTransform.getTranslation(pB);
            float out = Math.signum(pB.x) * (stubArms ? -0.30f : 0.45f);   // the stub-armed hamster's shoulders are wide: its paws angle in to meet the rope
            tgt.set(out * (0.45f + 0.35f * (1f - forward)), 0.85f + 0.15f * (1f - forward), forward).nor();   // ledge: forward + up; rope/cable: straight up, a little apart
            aim(up, lo, tgt, weight);
            aim(lo, fi, tgt, weight);
        }
    }

    private static final boolean DEBUG = Boolean.getBoolean("climb.heroDebug");
    private int dbgN;

    private float handModelY() {
        if (DEBUG && dbgN++ < 3) { upL.globalTransform.getTranslation(pB); fiL.globalTransform.getTranslation(pC); System.out.printf("AFTER upL=(%.2f,%.2f,%.2f) fistL=(%.2f,%.2f,%.2f)%n", pB.x, pB.y, pB.z, pC.x, pC.y, pC.z); }
        fiL.globalTransform.getTranslation(pB); fiR.globalTransform.getTranslation(pC);
        return (pB.y + pC.y) * 0.5f;
    }

    public HeroRig() { this(0); }

    public HeroRig(int character) {
        character = Characters.clamp(character);
        ham = character == 1; astro = Characters.astronaut(character); SCALE = Characters.scale(character);
        model = new G3dModelLoader(new JsonReader()).loadModel(Gdx.files.internal(Characters.model(character)));
        for (com.badlogic.gdx.graphics.g3d.Material m : model.materials) m.set(ColorAttribute.createEmissive(0.10f, 0.10f, 0.12f, 1f));
        if (ham) for (com.badlogic.gdx.graphics.g3d.Material m : model.materials) {
            com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute ta = (com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute) m.get(com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute.Diffuse);
            if (ta != null) GfxHooks.sharpen(ta.textureDescription.texture);
        }
        inst = new ModelInstance(model);
        if (Characters.astronaut(character)) { Node gun = inst.getNode("n22_Pistol", true, true); if (gun != null) gun.parts.clear(); }
        findNodes(); captureBind();                 // record the limbs' rest scale now, while the skeleton is still in its bind pose
        stubArms = ham && armLength() < 0.7f;
        if (stubArms) measureArmRoots();            // where the hamster's stub arms root inside its shoulders (the stretch and grip pivot there)
        handBall = ham || astro;                    // the bunny keeps its own tidy paws; the astronauts and hamster get ball hands
        if (handBall) {
            ballRad = (astro ? BALL_ASTRO : BALL_RATIO) * SCALE / Characters.scale(0);   // astronauts smaller (small hands, big body); hamster covers its wrist
            // The astronauts' hands are near-black; the hamster's are its own yellowish-tan fur/foot colour (the same diffuse
            // the feet use, so under the game lights the paws read as that warm tan) - not the grey beige that blended in, nor
            // the darker brown that read too dark.
            Color hc = astro ? new Color(0.12f, 0.12f, 0.14f, 1f) : new Color(0.78f, 0.51f, 0.34f, 1f);
            Material bm = new Material(ColorAttribute.createDiffuse(hc), ColorAttribute.createEmissive(hc.r * 0.12f, hc.g * 0.12f, hc.b * 0.12f, 1f));
            ballModel = new ModelBuilder().createSphere(2f, 2f, 2f, 12, 12, bm, VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal);
            ballL = new ModelInstance(ballModel); ballR = new ModelInstance(ballModel);
        }
        ac = new AnimationController(inst);
        play("Idle", -1, 1f, 0f);
    }

    // Root-cause isolation switches (diagnostics only; all three off in production). NO_RECLAMP disables the fix,
    // NO_POSE disables all procedural grip/limb posing, NO_BLEND removes animation cross-fades (so clips are set, not lerped).
    private static final boolean NO_RECLAMP = Boolean.getBoolean("climb.noReclamp");
    private static final boolean NO_POSE = Boolean.getBoolean("climb.noPose");
    private static final boolean NO_BLEND = Boolean.getBoolean("climb.noBlend");

    private void play(String id, int loops, float speed, float blend) {
        if (id.equals(current)) { if (ac.current != null) ac.current.speed = speed; return; }
        current = id;
        ac.animate(id, loops, speed, null, NO_BLEND ? 0f : blend);
    }

    private static float smooth(float cur, float target, float rate, float dt) { return cur + (target - cur) * Math.min(1f, rate * dt); }

    /** What the player is doing, as the animation system sees it. Both characters implement every state. */
    public enum Anim { IDLE, RUN, JUMP, BIG_JUMP, FALL, LAND, HANG, CLIMB, SHIMMY, PULLUP, HIT }
    public Anim anim = Anim.IDLE;
    private float hitT, hitDir, bigT, fallT, climbPhase; private boolean hitFresh;
    private static final float HIT_TIME = 0.62f, BIG_TIME = 0.95f;

    /** Test hook (-Dclimb.animCycle=true): cycles through every animation state, 1.5 s each, for contact-sheet screenshots. */
    private static final boolean CYCLE = Boolean.getBoolean("climb.animCycle");
    private float cycleClock;
    private float climbRefY, climbRefS; private boolean climbRefSet;   // the hero's own position last frame: the climb/shimmy cycle advances by real travel per render FRAME, not per 60 Hz sim step, so it looks identical at any refresh rate

    /** Distance travelled since the last frame, for advancing the climb/shimmy cycle by real movement rather than frame count. Returns 0 for an implausibly large jump (a floating-origin rebase or a teleport) so the cycle never lurches, and is frame-rate independent: the same travel advances the cycle the same amount at 60 or 300 fps. */
    static float frameAdvance(float delta) { float d = Math.abs(delta); return d > 1.5f ? 0f : d; }

    private Anim pick(Sim sim, float speed) {
        if (CYCLE) {
            Anim[] all = Anim.values(); int i = (int) (cycleClock / 1.5f) % all.length; float f = (cycleClock % 1.5f) / 1.5f;
            if (all[i] == Anim.HIT) { hitT = HIT_TIME * (1f - f * 0.9f); if (hitDir == 0f) hitDir = 1f; } else hitT = 0f;
            if (all[i] == Anim.BIG_JUMP) bigT = BIG_TIME * (1f - f * 0.9f); else bigT = 0f;
            return all[i];
        }
        if (hitT > 0) return Anim.HIT;
        switch (sim.mode) {
            case GROUND: return landT > 0 && speed < 0.5f ? Anim.LAND : (speed < 0.4f ? Anim.IDLE : Anim.RUN);
            case AIR: if (bigT > 0) return Anim.BIG_JUMP; if (sim.vy > 1f) return Anim.JUMP; return sim.vy < -3f || fallT > 0.2f ? Anim.FALL : Anim.JUMP;
            case ROPE: return Math.abs(sim.y - sim.py0) > 0.002f ? Anim.CLIMB : Anim.HANG;
            case CABLE: return Math.abs(sim.course.dsWrap(sim.s, sim.ps0)) > 0.002f ? Anim.SHIMMY : Anim.HANG;
            case LEDGE: return Anim.HANG;
            case BEAM: return sim.mounting() ? Anim.PULLUP : (speed < 0.4f ? Anim.IDLE : Anim.RUN);
            default: return Anim.PULLUP;
        }
    }

    public void update(Sim sim, float dt, float time, float wx, float wy, float wz, float phi, boolean reduced) {
        float speed = Math.abs(sim.vx);
        float targetYaw;
        switch (sim.mode) {
            case ROPE: targetYaw = sim.facing * 6f; break;
            case LEDGE: case PULLUP: targetYaw = sim.ledgeSide * HANG_YAW; break;
            case BEAM: targetYaw = sim.mounting() ? sim.facing * 22f : sim.facing * 68f; break;   // turns to face the ledge he is gripping
            case CABLE: targetYaw = sim.facing * 24f; break;
            default: targetYaw = sim.facing * 68f;   // clearly faces the direction of travel
        }
        if ((pendingEv & (Sim.EV_SHOVE | Sim.EV_HIT)) != 0) { hitT = HIT_TIME; hitFresh = true; current = ""; }
        if ((pendingEv & Sim.EV_BOUNCE) != 0) bigT = BIG_TIME;
        if ((pendingEv & Sim.EV_LAND) != 0) { landT = 0.30f; bigT = 0f; }
        pendingEv = 0;
        if (hitFresh) { hitDir = Math.signum(sim.vx); hitFresh = false; }
        if (hitT > 0) hitT -= dt;
        if (bigT > 0) bigT -= dt;
        fallT = sim.mode == Sim.Mode.AIR && sim.vy < -4f ? fallT + dt : 0f;
        sqV += (-140f * sq - 13f * sqV) * dt; sq = Math.max(-0.45f, Math.min(0.40f, sq + sqV * dt));
        if (landT > 0) landT -= dt;
        cycleClock += dt;
        anim = pick(sim, speed);
        if (!climbRefSet) { climbRefY = sim.y; climbRefS = sim.s; climbRefSet = true; }
        float climbTravel = frameAdvance(sim.y - climbRefY), shimmyTravel = frameAdvance(sim.course.dsWrap(sim.s, climbRefS));
        climbRefY = sim.y; climbRefS = sim.s;
        if (CYCLE && (anim == Anim.CLIMB || anim == Anim.SHIMMY)) climbPhase += dt * 2.8f;
        else if (anim == Anim.CLIMB) climbPhase += climbTravel * 2.2f;            // inchworm pull up the rope, advanced by the distance actually climbed THIS FRAME (the old delta was measured per 60 Hz sim step but added every render frame, so it ran ~refresh/60 too fast on a 144 Hz+ display)
        else if (anim == Anim.SHIMMY) climbPhase += shimmyTravel * 1.9f;          // likewise along the cable
        lineMove += (((anim == Anim.CLIMB || anim == Anim.SHIMMY) ? 1f : 0f) - lineMove) * Math.min(1f, 14f * dt);      // eases the hand-over-hand in and out (the pose never snaps between hanging and moving)

        boolean still = sim.mode == Sim.Mode.GROUND && speed < 0.4f && landT <= 0 && hitT <= 0 && !sim.won;
        if (still) {
            idleT += dt;
            if (beat != null) { beatT += dt; if (bubbleT > 0) bubbleT -= dt; if (beatT >= beatDur) { beat = null; bubbleT = 0; nextBeatAt = idleT + 3f + rnd.nextFloat() * 3.5f; } }
            else if (idleT >= nextBeatAt) startBeat();
            if (beat != null && beat.toCamera) targetYaw = 0f;       // breaks the fourth wall: faces the player
        } else { idleT = 0; beat = null; bubbleT = 0; nextBeatAt = 3.5f; }
        boolean slipping = sim.mode == Sim.Mode.GROUND && sim.onElem >= 0 && sim.course.get(sim.onElem).wet() && speed > 0.8f;
        slip = smooth(slip, slipping ? 1f : 0f, 10f, dt);
        if (slipping) targetYaw = 0f;            // he looks straight at the screen: "uh-oh"
        yaw = smooth(yaw, targetYaw, 18f, dt);
        switch (anim) {
            case HIT: play("HitReact", 1, 1.15f, 0.04f); break;
            case LAND: play("Jump_Land", 1, 1.5f, 0.05f); break;
            case IDLE: if (beat != null) play(beat.anim, 1, 1f, 0.2f); else play("Idle", -1, 1f, 0.15f); break;
            case RUN: if (speed < 3.8f) play("Walk", -1, Math.max(0.6f, speed / 2.6f), 0.12f); else play("Run", -1, Math.max(0.8f, speed / 5.2f), 0.10f); break;
            case JUMP: play("Jump", 1, 1.2f, 0.05f); break;
            case BIG_JUMP: play("Jump", 1, 1.7f, 0.05f); break;
            case FALL: play("Jump_Idle", -1, 1.35f, 0.10f); break;
            case HANG: case CLIMB: case SHIMMY: play("Jump_Idle", -1, 1f, 0.12f); break;
            default: play("Jump", 1, 1.2f, 0.05f);
        }
        if (slipping && anim == Anim.RUN) play("Jump_Idle", -1, 1.7f, 0.08f);          // arms and legs windmilling, trying to stop
        if (sim.won) play("Wave", -1, 1f, 0.2f);

        if (sim.mode == Sim.Mode.BEAM && sim.mounting()) {      // hauling over the beam: starts behind it (like on the rope), ends standing on top of it
            float k = MathUtils.clamp((sim.pullT / Sim.BEAM_MOUNT_TIME - 0.45f) / 0.55f, 0f, 1f);
            wz -= ROPE_FRONT * (1f - k * k * (3f - 2f * k));
        }
        float inch = anim == Anim.CLIMB ? INCH * MathUtils.sin(climbPhase * 2f) : 0f;          // inchworm: the whole body compresses, then stretches, with every pull up the rope
        float hinch = anim == Anim.SHIMMY && !reduced ? 1.6f * INCH * MathUtils.sin(climbPhase * 2f) * MathUtils.clamp(lineMove, 0f, 1f) : 0f;      // the cable's inchworm, sideways: the body stretches along the line with each reach, then draws together
        float sxz = SCALE * (1f - 0.5f * sq - 0.5f * inch), sy = SCALE * (1f + sq + inch);
        float sx = sxz * (1f + hinch), sz = sxz * (1f - 0.35f * hinch); sy *= 1f - 0.45f * hinch;
        float flip = anim == Anim.BIG_JUMP ? -sim.facing * 360f * (1f - bigT / BIG_TIME) : 0f;                   // somersault on a big bounce
        float knock = anim == Anim.HIT ? hitDir * 26f * Math.max(0f, hitT / HIT_TIME) : 0f;                       // thrown back
        float leanT = 0f;           // lean into the run, tuck up while rising, reach forward while falling
        if (!reduced) {
            if (sim.mode == Sim.Mode.GROUND) leanT = anim == Anim.RUN ? MathUtils.clamp(speed / 5.6f, 0f, 1f) * 9f : 0f;
            else if (sim.mode == Sim.Mode.AIR) leanT = sim.vy > 1f ? -5f : MathUtils.clamp(-sim.vy / 12f, 0f, 1f) * 8f;
        }
        lean = smooth(lean, leanT, 9f, dt);
        inst.transform.idt().translate(wx, wy + 0.6f, wz).rotate(0, 0, 1, flip + knock).translate(0, -0.6f, 0)
                .rotate(0, 1, 0, phi * MathUtils.radiansToDegrees + yaw).translate(0, 0.5f, 0).rotate(1, 0, 0, lean).translate(0, -0.5f, 0).scale(sx, sy, sz);
        ac.update(reduced ? Math.min(dt, 1f / 30f) : dt);
        if (!NO_RECLAMP) reclampLimbScales();     // undo AnimationController's blend-lerp scale shrink before anything reads these bones (the stretch/grip poses below re-apply from the clean bind scale, never accumulating)
        if (slip > 0.05f && !NO_POSE) slipFlail(time, slip, reduced);
        // gripping poses: both arms up on the ledge / rope / cable, hands snapped to the sim's grip point
        hangT = sim.mode == Sim.Mode.LEDGE ? hangT + dt : 0f;
        hangSway += dt * 2.4f;
        float grip = 0f; ballFront = 0f; ballPullX = 1f; ballLift = 0f;
        if (anim == Anim.HANG || anim == Anim.CLIMB || anim == Anim.SHIMMY) grip = 1f;
        else if (anim == Anim.PULLUP) grip = CYCLE ? 0.8f : Math.max(0f, 1f - sim.pullT / (sim.mode == Sim.Mode.BEAM ? Sim.BEAM_MOUNT_TIME : sim.T.pullUpTime) * 1.25f);
        if (grip > 0.01f && !NO_POSE) {
            boolean line = anim == Anim.CLIMB || anim == Anim.SHIMMY || (anim == Anim.HANG && (sim.mode == Sim.Mode.ROPE || sim.mode == Sim.Mode.CABLE));
            boolean cable = anim == Anim.SHIMMY || sim.mode == Sim.Mode.CABLE;
            if (stubArms) stretchArmsBy(grip, HAM_WIDEN, cable ? HAM_CABLE_REACH : HAM_REACH);   // on the overhead cable the stub-armed hamster stretches further, so it hangs with its head below the line (not above it)
            else if (astro && line && !cable) stretchArmsBy(grip, 0.08f, ASTRO_STRETCH);   // lengthen the astronauts' arms so the hands reach the rope that sits forward of the deep helmet
            else if (!astro && line && cable) stretchArmsBy(grip, BUN_STRETCH, BUN_STRETCH);   // bunny: a UNIFORM stretch (X==Y==Z, or the fist counter-scale distorts its real paws) so its short arms reach up and grip the overhead cable instead of hanging below it with no visible arms
            if (line) {                // rope or cable: both hands on the line, alternating hand over hand while moving; knees draw up alternately
                float moving = MathUtils.clamp(lineMove, 0f, 1f);
                gripLine(climbPhase, moving, cable);
                if (!cable && handBall) ballFront = BALL_FRONT * grip;   // vertical rope: clasp the ball hands in front of the line (pushed toward the camera)
                if (!cable && (astro || stubArms)) {   // vertical rope: close the feet on the line (astro + the stub-armed hamster; the re-rigged hamster climbs like the bunny)
                    feetToLine(climbPhase, moving);
                    if (astro) ballPullX = ROPE_BALL_X;                 // astronauts: draw the ball in onto the rope's line (their fist sits there). The hamster's own fist already lands beside the line, so its ball stays on its paw (ballPullX stays 1) and only pushes forward - pulling it in was what detached it from the arm.
                }
                else climbLegs(climbPhase, moving);                                                               // the overhead cable, and the bunny on a rope, keep the dangling knee draw-up
            }
            else raiseArms(grip, (sim.mode == Sim.Mode.LEDGE || sim.mode == Sim.Mode.PULLUP || (CYCLE && anim == Anim.HANG)) ? 1.0f : 0.12f);
            if ((sim.mode == Sim.Mode.LEDGE || (CYCLE && anim == Anim.HANG)) && !reduced) flail(time);
            restoreFistScale();                                                   // the arms are posed: give the paws their natural size back (no-op unless stretchArmsBy ran this frame)
            if (fiL != null && fiR != null) {
                float handWorld = wy + handModelY() * sy;
                float shift = (sim.y + sim.T.handHeight) - handWorld;
                if (stubArms && cable) { shift -= HAM_CABLE_DROP * grip; ballLift = HAM_CABLE_DROP * grip; }   // hang lower under the cable; the balls stay on the line
                float tremble = sim.mode == Sim.Mode.LEDGE && !reduced ? MathUtils.sin(time * 22f) * (0.4f + Math.min(0.8f, hangT * 0.3f)) : 0f;
                float behind = line ? (cable ? ROPE_FRONT : lineBack()) * grip : 0f;   // the vertical rope sits in front of the face (bigger for the astronauts' helmets); the overhead cable keeps its tuned offset
                // cable shimmy: the whole body swings side to side under the hands with each hand-over-hand, like a playful monkey swing (not with reduced motion)
                boolean shimmy = anim == Anim.SHIMMY && !reduced;
                float roll = shimmy ? 11f * MathUtils.clamp(lineMove, 0f, 1f) * MathUtils.sin(climbPhase + 0.6f) : 0f, pivot = handModelY() * sy;
                inst.transform.idt().translate(wx, wy + shift * grip, wz - behind).rotate(0, 1, 0, phi * MathUtils.radiansToDegrees + yaw)
                        .translate(0, pivot, 0).rotate(0, 0, 1, roll).translate(0, -pivot, 0).rotate(0, 0, 1, tremble).scale(sx, sy, sz);
            }
        }
        else if (anim == Anim.IDLE && beat == null && !NO_POSE) armsRest();     // plain standing: tuck the arms in from the clip's slight A-pose so he doesn't read as T-posing
        else if (astro && anim == Anim.FALL && !NO_POSE) armsFall();            // astronauts: the falling clip raised an arm in front of the visor; spread the arms out and down instead
        placeHandBalls();                                                        // sit a ball on each hand (same world spot the hand renders at)
    }

    // Where along the forearm->fist line the ball sits (1 = at the fist, lower = back toward the wrist). The hand mesh is
    // stripped at the wrist, so the ball must sit over that opening, not out at the fist bone (which floats past it).
    private static final float BALL_AT = Float.parseFloat(System.getProperty("climb.ballAt", "0.6"));
    private final Vector3 ballP2 = new Vector3();
    /** Position each hand ball over the stripped wrist (blended between the lower-arm and fist bones, as the arm renders). */
    private void placeHandBalls() {
        if (!handBall || ballL == null || fiL == null || fiR == null) return;
        placeBall(ballL, loL, fiL); placeBall(ballR, loR, fiR);
    }
    private void placeBall(ModelInstance ball, Node lower, Node fist) {
        ballM.set(inst.transform).mul(fist.globalTransform); ballM.getTranslation(ballP);   // the hand (fist) world position
        if (ballFront > 0f) {                   // on a vertical rope: the astronauts' ball draws onto the rope's line (ballPullX=ROPE_BALL_X, their fist is there); the hamster keeps its fist x (ballPullX=1) so the ball sits on its own paw, in front of the line
            ballP.x *= ballPullX;
            ballP.z += ballFront;               // and toward the camera, so it renders in front of the line
        } else if (lower != null) {             // everywhere else: sit the ball over the stripped wrist, blended back toward the forearm
            ballM.set(inst.transform).mul(lower.globalTransform); ballM.getTranslation(ballP2); ballP.lerp(ballP2, 1f - BALL_AT);
        }
        ballP.y += ballLift;                        // the hamster on the cable: the ball sits on the line while the body hangs lower
        ball.transform.idt().translate(ballP).scale(ballRad, ballRad, ballRad);
    }

    private static final float ARMS_REST = Float.parseFloat(System.getProperty("climb.armsRest", "0.80"));
    /**
     * Standing idle only: both skeletons' bare Idle clips rest the arms out in an A/T-pose (the bunny-skeleton hamster
     * worst of all). AIM each arm straight down (slightly out and forward) by a blend weight, so it ends hanging at the
     * side regardless of where the clip left it - a fixed rotation can't do that because the two skeletons start the arm
     * at different angles. Not applied during run (the clip swings them) or an idle "beat" (Wave/Punch pose the arms).
     */
    private void armsRest() {
        if (upL == null) findNodes();
        if (upL == null || upR == null) return;
        for (int side = 0; side < 2; side++) {
            Node up = side == 0 ? upL : upR, lo = side == 0 ? loL : loR, fi = side == 0 ? fiL : fiR;
            up.globalTransform.getTranslation(pB);
            float out = (pB.x >= 0f ? 1f : -1f) * 0.16f;
            tgt.set(out, -1f, 0.10f).nor();                 // down, a touch out and forward: arms rest at the sides
            aim(up, lo, tgt, ARMS_REST);
            if (lo != null && fi != null) aim(lo, fi, tgt, ARMS_REST);
        }
    }

    private static final float ASTRO_FALL_ARMS = Float.parseFloat(System.getProperty("climb.astroFallArms", "0.85"));
    /**
     * Astronauts falling: their Jump_Idle clip swings one arm up across the face (Barbara 24 %, Rae 11 % of the camera-facing head
     * behind a ball in the harness). Aim both arms out to the sides and down, a little forward - a skydiver's spread - by a blend
     * weight, so the visor stays clear whatever the clip does; the legs keep the clip. Bunny and hamster are untouched.
     */
    private void armsFall() {
        if (upL == null) findNodes();
        if (upL == null || upR == null) return;
        for (int side = 0; side < 2; side++) {
            Node up = side == 0 ? upL : upR, lo = side == 0 ? loL : loR, fi = side == 0 ? fiL : fiR;
            up.globalTransform.getTranslation(pB);
            float out = (pB.x >= 0f ? 1f : -1f) * 0.75f;
            tgt.set(out, -0.60f, 0.15f).nor();
            aim(up, lo, tgt, ASTRO_FALL_ARMS);
            if (lo != null && fi != null) aim(lo, fi, tgt, ASTRO_FALL_ARMS);
        }
    }

    /** The hamster's arms are tiny stubs: while gripping they stretch (cartoon rubber arms) so the paws can reach the rope or ledge above the head. */
    private void stretchArms(float grip) { stretchArmsBy(grip, HAM_WIDEN, HAM_REACH); }
    /** How much the hamster's arms thicken while they stretch (0 = natural thickness, cartoon rubber arms above). */
    private static final float HAM_WIDEN = Float.parseFloat(System.getProperty("climb.hamWiden", "0.7"));
    /**
     * How far (model units) each of the hamster's shoulder joints is drawn in toward the chest while it grips. Its upper-arm joints sit at
     * the very edge of its wide body, so an arm turned toward a line in front still showed its root as a pale stub outside either shoulder
     * (the owner's Build 62 report) whatever the stretch did; moved in, the root turns and lengthens inside the body. The clips' constant
     * translation tracks (and reclampLimbScales) put the joint back every frame.
     */
    private static final float HAM_TUCK = Float.parseFloat(System.getProperty("climb.hamTuck", "0.30"));
    /**
     * Lengthen (k) and widen (w) the arm bones so the hands reach a line that sits forward of the body - the hamster's big
     * rubber stretch, and a small astronaut stretch so their normal-length arms can still reach the rope that sits well
     * forward of their deep helmets. The fists are counter-scaled so they keep their natural size (the reach is arm length,
     * not hand size): each fist hangs below BOTH the upper and the lower arm, so it inherits the stretch twice over.
     */
    private void stretchArmsBy(float grip, float w, float lengthen) {
        if (upL == null) findNodes();
        if (upL == null || upR == null) return;
        // the paws' natural on-screen size, read before the arms are touched (the bones were re-clamped to bind this frame): restoreFistScale() puts it back once the arms are posed
        if (fiL != null) fiL.globalTransform.getScale(fistNatL);
        if (fiR != null) fiR.globalTransform.getScale(fistNatR);
        fistStretched = fiL != null || fiR != null;
        if (stubArms && HAM_TUCK > 0f) {            // tuck the shoulder joints in toward the chest first: the arm roots then turn and stretch inside the body instead of poking out of its sides (Build 63)
            for (Node up : new Node[]{upL, upR}) {
                up.globalTransform.getTranslation(pB);
                wNew.setToTranslation(-Math.signum(pB.x) * HAM_TUCK * grip, 0f, 0f).mul(up.globalTransform);
                commitBone(up);
            }
        }
        float a = 1f + w * grip, k = 1f + lengthen * grip;
        for (Node n : new Node[]{upL, upR, loL, loR}) if (n != null) {
            float r = rootDepth(n);
            if (r > 0f) n.localTransform.translate(0f, -r, 0f).scale(a, k, a).translate(0f, r, 0f);   // about the mesh root: the stub's inner end stays inside the shoulder and the arm lengthens toward the paw only (hamster)
            else n.localTransform.scale(a, k, a);
        }
        inst.calculateTransforms();
    }
    private final Vector3 fistNatL = new Vector3(), fistNatR = new Vector3(), fistT = new Vector3();
    private final Quaternion fistQ = new Quaternion();
    private boolean fistStretched;
    /**
     * Gives each fist back the on-screen size it had before stretchArmsBy, once the arms are aimed. The old fixed counter-scale
     * (1/k^2, the stretch inherited from both arm bones) was only exact for a straight arm: the elbow bend that aim() puts
     * between two non-uniformly scaled bones shears the chain, so the fist's real size depended on the pose (the hamster's
     * paws read up to 2.05x at some rope-climb phases, the astronauts' 1.42x). Here the fist's world frame is rebuilt from
     * its sheared one - same position, the orientation its axes point in, and its natural scale - and written back through
     * the parent, so the fist is exactly its natural size in every pose. Its position (what the ball hands and the hand
     * height use) is unchanged; the sheared local matrix is discarded by reclampLimbScales next frame like every limb pose.
     */
    private void restoreFistScale() {
        if (!fistStretched) return;
        fistStretched = false;
        for (int side = 0; side < 2; side++) {
            Node n = side == 0 ? fiL : fiR; Vector3 nat = side == 0 ? fistNatL : fistNatR;
            if (n == null || !finite(n.globalTransform)) continue;
            n.globalTransform.getTranslation(fistT); n.globalTransform.getRotation(fistQ, true);
            wNew.set(fistT, fistQ, nat);
            if (!finite(wNew) || !parentInverse(n)) continue;
            cand.set(parInv).mul(wNew);
            if (!finite(cand) || !(Math.abs(cand.det()) > 1e-7f)) continue;
            n.localTransform.set(cand);
        }
        inst.calculateTransforms();
    }
    private static final float HAM_REACH = Float.parseFloat(System.getProperty("climb.hamReach", "1.5"));
    // The hamster's rope reach (1.5) only lifts its hands to the top of its own head, so on the overhead cable the line ran through
    // its ears. A longer cable-only stretch hangs the body with the head under the line like the other characters (derived with
    // the GameplayShot cable captures, docs/VERIFICATION.md build 60); the rope reach, tuned for the face-level grip, is unchanged.
    private static final float HAM_CABLE_REACH = Float.parseFloat(System.getProperty("climb.hamCableReach", "4.2"));
    // Even fully stretched, the hamster's fists only reach 0.6 world units above its upper-arm joints (the non-uniform stretch shears as the
    // forearm aims), which is barely its own head height: so on the cable the body is hung this much lower than the fists dictate and the
    // ball hands are drawn up at the line instead (placeHandBalls), leaving the head clear of the cable. The arm ends sit inside the balls.
    private static final float HAM_CABLE_DROP = Float.parseFloat(System.getProperty("climb.hamCableDrop", "0.3"));
    private float ballLift;        // world units the ball hands are drawn above the fists this frame (the hamster on the cable)
    // The bunny grips the vertical rope fine with its natural arms, but its short arms don't reach up to the overhead cable, so
    // on the cable it hangs below with no visible arms. A uniform stretch (widen == lengthen) lengthens the reach without the
    // non-uniform fist counter-scale that would distort its REAL paws (the hamster/astronauts avoid that via ball hands).
    private static final float BUN_STRETCH = Float.parseFloat(System.getProperty("climb.bunStretch", "0.4"));
    // A small lengthening of the astronauts' arms while on a rope so the hands reach the line, which has to sit well forward
    // of their deep helmets (the body is pushed back by ASTRO_BACK to clear the visor; a normal-length arm falls short of it).
    private static final float ASTRO_STRETCH = Float.parseFloat(System.getProperty("climb.astroStretch", "0.35"));

    private final Vector3 pF = new Vector3();
    /** While hanging on a rope or cable the hero is drawn this far behind the line (world units) so his arms visibly reach forward and grip it. */
    private static final float ROPE_FRONT = 0.30f;
    /**
     * How far the body sits BEHIND the rope/cable so the LINE renders in front of the chest and face instead of spearing
     * the head. 0.30 was tuned to the bunny's small head; the astronauts' helmets are far deeper and drawn larger, so the
     * old offset left the rope punching through the visor. The hero reaches forward onto the line (gripLine) from here.
     */
    private static final float ASTRO_BACK = Float.parseFloat(System.getProperty("climb.astroBack", "0.82"));
    private static final float BUN_BACK   = Float.parseFloat(System.getProperty("climb.bunBack", "0.37"));
    // Grip heights (fraction of arm length above the upper-arm joint): the two hands sit a little above/below each other around
    // chest-to-face height and alternate while climbing. Kept low on purpose so the head stays up and camera-facing.
    private static final float GRIP_HI = Float.parseFloat(System.getProperty("climb.gripHi", "0.26"));
    private static final float GRIP_LO = Float.parseFloat(System.getProperty("climb.gripLo", "0.02"));
    // The astronauts grip below the shoulder line: their helmet's front bottom edge sits only ~0.05 world units above the
    // upper-arm joint, so a hand at the bunny's heights (and its ball, r 0.12, pushed 0.17 toward the camera) draws over the
    // visor. Derived from the measured helmet/joint geometry (docs/CHARACTER_REPAIR_PLAN.md, defect 3), same 0.24 alternation.
    private static final float ASTRO_GRIP_HI = Float.parseFloat(System.getProperty("climb.astroGripHi", "-0.20"));
    private static final float ASTRO_GRIP_LO = Float.parseFloat(System.getProperty("climb.astroGripLo", "-0.44"));
    // The hamster's hands at the bunny's heights sat level with its big chibi head (ball centre within 0.1 world units of the head
    // centre), so the upper ball brushed the lower muzzle in one climb phase. Its own heights put both balls below the muzzle at
    // chest height (same 0.24 alternation); derived with the GameplayShot FACE metric (docs/VERIFICATION.md, build 60).
    private static final float HAM_GRIP_HI = Float.parseFloat(System.getProperty("climb.hamGripHi", "-0.10"));
    private static final float HAM_GRIP_LO = Float.parseFloat(System.getProperty("climb.hamGripLo", "-0.34"));
    private float lineBack() { return astro ? ASTRO_BACK : BUN_BACK; }
    private static final float INCH = 0.15f;

    /** Both hands go to the rope (or along the cable): each arm is aimed from its shoulder at a point on the line, one hand reaching high while the other pulls down, swapping with the climb. */
    private float lineMove;         // 0 = hanging still, 1 = hand over hand
    private void gripLine(float phase, float moving, boolean cable) {
        if (upL == null) findNodes();
        if (upL == null || upR == null || fiL == null || fiR == null) return;
        float m = MathUtils.clamp(moving, 0f, 1f);
        for (int side = 0; side < 2; side++) {
            Node up = side == 0 ? upL : upR, lo = side == 0 ? loL : loR, fi = side == 0 ? fiL : fiR;
            up.globalTransform.getTranslation(pB); lo.globalTransform.getTranslation(pC); fi.globalTransform.getTranslation(pF);
            float len = pB.dst(pC) + pC.dst(pF);                                  // shoulder -> hand reach, in world units
            float lead = 0.5f + 0.5f * MathUtils.sin(phase + side * MathUtils.PI);   // 0..1, the two hands out of phase: hand over hand
            float side0 = pB.x == 0f ? (side == 0 ? -1f : 1f) : Math.signum(pB.x);   // which side this shoulder is on (left = -1)
            float tx, ty, tz;
            if (cable) {
                // a horizontal bar overhead: BOTH hands grip the bar spread apart along it (monkey-bar style), at the same
                // bar height, the leading hand reaching a little further along the traverse. (Was: hands clustered together
                // above the head because the spread was tiny and both reached to nearly the same point.)
                float spread = len * (0.40f + 0.16f * lead * m);
                tx = side0 * spread;                                   // each hand out to its own side, onto the bar
                ty = pB.y + len * 0.82f;                               // up at the bar (nearly full overhead reach)
                tz = pB.z + len * 0.40f;                               // reach forward to the line (he hangs a little behind it)
            } else {
                // a vertical rope straight in front of him at world (x=0, z=0); the body sits back off it by lineBack() world
                // units, which in bone space (before the instance scale) is lineBack()/SCALE ahead of the body's origin. Each
                // hand is aimed at the rope's own position so it lands ON the line whatever the arm's length - one high, one
                // low, alternating as he inches up. (Was: a target 1.3 bone units ahead of the shoulder, a reach unrelated to
                // the push-back; the aim then ran mostly forward and the short-armed hamster's higher hand landed 0.18 world
                // units beside the line. Earlier still: tx=-side0*len*0.05 crossed the hands past centre.)
                tx = 0f;                                               // onto the rope's lateral line (he is centred under it)
                // grip around chest height (NOT reaching overhead): reaching up tucks the head down between the arms and hides
                // the face. The astronauts' heights sit below the shoulder line so the balls clear the helmet (ASTRO_GRIP_*).
                float gHi = astro ? ASTRO_GRIP_HI : stubArms ? HAM_GRIP_HI : GRIP_HI, gLo = astro ? ASTRO_GRIP_LO : stubArms ? HAM_GRIP_LO : GRIP_LO;
                float h = MathUtils.lerp(side == 0 ? gHi : gLo, gLo + (gHi - gLo) * lead, m);
                ty = pB.y + len * h;                                   // one hand a little higher, one a little lower; alternate while climbing
                tz = lineBack() / SCALE;                               // the rope's depth in bone space: gripLine poses before the body is pushed back, so aiming at where the line will be lands the hand on it once the body slides back
            }
            tgt.set(tx - pB.x, ty - pB.y, tz - pB.z).nor();            // (aim() reuses 'dir' internally, so the target lives in its own vector)
            float root = rootDepth(up);
            if (root > 0f) aimAbout(up, lo, tgt, 1f, root); else aim(up, lo, tgt, 1f);   // the hamster's upper arm swings about its mesh root, so the root stays inside the shoulder
            aim(lo, fi, tgt, 1f);
        }
    }

    /** Climbing legs: knees draw up alternately (as if stepping up the rope); on a plain hang they dangle with a slow sway. */
    private void climbLegs(float phase, float moving) {
        if (ulL == null || ulR == null) return;
        for (int side = 0; side < 2; side++) {
            Node ul = side == 0 ? ulL : ulR, ll = side == 0 ? llL : llR;
            float lift = MathUtils.lerp(0.12f + 0.1f * MathUtils.sin(hangSway + side * 2.2f), Math.max(0f, MathUtils.sin(phase + side * MathUtils.PI)), moving);
            // gentle hip and knee draw-up: legs stay reading as legs (the old 0.9/1.2 rad folds buckled the shin into a skinny, un-leg-like crook)
            rotateBone(ul, 1f, 0f, 0f, 0.15f + 0.40f * lift);
            if (ll != null) rotateBone(ll, 1f, 0f, 0f, 0.20f + 0.50f * lift);
        }
    }
    private float hangSway;

    // How far inward (model units from the rope's x=0) each knee is aimed; the straightish leg then carries the shin+foot
    // (which are skinned to the lower leg) in to just beside the rope. Smaller = the legs angle in more. A gentle alternating
    // lift keeps the climb reading as moving. Both tunable via climb.kneeX / climb.footShuffle.
    private static final float KNEE_X = Float.parseFloat(System.getProperty("climb.kneeX", "0.11"));
    private static final float FOOT_SHUFFLE = Float.parseFloat(System.getProperty("climb.footShuffle", "0.12"));
    private final Vector3 legTgt = new Vector3();
    /** Angle the legs in against the vertical rope: aim each thigh (hip->knee direction) down and inward so the shin and foot
     *  (skinned to the lower leg) close on the line. The Foot node is a Root-parented helper that does NOT follow the leg, so
     *  the lower leg is the real leg tip. Axis-agnostic (aim finds the rotation). Astronauts and hamster only, rope only
     *  (gated by the caller); the bunny's dangle is left alone. */
    private void feetToLine(float phase, float moving) {
        if (ulL == null || ulR == null || llL == null || llR == null) return;
        float m = MathUtils.clamp(moving, 0f, 1f);
        for (int side = 0; side < 2; side++) {
            Node ul = side == 0 ? ulL : ulR, ll = side == 0 ? llL : llR; Vector3 footLocal = side == 0 ? footLocalL : footLocalR;
            ul.globalTransform.getTranslation(pB);                                           // hip (model space)
            float sideSign = side == 0 ? 1f : -1f;                                           // measured: the .L leg is on +x, the .R leg on -x
            float lift = FOOT_SHUFFLE * MathUtils.sin(phase + side * MathUtils.PI) * m;      // a small alternating shuffle up the line
            legTgt.set(sideSign * KNEE_X - pB.x, -1f + lift * 0.2f, 0f).nor();               // point the thigh down (-y) and inward toward the rope's lateral line
            aim(ul, ll, legTgt, 1f);                                                         // thigh: swing the knee down and in
            if (footLocal != null) { footEst.set(footLocal).mul(ll.globalTransform); aimTo(ll, footEst, legTgt, 1f); }   // shin: bring the foot the rest of the way in, straightening the leg toward the line
        }
    }

    /** Diagnostic hook: each fist's true on-screen world position (inst.transform x the bone's global), for checking the
     *  hands sit on the rope, which hangs at world x=0, z=0. Set NaN until the skeleton has been located. */
    public void fistWorld(Vector3 outL, Vector3 outR) {
        if (fiL == null || fiR == null) { outL.set(Float.NaN, Float.NaN, Float.NaN); outR.set(outL); return; }
        new Matrix4(inst.transform).mul(fiL.globalTransform).getTranslation(outL);
        new Matrix4(inst.transform).mul(fiR.globalTransform).getTranslation(outR);
    }

    /** Diagnostic hook: each foot's world position (the foot mesh is skinned to the lower leg; we locate it via its bind
     *  offset inside that bone, since the Foot node itself is a static Root-parented helper), for checking the feet rotate in
     *  against the vertical rope (world x=0). NaN until located. */
    public void footWorld(Vector3 outL, Vector3 outR) {
        if (llL == null || llR == null || footLocalL == null || footLocalR == null) { outL.set(Float.NaN, Float.NaN, Float.NaN); outR.set(outL); return; }
        outL.set(footLocalL).mul(llL.globalTransform).mul(inst.transform);
        outR.set(footLocalR).mul(llR.globalTransform).mul(inst.transform);
    }

    /**
     * Test hook: the smallest scale any arm or leg bone the grip posing drives has, read from its live local transform.
     * Healthy limbs sit at (or above, for the hamster's deliberate stretch) 1; the old scale leak drove this toward 0 over
     * a long grip, which is the shrinking arms/legs/ankles. Returns 1 before the skeleton has been located.
     */
    public float minLimbScale() {
        if (upL == null) return 1f;
        Vector3 s = new Vector3();
        float m = Float.MAX_VALUE;
        // arm and leg bones: their LOCAL scale catches the re-pose leak. The fists are NOT here: for the hamster their
        // local scale is deliberately counter-scaled to undo the compounded arm stretch, so their rendered size is
        // guarded by maxHandScale() (balloon) instead - a small fist LOCAL scale is correct here, not shrinkage.
        for (Node n : new Node[]{upL, loL, upR, loR, ulL, llL, ulR, llR}) {
            if (n == null) continue;
            n.localTransform.getScale(s);
            m = Math.min(m, Math.min(s.x, Math.min(s.y, s.z)));
        }
        return m == Float.MAX_VALUE ? 1f : m;
    }

    /**
     * Test hook: the largest scale component either fist reaches ON SCREEN (its global transform, so it includes the whole
     * arm chain), after all grip and stretch posing. The hamster stretches its arms to reach the line; its paws must NOT
     * balloon with them. Healthy paws sit near their bind scale (~1); the compounded arm stretch used to drive this well
     * above 2. Returns 0 before the skeleton has been located.
     */
    public float maxHandScale() {
        if (fiL == null && fiR == null) return 0f;
        Vector3 s = new Vector3();
        float m = 0f;
        for (Node n : new Node[]{fiL, fiR}) {
            if (n == null) continue;
            n.globalTransform.getScale(s);
            m = Math.max(m, Math.max(s.x, Math.max(s.y, s.z)));
        }
        return m;
    }

    /** Test hook: per-bone local scale (x/y/z) for every arm and leg bone, for diagnosing which limb shrinks and when. */
    public String limbScalesDebug() {
        String[] names = {"upL", "loL", "fiL", "upR", "loR", "fiR", "ulL", "llL", "ulR", "llR"};
        Node[] ns = {upL, loL, fiL, upR, loR, fiR, ulL, llL, ulR, llR};
        Vector3 v = new Vector3();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < ns.length; i++) {
            if (ns[i] == null) continue;
            ns[i].localTransform.getScale(v);
            b.append(names[i]).append('=').append(String.format("%.2f/%.2f/%.2f ", v.x, v.y, v.z));
        }
        return b.toString();
    }

    public void render(ModelBatch batch, Environment env) {
        batch.render(inst, env);
        if (handBall && ballL != null) { batch.render(ballL, env); batch.render(ballR, env); }
    }

    @Override public void dispose() { model.dispose(); if (ballModel != null) ballModel.dispose(); }
}
