package com.hotatticgames.climbup;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.hotatticgames.climbup.sim.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * The demo (docs/DEMO.md, owner directive 2026-10-28, Part 12.1). Numbers in the test names are the directive's required demo tests. These run in
 * both editions (they drive DemoFlow, the recipe, the baked courses and the records directly); the few that need the demo's build-time identity
 * are marked and skip in a full-game build: the demo workflow runs this class again with -Pclimb.edition=demo.
 */
public class DemoTest {
    static String text(String p) throws Exception { return new String(Files.readAllBytes(Paths.get("../assets/" + p)), StandardCharsets.UTF_8); }
    static DemoRecipe recipe() throws Exception { return DemoRecipe.parse(text("demo/recipe.json")); }

    static ClimbGame game(File dir) throws Exception {
        ClimbGame g = new ClimbGame(dir);
        g.store = new SaveStore(dir); g.settingsStore = g.store; g.history = new HistoryStore(dir); g.save = g.store.loadGame(); g.settings = new Settings(); g.tuning = TestUtil.tuning();
        return g;
    }
    static DemoFlow flow(ClimbGame g) throws Exception {
        DemoFlow f = new DemoFlow(g, recipe(), DemoRecords.load(g.dataDir), p -> { try { return Files.readAllBytes(Paths.get("../assets/" + p)); } catch (Exception e) { throw new RuntimeException(e); } });
        g.demoFlow = f; return f;
    }
    static File tmp() throws Exception { return Files.createTempDirectory("demo").toFile(); }

    // ------------------------------------------------------------------ the recipe and the sequence

    @Test public void t01_theDemoAlwaysBeginsOnNormal() throws Exception {
        DemoFlow f = flow(game(tmp()));
        assertEquals("a first start plays stage 1", 0, f.resumeStage());
        assertEquals(0, f.recipe.stages.get(0).difficulty);
        assertEquals("NORMAL, PUNISHER, NOT GONNA HAPPEN in that order", 1, f.recipe.stages.get(1).difficulty);
        assertEquals(2, f.recipe.stages.get(2).difficulty);
        f.records.stageFinished(0, 100, f.stage(0).seed, f.stage(0).fingerprint, false);
        f.records.restartSequence();
        assertEquals("PLAY DEMO AGAIN begins on NORMAL", 0, f.resumeStage());
    }

    @Test public void t02_05_08_eachStageHasItsTitleCardFadeInHoldFadeOut() throws Exception {
        DemoRecipe r = recipe();
        assertEquals("NORMAL DIFFICULTY", r.stages.get(0).title);
        assertEquals("PUNISHER DIFFICULTY", r.stages.get(1).title);
        assertEquals("NOT GONNA HAPPEN DIFFICULTY", r.stages.get(2).title);
        assertEquals("starts black", 0f, DemoCardScreen.alpha(r, 0f), 1e-6);
        assertTrue("fades in", DemoCardScreen.alpha(r, r.cardFadeIn * 0.5f) > 0.3f && DemoCardScreen.alpha(r, r.cardFadeIn * 0.5f) < 0.7f);
        assertEquals("holds fully visible", 1f, DemoCardScreen.alpha(r, r.cardFadeIn + r.cardHold * 0.5f), 1e-6);
        assertTrue("long enough to read", r.cardHold >= 1.5f);
        assertTrue("fades out", DemoCardScreen.alpha(r, r.cardFadeIn + r.cardHold + r.cardFadeOut * 0.5f) < 0.7f);
        assertEquals("then gone", 0f, DemoCardScreen.alpha(r, DemoCardScreen.length(r) + 0.01f), 1e-6);
    }

