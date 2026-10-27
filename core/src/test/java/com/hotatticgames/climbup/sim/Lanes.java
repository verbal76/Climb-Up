package com.hotatticgames.climbup.sim;

import java.util.*;

/**
 * Owner classification (MANDATORY CLASSIFICATION AMENDMENT, 2026-10-26): every Element.Type has exactly one primary lane, every generator module kind is
 * listed with its structural purpose. Adding an enum value without classifying it here makes {@link #lane} throw, so the reports (and LanesTest) fail
 * instead of silently defaulting.
 */
public final class Lanes {
    public enum Lane { TERRAIN, HAZARDS, ENEMIES, UTILITY }

    public static Lane lane(Element.Type k) {
        switch (k) {
            case STATIC: case CRUMBLE: case MOVE_H: case MOVE_V: case MOVE_Z: case SWING: case PAD: case ROPE: case CABLE: case SPRING: case SEESAW: case RAMP:
                return Lane.TERRAIN;
            case SAW_H: case SAW_V: case PENDULUM: case CANNON: case AIMED: case MORTAR: case SPIKE_TRAP: case SPIKE_BLOCK: case SPIKE_DROP: case AVALANCHE:
                return Lane.HAZARDS;
            case CRAB: case BEE: case SKULL: case BLOB: case ENFORCER:
                return Lane.ENEMIES;
            case GOAL: case KEY: case GATE: case CLUB:
                return Lane.UTILITY;
            default: throw new IllegalStateException("UNCLASSIFIED Element.Type " + k + ": add it to Lanes.lane (owner classification amendment)");
        }
    }

    public static String describe(Element.Type k) {
        switch (k) {
            case STATIC: return "Fixed platforms, ledges, stepping stones, decks, bridges (skin 1), wet surfaces, supporting geometry";
            case CRUMBLE: return "Platforms that crumble or disappear";
            case MOVE_H: return "Horizontally moving platforms (skin 1: crumbling mover)";
            case MOVE_V: return "Vertically moving platforms (skin 1: crumbling mover)";
            case MOVE_Z: return "Depth-moving platforms";
            case SWING: return "Swinging traversal platforms";
            case PAD: return "Bounce/launch pads";
            case ROPE: return "Climbable ropes";
            case CABLE: return "Traversal cables";
            case SPRING: return "Angled launching springs";
            case SEESAW: return "Tilting platforms and balance bridges";
            case RAMP: return "Sloped surfaces: 0 plain, 1 crumbling, 2 shaking/bouncy, 3 spinning, 4 conveyor/downhill";
            case SAW_H: return "Horizontal saw";
            case SAW_V: return "Vertical saw";
            case PENDULUM: return "Swinging hazard";
            case CANNON: return "Fixed cannon and its firing threat (its gray pillar is TERRAIN, render-only: no traversal geometry in the simulation)";
            case AIMED: return "Tracking/aimed cannon and its firing threat";
            case MORTAR: return "Mortar projectile and impact threat";
            case SPIKE_TRAP: return "Triggered/pop-up spike threat";
            case SPIKE_BLOCK: return "Spiked-block collision threat";
            case SPIKE_DROP: return "Falling spike slab (its platform is TERRAIN)";
            case CRAB: return "Crab enemy"; case BEE: return "Bee enemy"; case SKULL: return "Skull enemy"; case BLOB: return "Blob enemy";
            case ENFORCER: return "Enforcer robot (PUNISHER): pursuit across 3-5 decks, kick, air swat (orange/red)";
            case AVALANCHE: return "Spike-block avalanche: blocks drop from above, bounce and tumble down past you (PUNISHER 4-5, NORMAL castles 9-10: 2)";
            case GOAL: return "Completion/progression marker"; case KEY: return "Progression and route requirement";
            case GATE: return "Progression/access control"; case CLUB: return "Defensive/protective pickup (mitigation, never a negative enemy)";
            default: throw new IllegalStateException("UNCLASSIFIED Element.Type " + k);
        }
    }

    /** The 25 generator module kinds (CourseGenerator.Kind) and their structural purpose; structures the reports add: REST, CASTLE, GEM. */
    public static final String[][] KINDS = {
        {"HOP", "Terrain: platform-to-platform jumps"}, {"STAIRS", "Terrain: ascending/descending stepping sequences"},
        {"CRUMBLE", "Terrain: crumbling-platform sequences"}, {"MOVER_H", "Terrain: horizontal moving-platform traversal"},
        {"MOVER_V", "Terrain: vertical moving-platform traversal"}, {"PAD", "Terrain: launch/bounce traversal"},
        {"ROPE", "Terrain: rope climbing/traversal"}, {"CABLE", "Terrain: cable traversal"}, {"SWING", "Terrain: swinging-platform traversal"},
        {"GRAB", "Terrain: grab/catch traversal challenge"}, {"HAZ", "Mixed: supporting terrain plus placed hazards; scored separately"},
        {"TRAP", "Mixed: terrain and environmental traps; scored separately"}, {"SPRING", "Terrain: angled spring-launch traversal"},
        {"SEESAW", "Terrain: tipping/balance-platform traversal"}, {"BRIDGE", "Terrain: bridge geometry and bridge movement/collapse"},
        {"RAMP", "Terrain: ramps and ramp variants"}, {"MOVER_Z", "Terrain: depth-moving platform traversal"},
        {"ELEV_FALL", "Terrain: falling/collapsing elevator-style traversal"}, {"SLIDE_FALL", "Terrain: sliding/falling platform sequences"},
        {"MORTAR", "Mixed integrated structure: terrain islands/gaps plus mortar hazards"}, {"WET", "Terrain: wet/slippery surface traversal"},
        {"LONG", "Terrain: long traversal module"}, {"CREATURE", "Mixed: supporting terrain plus crab/bee/skull/blob enemies"},
        {"DROPS", "Mixed integrated structure: spike-drop row platforms/gaps plus spike-drop hazards"},
        {"FORK", "Terrain: branching routes, decoys and route rejoining"}};
    public static final String[][] STRUCTURES = {{"REST", "Rest/checkpoint platform"}, {"CASTLE", "Castle approach steps and deck"}, {"GEM", "Halfway red-gem checkpoint platform"}};
}
