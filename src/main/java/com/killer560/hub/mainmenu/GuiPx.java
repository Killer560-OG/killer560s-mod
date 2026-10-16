package com.killer560.hub.mainmenu;

import com.killer560.hub.hud.GuiRects;

/** {@link Px} over a frame's {@link GuiRects} batch, in whatever pose the caller set. Render thread only. */
final class GuiPx implements Px {

    private static final GuiPx INSTANCE = new GuiPx();

    private GuiRects r;

    private GuiPx() {
    }

    static GuiPx of(GuiRects r) {
        INSTANCE.r = r;
        return INSTANCE;
    }

    @Override
    public void fill(int x0, int y0, int x1, int y1, int argb) {
        if ((argb >>> 24) != 0 && x1 != x0 && y1 != y0) {
            r.fill(x0, y0, x1, y1, argb);
        }
    }

    @Override
    public void gradient(int x0, int y0, int x1, int y1, int top, int bottom) {
        if (((top | bottom) >>> 24) != 0) {
            r.gradient(x0, y0, x1, y1, top, bottom);
        }
    }
}
