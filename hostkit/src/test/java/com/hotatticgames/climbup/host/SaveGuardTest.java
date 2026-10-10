package com.hotatticgames.climbup.host;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.file.Files;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.HashSet;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** A module that raises the save-schema version must never be able to strand an existing climb. */
public class SaveGuardTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    KeyPair key; TrustedKeys keys; HostInfo host; File root, data; SaveGuard guard; ModuleStore store;

    @Before public void setUp() throws Exception {
        key = Bundles.newKey(); keys = new TrustedKeys().add(key.getPublic()); host = new HostInfo(Bundles.APP, 1, "internal");
        data = tmp.newFolder("data"); root = new File(data, "host");                       // like the app: the host's own state lives inside the data directory
        guard = new DirSnapshots(data, tmp.newFolder("snap"), new HashSet<>(Arrays.asList("host")));
        write("save.json", "{\"version\":6,\"seed\":777}"); write("history.bin", "slices..."); write("sub/settings.json", "{}");
        store = open();
        File d = store.openStaging(); Bundles.write(d, new Bundles.Spec(), key); assertNull(store.commitStaging()); store.boot(); store.confirm();      // v1, proven, schema 6
    }

    ModuleStore open() { return new ModuleStore(root, keys, host).withSaveGuard(guard); }
    void write(String rel, String text) throws Exception { File f = new File(data, rel); f.getParentFile().mkdirs(); Files.write(f.toPath(), text.getBytes()); }
    String read(String rel) throws Exception { return new String(Files.readAllBytes(new File(data, rel).toPath())); }
    String stage(Bundles.Spec s) throws Exception { File d = store.openStaging(); Bundles.write(d, s, key); return store.commitStaging(); }
    ModuleStore.Boot boot() { store = open(); return store.boot(); }

    @Test public void aSchemaBumpIsSnapshottedAndRolledBackSavesComeBackExactly() throws Exception {
        assertNull(stage(new Bundles.Spec().v(2).save(6, 7)));
        assertEquals(2, boot().manifest.moduleVersion);
        assertTrue("a snapshot was taken before v2's first launch", new File(tmp.getRoot(), "snap/v2").isDirectory());
        write("save.json", "{\"version\":7,\"seed\":777,\"new\":true}"); write("save.v7", "migrated"); new File(data, "history.bin").delete();         // v2 "migrates" the saves
        boot(); ModuleStore.Boot third = boot();                                           // v2 never confirms
        assertEquals("back on v1", 1, third.manifest.moduleVersion);
        assertEquals("{\"version\":6,\"seed\":777}", read("save.json")); assertEquals("slices...", read("history.bin")); assertEquals("{}", read("sub/settings.json"));
        assertFalse("nothing v2 created is left behind", new File(data, "save.v7").exists());
        assertTrue(store.st.rollback.contains("saves restored")); assertFalse("and the snapshot is cleaned up", new File(tmp.getRoot(), "snap/v2").exists());
        assertTrue("the host's own state was never touched", new File(root, "state.json").isFile());
    }

    @Test public void aConfirmedModuleKeepsItsSavesAndTheSnapshotIsDropped() throws Exception {
        assertNull(stage(new Bundles.Spec().v(2).save(6, 7))); boot(); write("save.json", "{\"version\":7}"); store.confirm();
        assertFalse(new File(tmp.getRoot(), "snap/v2").exists()); assertEquals("{\"version\":7}", read("save.json"));
        for (int i = 0; i < 5; i++) assertEquals(2, boot().manifest.moduleVersion);
    }

    @Test public void anUpdateThatKeepsTheSchemaTakesNoSnapshot() throws Exception {
        assertNull(stage(new Bundles.Spec().v(2))); boot();
        assertFalse(new File(tmp.getRoot(), "snap/v2").exists());
    }

    @Test public void ifTheSavesCannotBeSnapshottedTheSwitchWaitsInsteadOfRiskingThem() throws Exception {
        guard = new SaveGuard() { public boolean snapshot(String id) { return false; } public boolean restore(String id) { return false; } public void discard(String id) { } };
        assertNull(stage(new Bundles.Spec().v(2).save(6, 7)));
        ModuleStore.Boot b = boot(); assertEquals("stays on v1", 1, b.manifest.moduleVersion); assertEquals("still staged for a later start", 2, store.stagedVersion());
        assertTrue(store.st.lastResult.contains("could not snapshot"));
    }

    @Test public void aRestoreThatDiesHalfWayIsFinishedAtTheNextStart() throws Exception {
        assertNull(stage(new Bundles.Spec().v(2).save(6, 7))); boot(); write("save.json", "{\"version\":7}");
        final SaveGuard real = guard; final int[] calls = {0};
        guard = new SaveGuard() {                                                           // the first restore fails (as if the process were killed in it)
            public boolean snapshot(String id) { return real.snapshot(id); }
            public boolean restore(String id) { return calls[0]++ == 0 ? false : real.restore(id); }
            public void discard(String id) { real.discard(id); } };
        boot(); boot();                                                                     // launches 2 and 3: v2 is dropped, the restore fails once
        assertTrue(store.st.rollback.contains("WARNING")); assertEquals("{\"version\":7}", read("save.json"));
        ModuleStore.Boot again = boot();                                                     // the next start repeats it
        assertEquals(1, again.manifest.moduleVersion); assertEquals("{\"version\":6,\"seed\":777}", read("save.json")); assertEquals("", store.st.restoring);
    }
}
