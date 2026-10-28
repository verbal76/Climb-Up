package com.hotatticgames.climbup;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.Color;
import com.hotatticgames.climbup.render.WorldRenderer;
import com.hotatticgames.climbup.sim.Course;
import com.hotatticgames.climbup.sim.InputState;
import com.hotatticgames.climbup.sim.Sim;
import com.hotatticgames.climbup.sim.Tower;
import com.hotatticgames.climbup.ui.TextWrap;
import com.hotatticgames.climbup.ui.Ui;

/** Title screen: the live tower with the hero waiting at the continue point, plus original voxel title lettering. */
public final class TitleScreen extends ScreenAdapter {
    private final ClimbGame g;
    private Course course;
    private Sim sim;
    private WorldRenderer world;
    private float time;
    private boolean confirmNew;
    /** Build 64 (owner): the two approved taglines, shown turn and turn about each time the title screen comes up (never rotated during play). */
    static final String[] TAGLINES = {"TEN TOWERS. ONE CLOCK.", "TRAMPOLINES, CRABS AND CANNONS. YOU'RE WELCOME."};
    static String taglineFor(int visit) { return TAGLINES[Math.floorMod(visit, TAGLINES.length)]; }
    private static int visits = -1;         // how many times the title screen has been shown this session (the first visit shows the first tagline)
    private String lastBub;
    private com.badlogic.gdx.Screen next;   // screen change is applied after ui.end() so the sprite batch is never left open

    public TitleScreen(ClimbGame g) { this.g = g; }

    private boolean has;      // a climb in progress that can really be resumed

    @Override public void show() {
        g.prepareNextClimb();                       // a new climb started from here begins at once (Build 71)
        confirmNew = false; showMore = false; visits++;
        Tower tw = null; int idx = 0;
        has = g.climbValid();
        if (has) {        // backdrop: where the climb in progress stands
            try {
                tw = new Tower(g.save.seed, g.runTuning(), g.history.read(g.save.seed), g.save.cpSlice);
                idx = tw.worldIndex(new Tower.Ref(g.save.cpSlice, g.save.cpLocal));
                if (idx < 0) { tw = null; idx = 0; }
            } catch (Exception e) { tw = null; idx = 0; }
        }
        if (tw == null) { has = false; tw = new Tower(2024L, g.tuning); }
        course = tw.world;
        sim = Sim.startOn(course, g.tuning, idx);
        sim.setRange(0, course.size() - 1);
        for (int i = 0; i < 30; i++) sim.step(new InputState());
        world = new WorldRenderer(g.tuning, course, g.models, g.settings.quality, g.settings.character);
        world.setOrigin(tw.originY, tw.originS);
        world.reducedMotion = g.settings.reducedMotion; world.colourblind = g.settings.colourblind;
        world.snapCamera(sim);
        Gdx.input.setInputProcessor(new InputMultiplexer(g.ui));
        g.audio.music("music_menu");
    }

    @Override public void resize(int w, int h) { g.ui.resize(w, h); if (world != null) world.resize(w, h); }

