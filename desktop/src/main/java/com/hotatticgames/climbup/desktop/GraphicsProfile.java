package com.hotatticgames.climbup.desktop;

import java.util.Locale;

/** First-run choice of graphics defaults from what the machine reports. Pure (strings and numbers in, a decision out) so it can be tested without a GPU. */
public final class GraphicsProfile {
    public enum Tier { SOFTWARE, INTEGRATED, MIDDLE, STRONG }

    public static final class Decision {
        public final Tier tier;
        /** 0 low, 1 medium, 2 high: the game's own GRAPHICS QUALITY. */
        public final int quality;
        /** Percent of the window size the 3D scene is drawn at. */
        public final int renderScale;
        public final boolean skyEffects;
        /** 0 = unlimited (the monitor paces the frames with vsync on). */
        public final int frameLimit;
        /** Anti-aliasing samples (0, 2, 4, 8) and texture sharpness (1, 4, 8, 16). */
        public final int aa, anisotropy;
        public final String reason;
        Decision(Tier tier, int quality, int renderScale, boolean skyEffects, int frameLimit, int aa, int anisotropy, String reason) {
            this.tier = tier; this.quality = quality; this.renderScale = renderScale; this.skyEffects = skyEffects; this.frameLimit = frameLimit; this.aa = aa; this.anisotropy = anisotropy; this.reason = reason;
        }
        @Override public String toString() { return tier + ": quality " + quality + ", scale " + renderScale + "%, sky " + (skyEffects ? "on" : "off") + ", frame limit " + (frameLimit == 0 ? "none" : frameLimit) + ", anti-aliasing " + aa + "x, sharpness " + anisotropy + "x (" + reason + ")"; }
    }

    private GraphicsProfile() { }

    /** What kind of graphics chip the OpenGL renderer string describes. */
    public static Tier tierOf(String renderer, long vramMb) {
        String r = renderer == null ? "" : renderer.toLowerCase(Locale.ROOT);
        Tier t;
        if (r.isEmpty()) t = Tier.INTEGRATED;                                                 // unknown: be careful
        else if (r.contains("llvmpipe") || r.contains("softpipe") || r.contains("swrast") || r.contains("software") || r.contains("gdi generic") || r.contains("basic render") || r.contains("microsoft") || r.contains("swiftshader")) t = Tier.SOFTWARE;
        else if (r.contains("intel(r) arc") || r.contains("intel arc")) t = Tier.MIDDLE;                   // Intel's separate graphics cards
        else if (r.contains("intel") || r.contains("uhd") || r.contains("iris") || r.contains("hd graphics")) t = Tier.INTEGRATED;
        else if (r.contains("geforce")  || r.contains("nvidia") || r.contains("quadro") || r.contains("titan")) t = nvidiaTier(r);
        else if (r.contains("radeon") || r.contains("amd") || r.contains("ati ")) t = amdTier(r);
        else if (r.contains("apple") || r.contains("adreno") || r.contains("mali") || r.contains("vega")) t = Tier.INTEGRATED;
        else t = Tier.MIDDLE;
        if (vramMb > 0 && vramMb < 1500 && t.ordinal() > Tier.INTEGRATED.ordinal()) t = Tier.INTEGRATED;      // very little video memory: treat as weak
        else if (vramMb > 0 && vramMb < 3000 && t == Tier.STRONG) t = Tier.MIDDLE;
        return t;
    }

    private static Tier nvidiaTier(String r) {
        if (r.contains("rtx")) return r.contains("rtx 20") || r.contains("rtx 3050") ? Tier.MIDDLE : Tier.STRONG;
        if (r.contains("gtx 16") || r.contains("gtx 10") && !r.contains("1030") || r.contains("gtx 9")) return Tier.MIDDLE;
        if (r.contains(" mx") || r.contains("geforce gt ") || r.contains("gtx 6") || r.contains("gtx 7") || r.contains("gtx 5") || r.contains("gtx 4") || r.contains("1030")) return Tier.INTEGRATED;
        return Tier.MIDDLE;
    }

    private static Tier amdTier(String r) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("rx\\s*(\\d{3,4})").matcher(r);
        if (m.find()) { int n = Integer.parseInt(m.group(1)); return n < 5600 ? Tier.MIDDLE : Tier.STRONG; }       // RX 4xx/5xx and 5500 are middle; 5600 XT and newer are strong
        if (r.contains("radeon rx")) return Tier.MIDDLE;
        if (r.contains("radeon pro") || r.contains("radeon vii") || r.contains("radeon r9") || r.contains("fury")) return Tier.MIDDLE;
        return Tier.INTEGRATED;                                                                // "AMD Radeon(TM) Graphics", Vega 8 and the like are built into the processor
    }

    /** The defaults for this machine. {@code vramMb} is -1 when unknown; {@code refreshHz} 0 when unknown. */
    public static Decision decide(String renderer, long vramMb, int displayW, int displayH, int refreshHz) {
        Tier t = tierOf(renderer, vramMb);
        long px = (long) Math.max(0, displayW) * Math.max(0, displayH);
        String why = (renderer == null || renderer.isEmpty() ? "unknown graphics chip" : renderer) + (vramMb > 0 ? ", " + vramMb + " MB video memory" : "") + ", screen " + displayW + "x" + displayH + (refreshHz > 0 ? " @" + refreshHz + " Hz" : "");
        switch (t) {
            case SOFTWARE: return new Decision(t, 0, 50, false, 30, 0, 1, why);
            case INTEGRATED: {
                int scale = px > 3_000_000L ? 50 : px > 1_300_000L ? 75 : 100;       // above 1080p: half; 1080p: three quarters; smaller screens: full
                return new Decision(t, 0, scale, false, 0, px > 3_000_000L ? 0 : 2, 4, why);
            }
            case MIDDLE: return new Decision(t, 1, px > 8_000_000L ? 75 : 100, true, 0, px > 8_000_000L ? 2 : 4, 8, why);
            default: return new Decision(t, 2, 100, true, 0, 4, 16, why);
        }
    }

    /** First-run window kind: a large display (1920 x 1080 or more, which includes a TV) starts full screen, anything smaller starts in a window. */
    public static com.hotatticgames.climbup.desktop.input.DesktopConfig.DisplayMode defaultDisplay(int displayW, int displayH) {
        return displayW >= 1920 && displayH >= 1080 ? com.hotatticgames.climbup.desktop.input.DesktopConfig.DisplayMode.FULLSCREEN : com.hotatticgames.climbup.desktop.input.DesktopConfig.DisplayMode.WINDOWED;
    }

    /** The cap to hand the window system. With vsync on the monitor already paces frames, so a limit at or above its rate (or none) asks for no extra cap. */
    public static int effectiveFrameLimit(boolean vsync, int limit, int refreshHz) {
        if (limit <= 0) return 0;
        if (vsync && refreshHz > 0 && limit >= refreshHz) return 0;
        return limit;
    }
}
