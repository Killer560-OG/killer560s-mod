package com.killer560.hub.hud;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.ColoredRectangleRenderState;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.util.Mth;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.joml.Vector2f;

import java.util.Arrays;
import java.util.Objects;

/**
 * Several solid fills submitted as ONE GUI render-state element.
 *
 * <p>Every {@code GuiGraphicsExtractor.fill} is its own element, and {@code GuiRenderState} places each new element by
 * walking the strata from the top and testing it against every element already there
 * ({@code navigateToAboveHighestElementWithIntersectingBounds} / {@code hasIntersection}, javap 26.1.2 and 26.2). A slot
 * backdrop drawn as a fill plus a four-fill outline before each item of a 90-slot menu is 450 elements, each tested
 * against all the items before it: the Inventory Theme spent 8% of the render thread in {@code hasIntersection} with a
 * chest open (403-perf-hub, 2026-10-07).
 *
 * <p>The parts are exactly what {@code fill(int,int,int,int,int)} would build as {@link ColoredRectangleRenderState}s
 * (same pipeline, texture setup, pose, corner swap and scissor; {@code fill}, {@code outline} and {@code innerFill} are
 * byte-identical in 26.1.2 and 26.2), and the element emits their vertices in the order they were added, with the same
 * four {@code addVertexWith2DPose}/{@code setColor} calls {@code ColoredRectangleRenderState.buildVertices} makes, so the
 * pixels are the same. The element's bounds are the union of the parts' (each computed as
 * {@code ColoredRectangleRenderState.getBounds} does: {@code transformMaxBounds} of the corners, then the scissor's
 * {@code intersection}): anything drawn after it that touches that box lands above it, which keeps every overlap in its
 * original order. Parts that the scissor removes entirely are dropped, as vanilla drops an element with no bounds.
 *
 * <p>Allocation (fps-gui-1015, 2026-10-15): a part is six ints and a pose reference in this batcher's arrays, not a
 * render state, a pose copy and three or four bounds objects (ScreenRectangle, ScreenPosition, four Vector2f) - those
 * were about a third of the 340-700 KB the mod menu allocated per frame (95-perf-screens JFR). The pose copy is shared
 * while the pose does not change (it is never written after it is made). A lone part, or parts under different
 * scissors, still go out as the very {@code ColoredRectangleRenderState}s vanilla builds.
 */
public final class GuiRects {

    private static final int F = 6; // ints per part: x0 y0 x1 y1 col1 col2

    private GuiGraphicsExtractor graphics;
    private int[] data;
    private Matrix3x2fc[] poses;
    private ScreenRectangle[] scissors;
    private int count;
    /** A {@link #begin} batcher: its arrays come from {@link #POOL} and go back at {@link #submit} (the Batch keeps a
     *  copy), so a few-hundred-quad scene does not regrow them from 8 every frame. */
    private final boolean pooled;
    private Object[] holder;

    /** Spare part arrays for {@link #begin} batchers (render thread only). Several batches can be open at once (a card
     *  and its veil), so it is a stack, not one shared set. */
    private static final java.util.ArrayDeque<Object[]> POOL = new java.util.ArrayDeque<>();

    /** The last pose copy handed out, reused while the live pose has the same six values (render thread only). */
    private static Matrix3x2f sharedPose;
    /** Scratch for the bounds of one part (render thread only). */
    private static final Vector2f V = new Vector2f();

    private GuiRects(GuiGraphicsExtractor graphics, boolean pooled) {
        this.graphics = graphics;
        this.pooled = pooled;
        if (!pooled) {
            data = new int[8 * F];
            poses = new Matrix3x2fc[8];
            scissors = new ScreenRectangle[8];
        }
    }

    private void acquire() {
        Object[] a = POOL.pollFirst();
        if (a == null) {
            holder = new Object[3];
            data = new int[32 * F];
            poses = new Matrix3x2fc[32];
            scissors = new ScreenRectangle[32];
        } else {
            holder = a;
            data = (int[]) a[0];
            poses = (Matrix3x2fc[]) a[1];
            scissors = (ScreenRectangle[]) a[2];
        }
    }