    private float noticeT;
    @Override public void render(float dt) {
        time += Math.min(dt, 0.1f);
        g.models.preload(4f);                       // Build 71: whatever the splash did not get to, a little each frame (a no-op once all are loaded)
        world.render(sim, 1f, dt, time, true);
        Ui ui = g.ui; ui.begin();
        float W = ui.w(), H = ui.h();
        // title lettering: chunky two-layer voxel look
        float px = 15f;
        String t1 = g.platform.title();
        float tw = ui.font.width(t1, px);
        float tx = W / 2 - tw / 2, ty = H - 190 + (float) Math.sin(time * 1.6f) * 4f;
        ui.font.drawShadow(ui.batch, t1, tx + 6, ty - 8, px, new Color(0.35f, 0.12f, 0.02f, 1f), new Color(0, 0, 0, 0.6f));
        ui.font.drawShadow(ui.batch, t1, tx, ty, px, new Color(1f, 0.74f, 0.16f, 1f), new Color(0.2f, 0.07f, 0.0f, 0.9f));
        String tagline = taglineFor(visits);
        ui.textC(tagline, W / 2, ty - 56, Math.min(3.4f, (W - 40f) / (tagline.length() * 6f)), Ui.TEXT);      // Build 64: one of the two approved lines, alternating per visit; shrinks on a narrow screen rather than clip
        String bub = world.heroBubble();
        if (bub != null && !bub.equals(lastBub)) g.audio.play("talk", 0.4f, com.hotatticgames.climbup.render.Characters.voice(g.settings.character));
        lastBub = bub;
        world.heroHeadScreen(W, H, headPos);       // where his head is: the speech bubble sits above it, and the buttons stay out of its way
        float headY = headPos[1] > 0f && headPos[1] < H ? headPos[1] : TitleLayout.typicalHeadY(H);
        Item[] items = menu(has);
        TitleLayout lay = layoutFor(ui, W, H, items.length, headY);       // MORE and EXIT share the last row (TitleLayout pairLast)
        if (bub != null) drawBubble(ui, bub, headPos[0], headPos[1] + 6, world.heroBubbleAlpha(), lay.bubbleMinX);
        boolean notice = g.save.noticeOldClimb;        // the one-time notice that an old climb could not carry over comes first
        boolean prompt = notice || confirmNew || showMore;   // a dialog or the MORE panel hides the menu behind it
        if (!prompt) {
            for (int i = 0; i < items.length; i++) {          // reading order: the Windows keyboard/pad walks the buttons in this order
                TitleLayout.Rect r = lay.menu[i];
                if (ui.button(items[i].label(has), r.x, r.y, r.w, r.h, i == 0)) { g.audio.play("click"); choose(items[i]); }
            }
        }
        // status bar: who is playing and as which hero (the old PLAYING AS plate and HERO button on the right are gone; the hero picker lives in MORE), best height, best finish
        String who = g.save.name.isEmpty() ? Profiles.FIRST_NAME : g.save.name;
        String stat = who + " (" + com.hotatticgames.climbup.render.Characters.name(g.settings.character) + ")   BEST " + (int) g.save.bestHeight + " M" + (g.save.bestFinish > 0f ? "   BEST FINISH " + PlayScreen.fmtTime(g.save.bestFinish) : "");
        ui.rect(0, 0, W, 34, new Color(0.05f, 0.07f, 0.14f, 0.7f)); ui.text(stat, 72, 9, 2.8f, Ui.TEXT);          // thin bar; text kept 72 px from the edges (rounded phone corners clip anything closer)
        String ver = g.versionLabel();          // always visible: which build is this?
        ui.text(ver, W - 72 - ui.font.width(ver, 2.8f), 9, 2.8f, Ui.ACCENT);
        if (notice) legacyPrompt(ui, W, H); else if (confirmNew) newRunPrompt(ui, W, H); else if (showMore) morePanel(ui, W, H);
        if (g.saveNotice != null) {          // (R6: a refused course code says why, once, for a few seconds)
            noticeT += dt; ui.textC(g.saveNotice, W / 2, 52, 3.2f, Ui.ACCENT);
            if (noticeT > 6f) { g.saveNotice = null; noticeT = 0f; }
        }
        ui.end();
        g.autoShot("title", dt);
        if (exitRequested) { exitRequested = false; g.persist(); Gdx.app.exit(); return; }       // EXIT: everything is already saved; persist once more and close the game
        if (next != null) { Screen n = next; next = null; g.setScreen(n); }
    }

