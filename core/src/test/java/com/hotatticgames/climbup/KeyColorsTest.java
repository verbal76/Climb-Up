package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.render.KeyColors;
import org.junit.Test;

/**
 * The colourblind-safe key palette stays distinct under simulated deuteranopia, protanopia and tritanopia (Machado, Oliveira and Fernandes 2009,
 * severity 1.0, applied in linear RGB), measured as CIE76 colour difference in Lab; and every colour always has its own shape, so colour never
 * carries a key's identity alone (spec section 12).
 */
public class KeyColorsTest {
    /** Machado et al. 2009 simulation matrices (full severity), rows = simulated R, G, B from linear R, G, B. */
    private static final double[][] PROTAN = {{0.152286, 1.052583, -0.204868}, {0.114503, 0.786281, 0.099216}, {-0.003882, -0.048116, 1.051998}};
    private static final double[][] DEUTAN = {{0.367322, 0.860646, -0.227968}, {0.280085, 0.672501, 0.047413}, {-0.011820, 0.042940, 0.968881}};
    private static final double[][] TRITAN = {{1.255528, -0.076749, -0.178779}, {-0.078411, 0.930809, 0.147602}, {0.004733, 0.691367, 0.303900}};
    private static final double[][] NORMAL = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};

    private static double lin(double c) { return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); }
    private static double[] simulate(float[] rgb, double[][] m) {
        double r = lin(rgb[0]), g = lin(rgb[1]), b = lin(rgb[2]);
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) out[i] = Math.max(0, Math.min(1, m[i][0] * r + m[i][1] * g + m[i][2] * b));
        return out;
    }
    private static double f(double t) { return t > 0.008856 ? Math.cbrt(t) : 7.787 * t + 16.0 / 116.0; }
    /** CIE Lab (D65) from linear RGB. */
    private static double[] lab(double[] lin) {
        double x = (0.4124 * lin[0] + 0.3576 * lin[1] + 0.1805 * lin[2]) / 0.95047, y = 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2], z = (0.0193 * lin[0] + 0.1192 * lin[1] + 0.9505 * lin[2]) / 1.08883;
        return new double[]{116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z))};
    }
    private static double deltaE(float[] a, float[] b, double[][] m) {
        double[] la = lab(simulate(a, m)), lb = lab(simulate(b, m));
        return Math.sqrt((la[0] - lb[0]) * (la[0] - lb[0]) + (la[1] - lb[1]) * (la[1] - lb[1]) + (la[2] - lb[2]) * (la[2] - lb[2]));
    }
    private static double worstPair(float[][] palette, double[][] m) {
        double worst = Double.MAX_VALUE;
        for (int i = 0; i < palette.length; i++) for (int j = i + 1; j < palette.length; j++) worst = Math.min(worst, deltaE(palette[i], palette[j], m));
        return worst;
    }

    @Test public void theSafePaletteStaysApartUnderEveryColourVisionDeficiency() {
        String[] names = {"normal", "protanopia", "deuteranopia", "tritanopia"}; double[][][] sims = {NORMAL, PROTAN, DEUTAN, TRITAN};
        for (int k = 0; k < sims.length; k++) {
            double worst = worstPair(KeyColors.SAFE, sims[k]);
            assertTrue("safe palette under " + names[k] + ": closest pair deltaE " + worst, worst >= 25.0);     // 25 Lab units is well past "clearly different"
        }
    }

    @Test public void theSafePaletteIsNeverWorseThanTheOriginalWhereTheOriginalIsWeakest() {
        double[][][] sims = {PROTAN, DEUTAN, TRITAN};
        for (double[][] m : sims) assertTrue(worstPair(KeyColors.SAFE, m) >= worstPair(KeyColors.DEFAULT, m) - 0.01);
    }

    @Test public void everyColourHasItsOwnShapeAndNameWhateverThePalette() {
        java.util.Set<Integer> shapes = new java.util.HashSet<>(); java.util.Set<String> names = new java.util.HashSet<>();
        for (int c = 0; c < 4; c++) { shapes.add(KeyColors.shape(c)); names.add(KeyColors.name(c)); assertEquals(KeyColors.shape(c), KeyColors.shape(c + 4)); }
        assertEquals(4, shapes.size()); assertEquals(4, names.size());
        assertEquals(KeyColors.CIRCLE, KeyColors.shape(0)); assertEquals(KeyColors.DIAMOND, KeyColors.shape(3));
        for (int c = 0; c < 4; c++) { assertSame(KeyColors.DEFAULT[c], KeyColors.rgb(c, false)); assertSame(KeyColors.SAFE[c], KeyColors.rgb(c, true)); }
        assertEquals("GOLD", KeyColors.name(7));
    }
}
