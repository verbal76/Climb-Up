package com.hotatticgames.climbup;

/** Player settings (persisted separately from the save). All values apply immediately. */
public final class Settings {
    public int version = 1;
    public int music = 7, sfx = 9;            // 0..10
    public int textScale = 0;                 // stored value; legacy 0=100% 1=130% 2=160%, plus 3=85% 4=70% (step order handled below). Kept this way so existing saves are unchanged.
    public boolean reducedMotion = false;
    public int haptics = 2;                   // 0 off, 1 low, 2 high
    public boolean captions = false;
    public int quality = 2;                   // 0 low, 1 medium, 2 high
    public boolean leftHanded = false;
    public boolean assistSlow = false;        // game speed assist
    public boolean assistForgive = false;     // jump forgiveness assist
    public boolean tips = true;
    public int character = 0;                 // 0 = Quaternius bunny, 1 = chibi hamster
    public boolean otaEnabled = true;         // OTA: check for signed gameplay-number updates (Settings > About)
    public boolean highContrast = false;      // HUD/control outlines for visibility

    // TEXT SIZE choices, smallest to largest, as a -/+ stepper. Each entry is the value stored in textScale and the multiplier it means.
    // 70% and 85% were added below 100% at the owner's request (see docs/DECISIONS.md); legacy values 0/1/2 keep their old meaning so old saves are unaffected.
    private static final int[]   TEXT_STEP_SCALE = {4,     3,     0,     1,     2};
    private static final float[] TEXT_STEP_MUL   = {0.70f, 0.85f, 1.00f, 1.30f, 1.60f};

    public float textMul() {
        for (int i = 0; i < TEXT_STEP_SCALE.length; i++) if (TEXT_STEP_SCALE[i] == textScale) return TEXT_STEP_MUL[i];
        return 1f;                                   // an unknown stored value reads as 100%
    }
    /** The current step (0 = smallest); an unknown stored value reads as the 100% step. */
    public int textStep() {
        for (int i = 0; i < TEXT_STEP_SCALE.length; i++) if (TEXT_STEP_SCALE[i] == textScale) return i;
        for (int i = 0; i < TEXT_STEP_SCALE.length; i++) if (TEXT_STEP_SCALE[i] == 0) return i;
        return 0;
    }
    /** Sets TEXT SIZE to a step, clamped to the available range. */
    public void setTextStep(int step) { textScale = TEXT_STEP_SCALE[Math.max(0, Math.min(TEXT_STEP_SCALE.length - 1, step))]; }
    /** The current TEXT SIZE as a percent label, e.g. "70%" or "160%". */
    public String textPercent() { return Math.round(textMul() * 100f) + "%"; }

    /** How many TEXT SIZE steps there are. */
    public static int textStepCount() { return TEXT_STEP_SCALE.length; }
    /** The stored textScale value for a step index (clamped). */
    public static int textScaleForStep(int step) { return TEXT_STEP_SCALE[Math.max(0, Math.min(TEXT_STEP_SCALE.length - 1, step))]; }
}
