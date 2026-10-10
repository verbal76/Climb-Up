package com.hotatticgames.climbup.desktop.input;

import java.util.List;

/**
 * The rebinding dialog as a state machine (the screen only draws it): pick a slot, wait for the next valid input, show the proposed binding with any conflict,
 * then confirm or cancel. Nothing is changed in the bindings until {@link #confirm}; cancelling, or a controller vanishing, leaves them exactly as they were.
 */
public final class Remapper<T> {
    public enum State { IDLE, WAITING, PROPOSED }

    private final Bindings<T> target;
    public State state = State.IDLE;
    public Act act; public int slot; public T proposed; public List<Act> conflicts; public String message = "";

    public Remapper(Bindings<T> target) { this.target = target; }

    public void begin(Act a, int slot) { act = a; this.slot = slot; proposed = null; conflicts = null; message = ""; state = State.WAITING; }

    public void cancel() { state = State.IDLE; proposed = null; conflicts = null; message = ""; }

    /** The next valid input was captured. Returns false (and keeps waiting) when it is refused. */
    public boolean capture(T input, boolean reserved) {
        if (state != State.WAITING) return false;
        if (reserved) { message = "THAT INPUT IS RESERVED"; return false; }
        proposed = input; conflicts = target.conflicts(act, input); message = ""; state = State.PROPOSED; return true;
    }

    /** True when confirming would take the input from another action (the dialog must say so). */
    public boolean hasConflict() { return state == State.PROPOSED && conflicts != null && !conflicts.isEmpty(); }

    /** Applies the proposal. A conflict is resolved by taking the input from the other action, unless that would leave it with nothing. Returns the result; on anything but OK/SAME the dialog stays open. */
    public Bindings.Result confirm() {
        if (state != State.PROPOSED) return Bindings.Result.BAD_SLOT;
        Bindings.Result r = target.assign(act, slot, proposed, true);
        if (r == Bindings.Result.OK || r == Bindings.Result.SAME) { state = State.IDLE; message = ""; }
        else if (r == Bindings.Result.WOULD_ORPHAN) message = "THAT WOULD LEAVE " + conflicts.get(0).label + " WITH NO INPUT";
        return r;
    }

    /** Back to waiting for a different input (the dialog's RETRY). */
    public void retry() { if (act != null) begin(act, slot); }
}