    @Test public void t03_06_09_everyStageBeginsJustPastCastle2InDuskSpire() throws Exception {
        ClimbGame g = game(tmp()); DemoFlow f = flow(g);
        assertEquals("every stage is shown in Dusk Spire", "Dusk Spire", com.hotatticgames.climbup.render.Palette.NAMES[f.recipe.world]);
        assertEquals(2, f.recipe.fromCastle); assertEquals(3, f.recipe.toCastle);
        for (int d = 0; d < 3; d++) {
            ClimbGame.Run r = f.buildRun(d);
            Tower tw = r.tower; Course c = tw.world;
            int i = tw.afterCastle(2);
            assertTrue("stage " + d + ": castle 2 is in the window", i > 0);
            double deck = c.get(i - 1).y + tw.originY, start = c.get(i).y + tw.originY;
            assertEquals("stage " + d + ": the start castle's deck stands at 1,000 m", 1000.0, deck, 0.01);
            assertTrue("stage " + d + ": the start is just past its door", start >= deck - 0.5 && start < deck + 8);
            assertEquals("stage " + d + ": PlayScreen begins at castle 2 (the world picker's mid-tower start)", 1000, r.startHeight);
            assertTrue("stage " + d + ": the tower never grows", tw.sealed);
            f.beginStage(d);
            assertEquals("the stage's difficulty", d, g.save.difficulty);
            assertEquals("the stage's finish castle", 3, g.runTuning().finishCastle);
            assertEquals("the generator's numbers are the difficulty's own", DemoCourse.tuningStamp(g.tuningFor(d)), DemoCourse.tuningStamp(f.genTuning(d)));
        }
    }

    /** Walking through castle {@code n}'s open door on the demo stage's simulation tuning. */
    static int walkThrough(Tuning t, int n) {
        Course c = new Course(1, t.circumference());
        c.add(new Element(Element.Type.STATIC, 0f, 0f, 30f));
        Element gate = new Element(Element.Type.GATE, 10f, 0f, 3.4f); gate.len = 7.5f; gate.color = 1; gate.skin = n; gate.anchor = 0; c.hazards.add(gate);
        Sim s = Sim.startOn(c, t, 0); s.keysFree = false; s.keys = 1 << 1; s.s = 6.5f;
        InputState in = new InputState(); int ev = 0;
        for (int i = 0; i < 400; i++) { in.clear(); in.moveX = 1f; s.step(in); ev |= s.consumeEvents(); }
        return ev;
    }

    @Test public void t04_07_10_completingCastle3AdvancesToTheNextStageThenThePromo() throws Exception {
        ClimbGame g = game(tmp()); DemoFlow f = flow(g);
        for (int d = 0; d < 3; d++) {
            Tuning st = f.simTuning(d);
            assertTrue("castle 3's door ends stage " + d, (walkThrough(st, 3) & Sim.EV_FINISH) != 0);
            assertEquals("castle 2's door does not", 0, walkThrough(st, 2) & Sim.EV_FINISH);
            DemoFlow.Stage s = f.stage(d);
            f.records.stageFinished(d, 6000 + d, s.seed, s.fingerprint, false);
            if (d == 2) f.records.sequenceFinished(f.fingerprint());
            com.badlogic.gdx.Screen next = f.after(d);
            if (d < 2) { assertTrue("stage " + d + " leads to the next stage's title card", next instanceof DemoCardScreen); assertEquals(d + 1, f.resumeStage()); }
            else assertTrue("the last stage leads to the promotional screen", next instanceof DemoPromoScreen);
        }
        assertEquals("finished", 3, f.records.stage);
        assertEquals("the full game's finish castle is untouched", 10, g.tuning.finishCastle);
    }

    @Test public void t11_thePromotionalScreenSaysExactlyWhatTheOwnerWrote() throws Exception {
        DemoRecipe r = recipe();
        assertEquals("UPWARDLY", r.promoTitle);
        assertEquals("WISHLIST NOW ON STEAM", r.promoLine1);
        assertEquals("AVAILABLE NOVEMBER 2026", r.promoLine2);
        assertEquals("PLAY DEMO AGAIN", r.againButton);
        if (r.hasStoreUrl()) assertTrue("a Steam store page", r.storeUrl.startsWith("https://store.steampowered.com/app/"));
        // the official Steam logo is an owner-supplied file (Valve's branding asset); the demo never draws an imitation. When configured it must exist.
        if (!r.steamLogo.isEmpty()) assertTrue("the configured Steam logo file exists", new File("../assets/" + r.steamLogo).isFile());
    }

