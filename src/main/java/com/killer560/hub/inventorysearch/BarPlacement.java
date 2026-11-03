package com.killer560.hub.inventorysearch;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the Inventory Search bar goes so it covers nothing (killer560, 2026-11-02: "storage search seems to always be in a
 * bad spot. if anything ever goes low down there it blocks it"). The bar used to sit at a fixed spot 30 above the bottom
 * middle of every container screen, which is on top of the Storage Overlay's Inventory panel (its third row, slots hidden
 * and unclickable behind the bar), over the hotbar, XP and health bars on the plain inventory screen, and over a tall chest's
 * hotbar row at a big GUI scale.
 * <p>
 * Pure layout math, no Minecraft types, so the testkit can check it on its own. Every rect is {x0, y0, x1, y1} in
 * GUI-scaled screen units. {@code hard} rects (slots, the container's panel, widgets, the mod's own panels) are never
 * covered when any spot avoids them; {@code soft} ones (the in-game hotbar and status bars behind the screen) are avoided
 * too, but given up first when the window is too small for both.
 */
public final class BarPlacement {

    /** Room kept between the bar and anything it avoids. */
    public static final int GAP = 3;
    /** Room kept from the screen edge. */
    public static final int MARGIN = 2;

    /** The chosen top-left and how it was chosen. */
    public record Result(int x, int y, String kind) {
        /** Clear of every rect, the hotbar zone too. */
        public boolean clearOfAll() {
            return !kind.equals("free-over-hud") && !kind.equals("blocked");
        }

        /** No clear spot at all: drawn at the usual spot over whatever is there. */
        public boolean blocked() {
            return kind.equals("blocked");
        }
    }

    private BarPlacement() {
    }

    /**
     * @param preferred spots to take as they are when clear, in order ({x, y, kind}); the usual bottom-middle spot is one
     * @param anchorX   where the nearest-free-spot search measures from (normally the bottom-middle spot)
     */
    public static Result place(int screenW, int screenH, int w, int h, List<int[]> preferred, String[] preferredKinds,
                               int anchorX, int anchorY, List<int[]> hard, List<int[]> soft) {
        List<int[]> all = new ArrayList<>(hard.size() + soft.size());
        all.addAll(hard);
        all.addAll(soft);
        for (int i = 0; i < preferred.size(); i++) {
            int[] p = preferred.get(i);
            if (clear(p[0], p[1], w, h, screenW, screenH, all)) {
                return new Result(p[0], p[1], preferredKinds[i]);
            }
        }
        int[] spot = nearestFree(screenW, screenH, w, h, anchorX, anchorY, all);
        if (spot != null) {
            return new Result(spot[0], spot[1], "free");
        }
        spot = nearestFree(screenW, screenH, w, h, anchorX, anchorY, hard);
        if (spot != null) {
            return new Result(spot[0], spot[1], "free-over-hud");
        }
        // Nothing fits anywhere (a window smaller than the panels themselves): the usual spot, as before.
        return new Result(anchorX, anchorY, "blocked");
    }

    /** Whether a w x h box at x,y lies on screen and keeps {@link #GAP} from every rect. */
    public static boolean clear(int x, int y, int w, int h, int screenW, int screenH, List<int[]> rects) {
        if (x < MARGIN || y < MARGIN || x + w > screenW - MARGIN || y + h > screenH - MARGIN) {
            return false;
        }
        for (int[] r : rects) {
            if (x < r[2] + GAP && r[0] < x + w + GAP && y < r[3] + GAP && r[1] < y + h + GAP) {
                return false;
            }
        }
        return true;
    }

    /** The clear spot closest to the anchor, trying every edge-aligned x and y the rects offer, or null. */
    static int[] nearestFree(int screenW, int screenH, int w, int h, int anchorX, int anchorY, List<int[]> rects) {
        List<Integer> xs = new ArrayList<>();
        List<Integer> ys = new ArrayList<>();
        xs.add(anchorX);
        xs.add(MARGIN);
        xs.add(screenW - MARGIN - w);
        ys.add(anchorY);
        ys.add(MARGIN);
        ys.add(screenH - MARGIN - h);
        for (int[] r : rects) {
            xs.add(r[0] - GAP - w);
            xs.add(r[2] + GAP);
            ys.add(r[1] - GAP - h);
            ys.add(r[3] + GAP);
        }
        int[] best = null;
        long bestScore = Long.MAX_VALUE;
        for (int x : xs) {
            long dx = x - anchorX;
            for (int y : ys) {
                long dy = y - anchorY;
                long score = dx * dx + dy * dy;
                if (score >= bestScore || !clear(x, y, w, h, screenW, screenH, rects)) {
                    continue;
                }
                bestScore = score;
                best = new int[]{x, y};
            }
        }
        return best;
    }
}
