package com.hotatticgames.climbup.desktop.input;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** The inputs bound to each action (up to {@link #MAX_SLOTS} per action). T is an int code for keyboard/mouse and {@link Ctl} for controllers. Pure data, no libGDX state. */
public final class Bindings<T> {
    public static final int MAX_SLOTS = 3;
    public enum Result { OK, SAME, CONFLICT, WOULD_ORPHAN, BAD_SLOT }

    private final EnumMap<Act, List<T>> map = new EnumMap<>(Act.class);

    public Bindings() { for (Act a : Act.values()) map.put(a, new ArrayList<>()); }

    public List<T> get(Act a) { return map.get(a); }
    public T first(Act a) { List<T> l = map.get(a); return l.isEmpty() ? null : l.get(0); }
    public boolean has(Act a, T t) { return map.get(a).contains(t); }

    public void set(Act a, List<T> inputs) { List<T> l = map.get(a); l.clear(); for (T t : inputs) if (!l.contains(t) && l.size() < MAX_SLOTS) l.add(t); }

    /** The other actions, in the same context, that already use this input. */
    public List<Act> conflicts(Act a, T t) {
        List<Act> out = new ArrayList<>();
        for (Act o : Act.values()) if (o != a && o.sameContext(a) && map.get(o).contains(t)) out.add(o);
        return out;
    }

    /** Binds t in the given slot (slot == current size appends). A conflicting input is only taken from the other action when takeFromOthers is set, and never if that would leave it with nothing. */
    public Result assign(Act a, int slot, T t, boolean takeFromOthers) {
        List<T> l = map.get(a);
        if (slot < 0 || slot > l.size() || slot >= MAX_SLOTS) return Result.BAD_SLOT;
        if (slot < l.size() && l.get(slot).equals(t)) return Result.SAME;
        List<Act> others = conflicts(a, t);
        if (!others.isEmpty()) {
            if (!takeFromOthers) return Result.CONFLICT;
            for (Act o : others) if (map.get(o).size() <= 1) return Result.WOULD_ORPHAN;
            for (Act o : others) map.get(o).remove(t);
        }
        l.remove(t);                                     // the same input twice on one action collapses to one slot
        if (slot > l.size()) slot = l.size();
        if (slot == l.size()) l.add(t); else l.set(slot, t);
        return Result.OK;
    }

    /** Removes a slot; the last binding of an action can never be removed. */
    public boolean clearSlot(Act a, int slot) {
        List<T> l = map.get(a);
        if (slot < 0 || slot >= l.size() || l.size() <= 1) return false;
        l.remove(slot); return true;
    }

    /** Every action has at least one binding and none exceeds the slot limit. */
    public boolean valid() { for (Act a : Act.values()) { int n = map.get(a).size(); if (n < 1 || n > MAX_SLOTS) return false; } return true; }

    public Bindings<T> copy() { Bindings<T> c = new Bindings<>(); for (Act a : Act.values()) c.map.get(a).addAll(map.get(a)); return c; }
    public void copyFrom(Bindings<T> o) { for (Act a : Act.values()) { map.get(a).clear(); map.get(a).addAll(o.map.get(a)); } }
    public Map<Act, List<T>> view() { return map; }
    @Override public boolean equals(Object o) { return o instanceof Bindings && map.equals(((Bindings<?>) o).map); }
    @Override public int hashCode() { return map.hashCode(); }
}
