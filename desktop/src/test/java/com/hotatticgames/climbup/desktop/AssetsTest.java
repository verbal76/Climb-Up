package com.hotatticgames.climbup.desktop;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.audio.Audio;
import com.hotatticgames.climbup.sim.Tuning;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

/** The desktop build reads the same assets folder as every other platform; everything the game asks for by name must be there. */
public class AssetsTest {
    private static File root() { String p = System.getProperty("climb.assets"); File f = new File(p != null ? p : "../assets"); assertTrue(f.getAbsolutePath(), f.isDirectory()); return f; }

    @Test public void everyNamedSoundAndTrackExists() {
        for (String s : Audio.SFX) assertTrue(s, new File(root(), "audio/" + s + ".wav").isFile());
        for (String s : Audio.GAME_TRACKS) assertTrue(s, new File(root(), "audio/" + s + ".ogg").isFile() || new File(root(), "audio/" + s + ".mp3").isFile() || new File(root(), "audio/" + s + ".wav").isFile());
        assertTrue(new File(root(), "branding/studio_splash.png").isFile());
    }

    @Test public void theBundledTuningParses() throws Exception {
        Tuning t = Tuning.parse(new String(Files.readAllBytes(new File(root(), "data/tuning.json").toPath()), StandardCharsets.UTF_8));
        assertNotNull(t);
    }

    @Test public void assetRootResolvesBesideAJarOrFromTheProperty() {
        System.setProperty("climb.assets", root().getAbsolutePath());
        try { assertEquals(root().getAbsoluteFile(), DesktopLauncher.assetRoot()); } finally { System.clearProperty("climb.assets"); }
    }
}
