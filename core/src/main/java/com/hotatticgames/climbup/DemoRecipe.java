package com.hotatticgames.climbup;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The demo's recipe (assets/demo/recipe.json, docs/DEMO.md): which stages it plays, in order, on which fixed course each, where each stage begins and
 * ends, which world it is shown in, how long the stage title cards last, and every line of the closing promotional screen. Everything the demo says
 * or plays comes from here, so a later build's demo is made by changing this file (and baking its courses), never by editing code.
 */
public final class DemoRecipe {
    /** The recipe format this code reads; a recipe with another number is refused (never half-read). */
    public static final int FORMAT = 1;

    public static final class Stage {
        public final int difficulty; public final long seed; public final String title, course, sha256;
        Stage(int difficulty, long seed, String title, String course, String sha256) { this.difficulty = difficulty; this.seed = seed; this.title = title; this.course = course; this.sha256 = sha256; }
    }

    public final int recipe;                 // the recipe's own version (the demo workflow's "recipe version" input must match it)
    public final int fromCastle, toCastle;   // each stage starts just past castle fromCastle's door and ends walking through castle toCastle's
    public final int world;                  // the world every stage is SHOWN in (Palette index; 2 = Dusk Spire); generation is never changed
    public final float cardFadeIn, cardHold, cardFadeOut;
    public final List<Stage> stages;
    public final String promoTitle, promoLine1, promoLine2, storeUrl, storeButton, againButton, steamLogo, demoName;

    private DemoRecipe(JsonValue v) {
        if (v.getInt("format", -1) != FORMAT) throw new IllegalArgumentException("demo recipe format " + v.getInt("format", -1) + " (this build reads " + FORMAT + ")");
        recipe = v.getInt("recipe"); fromCastle = v.getInt("fromCastle"); toCastle = v.getInt("toCastle"); world = v.getInt("world");
        demoName = v.getString("demoName", "UPWARDLY DEMO");
        JsonValue c = v.get("titleCard");
        cardFadeIn = c.getFloat("fadeIn"); cardHold = c.getFloat("hold"); cardFadeOut = c.getFloat("fadeOut");
        List<Stage> s = new ArrayList<>();
        for (JsonValue st = v.get("stages").child; st != null; st = st.next)
            s.add(new Stage(st.getInt("difficulty"), Long.parseLong(st.getString("seed")), st.getString("title"), st.getString("course"), st.getString("sha256")));
        stages = Collections.unmodifiableList(s);
        JsonValue p = v.get("promo");
        promoTitle = p.getString("title"); promoLine1 = p.getString("line1"); promoLine2 = p.getString("line2");
        storeUrl = p.getString("storeUrl", "").trim(); storeButton = p.getString("storeButton"); againButton = p.getString("againButton");
        steamLogo = p.getString("steamLogo", "").trim();
        validate();
    }

    public static DemoRecipe parse(String json) { return new DemoRecipe(new JsonReader().parse(json)); }

    /** Refuses a recipe that could not make a complete demo: three stages in the order NORMAL, PUNISHER, NOT GONNA HAPPEN, a real section, a real world. */
    private void validate() {
        if (stages.size() != 3) throw new IllegalArgumentException("the demo plays exactly three stages, the recipe lists " + stages.size());
        for (int i = 0; i < stages.size(); i++) {
            Stage st = stages.get(i);
            if (st.difficulty != i) throw new IllegalArgumentException("stage " + (i + 1) + " must be difficulty " + i + " (NORMAL, PUNISHER, NOT GONNA HAPPEN in that order)");
            if (st.seed == 0L) throw new IllegalArgumentException("stage " + (i + 1) + " has no course seed");
            if (st.title.trim().isEmpty() || st.course.trim().isEmpty() || st.sha256.length() != 64) throw new IllegalArgumentException("stage " + (i + 1) + " is incomplete");
        }
        if (fromCastle < 1 || toCastle != fromCastle + 1) throw new IllegalArgumentException("a stage runs from one castle to the next (" + fromCastle + " -> " + toCastle + ")");
        if (world < 0 || world >= com.hotatticgames.climbup.render.Palette.ZONES) throw new IllegalArgumentException("no world " + world);
        if (!(cardFadeIn >= 0f && cardHold > 0f && cardFadeOut >= 0f)) throw new IllegalArgumentException("title card timings");
        if (promoTitle.isEmpty() || promoLine1.isEmpty() || againButton.isEmpty()) throw new IllegalArgumentException("promo text");
        if (!storeUrl.isEmpty() && !storeUrl.startsWith("https://store.steampowered.com/app/")) throw new IllegalArgumentException("the store link must be a Steam store page: " + storeUrl);
    }

    /** True when an approved Steam store page is configured: only then does the promo screen offer a store button. */
    public boolean hasStoreUrl() { return !storeUrl.isEmpty(); }
}
