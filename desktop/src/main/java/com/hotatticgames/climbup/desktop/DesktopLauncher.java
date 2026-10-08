package com.hotatticgames.climbup.desktop;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.hotatticgames.climbup.ClimbGame;
import java.io.File;

public final class DesktopLauncher {
    public static void main(String[] args) {
        Lwjgl3ApplicationConfiguration c = new Lwjgl3ApplicationConfiguration();
        c.setTitle("Climb up");
        c.setWindowedMode(Integer.getInteger("climb.w", 1280), Integer.getInteger("climb.h", 576));
        c.useVsync(true);
        c.setForegroundFPS(60);
        File dir = new File(System.getProperty("climb.data", System.getProperty("user.home") + "/.climbup"));
        new Lwjgl3Application(new ClimbGame(dir), c);
    }
}
