package com.killer560.hub.mainmenu;

/**
 * The drawing surface the picture scenes' overlays use ({@link PictureScene}): solid fills and vertical gradients, nothing
 * else. In the game it is {@link GuiPx} over the frame's {@link com.killer560.hub.hud.GuiRects} batch. The static helpers
 * are the color arithmetic and soft shapes the overlays share (ARGB ints, straight alpha).
 */
interface Px {

    void fill(int x0, int y0, int x1, int y1, int argb);

    /** A vertical gradient, {@code top} at y0. */
    void gradient(int x0, int y0, int x1, int y1, int top, int bottom);

    static int alpha(int argb, int a) {
        return (Math.max(0, Math.min(255, a)) << 24) | (argb & 0x00FFFFFF);
    }

    static int alpha(int argb, double a) {
        return alpha(argb, (int) Math.round(a * 255.0));
    }

    /** Channel-wise mix of two ARGB colors (alpha too). */
    static int mix(int a, int b, double t) {
        int r = 0;
        for (int s = 0; s < 32; s += 8) {
            int ca = (a >>> s) & 0xFF;
            int cb = (b >>> s) & 0xFF;
            r |= ((int) Math.round(ca + (cb - ca) * t) & 0xFF) << s;
        }
        return r;
    }

    /** An opaque color from 0..1 channels (clamped). */
    static int rgb(double r, double g, double b) {
        return 0xFF000000 | (ch(r) << 16) | (ch(g) << 8) | ch(b);
    }

    static int ch(double v) {
        return Math.max(0, Math.min(255, (int) Math.round(v * 255.0)));
    }

    static double r(int c) {
        return ((c >> 16) & 0xFF) / 255.0;
    }

    static double g(int c) {
        return ((c >> 8) & 0xFF) / 255.0;
    }

    static double b(int c) {
        return (c & 0xFF) / 255.0;
    }

    /** 0..1 from an integer and a salt; the same function the older scenes use. */
    static double hash(int i, int salt) {
        double v = Math.sin(i * 12.9898 + salt * 78.233) * 43758.5453;
        return v - Math.floor(v);
    }

    static double smooth(double e0, double e1, double x) {
        double t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }

    /** Value noise on an integer lattice, 0..1, smooth. */
    static double noise(double x, double y, int salt) {
        int ix = (int) Math.floor(x);
        int iy = (int) Math.floor(y);
        double fx = x - ix;
        double fy = y - iy;
        double ux = fx * fx * (3 - 2 * fx);
        double uy = fy * fy * (3 - 2 * fy);
        double a = lattice(ix, iy, salt);
        double b = lattice(ix + 1, iy, salt);
        double c = lattice(ix, iy + 1, salt);
        double d = lattice(ix + 1, iy + 1, salt);
        return a + (b - a) * ux + (c - a) * uy + (a - b - c + d) * ux * uy;
    }

    private static double lattice(int x, int y, int salt) {
        int h = x * 374761393 + y * 668265263 + salt * 1442695041;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / (double) 0xFFFFFF;
    }

    /** Fractal value noise, 0..1. */
    static double fbm(double x, double y, int octaves, int salt) {
        double sum = 0;
        double amp = 0.5;
        double norm = 0;
        for (int o = 0; o < octaves; o++) {
            sum += noise(x, y, salt + o * 17) * amp;
            norm += amp;
            x = x * 2.03 + 11.7;
            y = y * 2.03 + 3.1;
            amp *= 0.5;
        }
        return sum / norm;
    }

    /**
     * A soft round glow: Gaussian falloff drawn as narrow columns, each a few piecewise-linear vertical gradients, so it has
     * no rings. {@code a} is the peak alpha 0..1.
     */
    static void softGlow(Px p, double cx, double cy, double rx, double ry, int rgb, double a) {
        if (rx < 1 || ry < 1 || a <= 0.002) {
            return;
        }
        int cw = Math.max(2, (int) Math.round(rx / 18.0));
        double span = 2.4;
        int x0 = (int) Math.floor(cx - rx * span);
        int x1 = (int) Math.ceil(cx + rx * span);
        for (int x = x0; x < x1; x += cw) {
            double u = (x + cw * 0.5 - cx) / rx;
            double ax = a * Math.exp(-u * u * 1.6);
            if (ax * 255 < 0.6) {
                continue;
            }
            double prevV = -span;
            double prevA = 0;
            for (int k = 1; k < GLOW_KNOTS.length; k++) {
                double v = GLOW_KNOTS[k];
                double av = ax * Math.exp(-v * v * 1.6);
                int ya = (int) Math.round(cy + prevV * ry);
                int yb = (int) Math.round(cy + v * ry);
                if (yb > ya && (prevA > 0.002 || av > 0.002)) {
                    p.gradient(x, ya, x + cw, yb, alpha(rgb, prevA), alpha(rgb, av));
                }
                prevV = v;
                prevA = av;
            }
        }
    }

    /**
     * A small soft blob (an ember, a lit window's halo, a puff of smoke): three nested discs of horizontal spans, the
     * outer ones fainter, so it reads round and soft with a few dozen rectangles.
     */
    static void blob(Px p, double cx, double cy, double r, int rgb, double a) {
        if (r < 0.6 || a <= 0.003) {
            return;
        }
        for (int layer = 0; layer < 3; layer++) {
            double rr = r * (1.0 - layer * 0.3);
            double la = a * (layer == 0 ? 0.30 : layer == 1 ? 0.45 : 0.75);
            int step = rr > 12 ? 2 : 1;
            int c = alpha(rgb, la);
            int top = (int) Math.floor(cy - rr);
            int bottom = (int) Math.ceil(cy + rr);
            for (int y = top; y < bottom; y += step) {
                double dy = (y + step * 0.5 - cy) / rr;
                double q = 1 - dy * dy;
                if (q <= 0) {
                    continue;
                }
                double half = rr * Math.sqrt(q);
                int x0 = (int) Math.round(cx - half);
                int x1 = (int) Math.round(cx + half);
                if (x1 > x0) {
                    p.fill(x0, y, x1, y + step, c);
                }
            }
        }
    }

    double[] GLOW_KNOTS = {-2.4, -1.7, -1.2, -0.8, -0.45, -0.15, 0.15, 0.45, 0.8, 1.2, 1.7, 2.4};
}