    private void release() {
        if (!pooled || data == null) {
            return;
        }
        Arrays.fill(poses, 0, count, null);
        Arrays.fill(scissors, 0, count, null);
        if (POOL.size() < 32) {
            holder[0] = data;
            holder[1] = poses;
            holder[2] = scissors;
            POOL.addFirst(holder);
        }
        holder = null;
        data = null;
        poses = null;
        scissors = null;
    }

    /** A batcher kept by a caller that draws every frame (Health and Mana Bars): {@link #start} it each time instead
     *  of {@link #begin}, so the batcher itself is not allocated per frame. Render thread only. */
    public static GuiRects reusable() {
        return new GuiRects(null, false);
    }

    /** Starts a new batch on {@code graphics} with this batcher; forgets the previous batch's parts. */
    public GuiRects start(GuiGraphicsExtractor graphics) {
        Arrays.fill(poses, 0, count, null);
        Arrays.fill(scissors, 0, count, null);
        this.graphics = graphics;
        this.count = 0;
        return this;
    }

    /** Starts a batch on {@code graphics}; add fills, then {@link #submit()}. Use the current pose and scissor. */
    public static GuiRects begin(GuiGraphicsExtractor graphics) {
        return new GuiRects(graphics, true);
    }

    /** As {@code GuiGraphicsExtractor.fill(x0, y0, x1, y1, color)}. */
    public GuiRects fill(int x0, int y0, int x1, int y1, int color) {
        if (com.killer560.hub.gui.MenuAnim.fade < 1f) {
            color = com.killer560.hub.gui.MenuAnim.faded(color); // the mod menu fading (gui/MenuAnim)
        }
        if (x0 < x1) {
            int t = x0;
            x0 = x1;
            x1 = t;
        }
        if (y0 < y1) {
            int t = y0;
            y0 = y1;
            y1 = t;
        }
        add(x0, y0, x1, y1, color, color);
        return this;
    }

    /** As {@code GuiGraphicsExtractor.fillGradient(x0, y0, x1, y1, top, bottom)}: a vertical gradient, {@code top} at the
     *  upper edge ({@code col1} is the y0 vertex colour and {@code fillGradient} does not swap corners, javap 26.1.2 and
     *  26.2). Here the corners are put in order first, so {@code top} is always the upper colour. */
    public GuiRects gradient(int x0, int y0, int x1, int y1, int top, int bottom) {
        if (x0 > x1) {
            int t = x0;
            x0 = x1;
            x1 = t;
        }
        if (y0 > y1) {
            int t = y0;
            y0 = y1;
            y1 = t;
        }
        if (x0 == x1 || y0 == y1) {
            return this;
        }
        if (com.killer560.hub.gui.MenuAnim.fade < 1f) {
            top = com.killer560.hub.gui.MenuAnim.faded(top);
            bottom = com.killer560.hub.gui.MenuAnim.faded(bottom);
        }
        add(x0, y0, x1, y1, top, bottom);
        return this;
    }

    private void add(int x0, int y0, int x1, int y1, int col1, int col2) {
        if (data == null) {
            acquire(); // the first part of a pooled batch (a pooled batch already submitted starts a new one)
            if (released) {
                count = 0;
                released = false;
            }
        }
        if (count == poses.length) {
            int n = count * 2;
            data = Arrays.copyOf(data, n * F);
            poses = Arrays.copyOf(poses, n);
            scissors = Arrays.copyOf(scissors, n);
        }
        int o = count * F;
        data[o] = x0;
        data[o + 1] = y0;
        data[o + 2] = x1;
        data[o + 3] = y1;
        data[o + 4] = col1;
        data[o + 5] = col2;
        poses[count] = pose(graphics.pose());
        scissors[count] = graphics.scissorStack.peek();
        count++;
    }

