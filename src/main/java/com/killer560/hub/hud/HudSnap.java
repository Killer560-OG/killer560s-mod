package com.killer560.hub.hud;

import java.util.ArrayList;
import java.util.List;

/**
 * Snapping for the HUD editor (2026-10-07, killer560: "Those should kind of do a snapping style where they snap to align
 * with things. You can choose what all they will align with.").
 *
 * <p>Everything here is in the editor's screen coordinates (GUI pixels - the editor is not auto-scaled, so they are
 * the in-game HUD's own), on the element's drawn box without the editor's padding. A dragged box snaps, per axis, the
 * nearest of these within {@link #DISTANCE} pixels, each switchable in {@link HudConfig}:
 * <ul>
 *   <li>element edges: its left/right (top/bottom) edge onto any other listed element's left or right edge;</li>
 *   <li>element centres: its centre line onto another element's centre line;</li>
 *   <li>screen edges and the screen's centre lines;</li>
 *   <li>equal spacing: in a row (column) of elements it overlaps, the gap two neighbours already have, either
 *       continuing it past the end of the row or centring the box between two of them.</li>
 * </ul>
 * A resized edge snaps the same way, to edges, centres and the screen. Each snap leaves a {@link Guide} for the editor
 * to draw. Holding Alt turns it off for that drag (the editor decides that).
 */
public final class HudSnap {

    /** Pixels within which a box snaps: about the width of the cursor's tip at any GUI scale. */
    public static final int DISTANCE = 5;

    private HudSnap() {
    }

    /** A drawn box, x0/y0 inclusive, x1/y1 exclusive (x1 = x + width). */
    public record Box(int x0, int y0, int x1, int y1) {
        double cx() {
            return (x0 + x1) / 2.0;
        }

        double cy() {
            return (y0 + y1) / 2.0;
        }

        int w() {
            return x1 - x0;
        }

        int h() {
            return y1 - y0;
        }
    }

    /**
     * Something to draw while snapped: a full-length line at {@code at} ({@code vertical}: x = at), or for equal
     * spacing a short segment from {@code from} to {@code to} along the axis at {@code at} on the other one.
     */
    public record Guide(boolean vertical, double at, double from, double to, boolean spacing) {
    }

    /** What may be snapped to, read once per drag event from {@link HudConfig}. */
    public record Options(boolean elementEdges, boolean elementCentres, boolean screenEdges, boolean screenCentre,
                          boolean equalSpacing) {
        public static Options fromConfig() {
            HudConfig c = HudConfig.getInstance();
            return new Options(c.isSnapElementEdges(), c.isSnapElementCentres(), c.isSnapScreenEdges(),
                    c.isSnapScreenCentre(), c.isSnapEqualSpacing());
        }
    }

    /** One axis's best snap: how far to move, and what to draw. */
    private static final class Best {
        double delta = Double.MAX_VALUE;
        final List<Guide> guides = new ArrayList<>();

        void offer(double delta, Guide... guides) {
            if (Math.abs(delta) > DISTANCE) {
                return;
            }
            if (Math.abs(delta) < Math.abs(this.delta) - 1e-9) {
                this.delta = delta;
                this.guides.clear();
                this.guides.addAll(List.of(guides));
            } else if (Math.abs(Math.abs(delta) - Math.abs(this.delta)) <= 1e-9 && Math.signum(delta)
                    == Math.signum(this.delta)) {
                // The same move lines up with something else too: show that as well.
                this.guides.addAll(List.of(guides));
            }
        }

        boolean hit() {
            return delta != Double.MAX_VALUE;
        }
    }

    /** Result of {@link #move}: the snapped top-left and the guides to draw. */
    public record Moved(int x, int y, boolean snappedX, boolean snappedY, List<Guide> guides) {
    }

    /**
     * Snaps a box being MOVED. {@code box} is where the cursor puts it; {@code others} the other listed elements'
     * drawn boxes; {@code screenW/H} the editor's size.
     */
    public static Moved move(Box box, List<Box> others, int screenW, int screenH, Options o) {
        Best bx = axis(true, box, others, screenW, o, true);
        Best by = axis(false, box, others, screenH, o, true);
        int x = box.x0() + (bx.hit() ? (int) Math.round(bx.delta) : 0);
        int y = box.y0() + (by.hit() ? (int) Math.round(by.delta) : 0);
        List<Guide> guides = new ArrayList<>(bx.guides);
        guides.addAll(by.guides);
        return new Moved(x, y, bx.hit(), by.hit(), guides);
    }

