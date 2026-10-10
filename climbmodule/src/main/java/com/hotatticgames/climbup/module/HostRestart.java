package com.hotatticgames.climbup.module;

import com.badlogic.gdx.Gdx;

/**
 * Settings > About > RESTART GAME: relaunches the host app as a fresh task (the old activity is cleared, the host boots again and activates a staged release at once). The process is NOT killed afterwards: killing it right after the launch raced the system and dropped the player to the home screen on a Pixel. Uses reflection on the Android classes
 * (the module is compiled without the Android SDK) and works on the host that is already installed: no new host component is needed. Any failure leaves the game running.
 */
final class HostRestart {
    private HostRestart() { }

    static void relaunch() {
        try {
            Object ctx = Gdx.app;                                           // the host's AndroidApplication, a Context
            Class<?> context = Class.forName("android.content.Context");
            String pkg = (String) context.getMethod("getPackageName").invoke(ctx);
            Object pm = context.getMethod("getPackageManager").invoke(ctx);
            Object intent = Class.forName("android.content.pm.PackageManager").getMethod("getLaunchIntentForPackage", String.class).invoke(pm, pkg);
            if (intent == null) return;
            Class<?> intentClass = Class.forName("android.content.Intent");
            intentClass.getMethod("addFlags", int.class).invoke(intent, 0x10000000 | 0x00008000);      // NEW_TASK | CLEAR_TASK
            context.getMethod("startActivity", intentClass).invoke(ctx, intent);
        } catch (Throwable ignored) { }
    }
}
