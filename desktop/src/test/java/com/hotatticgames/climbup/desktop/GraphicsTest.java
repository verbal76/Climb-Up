package com.hotatticgames.climbup.desktop;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.desktop.GraphicsProfile.Tier;
import com.hotatticgames.climbup.desktop.input.DesktopConfig;
import com.hotatticgames.climbup.desktop.input.DesktopConfig.DisplayMode;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GraphicsTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    // ---------------------------------------------------------------- first-run quality decision

    @Test public void graphicsChipsAreSortedIntoTiers() {
        assertEquals(Tier.STRONG, GraphicsProfile.tierOf("NVIDIA GeForce RTX 4070/PCIe/SSE2", -1));
        assertEquals(Tier.MIDDLE, GraphicsProfile.tierOf("NVIDIA GeForce GTX 1650/PCIe/SSE2", -1));
        assertEquals(Tier.INTEGRATED, GraphicsProfile.tierOf("NVIDIA GeForce MX150/PCIe/SSE2", -1));
        assertEquals(Tier.INTEGRATED, GraphicsProfile.tierOf("Intel(R) UHD Graphics 620", -1));
        assertEquals(Tier.INTEGRATED, GraphicsProfile.tierOf("Intel(R) Iris(R) Xe Graphics", -1));
        assertEquals(Tier.MIDDLE, GraphicsProfile.tierOf("Intel(R) Arc(TM) A770 Graphics", -1));
        assertEquals(Tier.MIDDLE, GraphicsProfile.tierOf("AMD Radeon RX 580 Series", -1));
        assertEquals(Tier.STRONG, GraphicsProfile.tierOf("AMD Radeon RX 7800 XT", -1));
        assertEquals(Tier.INTEGRATED, GraphicsProfile.tierOf("AMD Radeon(TM) Graphics", -1));
        assertEquals(Tier.INTEGRATED, GraphicsProfile.tierOf("AMD Radeon(TM) Vega 8 Graphics", -1));
        assertEquals(Tier.SOFTWARE, GraphicsProfile.tierOf("llvmpipe (LLVM 15.0.7, 256 bits)", -1));
        assertEquals(Tier.SOFTWARE, GraphicsProfile.tierOf("GDI Generic", -1));
        assertEquals(Tier.INTEGRATED, GraphicsProfile.tierOf("", -1));
        assertEquals(Tier.INTEGRATED, GraphicsProfile.tierOf(null, -1));
    }

    @Test public void littleVideoMemoryPullsAChipDown() {
        assertEquals(Tier.INTEGRATED, GraphicsProfile.tierOf("NVIDIA GeForce GTX 1650", 1024));
        assertEquals(Tier.MIDDLE, GraphicsProfile.tierOf("NVIDIA GeForce RTX 4070", 2048));
        assertEquals(Tier.STRONG, GraphicsProfile.tierOf("NVIDIA GeForce RTX 4070", 12288));
    }

    @Test public void aStrongCardOnATvKeepsFullSharpness() {
        GraphicsProfile.Decision d = GraphicsProfile.decide("NVIDIA GeForce RTX 3080", 10240, 3840, 2160, 60);
        assertEquals(2, d.quality); assertEquals(100, d.renderScale); assertTrue(d.skyEffects);
        assertEquals(4, d.aa); assertEquals(16, d.anisotropy); assertEquals(0, d.frameLimit);
    }

    @Test public void aWeakChipGetsLowerDefaultsAndTheBiggerTheScreenTheLowerTheScale() {
        GraphicsProfile.Decision a = GraphicsProfile.decide("Intel(R) UHD Graphics 620", -1, 1366, 768, 60);
        GraphicsProfile.Decision b = GraphicsProfile.decide("Intel(R) UHD Graphics 620", -1, 1920, 1080, 60);
        GraphicsProfile.Decision c = GraphicsProfile.decide("Intel(R) UHD Graphics 620", -1, 3840, 2160, 60);
        assertEquals(100, a.renderScale); assertEquals(75, b.renderScale); assertEquals(50, c.renderScale);
        assertEquals(0, b.quality); assertFalse(b.skyEffects);
        assertTrue(c.aa < 4);
        GraphicsProfile.Decision soft = GraphicsProfile.decide("llvmpipe", -1, 1280, 720, 60);
        assertEquals(50, soft.renderScale); assertEquals(0, soft.aa); assertEquals(30, soft.frameLimit);
        assertTrue(soft.toString().contains("llvmpipe"));
    }

    @Test public void everyDecisionUsesOnlyListedChoices() {
        String[] chips = {"RTX 4090", "GTX 1050", "Intel UHD", "llvmpipe", "Radeon RX 6600", "Apple M1", "", "Some New Chip"};
        int[][] screens = {{1280, 720}, {1920, 1080}, {2560, 1440}, {3440, 1440}, {3840, 2160}};
        for (String chip : chips) for (int[] s : screens) for (long vram : new long[]{-1, 1024, 8192}) {
            GraphicsProfile.Decision d = GraphicsProfile.decide(chip, vram, s[0], s[1], 60);
            assertTrue(contains(DesktopConfig.SCALES, d.renderScale)); assertTrue(contains(DesktopConfig.AA_LEVELS, d.aa)); assertTrue(contains(DesktopConfig.SHARPNESS, d.anisotropy));
            assertTrue(contains(DesktopConfig.FRAME_LIMITS, d.frameLimit)); assertTrue(d.quality >= 0 && d.quality <= 2);
        }
    }

    private static boolean contains(int[] a, int v) { for (int x : a) if (x == v) return true; return false; }

    @Test public void aLargeScreenStartsFullScreenAndASmallOneInAWindow() {
        assertEquals(DisplayMode.FULLSCREEN, GraphicsProfile.defaultDisplay(3840, 2160));
        assertEquals(DisplayMode.FULLSCREEN, GraphicsProfile.defaultDisplay(1920, 1080));
        assertEquals(DisplayMode.WINDOWED, GraphicsProfile.defaultDisplay(1366, 768));
    }

    @Test public void vsyncPacesFramesSoALimitAtOrAboveTheMonitorAsksForNoExtraCap() {
        assertEquals(0, GraphicsProfile.effectiveFrameLimit(true, 0, 60));
        assertEquals(0, GraphicsProfile.effectiveFrameLimit(true, 144, 144));
        assertEquals(60, GraphicsProfile.effectiveFrameLimit(true, 60, 144));
        assertEquals(144, GraphicsProfile.effectiveFrameLimit(false, 144, 60));
        assertEquals(0, GraphicsProfile.effectiveFrameLimit(false, 0, 60));
    }

    // ---------------------------------------------------------------- GPU preference command (never executed here)

    @Test public void gpuPreferenceCommandsAreExactlyWhatWindowsExpects() {
        String exe = "C:\\Games\\Upwardly\\Upwardly.exe";
        assertEquals(Arrays.asList("reg", "add", "HKCU\\Software\\Microsoft\\DirectX\\UserGpuPreferences", "/v", exe, "/t", "REG_SZ", "/d", "GpuPreference=2;", "/f"), GpuPreference.addCommand(exe));
        assertEquals(Arrays.asList("reg", "delete", "HKCU\\Software\\Microsoft\\DirectX\\UserGpuPreferences", "/v", exe, "/f"), GpuPreference.deleteCommand(exe));
        assertEquals(Arrays.asList("reg", "query", "HKCU\\Software\\Microsoft\\DirectX\\UserGpuPreferences", "/v", exe), GpuPreference.queryCommand(exe));
    }

    @Test public void onlyThePackagedExeGetsAPreference() {
        assertTrue(GpuPreference.isGameExe("C:\\Games\\Upwardly\\Upwardly.exe"));
        assertTrue(GpuPreference.isGameExe("D:/x/upwardly.EXE"));
        assertFalse(GpuPreference.isGameExe("C:\\Program Files\\Java\\bin\\javaw.exe"));
        assertFalse(GpuPreference.isGameExe(null));
        assertFalse(GpuPreference.isGameExe("/usr/bin/java"));
    }

    @Test public void queryOutputIsRecognised() {
        assertTrue(GpuPreference.isHigh("\nHKEY_CURRENT_USER\\Software\\Microsoft\\DirectX\\UserGpuPreferences\n    C:\\a\\Upwardly.exe    REG_SZ    GpuPreference=2;\n\n"));
        assertFalse(GpuPreference.isHigh("    C:\\a\\Upwardly.exe    REG_SZ    GpuPreference=1;"));
        assertFalse(GpuPreference.isHigh(""));
        assertFalse(GpuPreference.isHigh(null));
    }

    @Test public void skippedOffWindowsAndWithoutAnExe() {
        assertEquals(GpuPreference.Result.SKIPPED, GpuPreference.apply("C:\\a\\Upwardly.exe", true, false));
        assertEquals(GpuPreference.Result.SKIPPED, GpuPreference.apply(null, true, true));
    }

    // ---------------------------------------------------------------- display modes and the resolution list

    private static List<int[]> modes(int[]... m) { return new ArrayList<>(Arrays.asList(m)); }

    @Test public void pickerListKeepsTheHighestRefreshPerSizeLargestFirst() {
        List<int[]> r = DisplayManager.pickerList(modes(new int[]{1920, 1080, 60}, new int[]{1920, 1080, 144}, new int[]{1920, 1080, 120}, new int[]{3840, 2160, 60}, new int[]{1280, 720, 60}, new int[]{640, 480, 60}, new int[]{1920, 1200, 60}));
        assertEquals(4, r.size());
        assertArrayEquals(new int[]{3840, 2160, 60}, r.get(0));
        assertArrayEquals(new int[]{1920, 1200, 60}, r.get(1));
        assertArrayEquals(new int[]{1920, 1080, 144}, r.get(2));
        assertArrayEquals(new int[]{1280, 720, 60}, r.get(3));
    }

    @Test public void anEmptyOrTinyMonitorListStillGivesAChoice() {
        assertEquals(1, DisplayManager.pickerList(new ArrayList<>()).size());
        List<int[]> r = DisplayManager.pickerList(modes(new int[]{800, 600, 60}));
        assertArrayEquals(new int[]{1280, 720, 60}, r.get(0));
    }

    @Test public void aSavedFullScreenSizeThatIsGoneFallsBackToNative() {
        List<int[]> list = DisplayManager.pickerList(modes(new int[]{3840, 2160, 60}, new int[]{1920, 1080, 120}));
        int[] nat = {3840, 2160, 60};
        assertArrayEquals(new int[]{1920, 1080, 120}, DisplayManager.fullscreenMode(list, 1920, 1080, nat));
        assertArrayEquals(nat, DisplayManager.fullscreenMode(list, 2560, 1440, nat));
        assertArrayEquals(nat, DisplayManager.fullscreenMode(list, 0, 0, nat));
        assertTrue(DisplayManager.listed(list, 1920, 1080)); assertFalse(DisplayManager.listed(list, 2560, 1440));
    }

    @Test public void pickerStepsThroughAutoThenEverySizeAndStopsAtTheEnds() {
        List<int[]> list = DisplayManager.pickerList(modes(new int[]{3840, 2160, 60}, new int[]{1920, 1080, 60}, new int[]{1280, 720, 60}));
        assertEquals("NATIVE (AUTO)", DisplayManager.pickLabel(0, 0));
        assertEquals("1920 X 1080", DisplayManager.pickLabel(1920, 1080));
        int[] cur = {0, 0};
        int[][] seen = new int[4][];
        for (int i = 0; i < 4; i++) { seen[i] = cur; cur = DisplayManager.step(list, true, cur[0], cur[1], false); }
        assertArrayEquals(new int[]{0, 0}, seen[0]); assertArrayEquals(new int[]{3840, 2160}, seen[1]); assertArrayEquals(new int[]{1920, 1080}, seen[2]); assertArrayEquals(new int[]{1280, 720}, seen[3]);
        assertArrayEquals(new int[]{1280, 720}, cur);                                                      // already the smallest: stays
        assertArrayEquals(new int[]{0, 0}, DisplayManager.step(list, true, 0, 0, true));                   // already auto: stays
        assertArrayEquals(new int[]{3840, 2160}, DisplayManager.step(list, false, 3840, 2160, true));      // a window has no auto entry
        assertArrayEquals(new int[]{1920, 1080}, DisplayManager.step(list, false, 1900, 1000, false));     // a dragged custom size moves to the nearest listed one
    }

    @Test public void windowSizesAreSaneWhenLeavingFullScreen() {
        assertArrayEquals(new int[]{1280, 720}, DisplayManager.windowSize(3840, 2160, 3840, 2160));       // would cover the whole desktop
        assertArrayEquals(new int[]{1600, 900}, DisplayManager.windowSize(1600, 900, 3840, 2160));
        assertArrayEquals(new int[]{1280, 720}, DisplayManager.windowSize(100, 50, 1920, 1080));
        assertArrayEquals(new int[]{1024, 600}, DisplayManager.windowSize(1280, 720, 1024, 600));         // a tiny desktop clamps
    }

    @Test public void borderlessDrawsAtThePickedSizeButNeverLargerThanTheScreen() {
        assertEquals(1f, DisplayManager.borderlessScale(0, 0, 3840, 2160), 1e-6f);
        assertEquals(0.5f, DisplayManager.borderlessScale(1920, 1080, 3840, 2160), 1e-6f);
        assertEquals(1f, DisplayManager.borderlessScale(3840, 2160, 1920, 1080), 1e-6f);
    }

    @Test public void f11GoesToTheLastFullScreenKindAndBackToAWindow() {
        DesktopConfig c = new DesktopConfig();
        assertEquals(DisplayMode.FULLSCREEN, DisplayManager.toggled(c));
        c.lastFull = DisplayMode.BORDERLESS;
        assertEquals(DisplayMode.BORDERLESS, DisplayManager.toggled(c));
        c.display = DisplayMode.BORDERLESS;
        assertEquals(DisplayMode.WINDOWED, DisplayManager.toggled(c));
        c.display = DisplayMode.FULLSCREEN;
        assertEquals(DisplayMode.WINDOWED, DisplayManager.toggled(c));
    }

    // ---------------------------------------------------------------- persistence

    @Test public void graphicsSettingsSurviveARestart() {
        File f = new File(tmp.getRoot(), "upwardly-desktop.cfg");
        DesktopConfig c = new DesktopConfig();
        c.display = DisplayMode.BORDERLESS; c.lastFull = DisplayMode.BORDERLESS; c.modeW = 2560; c.modeH = 1440;
        c.renderScale = 75; c.frameLimit = 144; c.vsync = false; c.skyEffects = false; c.highPerfGpu = false;
        c.aa = 8; c.anisotropy = 16; c.graphicsDecided = true;
        assertTrue(c.save(f));
        DesktopConfig r = DesktopConfig.load(f);
        assertEquals(DisplayMode.BORDERLESS, r.display); assertEquals(DisplayMode.BORDERLESS, r.lastFull);
        assertEquals(2560, r.modeW); assertEquals(1440, r.modeH);
        assertEquals(75, r.renderScale); assertEquals(144, r.frameLimit); assertFalse(r.vsync); assertFalse(r.skyEffects); assertFalse(r.highPerfGpu);
        assertEquals(8, r.aa); assertEquals(16, r.anisotropy); assertTrue(r.graphicsDecided);
    }

    @Test public void freshConfigDefaultsAreFullQualityAndFasterGpuOn() {
        DesktopConfig c = new DesktopConfig();
        assertEquals(100, c.renderScale); assertEquals(0, c.frameLimit); assertTrue(c.vsync); assertTrue(c.skyEffects); assertTrue(c.highPerfGpu);
        assertEquals(0, c.modeW); assertEquals(0, c.modeH); assertFalse(c.graphicsDecided); assertEquals(4, c.aa);
        DesktopConfig r = DesktopConfig.parse("version=1\ninputMode=AUTO\n");          // an older file without any graphics keys
        assertEquals(100, r.renderScale); assertTrue(r.highPerfGpu); assertFalse(r.graphicsDecided);
    }

    @Test public void handEditedGraphicsValuesAreSnappedOrIgnored() {
        DesktopConfig r = DesktopConfig.parse("version=1\ngfx.scale=80\ngfx.fps=100\ngfx.aa=3\ngfx.aniso=99\ngfx.vsync=maybe\ngfx.mode=10x10\ngfx.lastfull=WINDOWED\ngfx.sky=false\n");
        assertEquals(75, r.renderScale); assertEquals(120, r.frameLimit); assertEquals(2, r.aa); assertEquals(16, r.anisotropy);
        assertTrue(r.vsync);                                    // "maybe" is not a value: the default stays
        assertEquals(0, r.modeW);                               // a size too small to be a real mode is ignored
        assertEquals(DisplayMode.FULLSCREEN, r.lastFull);       // a window is never "the last full-screen kind"
        assertFalse(r.skyEffects);
    }
}
