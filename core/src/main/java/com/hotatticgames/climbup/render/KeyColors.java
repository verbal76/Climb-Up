package com.hotatticgames.climbup.render;

/**
 * The four key / castle-gate colours, in two palettes, and the shape that always goes with each colour.
 *
 * Colour never carries the meaning alone (spec section 12): every key, gate and HUD key icon also shows its shape (circle, square,
 * triangle, diamond) and every toast names the colour. The COLOURBLIND-SAFE COLOURS setting swaps the original palette for one that
 * stays distinct under deuteranopia, protanopia and tritanopia (Okabe and Ito's vermilion, blue, bluish green and yellow);
 * {@code KeyColorsTest} simulates all three and checks every pair of safe colours stays apart.
 */
public final class KeyColors {
    private KeyColors() { }

    public static final String[] NAMES = {"RED", "BLUE", "GREEN", "GOLD"};
    /** The original palette: red, blue, green, gold. */
    public static final float[][] DEFAULT = {{0.95f, 0.22f, 0.20f}, {0.25f, 0.55f, 1f}, {0.30f, 0.85f, 0.35f}, {1f, 0.82f, 0.18f}};
    /**
     * The colourblind-safe palette, in the same slots so the names still read: vermilion (213,94,0), sky blue (86,180,233), deep green (0,90,60)
     * and pale yellow (250,235,120). Chosen by a search over Okabe-Ito-style candidates for the largest worst-case Lab difference under simulated
     * protanopia, deuteranopia and tritanopia (40 / 36 / 41 Lab units; the original palette scores 22 / 18 / 29): the plain Okabe-Ito set fails
     * tritanopia (its blue and bluish green collapse to 17), which is why the green is darker and the blue lighter here.
     */
    public static final float[][] SAFE = {{0.835f, 0.369f, 0.000f}, {0.337f, 0.706f, 0.914f}, {0.000f, 0.353f, 0.235f}, {0.980f, 0.922f, 0.471f}};

    public static final int CIRCLE = 0, SQUARE = 1, TRIANGLE = 2, DIAMOND = 3;
    public static final String[] SHAPES = {"CIRCLE", "SQUARE", "TRIANGLE", "DIAMOND"};

    public static int index(int color) { return ((color % 4) + 4) % 4; }
    /** The colour's rgb (0..1) in the chosen palette. */
    public static float[] rgb(int color, boolean safe) { return (safe ? SAFE : DEFAULT)[index(color)]; }
    public static String name(int color) { return NAMES[index(color)]; }
    /** The shape drawn with the colour: red circle, blue square, green triangle, gold diamond, whatever the palette. */
    public static int shape(int color) { return index(color); }
}
