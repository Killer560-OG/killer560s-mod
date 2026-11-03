package com.killer560.hub.gui;

import com.killer560.hub.itemrarity.ItemRarityFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;

/**
 * One item in a cell of the mod's own item grids (Inventory Sorter, Custom Items, ...), drawn the way a vanilla slot
 * draws it but fitted to the cell: the Item Rarity box behind it (when that feature is on) sized to the cell, then the
 * item centered and scaled to about 80% of the cell, its count and durability bar scaled and placed with it.
 * <p>
 * A cell of 18 (a vanilla slot) draws the item at exactly 16, unscaled. Bigger cells scale the item so its edge lands
 * on whole framebuffer pixels (the pose's own scale times the GUI scale), which keeps it crisp. killer560, 2026-11-02:
 * "in /invsort the items dont fit. also if i have item rarity boxes on those should show here too".
 */
public final class ItemCell {

    /** The share of the cell's inside (inside its frame line) the item fills: about 80% of the whole cell. */
    public static final float FILL = 0.85f;

    private ItemCell() {
    }

    /** Framebuffer pixels per GUI unit at the current pose (the screen's own scale times the window's GUI scale). */
    private static float pixelsPerUnit(GuiGraphicsExtractor g) {
        float pose = Math.abs(g.pose().m00());
        int gui = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
        return Math.max(0.0001f, pose == 0 ? 1f : pose) * gui;
    }

    /** The item's drawn edge, in GUI units, for a cell of {@code size}: 16 for a vanilla-sized cell, else about
     *  {@link #FILL} of the cell rounded to whole framebuffer pixels. */
    public static float itemSize(int size, float pixelsPerUnit) {
        float target = Math.max(16f, size * FILL);
        if (target <= 16f) {
            return 16f;
        }
        return Math.max(1f, Math.round(target * pixelsPerUnit)) / pixelsPerUnit;
    }

    /**
     * Draws {@code stack} in the {@code size} x {@code size} cell at (x, y): rarity box (cell-sized), the item centered
     * and scaled, then its decorations. Returns {x, y, edge} of the drawn item in GUI units, or null for an empty stack.
     */
    public static float[] draw(GuiGraphicsExtractor g, Font font, ItemStack stack, int x, int y, int size) {
        return draw(g, font, stack, x, y, size, true);
    }

    /** As {@link #draw(GuiGraphicsExtractor, Font, ItemStack, int, int, int)}; {@code rarity} false leaves the box out
     *  (a held item following the mouse). */
    public static float[] draw(GuiGraphicsExtractor g, Font font, ItemStack stack, int x, int y, int size, boolean rarity) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        if (rarity) {
            try {
                ItemRarityFeature.drawBox(g, stack, x, y, size);
            } catch (Throwable ignored) {
                // Visual only, as in the slot mixin: never break the screen over a lore edge case.
            }
        }
        float ppu = pixelsPerUnit(g);
        float edge = itemSize(size, ppu);
        float off = Math.round((size - edge) / 2f * ppu) / ppu;
        float ix = x + off;
        float iy = y + off;
        if (edge == 16f && off == Math.round(off)) {
            g.item(stack, (int) ix, (int) iy);
            if (font != null) {
                g.itemDecorations(font, stack, (int) ix, (int) iy);
            }
            return new float[]{ix, iy, edge};
        }
        float s = edge / 16f;
        g.pose().pushMatrix();
        g.pose().translate(ix, iy);
        g.pose().scale(s, s);
        g.item(stack, 0, 0);
        if (font != null) {
            g.itemDecorations(font, stack, 0, 0);
        }
        g.pose().popMatrix();
        return new float[]{ix, iy, edge};
    }

    /** Draws a held item centered on the mouse at the size it has in a cell of {@code size}. */
    public static void drawHeld(GuiGraphicsExtractor g, ItemStack stack, int mouseX, int mouseY, int size) {
        float edge = itemSize(size, pixelsPerUnit(g));
        float s = edge / 16f;
        g.pose().pushMatrix();
        g.pose().translate(mouseX - edge / 2f, mouseY - edge / 2f);
        g.pose().scale(s, s);
        g.item(stack, 0, 0);
        g.pose().popMatrix();
    }
}
