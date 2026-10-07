package com.killer560.hub.hud;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.ColoredRectangleRenderState;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2f;

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
 * <p>The parts here are exactly the {@link ColoredRectangleRenderState}s {@code fill(int,int,int,int,int)} would build
 * (same pipeline, texture setup, pose copy, corner swap and scissor; {@code fill}, {@code outline} and {@code innerFill}
 * are byte-identical in 26.1.2 and 26.2), and the element emits their vertices in the order they were added, so the
 * pixels are the same. The element's bounds are the union of the parts': anything drawn after it that touches that box
 * lands above it, which keeps every overlap in its original order. Parts that the scissor removes entirely are dropped,
 * as vanilla drops an element with no bounds.
 */
public final class GuiRects {

    private final GuiGraphicsExtractor graphics;
    private ColoredRectangleRenderState[] parts = new ColoredRectangleRenderState[8];
    private int count;

    private GuiRects(GuiGraphicsExtractor graphics) {
        this.graphics = graphics;
    }

    /** Starts a batch on {@code graphics}; add fills, then {@link #submit()}. Use the current pose and scissor. */
    public static GuiRects begin(GuiGraphicsExtractor graphics) {
        return new GuiRects(graphics);
    }

    /** As {@code GuiGraphicsExtractor.fill(x0, y0, x1, y1, color)}. */
    public GuiRects fill(int x0, int y0, int x1, int y1, int color) {
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
        if (count == parts.length) {
            parts = Arrays.copyOf(parts, count * 2);
        }
        parts[count++] = new ColoredRectangleRenderState(RenderPipelines.GUI, TextureSetup.noTexture(),
                new Matrix3x2f(graphics.pose()), x0, y0, x1, y1, color, color, graphics.scissorStack.peek());
        return this;
    }

    /** As {@code GuiGraphicsExtractor.outline(x, y, w, h, color)}: the same four fills in the same order. */
    public GuiRects outline(int x, int y, int w, int h, int color) {
        fill(x, y, x + w, y + 1, color);
        fill(x, y + h - 1, x + w, y + h, color);
        fill(x, y + 1, x + 1, y + h - 1, color);
        fill(x + w - 1, y + 1, x + w, y + h - 1, color);
        return this;
    }

    /** Hands the fills to the render state: one element, or each on its own if they do not share a scissor. */
    public void submit() {
        int kept = 0;
        ScreenRectangle scissor = null;
        boolean sameScissor = true;
        int left = Integer.MAX_VALUE;
        int top = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        int bottom = Integer.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            ColoredRectangleRenderState p = parts[i];
            ScreenRectangle b = p.bounds();
            if (b == null) {
                continue; // vanilla's addGuiElement drops an element with no bounds
            }
            if (kept == 0) {
                scissor = p.scissorArea();
            } else if (!Objects.equals(scissor, p.scissorArea())) {
                sameScissor = false;
            }
            parts[kept++] = p;
            left = Math.min(left, b.left());
            top = Math.min(top, b.top());
            right = Math.max(right, b.right());
            bottom = Math.max(bottom, b.bottom());
        }
        if (kept == 0) {
            return;
        }
        if (kept == 1 || !sameScissor) {
            for (int i = 0; i < kept; i++) {
                graphics.guiRenderState.addGuiElement(parts[i]);
            }
            return;
        }
        graphics.guiRenderState.addGuiElement(new Batch(Arrays.copyOf(parts, kept),
                new ScreenRectangle(left, top, right - left, bottom - top), scissor));
    }

    private record Batch(ColoredRectangleRenderState[] parts, ScreenRectangle bounds, ScreenRectangle scissorArea)
            implements GuiElementRenderState {

        @Override
        public void buildVertices(VertexConsumer consumer) {
            for (ColoredRectangleRenderState part : parts) {
                part.buildVertices(consumer);
            }
        }

        @Override
        public RenderPipeline pipeline() {
            return RenderPipelines.GUI;
        }

        @Override
        public TextureSetup textureSetup() {
            return TextureSetup.noTexture();
        }
    }
}
