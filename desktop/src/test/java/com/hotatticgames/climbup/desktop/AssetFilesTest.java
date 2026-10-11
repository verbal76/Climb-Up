package com.hotatticgames.climbup.desktop;

import static org.junit.Assert.*;

import com.badlogic.gdx.Files;
import com.badlogic.gdx.files.FileHandle;
import java.io.File;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The packaged build reads assets from a folder beside the jar: relative names resolve against it, and handles derived from one (model textures) must not be resolved twice. */
public class AssetFilesTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void relativeNamesResolveUnderTheRoot() throws Exception {
        File root = tmp.newFolder("assets"); new File(root, "models").mkdirs(); new File(root, "models/a.txt").createNewFile();
        AssetFiles f = new AssetFiles(root);
        FileHandle h = f.internal("models/a.txt");
        assertEquals(new File(root, "models/a.txt").getAbsolutePath(), h.file().getAbsolutePath());
        assertTrue(h.exists());
    }

    @Test public void anAlreadyResolvedAbsolutePathIsNotPrefixedAgain() throws Exception {
        File root = tmp.newFolder("assets"); new File(root, "models/Textures").mkdirs(); File tex = new File(root, "models/Textures/colormap.png"); tex.createNewFile();
        AssetFiles f = new AssetFiles(root);
        FileHandle model = f.internal("models/m.obj");
        String derived = model.parent().child("Textures/colormap.png").path();         // what the model loader asks for next
        assertTrue(new File(derived).isAbsolute());
        assertEquals(tex.getAbsolutePath(), f.getFileHandle(derived, Files.FileType.Internal).file().getAbsolutePath());
        assertTrue(f.getFileHandle(derived, Files.FileType.Internal).exists());
    }

    @Test public void withoutARootTheStockBehaviourIsUsed() {
        assertNotNull(new AssetFiles(null).internal("data/tuning.json"));
    }
}
