package com.hotatticgames.climbup.host;

/**
 * Protects the player's saves across a module switch that raises the save-schema version. Before such a module's first launch the host takes a snapshot; if that module is rolled back
 * before it has proven itself, the snapshot is restored, so the previous module finds the saves exactly as they were. Once the module is confirmed the snapshot is dropped.
 * Implementations must make {@link #restore} safe to repeat (the host repeats it if the process dies half-way).
 */
public interface SaveGuard {
    /** Copies the current saves under {@code id}. Returns false if it could not (the switch is then refused rather than risking the saves). */
    boolean snapshot(String id);
    /** Puts the saves back as they were when {@code id} was taken. Returns false if it could not. */
    boolean restore(String id);
    /** Removes the snapshot. */
    void discard(String id);
}
