package com.hotatticgames.climbup.fx;

import com.badlogic.gdx.graphics.Color;
import com.hotatticgames.climbup.render.Particles;
import java.util.Random;

/**
 * Build 83 game juice: small, short particle accents for moments that had sound but no visual feedback (or neither). Presentation only: it
 * reads positions the simulation already published and never writes anything back; its randomness is its own generator.
 *
 * Rules (owner: "make the game feel better without making it play differently"): every effect is a few cube particles near the player that
 * fade in well under a second, so nothing covers the route; none shakes the camera or flashes the screen; with REDUCED MOTION on or at the
 * lowest graphics quality none of them is drawn (the existing minimal presentation), and the existing particle density setting scales them.
 */
public final class Juice {
    private final Particles p;
    private final Random rng = new Random(0x83F00DL);
    /** Off with REDUCED MOTION and at LOW graphics quality. */
    public boolean enabled = true;

    private static final Color FIBRE = new Color(0.72f, 0.55f, 0.33f, 1f), SPARK = new Color(1f, 0.92f, 0.6f, 1f), DUST = new Color(0.9f, 0.88f, 0.8f, 1f),
            FLAME = new Color(1f, 0.55f, 0.15f, 1f), FLAME_HOT = new Color(1f, 0.85f, 0.4f, 1f), SMOKE = new Color(0.62f, 0.62f, 0.66f, 1f),
            STAR = new Color(1f, 1f, 0.85f, 1f), GOLD = new Color(1f, 0.85f, 0.3f, 1f), MINT = new Color(0.55f, 1f, 0.8f, 1f), PINK = new Color(1f, 0.55f, 0.75f, 1f);

    public Juice(Particles particles) { p = particles; }

    private float r(float a, float b) { return a + rng.nextFloat() * (b - a); }

    /** Hands close on a rope: a few fibres flick off. */
    public void ropeGrab(float s, float handY) {
        if (!enabled) return;
        for (int k = 0; k < 4; k++) p.spawn(s + r(-0.12f, 0.12f), handY + r(-0.1f, 0.1f), r(-1.2f, 1.2f), r(0.2f, 1.2f), FIBRE, r(0.04f, 0.07f), 7f, r(0.3f, 0.45f));
    }

    /** Hands snap onto a cable: a quick spray of small sparks along the wire. */
    public void cableGrab(float s, float handY) {
        if (!enabled) return;
        for (int k = 0; k < 5; k++) p.spawn(s + r(-0.1f, 0.1f), handY + 0.05f, r(-2.4f, 2.4f), r(-0.3f, 0.9f), SPARK, r(0.035f, 0.055f), 6f, r(0.18f, 0.3f));
    }

    /** Fingertips catch a ledge: a puff of grit from the edge. */
    public void ledgeGrab(float s, float handY, int facing) {
        if (!enabled) return;
        for (int k = 0; k < 5; k++) p.spawn(s + facing * r(0.05f, 0.3f), handY + r(-0.05f, 0.08f), facing * r(0.1f, 0.9f) + r(-0.3f, 0.3f), r(-0.6f, 0.4f), DUST, r(0.05f, 0.08f), 5f, r(0.3f, 0.5f));
    }

    /** A hard landing (a long fall): a low ring of dust skims out both ways along the deck. */
    public void hardLanding(float s, float y, float speed) {
        if (!enabled) return;
        int n = speed > 20f ? 8 : 6;
        for (int k = 0; k < n; k++) { float side = (k & 1) == 0 ? 1f : -1f; p.spawn(s + side * r(0.1f, 0.3f), y + 0.04f, side * r(2.2f, 3.6f), r(0.1f, 0.5f), DUST, r(0.07f, 0.11f), 2.5f, r(0.35f, 0.5f)); }
    }

    /** A turbo ramp's booster lights: a hot flash at the feet, flung back against the launch. */
    public void turboLaunch(float s, float y, float dirX) {
        if (!enabled) return;
        for (int k = 0; k < 7; k++) p.spawn(s - dirX * r(0f, 0.3f), y + r(0f, 0.3f), -dirX * r(1.5f, 4f) + r(-0.5f, 0.5f), r(-0.6f, 1.2f), (k & 1) == 0 ? FLAME : FLAME_HOT, r(0.06f, 0.1f), 1f, r(0.22f, 0.35f));
    }

    /** One exhaust ember per call while a turbo flight lasts (the caller paces it). */
    public void turboTrail(float s, float y, float vx) {
        if (!enabled) return;
        float back = vx >= 0f ? -1f : 1f;
        p.spawn(s + back * 0.25f + r(-0.08f, 0.08f), y + 0.35f + r(-0.1f, 0.1f), back * r(0.5f, 1.4f), r(-0.4f, 0.2f), rng.nextBoolean() ? FLAME : FLAME_HOT, r(0.045f, 0.07f), 0.5f, r(0.18f, 0.28f));
    }

    /** A turbo flight cut short: a small grey puff. */
    public void turboFizzle(float s, float y) {
        if (!enabled) return;
        for (int k = 0; k < 4; k++) p.spawn(s + r(-0.2f, 0.2f), y + r(0.2f, 0.6f), r(-0.6f, 0.6f), r(0.2f, 0.8f), SMOKE, r(0.08f, 0.12f), -0.4f, r(0.3f, 0.45f));
    }

    /** A timed rebound off a cannon ball or saw: a tight star of white sparks where it met. */
    public void rebound(float s, float y) {
        if (!enabled) return;
        for (int k = 0; k < 6; k++) { double a = k * Math.PI / 3 + r(-0.2f, 0.2f); p.spawn(s, y, (float) Math.cos(a) * 2.6f, (float) Math.sin(a) * 2.6f, STAR, 0.05f, 0f, 0.22f); }
    }

    /** A new personal best for a castle: a short burst of confetti above the head (gold, mint and pink, so it does not read as a hazard). */
    public void personalBest(float s, float headY) {
        if (!enabled) return;
        for (int k = 0; k < 14; k++) { Color c = k % 3 == 0 ? GOLD : k % 3 == 1 ? MINT : PINK; p.spawn(s + r(-0.4f, 0.4f), headY + r(0f, 0.3f), r(-1.6f, 1.6f), r(2f, 4f), c, r(0.05f, 0.08f), 6f, r(0.7f, 1.0f)); }
    }
}
