package com.hotatticgames.climbup.desktop;

import com.badlogic.gdx.Audio;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.backends.lwjgl3.audio.OpenALLwjgl3Audio;
import org.lwjgl.openal.AL10;

/**
 * Build 83: desktop effects at the same level as Android.
 *
 * Measured with OpenAL Soft's wave writer (docs/AUDIO_POLISH_REPORT.md): libGDX plays a mono effect at "pan 0" by placing its OpenAL source
 * at (0, 0, 1), which OpenAL Soft renders 7.9 dB down in each stereo channel; stereo music and Android's SoundPool play at file level, so on a
 * PC every effect sat about 8 dB lower against the music than on a phone. The game never pans an effect, so each effect voice is made
 * non-spatial (AL_SOFT_source_spatialize: front-centre, 4.5 dB down per channel on stereo, the centre speaker on surround) and given the
 * 4.5 dB back as source gain (OpenAL allows a source gain above 1; AL_MAX_GAIN is raised to let it through). Without the extension the
 * measured 7.9 dB is made up instead. Music and the ambience beds are untouched. Effects only; nothing here can reach the simulation.
 */
public final class OpenAlLevels {
    private OpenAlLevels() { }

    static final int AL_SOURCE_SPATIALIZE_SOFT = 0x1214;
    /** Make-up gains (linear) for a centred mono voice: non-spatial (-4.5 dB measured) and spatial at libGDX's pan-0 position (-7.9 dB measured). */
    static final float MAKEUP_NONSPATIAL = (float) Math.pow(10, 4.5 / 20), MAKEUP_SPATIAL = (float) Math.pow(10, 7.9 / 20);

    private static int mode = -1;          // -1 not yet known, 0 not OpenAL (do nothing), 1 non-spatial + 4.5 dB, 2 spatial + 7.9 dB

    /** Installs the hook (any time before or after the application starts; it looks at the audio backend on the first effect). */
    public static void install() {
        if (Boolean.getBoolean("climb.noAlLevels")) return;
        com.hotatticgames.climbup.audio.Audio.levelHook = OpenAlLevels::level;
    }

    static void level(Sound s, long id, float volume) {
        try {
            Audio a = Gdx.audio;
            if (mode < 0) mode = !(a instanceof OpenALLwjgl3Audio) ? 0 : AL10.alIsExtensionPresent("AL_SOFT_source_spatialize") ? 1 : 2;
            if (mode == 0) return;
            int src = ((OpenALLwjgl3Audio) a).getSourceId(id);
            if (src < 0) return;
            if (mode == 1) AL10.alSourcei(src, AL_SOURCE_SPATIALIZE_SOFT, AL10.AL_FALSE);
            AL10.alSourcef(src, AL10.AL_MAX_GAIN, 4f);
            AL10.alSourcef(src, AL10.AL_GAIN, volume * (mode == 1 ? MAKEUP_NONSPATIAL : MAKEUP_SPATIAL));
        } catch (Throwable t) { mode = 0; }          // never let a level tweak break the sound: fall back to libGDX's own level
    }
}
