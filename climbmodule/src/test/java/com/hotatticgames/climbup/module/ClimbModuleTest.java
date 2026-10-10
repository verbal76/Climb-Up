package com.hotatticgames.climbup.module;

import static org.junit.Assert.*;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.hotatticgames.climbup.spi.GameModule;
import com.hotatticgames.climbup.spi.HostEnv;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.Test;

/** The Climb Up game module: what it contains, that it loads from its own class loader under a host loader that owns only libGDX and the SPI, and that it plays exactly like the code run directly. */
public class ClimbModuleTest {
    static File jar() { return new File(System.getProperty("climb.moduleJar")); }
    static URL location(Class<?> c) throws Exception { return c.getProtectionDomain().getCodeSource().getLocation(); }

    /** A host-like loader: it exposes libGDX and the SPI (the very classes this test uses) and hides everything else, in particular :core, so the module must bring its own. */
    static ClassLoader hostLoader() {
        final ClassLoader app = ClimbModuleTest.class.getClassLoader();
        return new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("com.badlogic.gdx.") || name.startsWith("com.hotatticgames.climbup.spi.")) return app.loadClass(name);
                return super.loadClass(name, resolve);
            }
        };
    }

    @Test public void theModuleHoldsAllOfCoreAndNoneOfTheHostsClasses() throws Exception {
        Set<String> inJar = new HashSet<>();
        try (ZipFile z = new ZipFile(jar())) { for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) { String n = en.nextElement().getName(); if (n.endsWith(".class")) inJar.add(n); } }
        for (String n : inJar) assertFalse("host-owned class in module: " + n, n.startsWith("com/badlogic/") || n.startsWith("com/hotatticgames/climbup/spi/"));
        int coreClasses = 0;
        for (String dir : System.getProperty("climb.coreClasses").split(File.pathSeparator)) {
            java.nio.file.Path root = Paths.get(dir);
            try (java.util.stream.Stream<java.nio.file.Path> w = Files.walk(root)) {
                for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) w.filter(x -> x.toString().endsWith(".class"))::iterator) {
                    coreClasses++; String entry = root.relativize(p).toString().replace(File.separatorChar, '/');
                    assertTrue("missing from module: " + p, inJar.contains(entry));
                    try (ZipFile z = new ZipFile(jar())) { assertArrayEquals("byte-identical to core's compiled class: " + entry, Files.readAllBytes(p), z.getInputStream(z.getEntry(entry)).readAllBytes()); }
                }
            }
        }
        assertTrue("core has classes (" + coreClasses + ")", coreClasses > 50);
        assertTrue(inJar.contains("com/hotatticgames/climbup/module/ClimbModule.class"));
        System.out.println("module jar: " + inJar.size() + " classes (" + coreClasses + " from core, unchanged), " + jar().length() + " bytes");
    }

    @Test public void theHostLoadsTheRealGameThroughItsOwnClassLoader() throws Exception {
        ClassLoader host = hostLoader();
        URLClassLoader mod = new URLClassLoader(new URL[]{jar().toURI().toURL()}, host);
        Class<?> entry = Class.forName("com.hotatticgames.climbup.module.ClimbModule", true, mod);
        GameModule m = (GameModule) entry.getDeclaredConstructor().newInstance();
        assertEquals(GameModule.INTERFACE_VERSION, m.interfaceVersion());
        assertSame("the SPI is the host's, not a second copy", GameModule.class, Class.forName(GameModule.class.getName(), false, mod));
        Class<?> climbGame = Class.forName("com.hotatticgames.climbup.ClimbGame", false, mod);
        assertSame("the game's classes come from the module loader", mod, climbGame.getClassLoader());
        assertSame("libGDX comes from the host", com.badlogic.gdx.Game.class, Class.forName("com.badlogic.gdx.Game", false, mod));
        ApplicationListener l = m.create(new HostEnv() {
            public File dataDir() { return new File("build/modtest-data"); } public int appBuild() { return 7; } public int hostLevel() { return 1; } public File moduleDir() { return null; }
            public void confirmHealthy() { } public void climbInProgress(boolean b) { } public void diag(String s) { }
        });
        assertNotNull(l); assertSame(climbGame, l.getClass().getSuperclass());
    }

    @Test public void theModuleLoadedGamePlaysTheSameClimbAsTheDirectlyRunCode() throws Exception {
        String tuning = new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json")), "UTF-8");
        String direct = SelfTest.digest(tuning, 12, 3600);
        ClassLoader host = hostLoader();
        URLClassLoader mod = new URLClassLoader(new URL[]{jar().toURI().toURL()}, host);
        GameModule m = (GameModule) Class.forName("com.hotatticgames.climbup.module.ClimbModule", true, mod).getDeclaredConstructor().newInstance();
        String viaModule = m.selfTest("digest:12:3600");
        System.out.println("direct : " + direct + "\nmodule : " + viaModule);
        assertEquals("exact agreement, no tolerance", direct, viaModule);
        assertTrue("it actually climbed", direct.contains("failed=false"));
        assertNotEquals("a different seed is a different climb", direct, SelfTest.digest(tuning, 13, 3600));
        assertEquals("and the digest is repeatable", direct, SelfTest.digest(tuning, 12, 3600));
    }
}
