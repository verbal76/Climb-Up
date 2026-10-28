package com.hotatticgames.climbup.desktop.input;

import java.util.ArrayList;
import java.util.List;

/** Menu focus over the buttons drawn in the last frame; spatial moves (up/down/left/right pick the nearest button in that direction). Pure geometry, no libGDX. */
public final class FocusModel {
    public static final class Item { public final String label; public final float x, y, w, h; public final boolean accent; Item(String l, float x, float y, float w, float h, boolean a) { label = l; this.x = x; this.y = y; this.w = w; this.h = h; accent = a; }
        float cx() { return x + w / 2; } float cy() { return y + h / 2; } }

    private final List<Item> items = new ArrayList<>(), last = new ArrayList<>();
    public int focus = -1;
    private boolean wantDefault = true;

    public void beginFrame() { items.clear(); }
    public int count() { return items.size(); }
    public int add(String label, float x, float y, float w, float h, boolean accent) { items.add(new Item(label, x, y, w, h, accent)); return items.size() - 1; }
    public Item item(int i) { return i >= 0 && i < items.size() ? items.get(i) : null; }

    /** Ends the frame: clamps or defaults the focus. Items drawn this frame become the targets of next frame's moves. */
    public void endFrame() {
        last.clear(); last.addAll(items);
        if (items.isEmpty()) { focus = -1; return; }
        if (wantDefault || focus < 0 || focus >= items.size()) {
            focus = 0; for (int i = 0; i < items.size(); i++) if (items.get(i).accent) { focus = i; break; }
            wantDefault = false;
        }
    }

    public void reset() { wantDefault = true; }

    /** dir: 0 up, 1 down, 2 left, 3 right (screen directions; y grows upward in UI space). Returns whether the focus moved. */
    public boolean move(int dir) {
        if (last.isEmpty()) return false;
        if (focus < 0 || focus >= last.size()) { focus = 0; return true; }
        Item f = last.get(focus);
        int best = -1; float bestScore = Float.MAX_VALUE;
        for (int pass = 0; pass < 2 && best < 0; pass++) {            // pass 0: only buttons that share a row/column with the focused one (nearest first); pass 1: any button in that direction
            for (int i = 0; i < last.size(); i++) {
                if (i == focus) continue;
                Item c = last.get(i);
                float dx = c.cx() - f.cx(), dy = c.cy() - f.cy();
                float along = dir == 0 ? dy : dir == 1 ? -dy : dir == 2 ? -dx : dx;
                float across = dir <= 1 ? Math.abs(dx) : Math.abs(dy);
                if (along <= 4f) continue;
                boolean overlaps = dir <= 1 ? Math.min(f.x + f.w, c.x + c.w) - Math.max(f.x, c.x) > 2f : Math.min(f.y + f.h, c.y + c.h) - Math.max(f.y, c.y) > 2f;
                if (pass == 0 && !overlaps) continue;
                float score = pass == 0 ? along + 0.01f * across : along + 2.5f * across;
                if (score < bestScore) { bestScore = score; best = i; }
            }
        }
        if (best < 0) return false;
        focus = best; return true;
    }

    public void focusAt(float px, float py) {
        for (int i = last.size() - 1; i >= 0; i--) { Item c = last.get(i); if (px >= c.x && px <= c.x + c.w && py >= c.y && py <= c.y + c.h) { focus = i; return; } }
    }

    public Item lastItem(int i) { return i >= 0 && i < last.size() ? last.get(i) : null; }

    public Item focused() { return focus >= 0 && focus < last.size() ? last.get(focus) : null; }
}
