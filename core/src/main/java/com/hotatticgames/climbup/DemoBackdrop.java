package com.hotatticgames.climbup;

import com.hotatticgames.climbup.render.WorldRenderer;
import com.hotatticgames.climbup.sim.InputState;
import com.hotatticgames.climbup.sim.Sim;
import com.hotatticgames.climbup.sim.Tower;

/**
 * The demo menus' backdrop: the live tower of the first stage with the hero waiting just past castle 2's door, in the demo's world (like the full
 * game's title screen, which shows where the climb in progress stands). Nothing here is played: the simulation only idles the hero.
 */
final class DemoBackdrop {
    private Sim sim; private WorldRenderer world; private float time;

    /** False when the course could not be loaded (the menu then draws its plain background and says why). */
    boolean open(ClimbGame g, DemoFlow flow) {
        try {
            DemoCourse c = flow.course(0);
            Tower tw = new Tower(c.seed, flow.genTuning(0), c.slices, c.startSlice);
            tw.sealed = true;
            int idx = tw.afterCastle(flow.recipe.fromCastle);
            if (idx < 0) return false;
            sim = Sim.startOn(tw.world, g.tuning, idx);
            sim.setRange(0, tw.world.size() - 1);
            for (int i = 0; i < 30; i++) sim.step(new InputState());
            world = new WorldRenderer(g.tuning, tw.world, g.models, g.settings.quality, g.settings.character);
            world.setOrigin(tw.originY, tw.originS);
            world.zoneOverride = flow.recipe.world;
            world.reducedMotion = g.settings.reducedMotion; world.colourblind = g.settings.colourblind;
            world.snapCamera(sim);
            return true;
        } catch (RuntimeException e) { dispose(); return false; }
    }

    void resize(int w, int h) { if (world != null) world.resize(w, h); }
    void setCharacter(int c) { if (world != null) world.setCharacter(c); }

    /** Draws the backdrop (or a plain dark sky when there is none). */
    void render(float dt) {
        time += Math.min(dt, 0.1f);
        if (world != null) world.render(sim, 1f, dt, time, true);
        else { com.badlogic.gdx.Gdx.gl.glClearColor(0.10f, 0.07f, 0.16f, 1f); com.badlogic.gdx.Gdx.gl.glClear(com.badlogic.gdx.graphics.GL20.GL_COLOR_BUFFER_BIT); }
    }
    float time() { return time; }
    WorldRenderer world() { return world; }

    void dispose() { if (world != null) { world.dispose(); world = null; } }
}