    /** A copy of {@code live} that no one writes: the shared one when it still holds the same values. */
    private static Matrix3x2fc pose(Matrix3x2fc live) {
        Matrix3x2f s = sharedPose;
        if (s != null && s.m00() == live.m00() && s.m01() == live.m01() && s.m10() == live.m10()
                && s.m11() == live.m11() && s.m20() == live.m20() && s.m21() == live.m21()
                // -0.0 == 0.0 above; a copy keeps the sign bits, so tell them apart too
                && Float.floatToRawIntBits(s.m00()) == Float.floatToRawIntBits(live.m00())
                && Float.floatToRawIntBits(s.m01()) == Float.floatToRawIntBits(live.m01())
                && Float.floatToRawIntBits(s.m10()) == Float.floatToRawIntBits(live.m10())
                && Float.floatToRawIntBits(s.m11()) == Float.floatToRawIntBits(live.m11())
                && Float.floatToRawIntBits(s.m20()) == Float.floatToRawIntBits(live.m20())
                && Float.floatToRawIntBits(s.m21()) == Float.floatToRawIntBits(live.m21())) {
            return s;
        }
        s = new Matrix3x2f(live);
        sharedPose = s;
        return s;
    }

    /** As {@code GuiGraphicsExtractor.outline(x, y, w, h, color)}: the same four fills in the same order. */
    public GuiRects outline(int x, int y, int w, int h, int color) {
        fill(x, y, x + w, y + 1, color);
        fill(x, y + h - 1, x + w, y + h, color);
        fill(x, y + 1, x + 1, y + h - 1, color);
        fill(x + w - 1, y + 1, x + w, y + h - 1, color);
        return this;
    }

    /** Bounds of part {@code i} into {@code out} (left, top, right, bottom); false when it has none (scissored away).
     *  Exactly {@code ColoredRectangleRenderState.getBounds}: {@code new ScreenRectangle(x0, y0, x1 - x0, y1 - y0)
     *  .transformMaxBounds(pose)}, intersected with the scissor (javap 26.1.2; 26.2 the same). */
    private boolean bounds(int i, int[] out) {
        int o = i * F;
        int left = data[o];
        int top = data[o + 1];
        int right = left + (data[o + 2] - left);
        int bottom = top + (data[o + 3] - top);
        Matrix3x2fc p = poses[i];
        p.transformPosition(left, top, V);
        float ax = V.x;
        float ay = V.y;
        p.transformPosition(right, top, V);
        float bx = V.x;
        float by = V.y;
        p.transformPosition(left, bottom, V);
        float cx = V.x;
        float cy = V.y;
        p.transformPosition(right, bottom, V);
        float dx = V.x;
        float dy = V.y;
        float minX = Math.min(Math.min(ax, cx), Math.min(bx, dx));
        float maxX = Math.max(Math.max(ax, cx), Math.max(bx, dx));
        float minY = Math.min(Math.min(ay, cy), Math.min(by, dy));
        float maxY = Math.max(Math.max(ay, cy), Math.max(by, dy));
        int l = Mth.floor(minX);
        int t = Mth.floor(minY);
        int r = l + Mth.ceil(maxX - minX);
        int b = t + Mth.ceil(maxY - minY);
        ScreenRectangle s = scissors[i];
        if (s != null) {
            l = Math.max(s.left(), l);
            t = Math.max(s.top(), t);
            r = Math.min(s.right(), r);
            b = Math.min(s.bottom(), b);
            if (l >= r || t >= b) {
                return false;
            }
        }
        out[0] = l;
        out[1] = t;
        out[2] = r;
        out[3] = b;
        return true;
    }

    private static final int[] B = new int[4];

    /** Hands the fills to the render state: one element, or each on its own if they do not share a scissor. */
    public void submit() {
        try {
            submitParts();
        } finally {
            if (pooled && data != null) {
                release();
                released = true;
            }
        }
    }

    /** Set once a pooled batch's arrays went back: its parts were submitted, and the count is kept only for reading. */
    private boolean released;

