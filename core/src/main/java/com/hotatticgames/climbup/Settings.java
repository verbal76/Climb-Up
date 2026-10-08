package com.hotatticgames.climbup;

/** Player settings (persisted separately from the save). All values apply immediately. */
public final class Settings {
    public int version = 1;
    public int music = 7, sfx = 9;            // 0..10
    public int textScale = 0;                 // 0=100% 1=130% 2=160%
    public boolean reducedMotion = false;
    public int haptics = 2;                   // 0 off, 1 low, 2 high
    public boolean captions = false;
    public int quality = 2;                   // 0 low, 1 medium, 2 high
    public boolean leftHanded = false;
    public boolean assistSlow = false;        // game speed assist
    public boolean assistForgive = false;     // jump forgiveness assist
    public boolean tips = true;
    public int character = 0;                 // 0 = Quaternius bunny, 1 = chibi hamster
    public boolean highContrast = false;      // HUD/control outlines for visibility

    public float textMul() { return textScale == 0 ? 1f : textScale == 1 ? 1.3f : 1.6f; }
}
