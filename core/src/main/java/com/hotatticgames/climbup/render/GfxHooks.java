package com.hotatticgames.climbup.render;

/**
 * Two optional drawing switches a launcher may set. The defaults (full-size picture, sky effects on) are exactly the original behaviour, so a launcher
 * that never touches them (Android) draws as before. They only change what is drawn, never the simulation.
 */
public final class GfxHooks {
    private GfxHooks() { }
    /** Fraction of the window size the 3D scene is drawn at before it is stretched to the window (1 = native). The menus and text are always native. */
    public static volatile float renderScale = 1f;
    /** False skips the sun glow, light shafts and the far sky layer (planets, far clouds, sky ships). */
    public static volatile boolean skyEffects = true;
    /** Desktop only: 3D textures that are real pictures (not flat colour palettes) get mipmaps and anisotropic filtering so they do not shimmer in the distance. */
    public static volatile boolean mipmaps = false;
    /** Anisotropic filtering level used with {@link #mipmaps}; 1 = off. */
    public static volatile float anisotropy = 1f;

    /** Linear filtering as before; with {@link #mipmaps} on, trilinear with mipmaps plus anisotropy where the driver offers it. Any failure leaves plain linear. */
    public static void sharpen(com.badlogic.gdx.graphics.Texture t) {
        t.setFilter(com.badlogic.gdx.graphics.Texture.TextureFilter.Linear, com.badlogic.gdx.graphics.Texture.TextureFilter.Linear);
        if (!mipmaps) return;
        try {
            t.bind();
            com.badlogic.gdx.Gdx.gl.glGenerateMipmap(com.badlogic.gdx.graphics.GL20.GL_TEXTURE_2D);
            t.setFilter(com.badlogic.gdx.graphics.Texture.TextureFilter.MipMapLinearLinear, com.badlogic.gdx.graphics.Texture.TextureFilter.Linear);
            if (anisotropy > 1f) t.setAnisotropicFilter(anisotropy);
        } catch (RuntimeException e) { t.setFilter(com.badlogic.gdx.graphics.Texture.TextureFilter.Linear, com.badlogic.gdx.graphics.Texture.TextureFilter.Linear); }
    }
}
