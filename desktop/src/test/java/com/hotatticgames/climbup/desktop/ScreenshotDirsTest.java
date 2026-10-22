package com.hotatticgames.climbup.desktop;

import static org.junit.Assert.*;

import com.badlogic.gdx.graphics.Pixmap;
import com.hotatticgames.climbup.Screenshots;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import javax.imageio.ImageIO;
import org.junit.Test;

/** Build 65: where a desktop screenshot goes, and that the PNG the game writes is a real PNG with the rows in the order they were given. */
public class ScreenshotDirsTest {
    @Test public void picturesIsUnderTheHomeOnWindowsAndMac() {
        assertEquals(new File("C:\\Users\\kev", "Pictures"), DataDirs.resolvePictures("Windows 11", "C:\\Users\\kev", null));
        assertEquals(new File("/Users/kev", "Pictures"), DataDirs.resolvePictures("Mac OS X", "/Users/kev", null));
    }

    @Test public void linuxReadsTheXdgPicturesFolderAndFallsBackToPictures() {
        String dirs = "# This file is written by xdg-user-dirs-update\nXDG_DESKTOP_DIR=\"$HOME/Desktop\"\nXDG_PICTURES_DIR=\"$HOME/Bilder\"\n";
        assertEquals(new File("/home/deck/Bilder"), DataDirs.resolvePictures("Linux", "/home/deck", dirs));
        assertEquals(new File("/home/deck/Pictures"), DataDirs.resolvePictures("Linux", "/home/deck", "XDG_DESKTOP_DIR=\"$HOME/Desktop\"\n"));
        assertEquals(new File("/home/deck/Pictures"), DataDirs.resolvePictures("Linux", "/home/deck", null));
    }

    @Test public void theFolderIsPicturesUpwardlyScreenshotsOnlyWhenPicturesExists() throws Exception {
        File data = Files.createTempDirectory("data").toFile(), pictures = Files.createTempDirectory("pics").toFile();
        assertEquals(new File(new File(pictures, "Upwardly"), "Screenshots"), DataDirs.screenshotDir(data, pictures));
        assertEquals(new File(data, "Screenshots"), DataDirs.screenshotDir(data, new File(pictures, "missing")));
        assertEquals(new File(data, "Screenshots"), DataDirs.screenshotDir(data, null));
    }

    @Test public void thePngKeepsSizeAndRowOrder() throws Exception {
        com.badlogic.gdx.utils.GdxNativesLoader.load();                                   // a Pixmap lives in native memory; no application is running in this test
        Pixmap pm = new Pixmap(6, 4, Pixmap.Format.RGBA8888);
        int[] rows = {0xff0000ff, 0x00ff00ff, 0x0000ffff, 0xffffffff};             // red, green, blue, white, top to bottom
        for (int y = 0; y < 4; y++) { pm.setColor(rows[y]); pm.drawLine(0, y, 5, y); }
        byte[] png = Screenshots.encodePng(pm); pm.dispose();
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        assertEquals(6, img.getWidth()); assertEquals(4, img.getHeight());
        assertEquals(0xffff0000, img.getRGB(0, 0)); assertEquals(0xff00ff00, img.getRGB(3, 1)); assertEquals(0xff0000ff, img.getRGB(5, 2)); assertEquals(0xffffffff, img.getRGB(2, 3));
    }
}
