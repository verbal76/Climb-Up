package com.hotatticgames.climbup.sim;

import com.badlogic.gdx.utils.Json;

/** All movement, world and generator numbers. Loaded from assets/data/tuning.json (OTA-replaceable data). */
public class Tuning {
    public float radius = 10f;
    public float runSpeed = 5.6f, groundAccel = 48f, groundDecel = 60f, airAccel = 28f, airDrag = 3f;
    public float gravity = 34f, jumpVel = 11.5f, jumpCutMul = 0.45f, maxFall = 24f;
    public float coyote = 0.12f, jumpBuffer = 0.13f;
    public float halfWidth = 0.28f, height = 1.25f, handHeight = 1.15f, edgeOverhang = 0.2f;
    public float padBounce = 21f, padBounceHeld = 24f;
    public float climbSpeed = 3.4f, ropeGrabRadius = 0.42f, cableShimmy = 3.6f, grabLockout = 0.28f;
    public float ledgeReachX = 0.4f, ledgeReachBelow = 0.9f, ledgeReachAbove = 0.15f, pullUpTime = 0.32f;
    public float crumbleDelay = 0.7f, crumbleRespawn = 3.4f;
    public float fallRespawnDepth = 12f;
    public float seesawMaxTilt = 0.55f, seesawRate = 1.0f, seesawRelax = 1.0f, seesawSlide = 5f, seesawUphill = 0.45f;   // seesaw bridge: slope (height per metre), tip / relax speed, downhill slide, uphill slow-down
    public float courseHeight = 520f;        // finite test towers only; the shipped game is endless
    public float zoneHeight = 130f; public int zoneCount = 5;   // each world (meadow, frost, dusk, night, space) lasts zoneHeight metres, then they repeat
    public float rampHeight = 520f;          // height over which base difficulty (gap sizes, mover amplitude) ramps up in the endless climb
    public float chunkHeight = 60f;          // the endless tower is generated in chunks of about this height
    public float hazardStartY = 90f, hazardRampY = 1400f;   // hazards appear from hazardStartY and reach full frequency hazardRampY later
    public float minTimingWindow = 0.2f;     // fraction of a hazard's cycle that must leave a workable move at the start of the ramp (relaxes slightly at full intensity)
    public float spiralPitch = 24f;          // height gained per revolution, enforced by the generator
    public int restEvery = 5, checkpointEveryRests = 1;
    public float minLinkMargin = 0.07f;
    public float assistJumpForgiveness = 0.08f, assistSlowFactor = 0.8f;

    public float circumference() { return (float) (2 * Math.PI * radius); }

    public static Tuning parse(String json) {
        Json j = new Json();
        j.setIgnoreUnknownFields(true);
        return j.fromJson(Tuning.class, json);
    }
}