    /** The main menu: one column, top first. The first one is the big accent button; MORE opens the panel with everything else, and EXIT (Build 67, owner: not hidden under MORE) sits beside it on the last row. */
    enum Item {
        PLAY, NEW_RUN, SETTINGS, MORE, EXIT;
        String label(boolean hasClimb) {
            switch (this) {
                case PLAY: return hasClimb ? "CONTINUE" : "PLAY";
                case NEW_RUN: return "NEW RUN";
                case SETTINGS: return "SETTINGS";
                case MORE: return "MORE";
                default: return "EXIT";
            }
        }
    }
    private static final Item[] WITH_CLIMB = Item.values(), NO_CLIMB = Item.values();
    static Item[] menu(boolean hasClimb) { return hasClimb ? WITH_CLIMB : NO_CLIMB; }      // Build 64: NEW RUN is always there (RANDOM RUN or a typed course code); PLAY alone starts a random climb when there is none

    /** The MORE panel's buttons in reading order (two columns). The hero picker cycles in place; LEGACY RUNS only exists when there are old climbs. EXIT left for the title in Build 67. */
    enum More {
        PLAYERS, HERO, TIMES, LEGACY, CREDITS;
        String label(int character, int legacyRuns) {
            switch (this) {
                case PLAYERS: return "PLAYERS";
                case HERO: return "HERO: " + com.hotatticgames.climbup.render.Characters.name(character);
                case TIMES: return "TIMES";
                case LEGACY: return "LEGACY RUNS (" + legacyRuns + ")";
                default: return "CREDITS";
            }
        }
    }
    private static final More[] MORE_ALL = More.values(), MORE_NO_LEGACY = {More.PLAYERS, More.HERO, More.TIMES, More.CREDITS};
    static More[] more(boolean hasLegacy) { return hasLegacy ? MORE_ALL : MORE_NO_LEGACY; }

    private boolean exitRequested, showMore;
    private void choose(Item it) {
        switch (it) {
            case PLAY: next = new PlayScreen(g, false); break;
            case NEW_RUN: confirmNew = true; break;                 // replaces the saved climb: asks first
            case SETTINGS: next = new SettingsScreen(g, this); break;
            case MORE: showMore = true; break;
            default: exitRequested = true;                          // EXIT: the game saves once more and closes (render() does it after the batch has ended)
        }
    }
    private void chooseMore(More it) {
        switch (it) {
            case PLAYERS: showMore = false; next = new PlayersScreen(g, this); break;
            case HERO:                                                 // cycles in place; the hero on the title changes at once
                g.settings.character = com.hotatticgames.climbup.render.Characters.next(g.settings.character);
                world.setCharacter(g.settings.character); g.persist(); break;
            case TIMES: showMore = false; next = new TimesScreen(g, this); break;          // the speed-run times table (from the records feature)
            case LEGACY: showMore = false; next = new LegacyScreen(g, this); break;
            default: showMore = false; next = new CreditsScreen(g, this); break;
        }
    }

    /** The MORE panel over the dimmed title: PLAYERS, the hero picker, TIMES, LEGACY RUNS (when there are any) and CREDITS, with BACK (the pad's back button, Esc) closing it. */
    private void morePanel(Ui ui, float W, float H) {
        ui.rect(0, 0, W, H, new Color(0f, 0f, 0.05f, 0.72f));
        More[] items = more(!g.save.legacy.isEmpty());
        TitleLayout.More m = TitleLayout.more(W, H, items.length, Dp.unitsPerDp(ui));
        ui.panel(m.panel.x, m.panel.y, m.panel.w, m.panel.h);
        float t = Math.min(4.6f * Math.min(ui.tm(), 1.3f), TitleLayout.MORE_TITLE_H / 7f);
        ui.textC("MORE", W / 2, m.panel.top() - TitleLayout.MORE_PAD - ui.font.height(t), t, Ui.ACCENT);
        for (int i = 0; i < items.length; i++) {          // reading order: the Windows keyboard/pad walks the buttons in this order
            TitleLayout.Rect r = m.buttons[i];
            if (ui.button(items[i].label(g.settings.character, g.save.legacy.size()), r.x, r.y, r.w, r.h)) { g.audio.play("click"); chooseMore(items[i]); }
        }
        if (ui.button("BACK", m.back.x, m.back.y, m.back.w, m.back.h, true)) { g.audio.play("click"); showMore = false; }
    }

