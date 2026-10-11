package com.hotatticgames.climbup.platform;

import com.hotatticgames.climbup.ClimbGame;
import com.hotatticgames.climbup.SettingsScreen;
import com.hotatticgames.climbup.ui.Ui;
import java.util.Collections;
import java.util.List;

/**
 * The seam between the shared game and a launcher. Android uses {@link #MOBILE}, whose every answer reproduces the original behaviour exactly
 * (touch controls, OTA allowed, "CLIMB UP"). The Windows launcher supplies its own implementation (keyboard, mouse, controllers, UPWARDLY title, no network).
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
    /** Draws the pause control in the HUD corner; returns false to let the shared code draw the original touch pause icon. */
    boolean drawPauseButton(Ui ui, float x, float y, float size);
    /** Extra Settings tabs (display, controls). Empty on mobile. */
    List<SettingsTab> settingsTabs();
    /** The achievements/stats sink for this launcher. Default: a no-op (Android, headless). The Windows launcher returns a Steam-backed one. */
    default Achievements achievements() { return Achievements.NONE; }
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
        @Override public String title() { return "CLIMB UP"; }
        @Override public GameInput gameInput() { return null; }
        @Override public void attach(ClimbGame game) { }
        @Override public void frame(float dt) { }
        @Override public void screenChanged() { }
        @Override public String tip(String text) { return text; }
        @Override public void drawPlayHints(Ui ui, boolean carryingClub, float width, float height) { }
        @Override public boolean drawPauseButton(Ui ui, float x, float y, float size) { return false; }
        @Override public List<SettingsTab> settingsTabs() { return Collections.emptyList(); }
        @Override public void dispose() { }
    };
}