    /**
     * Snaps one EDGE being dragged by a resize: {@code edge} is where the cursor puts it ({@code vertical}: an x).
     * Returns the snapped coordinate, or {@code edge} unchanged, and adds the guide to {@code guides}.
     */
    public static int edge(boolean vertical, int edge, List<Box> others, int screenSize, Options o, List<Guide> guides) {
        Best b = new Best();
        for (Box t : others) {
            if (o.elementEdges()) {
                for (int e : vertical ? new int[]{t.x0(), t.x1()} : new int[]{t.y0(), t.y1()}) {
                    b.offer(e - edge, new Guide(vertical, e, 0, 0, false));
                }
            }
            if (o.elementCentres()) {
                double c = vertical ? t.cx() : t.cy();
                b.offer(Math.round(c) - edge, new Guide(vertical, c, 0, 0, false));
            }
        }
        if (o.screenEdges()) {
            b.offer(-edge, new Guide(vertical, 0, 0, 0, false));
            b.offer(screenSize - edge, new Guide(vertical, screenSize, 0, 0, false));
        }
        if (o.screenCentre()) {
            b.offer(Math.round(screenSize / 2.0) - edge, new Guide(vertical, screenSize / 2.0, 0, 0, false));
        }
        if (!b.hit()) {
            return edge;
        }
        guides.addAll(b.guides);
        return edge + (int) Math.round(b.delta);
    }

    /** The best snap along one axis ({@code xAxis}: horizontal positions) for a moved box. */
    private static Best axis(boolean xAxis, Box box, List<Box> others, int screenSize, Options o, boolean spacing) {
        Best b = new Best();
        int lo = xAxis ? box.x0() : box.y0();
        int hi = xAxis ? box.x1() : box.y1();
        int size = hi - lo;
        double mid = (lo + hi) / 2.0;
        for (Box t : others) {
            int tlo = xAxis ? t.x0() : t.y0();
            int thi = xAxis ? t.x1() : t.y1();
            if (o.elementEdges()) {
                for (int e : new int[]{tlo, thi}) {
                    b.offer(e - lo, new Guide(xAxis, e, 0, 0, false));
                    b.offer(e - hi, new Guide(xAxis, e, 0, 0, false));
                }
            }
            if (o.elementCentres()) {
                double c = (tlo + thi) / 2.0;
                // Rounded the way the box will land, so a box of the other parity sits half a pixel off and says so.
                b.offer(Math.round(c - size / 2.0) - lo, new Guide(xAxis, c, 0, 0, false));
            }
        }
        if (o.screenEdges()) {
            b.offer(-lo, new Guide(xAxis, 0, 0, 0, false));
            b.offer(screenSize - hi, new Guide(xAxis, screenSize, 0, 0, false));
        }
        if (o.screenCentre()) {
            b.offer(Math.round(screenSize / 2.0 - size / 2.0) - lo, new Guide(xAxis, screenSize / 2.0, 0, 0, false));
        }
        if (spacing && o.equalSpacing()) {
            spacing(xAxis, box, others, b);
        }
        return b;
    }

    /**
     * Equal spacing along one axis, among the elements that share the box's row (they overlap it on the other axis):
     * repeat a gap two neighbours already have on either end of the row, or centre the box between two of them.
     */
    private static void spacing(boolean xAxis, Box box, List<Box> others, Best b) {
        int cross0 = xAxis ? box.y0() : box.x0();
        int cross1 = xAxis ? box.y1() : box.x1();
        List<Box> row = new ArrayList<>();
        for (Box t : others) {
            int t0 = xAxis ? t.y0() : t.x0();
            int t1 = xAxis ? t.y1() : t.x1();
            if (t0 < cross1 && cross0 < t1) {
                row.add(t);
            }
        }
        if (row.isEmpty()) {
            return;
        }
        row.sort((p, q) -> Integer.compare(lo(xAxis, p), lo(xAxis, q)));
        int lo = lo(xAxis, box);
        int size = hi(xAxis, box) - lo;
        // The gap markers run just past the box's far side, so the edge and centre guides of the same snap (drawn
        // through the box) do not cover them.
        double at = (xAxis ? box.y1() : box.x1()) + 3;
        for (int i = 0; i + 1 < row.size(); i++) {
            Box p = row.get(i);
            Box q = row.get(i + 1);
            int gap = lo(xAxis, q) - hi(xAxis, p);
            if (gap <= 0) {
                continue;
            }
            // After q, the same gap again.
            int after = hi(xAxis, q) + gap;
            b.offer(after - lo, seg(xAxis, at, hi(xAxis, p), lo(xAxis, q)), seg(xAxis, at, hi(xAxis, q), after));
            // Before p.
            int before = lo(xAxis, p) - gap - size;
            b.offer(before - lo, seg(xAxis, at, hi(xAxis, p), lo(xAxis, q)),
                    seg(xAxis, at, before + size, lo(xAxis, p)));
        }
        // Centred between any two row members that leave room for it.
        for (int i = 0; i < row.size(); i++) {
            for (int j = 0; j < row.size(); j++) {
                Box p = row.get(i);
                Box q = row.get(j);
                int room = lo(xAxis, q) - hi(xAxis, p);
                if (room <= size) {
                    continue;
                }
                int at0 = (int) Math.round(hi(xAxis, p) + (room - size) / 2.0);
                b.offer(at0 - lo, seg(xAxis, at, hi(xAxis, p), at0), seg(xAxis, at, at0 + size, lo(xAxis, q)));
            }
        }
    }

    private static Guide seg(boolean xAxis, double at, double from, double to) {
        return new Guide(!xAxis, at, from, to, true);
    }

    private static int lo(boolean xAxis, Box b) {
        return xAxis ? b.x0() : b.y0();
    }

    private static int hi(boolean xAxis, Box b) {
        return xAxis ? b.x1() : b.y1();
    }
}