    @Test public void t12_stagesCannotBeChosenOrSkipped() throws Exception {
        ClimbGame g = game(tmp()); DemoFlow f = flow(g);
        DemoRecords r = f.records;
        r.stage = 2;                                                  // as if a save file were edited to jump ahead
        r.save(g.dataDir);
        assertEquals("an edited save cannot skip stages", 0, DemoRecords.load(g.dataDir).stage);
        r = DemoRecords.load(g.dataDir);
        r.stageFinished(0, 100, 1, "x", false); r.save(g.dataDir);
        assertEquals("clearing NORMAL reaches PUNISHER", 1, DemoRecords.load(g.dataDir).stage);
        java.util.Set<String> starters = new java.util.HashSet<>();
        for (java.lang.reflect.Method m : DemoFlow.class.getDeclaredMethods()) if (java.lang.reflect.Modifier.isPublic(m.getModifiers()) && m.getReturnType() == com.badlogic.gdx.Screen.class) starters.add(m.getName());
        assertEquals("the only public ways into a stage: the next one, resuming the sequence, the stage after a finish, playing it all again",
            new java.util.HashSet<>(java.util.Arrays.asList("stageScreen", "after", "resume", "playAgain")), starters);
        String settings = text("../core/src/main/java/com/hotatticgames/climbup/SettingsScreen.java");
        assertTrue("the difficulty row is never drawn in the demo", settings.contains("if (!Edition.DEMO) row(\"DIFFICULTY (NEW CLIMBS)\""));
        assertTrue("nor the world picker or the determinism check", settings.contains("if (checkRunning || Edition.DEMO) return;") && settings.contains("if (pickBusy || checkRunning || Edition.DEMO) return;"));
    }

    @Test public void t13_retriesKeepTheCurrentStage() throws Exception {
        ClimbGame g = game(tmp()); DemoFlow f = flow(g);
        DemoFlow.Stage s0 = f.stage(0);
        f.records.stageFinished(0, 100, s0.seed, s0.fingerprint, false);
        assertEquals(1, f.resumeStage());
        for (int k = 0; k < 3; k++) { ClimbGame.Run r = f.take(1); f.beginStage(1); assertNotNull(r.tower); }       // RESTART STAGE, three times
        assertEquals("restarting PUNISHER stays on PUNISHER", 1, f.resumeStage());
        assertEquals(1, g.save.difficulty);
        f.records.save(g.dataDir);
        assertEquals("leaving mid-stage and coming back: still PUNISHER", 1, DemoRecords.load(g.dataDir).stage);
    }

    @Test public void t14_nothingOutsideCastle2To3IsPlayable() throws Exception {
        ClimbGame g = game(tmp()); DemoFlow f = flow(g);
        for (int d = 0; d < 3; d++) {
            DemoCourse c = f.course(d);
            for (byte[] b : c.slices) for (Element h : SliceCodec.decode(b).c.hazards) assertFalse("stage " + d + ": no castle past 3 exists", h.type == Element.Type.GATE && h.skin > 3);
            ClimbGame.Run r = f.buildRun(d);
            int top = r.tower.sliceCount(); r.tower.requestNext(); r.tower.ensureAbove(1e9, 150f); r.tower.poll();
            assertEquals("stage " + d + ": the sealed tower makes no slice", top, r.tower.sliceCount());
            try { r.tower.extend(); fail("a sealed tower never grows"); } catch (IllegalStateException expected) { }
            // a fall below the start castle's deck ends at the stage floor: back to the checkpoint, never down into castle 1-2's section
            DemoFlow.Stage st = f.stage(d);
            Tower tw = r.tower; int i = tw.afterCastle(2);
            Sim sim = Sim.startOn(tw.world, f.simTuning(d), i); sim.checkpoint = i; sim.keysFree = false; sim.setRange(0, tw.world.size() - 1);
            tw.maintain(sim); DemoFlow.applyFloor(sim, tw, st);
            sim.mode = Sim.Mode.AIR; sim.onElem = -1; sim.y = tw.local(st.floorAbs) - 6.5f; sim.vy = -20f;
            boolean back = false;
            for (int k = 0; k < 30 && !back; k++) { sim.step(new InputState()); tw.maintain(sim); DemoFlow.applyFloor(sim, tw, st); back = (sim.consumeEvents() & Sim.EV_RESPAWN) != 0 || (sim.y > tw.local(st.floorAbs) && sim.mode != Sim.Mode.AIR); }
            assertTrue("stage " + d + ": a fall past the floor goes back to the checkpoint", back);
            assertTrue("stage " + d + ": and stands above 1,000 m again", tw.absY(sim.y) > 995.0);
        }
    }