    private void submitParts() {
        if (data == null) {
            return; // nothing added (or already submitted)
        }
        int kept = 0;
        ScreenRectangle scissor = null;
        boolean sameScissor = true;
        int left = Integer.MAX_VALUE;
        int top = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        int bottom = Integer.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            if (!bounds(i, B)) {
                continue; // vanilla's addGuiElement drops an element with no bounds
            }
            if (kept == 0) {
                scissor = scissors[i];
            } else if (!Objects.equals(scissor, scissors[i])) {
                sameScissor = false;
            }
            if (kept != i) {
                System.arraycopy(data, i * F, data, kept * F, F);
                poses[kept] = poses[i];
                scissors[kept] = scissors[i];
            }
            kept++;
            left = Math.min(left, B[0]);
            top = Math.min(top, B[1]);
            right = Math.max(right, B[2]);
            bottom = Math.max(bottom, B[3]);
        }
        if (kept == 0) {
            return;
        }
        if (kept == 1 || !sameScissor) {
            for (int i = 0; i < kept; i++) {
                graphics.guiRenderState.addGuiElement(state(data, poses, scissors, i));
            }
            return;
        }
        graphics.guiRenderState.addGuiElement(new Batch(Arrays.copyOf(data, kept * F), Arrays.copyOf(poses, kept), kept,
                new ScreenRectangle(left, top, right - left, bottom - top), scissor));
    }

    /** Part {@code i} as the {@link ColoredRectangleRenderState} {@code fill} builds (vanilla computes its bounds). */
    private static ColoredRectangleRenderState state(int[] data, Matrix3x2fc[] poses, ScreenRectangle[] scissors, int i) {
        int o = i * F;
        return new ColoredRectangleRenderState(RenderPipelines.GUI, TextureSetup.noTexture(), poses[i], data[o],
                data[o + 1], data[o + 2], data[o + 3], data[o + 4], data[o + 5], scissors[i]);
    }

    private static final class Batch implements GuiElementRenderState {
        private final int[] data;
        private final Matrix3x2fc[] poses;
        private final int n;
        private final ScreenRectangle bounds;
        private final ScreenRectangle scissorArea;

        Batch(int[] data, Matrix3x2fc[] poses, int n, ScreenRectangle bounds, ScreenRectangle scissorArea) {
            this.data = data;
            this.poses = poses;
            this.n = n;
            this.bounds = bounds;
            this.scissorArea = scissorArea;
        }

        /** As {@code ColoredRectangleRenderState.buildVertices}, part by part in order. */
        @Override
        public void buildVertices(VertexConsumer consumer) {
            for (int i = 0; i < n; i++) {
                int o = i * F;
                Matrix3x2fc pose = poses[i];
                float x0 = data[o];
                float y0 = data[o + 1];
                float x1 = data[o + 2];
                float y1 = data[o + 3];
                int col1 = data[o + 4];
                int col2 = data[o + 5];
                consumer.addVertexWith2DPose(pose, x0, y0).setColor(col1);
                consumer.addVertexWith2DPose(pose, x0, y1).setColor(col2);
                consumer.addVertexWith2DPose(pose, x1, y1).setColor(col2);
                consumer.addVertexWith2DPose(pose, x1, y0).setColor(col1);
            }
        }

        /** The parts as vanilla render states (the testkit reads them; never on the draw path). */
        ColoredRectangleRenderState[] parts() {
            ColoredRectangleRenderState[] out = new ColoredRectangleRenderState[n];
            ScreenRectangle[] sc = new ScreenRectangle[n];
            Arrays.fill(sc, scissorArea);
            for (int i = 0; i < n; i++) {
                out[i] = state(data, poses, sc, i);
            }
            return out;
        }

        @Override
        public RenderPipeline pipeline() {
            return RenderPipelines.GUI;
        }

        @Override
        public TextureSetup textureSetup() {
            return TextureSetup.noTexture();
        }

        @Override
        public ScreenRectangle bounds() {
            return bounds;
        }

        @Override
        public ScreenRectangle scissorArea() {
            return scissorArea;
        }
    }
}
