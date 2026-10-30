package com.hotatticgames.climbup;

/**
 * Every contextual tip (mechanic introduction): its key (stored in {@code SaveData.shownTips}, so the keys never change), the compact line the
 * in-play banner shows, the full instruction (unchanged from the earlier tip box; shown in the pause menu's LAST TIP panel), and how long the
 * banner stays. Moved out of PlayScreen for the owner's pop-up UX fix so the texts and timings are checked by tests.
 *
 * Wording rules: the short lines use the same control words the full texts used ("HOLD JUMP", "PRESS SWING", "STICK"), so the desktop's
 * {@code Platform.tip} rewrite turns them into the player's own keys or buttons.
 */
public final class TipCatalog {
    private TipCatalog() { }

    public static final class Tip {
        public final String key, brief, full; public final float hold;
        Tip(String key, String brief, String full, float hold) { this.key = key; this.brief = brief; this.full = full; this.hold = hold; }
    }

    private static Tip t(String key, String brief, String full) { return new Tip(key, brief, full, com.hotatticgames.climbup.ui.TipBanner.HOLD); }
    private static Tip slow(String key, String brief, String full) { return new Tip(key, brief, full, com.hotatticgames.climbup.ui.TipBanner.HOLD_LONG); }

    public static final Tip[] ALL = {
        t("jump", "HOLD JUMP TO LEAP HIGHER", "HOLD JUMP FOR A HIGHER LEAP. THE STICK STEERS IN THE AIR."),
        t("ledge", "PUSH INTO A LEDGE TO GRAB IT", "SHORT BY A HAIR? PUSH TOWARD THE LEDGE TO GRAB, THEN STICK UP OR JUMP TO PULL UP."),
        t("pad", "BOUNCE PADS LAUNCH YOU UP", "LAND ON A BOUNCE PAD TO LAUNCH. HOLD JUMP FOR EXTRA HEIGHT."),
        t("rope", "TOUCH A ROPE TO GRAB IT", "GRAB A ROPE BY TOUCHING IT. STICK UP/DOWN CLIMBS. JUMP TO LEAP OFF."),
        t("crumble", "CRUMBLING TILES: KEEP MOVING", "CRUMBLING TILES DROP FAST. KEEP MOVING."),
        t("move", "RIDE THE MOVING PLATFORMS", "RIDE MOVING PLATFORMS, OR WAIT FOR THE RIGHT MOMENT TO JUMP."),
        t("cable", "JUMP UP TO HANG FROM CABLES", "JUMP UP TO CABLES AND HANG. STICK LEFT/RIGHT SHIMMIES. DOWN OR JUMP DROPS."),
        t("swing", "TIME YOUR LEAP TO THE SWING", "TIME YOUR LEAP ONTO THE SWINGING PLATFORM."),
        t("checkpoint", "GEMS AND CASTLES ARE CHECKPOINTS", "RED GEMS AND CASTLES ARE CHECKPOINTS. REACH ONE AND YOU RESTART FROM IT WHEN YOU FALL."),
        t("saw", "SAWS SLIDE: WATCH THE RHYTHM", "SAWBLADES SLIDE BACK AND FORTH. WATCH THE RHYTHM, THEN GO. A HIT THROWS YOU OFF."),
        t("cannon", "WAIT FOR THE CANNONBALL TO PASS", "CANNONS FIRE SPIKED BALLS. WAIT FOR ONE TO PASS, THEN LEAP."),
        t("trap", "SPIKES POP UP ON A BEAT", "SPIKES POP UP ON A BEAT. WAIT IN THE SAFE PATCH, THEN RUN ACROSS WHEN THEY ARE DOWN."),
        t("block", "HOP OVER THE SPIKE BLOCKS", "STONE SPIKE BLOCKS: HOP OVER THEM, AND DON'T DROP INTO ONE."),
        t("crab", "CRABS BUMP YOU: HOP OVER THEM", "CRABS BUMP YOU AROUND LIKE PING-PONG. TIME YOUR RUN, HOP OVER, OR KNOCK THEM OFF THE EDGE WITH THE SPIKED CLUB."),
        t("bee", "BEES ONLY NUDGE YOU", "BEES BUZZ IN, BUMP YOU AND FLY OFF AGAIN. THEY ONLY NUDGE YOU: THE DANGER IS WHAT IS BELOW."),
        t("club", "SPIKED CLUB: PRESS SWING TO BONK", "A SPIKED CLUB! PRESS SWING TO KNOCK CRABS OFF THE PLATFORM. IT WEARS OFF AFTER A WHILE."),
        t("drop", "SLIP UNDER THE RAISED SLABS", "SPIKED SLABS SLAM DOWN ON A BEAT. SLIP UNDER WHILE THEY ARE RAISED."),
        t("spring", "SPRINGS LAUNCH YOU ALONG THE ARROW", "SPRINGS LAUNCH YOU ALONG THE ARROW. STEER IN THE AIR TO LAND IT."),
        t("ramp", "RAMPS: JUST RUN UP", "RAMPS LEAD UP TO BLOCKS TOO TALL TO JUMP. JUST RUN UP."),
        t("rampcrumble", "CRUMBLING RAMP: RUN!", "THIS RAMP CRUMBLES WHEN YOU STAND ON IT. RUN!"),
        t("rampshake", "SHAKY RAMP: KEEP MOVING", "A SHAKY RAMP BOUNCES YOU AROUND. KEEP MOVING AND AIM FOR THE TOP."),
        t("rampspin", "SPINNING RAMP: TIME YOUR JUMP", "A SPINNING RAMP! IT TURNS LIKE A RECORD AND FLINGS YOU SIDEWAYS. TIME YOUR JUMP."),
        t("rampbelt", "CONVEYOR RAMP: KEEP RUNNING", "A CONVEYOR RAMP! THE BELT RUNS DOWNHILL. KEEP RUNNING OR IT CARRIES YOU BACK DOWN."),
        t("beltbridge", "CONVEYOR BRIDGE: DON'T STOP", "A CONVEYOR BRIDGE! THE PLANKS ROLL LEFT. DON'T STAND STILL OR YOU'LL RIDE OFF THE END."),
        t("seesaw", "TIPPING BRIDGE: KEEP RUNNING", "THE BRIDGE TIPS UNDER YOU. KEEP RUNNING ACROSS BEFORE IT DUMPS YOU OFF."),
        slow("gate", "LOCKED CASTLE: ITS KEY IS BELOW THE PATH", "A COLOURED CASTLE NEEDS THE KEY OF ITS COLOUR. THE KEY IS HIDDEN BELOW THE PATH BEFORE IT: DROP DOWN, FIND IT, CLIMB BACK."),
    };

    /**
     * Starts the tip {@code key} on the banner if the player has not seen it and the banner is free; marks it seen (in the save's list) only when
     * it starts. {@code rewrite} is the platform's control-word rewrite (Platform.tip). Returns true when the key is now handled (shown, or unknown).
     */
    public static boolean offer(com.hotatticgames.climbup.ui.TipBanner banner, java.util.List<String> seen, String key, java.util.function.UnaryOperator<String> rewrite) {
        if (seen.contains(key) || !banner.ready()) return false;
        seen.add(key);
        Tip t = get(key);
        if (t != null) banner.show(key, rewrite.apply(t.brief), rewrite.apply(t.full), t.hold);
        return true;
    }

    /** The tip with this key, or null. */
    public static Tip get(String key) { for (Tip t : ALL) if (t.key.equals(key)) return t; return null; }
}