    @Test public void t15_settingsAndCharacterCarryAcrossStagesAndPersist() throws Exception {
        ClimbGame g = game(tmp()); DemoFlow f = flow(g);
        g.settings.character = 3; g.settings.music = 4; g.settings.reducedMotion = true; g.settings.captions = true;
        for (int d = 0; d < 3; d++) f.beginStage(d);
        assertEquals("the character stays", 3, g.settings.character);
        assertEquals(4, g.settings.music); assertTrue(g.settings.reducedMotion); assertTrue(g.settings.captions);
        g.settingsStore.saveSettings(g.settings);
        Settings back = g.settingsStore.loadSettings();
        assertEquals(3, back.character); assertEquals(4, back.music); assertTrue(back.reducedMotion);
    }

    @Test public void t16_developerFeaturesAreGoneInTheDemoBuild() throws Exception {
        assumeTrue("the demo edition (the demo workflow runs this class with -Pclimb.edition=demo)", Edition.DEMO);
        System.setProperty("climb.autopilot", "true"); System.setProperty("climb.startHeight", "4000"); System.setProperty("climb.seed", "7"); System.setProperty("climb.hazard", "CANNON");
        System.setProperty("climb.start", "play"); System.setProperty("climb.demo", "true"); System.setProperty("climb.data", "/tmp/x");
        Edition.scrubLaunchOptions();
        assertNull(System.getProperty("climb.autopilot")); assertNull(System.getProperty("climb.startHeight")); assertNull(System.getProperty("climb.seed"));
        assertNull(System.getProperty("climb.hazard")); assertNull(System.getProperty("climb.start")); assertNull(System.getProperty("climb.demo"));
        assertEquals("where files go is kept", "/tmp/x", System.getProperty("climb.data")); System.clearProperty("climb.data");
        System.setProperty("climb.autopilot", "true");
        assertFalse("dev hooks read nothing in the demo", Edition.devFlag("climb.autopilot")); assertNull(Edition.devProp("climb.autopilot"));
        assertEquals(Integer.valueOf(0), Edition.devInt("climb.startHeight", 0)); System.clearProperty("climb.autopilot");
        ClimbGame g = game(tmp());
        try { g.openRun(true); fail("the demo has no free climb"); } catch (IllegalStateException expected) { }
        assertTrue("the demo's main menu", g.mainMenu() instanceof DemoTitleScreen);
        assertTrue(g.versionLabel().startsWith("DEMO "));
    }

    @Test public void t16b_theFullGameBuildIsUnchanged() throws Exception {
        assumeTrue("the full edition", !Edition.DEMO);
        System.setProperty("climb.autopilot", "true");
        assertTrue("dev hooks still work in the full game", Edition.devFlag("climb.autopilot")); System.clearProperty("climb.autopilot");
        ClimbGame g = game(tmp());
        assertEquals("NO demo stage tuning in the full game", 10, g.runTuning().finishCastle);
    }

    @Test public void t17_demoDataIsItsOwn() throws Exception {
        File dir = tmp(); ClimbGame g = game(dir); DemoFlow f = flow(g);
        f.records.stageFinished(0, 1234, f.stage(0).seed, f.stage(0).fingerprint, false); f.records.save(dir);
        assertTrue("demo.json", new File(dir, "demo.json").isFile());
        for (int d = 0; d < 3; d++) f.beginStage(d);
        assertEquals("a demo stage leaves no climb on disk to resume", 0, g.save.seed);
        assertTrue("and no record board entry: its times are the demo's own", g.save.climbNotComparable);
        assertFalse("no climb history", new File(dir, "history.bin").isFile());
        new File(dir, "demo.json").delete();
        Files.write(new File(dir, "demo.json").toPath(), "{ not json".getBytes(StandardCharsets.UTF_8));
        DemoRecords r = DemoRecords.load(dir);
        assertEquals("a damaged demo file starts afresh", 0, r.stage);
        assertTrue("and is kept aside", new File(dir, "demo.json.corrupt").isFile());
    }

