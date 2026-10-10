package com.hotatticgames.climbup.host;

import com.badlogic.gdx.ApplicationListener;
import com.hotatticgames.climbup.spi.GameModule;
import java.io.File;
import java.util.function.Supplier;

/**
 * Cold-start glue: asks the store which verified module to run, loads and instantiates it, and falls through to the previous good module, then to the APK's signed baseline,
 * then to the host's built-in recovery, if loading or creating fails. Platform neutral: the Android host supplies a DexClassLoader-based {@link ClassLoading}, tests a URLClassLoader.
 */
public final class HostBoot {
    private HostBoot() {}

    /** Opens the module's code file in a class loader whose parent is {@code parent} (the host loader, which owns libGDX and the SPI). */
    public interface ClassLoading { ClassLoader open(File codeFile, ClassLoader parent) throws Exception; }

    public static final class Started {
        public final GameModule module; public final ModuleManifest manifest; public final File dir; public final String note;
        /** Game files this module serves in place of the APK's; the host installs an {@link OverlayFiles} only when this is not empty. */
        public final java.util.Map<String, AssetManifest.Asset> overrides;
        Started(GameModule m, ModuleManifest mf, File d, String n, java.util.Map<String, AssetManifest.Asset> o) { module = m; manifest = mf; dir = d; note = n; overrides = o; }
        public boolean recovery() { return module == null; }
    }

    /** @param baseline extracts the signed baseline bundled in the APK to a directory when asked (null if the build has none); used only when nothing installed is runnable. */
    public static Started start(ModuleStore store, ClassLoading loading, ClassLoader parent, Supplier<File> baseline) {
        ModuleStore.Boot b = store.boot();
        if (b.recovery() && baseline != null) {
            File dir = null;
            try { dir = baseline.get(); } catch (Throwable ignored) { }
            if (dir != null && store.installBaseline(dir) == null) b = store.boot();
        }
        return load(store, b, loading, parent);
    }

    /** The module that start() returned threw while the host was creating its game (before any frame): drop it and continue with the next candidate. */
    public static Started failed(ModuleStore store, ClassLoading loading, ClassLoader parent, Throwable why) {
        return load(store, store.loadFailed(why.getClass().getSimpleName() + ": " + why.getMessage()), loading, parent);
    }

    private static Started load(ModuleStore store, ModuleStore.Boot b, ClassLoading loading, ClassLoader parent) {
        for (int attempt = 0; attempt < 4 && !b.recovery(); attempt++) {
            try {
                ClassLoader l = loading.open(new File(b.dir, b.manifest.dex), parent);
                requireSharedTypes(l);
                Class<?> c = Class.forName(b.manifest.entry, true, l);
                if (!GameModule.class.isAssignableFrom(c)) throw new IllegalStateException(b.manifest.entry + " does not implement the host's GameModule");
                GameModule m = (GameModule) c.getDeclaredConstructor().newInstance();
                if (m.interfaceVersion() != b.manifest.interfaceVersion || m.interfaceVersion() != GameModule.INTERFACE_VERSION) throw new IllegalStateException("module reports interface " + m.interfaceVersion());
                return new Started(m, b.manifest, b.dir, b.note, b.overrides);
            } catch (Throwable t) {
                b = store.loadFailed(t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
        return new Started(null, null, null, b.note, java.util.Collections.<String, AssetManifest.Asset>emptyMap());
    }

    /** The module must see the host's own copy of the SPI and libGDX, never a second one (that would split static state, the GL context and the native libraries). */
    static void requireSharedTypes(ClassLoader l) throws ClassNotFoundException {
        for (Class<?> shared : new Class<?>[]{GameModule.class, ApplicationListener.class}) {
            if (Class.forName(shared.getName(), false, l) != shared) throw new IllegalStateException("module carries its own copy of " + shared.getName());
        }
    }
}