    private final float[] headPos = new float[2];
    private TitleLayout lay; private float layW, layH, layUpd, layHead; private int layN;
    /** The layout for this screen shape; button heights come from the real screen density so every button is at least 48dp (see {@link TitleLayout}). */
    private TitleLayout layoutFor(Ui ui, float W, float H, int n, float headY) {
        float upd = Dp.unitsPerDp(ui);
        if (lay == null || W != layW || H != layH || n != layN || upd != layUpd || Math.abs(headY - layHead) > 0.5f) {
            lay = TitleLayout.of(W, H, n, upd, headY, true); layW = W; layH = H; layN = n; layUpd = upd; layHead = headY;
        }
        return lay;
    }

    /** The hero's speech bubble: like {@code Ui.bubble}, but its body never starts left of {@code minX}, so a long line is not hidden behind the menu (the tail still points at his head). */
    private static void drawBubble(Ui ui, String s, float cx, float y, float alpha, float minX) {
        float px = 3.2f * ui.tm(), w = ui.font.width(s, px) + 36, h = ui.font.height(px) + 28;
        float x = TitleLayout.bubbleX(ui.w(), w, cx, minX);
        Color edge = new Color(0.08f, 0.08f, 0.14f, alpha), fill = new Color(1f, 1f, 1f, alpha);
        ui.rect(x - 4, y - 4, w + 8, h + 8, edge); ui.rect(x, y, w, h, fill);
        ui.rect(cx - 8, y - 14, 16, 14, edge); ui.rect(cx - 4, y - 8, 8, 10, fill);     // tail pointing down at him
        ui.font.draw2(ui.batch, s, x + 18, y + 14, px, new Color(0.08f, 0.08f, 0.14f, alpha));
    }

    /**
     * NEW RUN (Build 64): RANDOM RUN draws a fresh course, SEEDED RUN asks for a friend's course code. Over a saved climb nothing is lost unless one of them is chosen;
     * CANCEL (the highlighted button) leaves the saved climb exactly as it was.
     */
    private void newRunPrompt(Ui ui, float W, float H) {
        ui.rect(0, 0, W, H, new Color(0f, 0f, 0.05f, 0.72f));
        float scale = Math.min(ui.tm(), 1.3f), pw = Math.min(W - 60, 880), bh = Dp.touchHeight(84f, 110f, 110f, Dp.unitsPerDp(ui)), ph = Math.min(H - 40, 300 * Math.max(1f, scale * 0.9f) + 2 * bh), bw = Math.min(380, (pw - 90) / 2);
        float px = W / 2 - pw / 2, py = Math.max(20, H / 2 - ph / 2);
        ui.panel(px, py, pw, ph);
        float t = 4.6f * scale, b = 3.2f * scale;
        String l1 = has ? "YOUR SAVED CLIMB (" + (int) g.save.climbHeight + " M) WILL BE REPLACED." : "A RANDOM COURSE, OR A FRIEND'S COURSE CODE.";
        java.util.List<String> body = TextWrap.wrap(l1, str -> ui.font.width(str, b), pw - 80);
        ui.textC("START A NEW RUN?", W / 2, py + ph - 40 - ui.font.height(t), t, Ui.ACCENT);
        float y = py + ph - 40 - ui.font.height(t) - 40;
        for (String line : body) { y -= ui.font.height(b); ui.textC(line, W / 2, y, b, Ui.TEXT); y -= 14; }
        float row = py + 40 + bh + 18;
        if (ui.button("RANDOM RUN", W / 2 - bw - 14, row, bw, bh)) { confirmNew = false; startRun(null); }
        if (ui.button("SEEDED RUN", W / 2 + 14, row, bw, bh)) { confirmNew = false; g.audio.play("click"); next = new SeedScreen(g, this); }
        if (ui.button("CANCEL", W / 2 - bw / 2, py + 40, bw, bh, true)) { g.audio.play("click"); confirmNew = false; }
    }