    @Test public void t18_stageTimeIsSimulationStepsOnly() throws Exception {
        ClimbGame g = game(tmp()); DemoFlow f = flow(g);
        g.save.runTicks = 999; g.save.runClock = 16.65f;
        f.beginStage(1);
        assertEquals("a stage's clock starts at zero", 0, g.save.runTicks);
        for (int i = 0; i < 600; i++) RunRecord.tick(g.save, Sim.DT, true);
        assertEquals("ten seconds of steps", 600, g.save.runTicks);
        DemoFlow.Stage s = f.stage(1);
        DemoRecords.Result res = f.records.stageFinished(1, g.save.runTicks, s.seed, s.fingerprint, false);
        assertTrue(res.newBest);
        assertEquals("the record is the steps, nothing else", 600, f.records.bestFor(1, s.seed, s.fingerprint));
        assertEquals("a best from another course is not compared", 0, f.records.bestFor(1, s.seed + 1, s.fingerprint));
        assertEquals("nor one from another generator", 0, f.records.bestFor(1, s.seed, "r15-other"));
        DemoRecords.Result slower = f.records.stageFinished(1, 700, s.seed, s.fingerprint, false);
        assertFalse(slower.newBest); assertEquals(600, f.records.bestFor(1, s.seed, s.fingerprint));
        DemoRecords.Result assisted = f.records.stageFinished(1, 10, s.seed, s.fingerprint, true);
        assertFalse("an assisted time is never a best", assisted.newBest); assertEquals(600, f.records.bestFor(1, s.seed, s.fingerprint));
        assertFalse("nor counts for the whole-run best", f.records.runClean);
    }

    @Test public void t19_theFeaturedCoursesAreFixedAndCheckedAgainstTheGenerator() throws Exception {
        ClimbGame g = game(tmp()); DemoFlow f = flow(g); DemoRecipe r = f.recipe;
        String fp = f.fingerprint();
        assertFalse("every course loads: " + fp, fp.contains("INVALID"));
        java.util.Set<Long> seeds = new java.util.HashSet<>();
        for (int d = 0; d < 3; d++) {
            DemoRecipe.Stage st = r.stages.get(d);
            byte[] file = Files.readAllBytes(Paths.get("../assets/" + st.course));
            DemoCourse a = DemoCourse.load(file, r, st, f.genTuning(d)), b = DemoCourse.load(file, r, st, f.genTuning(d));
            assertEquals("stage " + d + ": the recipe pins the file", st.sha256, a.sha256);
            assertEquals(a.fingerprint(), b.fingerprint());
            assertEquals("made by this build's generation rules", CourseGenerator.RULES_VERSION, a.rules);
            seeds.add(st.seed);
            // the first slice the generator makes for the seed is byte-identical to the baked one (the whole course is re-made and compared by DemoBake verify in the demo workflow)
            Tower t0 = new Tower(st.seed, f.genTuning(d));
            assertArrayEquals("stage " + d + ": slice 0 is what the generator makes today", t0.blob(0), a.slices.get(0));
            // tampering is refused, never played
            byte[] bad = file.clone(); bad[bad.length / 2] ^= 0x40;
            try { DemoCourse.load(bad, r, st, f.genTuning(d)); fail("a changed course file is refused"); } catch (DemoCourse.Invalid expected) { }
            try { DemoCourse.load(file, r, st, f.genTuning((d + 1) % 3)); fail("another difficulty's numbers are refused"); } catch (DemoCourse.Invalid expected) { }
        }
        assertEquals("three different courses", 3, seeds.size());
    }

    @Test public void recipeRefusesAnythingButTheOwnersSequence() throws Exception {
        String ok = text("demo/recipe.json");
        DemoRecipe.parse(ok);
        for (String bad : new String[]{ok.replace("\"difficulty\": 1", "\"difficulty\": 2"), ok.replace("\"toCastle\": 3", "\"toCastle\": 5"), ok.replace("\"format\": 1", "\"format\": 9"),
                ok.replace("\"storeUrl\": \"" + recipe().storeUrl + "\"", "\"storeUrl\": \"https://example.com/x\"")}) {
            try { DemoRecipe.parse(bad); fail("refused: " + bad.substring(0, 40)); } catch (RuntimeException expected) { }
        }
    }

    @Test public void tuningStampIgnoresReflectionOrderButNotNumbers() throws Exception {
        Tuning a = TestUtil.tuning(), b = TestUtil.tuning();
        assertEquals(DemoCourse.tuningStamp(a), DemoCourse.tuningStamp(b));
        b.jumpVel += 0.001f;
        assertNotEquals(DemoCourse.tuningStamp(a), DemoCourse.tuningStamp(b));
        assertNotEquals("each difficulty has its own numbers", DemoCourse.tuningStamp(a), DemoCourse.tuningStamp(a.hardCopy(1)));
    }
}
