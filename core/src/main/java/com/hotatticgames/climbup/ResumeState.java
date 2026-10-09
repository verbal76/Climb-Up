package com.hotatticgames.climbup;

import com.hotatticgames.climbup.sim.Sim;
import com.hotatticgames.climbup.sim.Tower;

/**
 * What a resumed climb needs beyond geometry: the keys carried and the castles already opened. (The whole tower is rebuilt from the stored history, so a key that lies below the checkpoint
 * is still there to be fetched; only what the player has DONE has to be saved.)
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
    public static void restore(SaveData sd, Sim sim, Tower tower) {
        if (sd.openedUpTo < sd.towers) sd.openedUpTo = sd.towers;
        sim.keys = sd.keysHeld;
        tower.openedUpTo = sd.openedUpTo;
        tower.applyDone(sim);
    }
}
