package com.hotatticgames.climbup.module;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.Color;
import com.hotatticgames.climbup.TitleScreen;
import com.hotatticgames.climbup.spi.HostEnv;
import com.hotatticgames.climbup.ui.Ui;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Automatic updates on the title screen. While the host is still checking the channel a short "checking" panel holds the title; when a signed update has been downloaded the panel says so,
 * the game relaunches itself and the new version is running. Only on the title screen (never during a climb), never twice for the same staged update (a marker file stops any loop), and
 * a failed or slow check never blocks play for more than a few seconds. Visual only: no game state is read or changed except the normal save before the relaunch.
 */
final class UpdateGate {
    private static final float SHOW_AFTER = 0.5f, GIVE_UP = 8f, READY_PHASE = 1.4f, RESTART_AT = 2.8f;
    private final HostEnv env; private final ModuleGame game;
    private float titleTime, stagedTime; private boolean restarted;
    private InputProcessor saved, blocker;

    UpdateGate(HostEnv env, ModuleGame game) { this.env = env; this.game = game; }

    private enum S { CHECKING, STAGED, IDLE }

    private String status() { try { String s = env.updateStatus(); return s == null ? "" : s; } catch (Throwable t) { return "-"; } }

    private S classify(String s) {
        String l = s.toLowerCase();
        if (l.contains("downloaded and applies at the next start")) return S.STAGED;
        if (l.isEmpty() || l.contains("not checked yet")) return S.CHECKING;
        return S.IDLE;
    }

    private File marker() { return new File(env.dataDir(), "autoapply.txt"); }

    private boolean alreadyTried(String s) {
        try { File f = marker(); return f.isFile() && new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).equals(s); } catch (Throwable t) { return false; }
    }

    private void remember(String s) { try { Files.write(marker().toPath(), s.getBytes(StandardCharsets.UTF_8)); } catch (Throwable ignored) { } }

    private int phase = 0;      // 0 none, 1 checking panel, 2 ready panel, 3 restarting panel
    private String version = "";

    /** Before the screen draws: decides the panel and holds the input while it is up. */
    void before(boolean onTitle, float dt) {
        phase = 0;
        if (restarted || !onTitle) { release(); if (!onTitle) { titleTime = 0; stagedTime = 0; } return; }
        titleTime += dt;
        String s = status(); S st = classify(s);
        if (st == S.STAGED && !alreadyTried(s)) {
            stagedTime += dt;
            Matcher m = Pattern.compile("[vV](\\d+)").matcher(s); version = m.find() ? m.group(1) : "";
            phase = stagedTime < READY_PHASE ? 2 : 3;
            if (stagedTime >= RESTART_AT) {
                restarted = true; remember(s);
                try { game.persist(); } catch (Throwable ignored) { }
                env.diag("auto-apply: restarting for " + s);
                HostRestart.relaunch();
            }
        } else if (st == S.CHECKING && titleTime > SHOW_AFTER && titleTime < GIVE_UP) phase = 1;
        if (phase != 0) hold(); else release();
    }

    /** After the screen drew: the panel, in the game's own look. */
    void after() {
        if (phase == 0) return;
        Ui u = game.ui; if (u == null) return;
        float w = u.w(), h = u.h(), pw = 820, ph = 250, x = w / 2f - pw / 2f, y = h / 2f - ph / 2f;
        String a, b;
        if (phase == 1) { a = "CHECKING FOR UPDATES"; b = "ONE MOMENT" + dots(); }
        else if (phase == 2) { a = version.isEmpty() ? "UPDATE DOWNLOADED" : "UPDATE V" + version + " DOWNLOADED"; b = "APPLYING UPDATE" + dots(); }
        else { a = "RESTARTING THE GAME"; b = "BACK IN A MOMENT" + dots(); }
        u.viewport.apply(); u.batch.setProjectionMatrix(u.viewport.getCamera().combined); u.batch.begin();
        u.rect(0, 0, w, h, new Color(0.02f, 0.03f, 0.08f, 0.62f));
        u.panel(x, y, pw, ph);
        u.textC(a, w / 2f, y + ph - 96, 5.2f, Ui.ACCENT);
        u.textC(b, w / 2f, y + 70, 4f, Ui.TEXT);
        u.batch.end();
    }

    private String dots() { int n = (int) (System.nanoTime() / 400_000_000L) % 4; return "...".substring(0, n); }

    private void hold() {
        try {
            if (blocker == null) { blocker = new InputAdapter(); saved = Gdx.input.getInputProcessor(); Gdx.input.setInputProcessor(blocker); }
        } catch (Throwable ignored) { }
    }

    private void release() {
        try {
            if (blocker != null) { if (Gdx.input.getInputProcessor() == blocker) Gdx.input.setInputProcessor(saved); blocker = null; saved = null; }
        } catch (Throwable ignored) { }
    }
}