    /** Starts a fresh climb: a random course ({@code seed} null), or the course behind a typed code. The saved climb, if any, is forgotten first. */
    void startRun(Long seed) { g.beginFreshRun(seed); g.audio.play("click"); next = new PlayScreen(g, false); }

    private static final String[] NOTICE_A = {"THIS UPDATE CHANGED HOW A CLIMB IS STORED, SO YOUR", "UNFINISHED CLIMB COULD NOT CARRY OVER."};
    private static final String[] NOTICE_B = {"IT IS KEPT AS A LEGACY RUN. YOUR RECORDS AND SETTINGS ARE SAFE.", "YOUR NEXT CLIMB STARTS FRESH."};

    private static java.util.List<String> wrapAll(String[] src, java.util.function.ToDoubleFunction<String> width, float maxW) {
        java.util.List<String> out = new java.util.ArrayList<>(); for (String s : src) out.addAll(TextWrap.wrap(s, width, maxW)); return out;
    }

    /**
     * One-time notice after the save format change: the old climb could not carry over. The panel is as wide as the notice's own lines need (the wide layout), up to the screen;
     * a line is only wrapped when it still does not fit (large text sizes, narrow screens), and the text shrinks a step at a time if the panel would not fit the screen height.
     */
    private void legacyPrompt(Ui ui, float W, float H) {
        ui.rect(0, 0, W, H, new Color(0f, 0f, 0.05f, 0.72f));
        float pad = 44, bw = 360, bh = Dp.touchHeight(80f, 110f, 110f, Dp.unitsPerDp(ui)), scale = ui.tm(), titlePx = 4.6f, body1 = 3.2f, body2 = 3f;
        float pw, ph; java.util.List<String> a, b;
        while (true) {
            float t = titlePx * Math.min(scale, 1.3f), p1 = body1 * scale, p2 = body2 * scale;
            float need = ui.font.width("NEW VERSION, NEW TOWER", t);
            for (String l : NOTICE_A) need = Math.max(need, ui.font.width(l, p1));
            for (String l : NOTICE_B) need = Math.max(need, ui.font.width(l, p2));
            pw = Math.max(Math.min(W - 60, 900), Math.min(W - 60, need + 2 * pad)); float maxW = pw - 2 * pad;
            a = wrapAll(NOTICE_A, str -> ui.font.width(str, p1), maxW); b = wrapAll(NOTICE_B, str -> ui.font.width(str, p2), maxW);
            ph = 40 + ui.font.height(t) + 34 + a.size() * (ui.font.height(p1) + 14) + 22 + b.size() * (ui.font.height(p2) + 14) + 30 + bh + 40;
            if (ph <= H - 40 || scale <= 0.7f) break;
            scale -= 0.1f;
        }
        float t = titlePx * Math.min(scale, 1.3f), p1 = body1 * scale, p2 = body2 * scale;
        if (ui.font.width("NEW VERSION, NEW TOWER", t) > pw - 2 * pad) t = Math.max(2f, t * (pw - 2 * pad) / ui.font.width("NEW VERSION, NEW TOWER", t));
        float px = W / 2 - pw / 2, py = Math.max(20, H / 2 - ph / 2);
        ui.panel(px, py, pw, ph);
        float cx = W / 2, top = py + ph - 40, y = top - ui.font.height(t);
        ui.textC("NEW VERSION, NEW TOWER", cx, y, t, Ui.ACCENT);
        top = y - 34;
        for (String line : a) { y = top - ui.font.height(p1); ui.textC(line, cx, y, p1, Ui.TEXT); top = y - 14; }
        top -= 22;
        for (String line : b) { y = top - ui.font.height(p2); ui.textC(line, cx, y, p2, Ui.DIM); top = y - 14; }
        if (ui.button("OK", cx - bw / 2, py + 40, bw, bh, true)) { g.audio.play("click"); g.save.noticeOldClimb = false; g.persist(); }
    }

    @Override public void hide() { dispose(); }
    @Override public void dispose() { if (world != null) { world.dispose(); world = null; } }
}
