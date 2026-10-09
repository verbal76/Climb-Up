package com.hotatticgames.climbup;

import com.hotatticgames.climbup.sim.Course;
import com.hotatticgames.climbup.sim.CourseGenerator;
import com.hotatticgames.climbup.sim.Element;
import com.hotatticgames.climbup.sim.Sim;
import com.hotatticgames.climbup.sim.Tuning;

/**
 * What a resumed climb needs beyond geometry. The save keeps only the slice of the last checkpoint, so the keys carried and the castles already opened must be saved too,
 * and a key that lay in a section older than the stored slice (which no longer exists after a restart) must never leave the player stranded in front of a closed castle.
 */
public final class ResumeState {
    private ResumeState() {}

    /** Records the live state into the save (call every frame or at every persist). */
    public static void capture(SaveData sd, Sim sim) { sd.keysHeld = sim.keys; }

    /** A castle was opened: remember it (castles open strictly in order). Returns true the first time this castle number is opened in this climb. */
    public static boolean gateOpened(SaveData sd, int castleNo) {
        if (castleNo <= sd.openedUpTo) return false;
        sd.openedUpTo = castleNo; return true;
    }

    /** Re-applies the saved state to a freshly resumed simulation. */
    public static void restore(SaveData sd, Sim sim, Course world, Tuning t) {
        if (sd.openedUpTo < sd.towers) sd.openedUpTo = sd.towers;                    // saves from before openedUpTo existed: castles open in order, so the count is the number
        sim.keys = sd.keysHeld;
        for (int i = 0; i < world.hazards.size(); i++) {
            Element h = world.hazards.get(i);
            if (h.type == Element.Type.GATE && h.skin > 0 && h.skin <= sd.openedUpTo && i < sim.featDone.length) sim.featDone[i] = true;       // already opened: stays open
        }
        if (t.castleSpacing <= 0f || world.size() == 0) return;
        int n = sd.openedUpTo + 1;                                                    // the next castle to open
        int colour = CourseGenerator.castleColor(sd.seed, n);
        float lowest = Float.MAX_VALUE; for (int i = 0; i < world.size(); i++) lowest = Math.min(lowest, world.get(i).y);
        boolean haveKey = (sim.keys & (1 << colour)) != 0;
        if (!haveKey && CourseGenerator.keyHeight(sd.seed, n, t.castleSpacing) < lowest + 40f) sim.keys |= 1 << colour;        // its key lay in a section that no longer exists: never strand the run
    }
}
