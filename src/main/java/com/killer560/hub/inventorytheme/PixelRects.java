package com.killer560.hub.inventorytheme;

import com.killer560.hub.hud.GuiRects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix3x2f;

/**
 * Fills placed in FRAMEBUFFER pixels, from coordinates given in the caller's GUI units (killer560, 2026-10-07: "right now
 * 1 is far too large, I want to be able to make it thinner"). A GUI-unit fill is {@code guiScale} pixels at the least, so
 * a one-unit line was 2 pixels at GUI 2 and 3 at GUI 3; Line Width is now counted in real pixels.
 * <p>
 * {@link #begin} remembers the pose the caller drew in (its translate and uniform scale: a container screen's
 * leftPos/topPos, the themed hotbar's scale, Auto Scale's), then draws under {@code identity().scale(1 / guiScale)} -
 * the same "screen pixels from any pose" step the Custom Crosshair takes (docs/LESSONS-GUI.md). {@link #x}/{@link #y}
 * map a GUI-unit coordinate of the caller's pose to the nearest pixel, so a box's edges land where vanilla's own
 * sprite edges would, and a line is then a whole number of pixels thick at every GUI scale and hotbar scale. Everything
 * still goes out as ONE render-state element through {@link GuiRects}. {@link #submit} restores the pose.
 */
final class PixelRects {

    private final GuiGraphicsExtractor graphics;
    private final GuiRects rects;
    private final float m00;
    private final float m11;
    private final float m20;
    private final float m21;
    private final int guiScale;

    private PixelRects(GuiGraphicsExtractor graphics, Matrix3x2f pose, int guiScale) {
        this.graphics = graphics;
        this.m00 = pose.m00();
        this.m11 = pose.m11();
        this.m20 = pose.m20();
        this.m21 = pose.m21();
        this.guiScale = guiScale;
        graphics.pose().pushMatrix();
        graphics.pose().identity();
        graphics.pose().scale(1f / guiScale, 1f / guiScale);
        this.rects = GuiRects.begin(graphics);
    }

    static PixelRects begin(GuiGraphicsExtractor graphics) {
        int gs = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
        return new PixelRects(graphics, new Matrix3x2f(graphics.pose()), gs);
    }

    /** Pixels in one GUI unit of the caller's pose (guiScale times the pose's own scale), at least 1. */
    int unit() {
        return Math.max(1, Math.round(m00 * guiScale));
    }

    /** The pixel column of GUI-unit x in the caller's pose. */
    int x(float gx) {
        return Math.round((m00 * gx + m20) * guiScale);
    }

    /** The pixel row of GUI-unit y in the caller's pose. */
    int y(float gy) {
        return Math.round((m11 * gy + m21) * guiScale);
    }

    /** A fill in pixels; empty or inverted rects draw nothing. */
    PixelRects fill(int x0, int y0, int x1, int y1, int color) {
        if (x1 > x0 && y1 > y0) {
            rects.fill(x0, y0, x1, y1, color);
        }
        return this;
    }

    /** An outline {@code t} pixels thick drawn inside the pixel box, as four non-overlapping strips (so a translucent
     *  colour never blends twice). 0 draws nothing; a width over half the box is capped there. */
    PixelRects outline(int x0, int y0, int x1, int y1, int t, int color) {
        int w = x1 - x0;
        int h = y1 - y0;
        t = Math.min(t, Math.min(w, h) / 2);
        if (t <= 0) {
            return this;
        }
        fill(x0, y0, x1, y0 + t, color);
        fill(x0, y1 - t, x1, y1, color);
        fill(x0, y0 + t, x0 + t, y1 - t, color);
        fill(x1 - t, y0 + t, x1, y1 - t, color);
        return this;
    }

    void submit() {
        try {
            rects.submit();
        } finally {
            graphics.pose().popMatrix();
        }
    }
}
