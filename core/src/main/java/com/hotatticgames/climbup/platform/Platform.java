package com.hotatticgames.climbup.platform;

import com.hotatticgames.climbup.ClimbGame;
import com.hotatticgames.climbup.SettingsScreen;
import com.hotatticgames.climbup.ui.Ui;
import java.util.Collections;
import java.util.List;

/**
 * The seam between the shared game and a launcher. Android uses {@link #MOBILE} (touch controls, OTA allowed, the "UPWARDLY"
 * title). The Windows launcher supplies its own implementation (keyboard, mouse, controllers, same UPWARDLY title, no network).
 * Nothing here touches the simulation: platforms only decide how the {@code InputState} gets filled and what is drawn around the game.
 */
public interface Platform {
    /** True on a desktop launcher: keyboard/mouse/controller input, no touch overlay. */
    boolean desktop();
    /** Whether the on-screen stick/jump/swing overlay is drawn and touch pointers steer the hero. */
    boolean touchControls();
    /** Whether the game may make any network call (the signed OTA content check). */
    boolean networkAllowed();
    /** The public-facing game name shown on the title screen, credits and About. */
    String title();
    /** The gameplay input source for desktop launchers; {@code null} means "use the original touch/keyboard path". */
    GameInput gameInput();
    /** Called once from {@code ClimbGame.create} after the shared services exist. */
    void attach(ClimbGame game);
    /** Called once per rendered frame before the active screen renders. */
    void frame(float dt);
    /** Called whenever the active screen changes. */
    void screenChanged();
    /** Replaces touch wording in tips/toasts ("TAP SWING") with the active device's prompts. */
    String tip(String text);
    /** Draws the unobtrusive control hints during play (desktop only). */
    void drawPlayHints(Ui ui, boolean carryingClub, float width, float height);
    /** Screen pixels the play-hint bar occupies from the bottom edge while it shows (0 when it does not), so bottom text keeps clear of it. */
    default float playHintsHeight() { return 0f; }
    /** Extra Settings tabs (display, HUD, graphics, controls). Empty on mobile. */
    List<SettingsTab> settingsTabs();
    /** The achievements/stats sink for this launcher. Default: a no-op (Android, headless). The Windows launcher returns a Steam-backed one. */
    default Achievements achievements() { return Achievements.NONE; }
    /** The folder a player's screenshots go to, or null for the game's data folder plus "Screenshots" (headless, Android's fallback). Desktop: the user's Pictures folder. */
    default java.io.File screenshotDir() { return null; }
    /**
     * A platform that stores screenshots itself (Android's gallery) writes the PNG and returns where it went, for the "SCREENSHOT SAVED" line; null means
     * "not handled", and the game writes the file into {@link #screenshotDir()}. Called off the render thread; may throw when the write fails.
     */
    default String saveScreenshot(String name, byte[] png) throws java.io.IOException { return null; }
    /** Releases platform resources. */
    void dispose();

    /** One extra Settings tab. */
    interface SettingsTab {
        String name();
        void draw(ClimbGame g, SettingsScreen screen);
    }

    /** The original behaviour. */
    Platform MOBILE = new Platform() {
        @Override public boolean desktop() { return false; }
        @Override public boolean touchControls() { return true; }
        @Override public boolean networkAllowed() { return true; }
        @Override public String title() { return "UPWARDLY"; }
        @Override public GameInput gameInput() { return null; }
        @Override public void attach(ClimbGame game) { }
        @Override public void frame(float dt) { }
        @Override public void screenChanged() { }
        @Override public String tip(String text) { return text; }
        @Override public void drawPlayHints(Ui ui, boolean carryingClub, float width, float height) { }
        @Override public List<SettingsTab> settingsTabs() { return Collections.emptyList(); }
        @Override public void dispose() { }
    };
}
