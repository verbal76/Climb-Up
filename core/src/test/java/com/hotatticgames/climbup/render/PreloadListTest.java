package com.hotatticgames.climbup.render;

import static org.junit.Assert.*;

import java.io.File;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Test;

/** Build 71: the models preloaded during the splash are exactly the world models the game ships, so none is left to load mid-climb and none is missing. */
public class PreloadListTest {
    private static Set<String> dir(String d, String ext, boolean noAstronauts) {
        Set<String> s = new TreeSet<>();
        for (File f : new File("../assets/" + d).listFiles()) {
            String n = f.getName();
            if (n.endsWith(ext) && !(noAstronauts && n.startsWith("astronaut_"))) s.add(n.substring(0, n.length() - ext.length()));
        }
        return s;
    }

    @Test public void theListsMatchTheShippedModels() {
        assertEquals(dir("pack", ".g3dj", false), new TreeSet<>(Arrays.asList(Models.PRELOAD_PACK)));
        assertEquals(dir("models", ".obj", false), new TreeSet<>(Arrays.asList(Models.PRELOAD_OBJ)));
        assertEquals("the astronauts are characters (HeroRig), not scenery", dir("space", ".g3dj", true), new TreeSet<>(Arrays.asList(Models.PRELOAD_SPACE)));
    }
}
