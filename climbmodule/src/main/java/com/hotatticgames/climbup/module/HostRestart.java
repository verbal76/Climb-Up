package com.hotatticgames.climbup.module;

import com.badlogic.gdx.Gdx;

/**
 * Settings > About > RESTART GAME: relaunches the host app as a fresh task and ends this process, so a staged release activates at once. Uses reflection on the Android classes
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
            Thread t = new Thread(() -> { try { Thread.sleep(300); } catch (InterruptedException ignored) { } Runtime.getRuntime().exit(0); }, "host-restart");
            t.setDaemon(true); t.start();
        } catch (Throwable ignored) { }
    }
}
