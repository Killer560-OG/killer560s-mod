package com.killer560.hub.compactorpreview;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.world.item.ItemStack;

import java.util.Map;

/** The slot grid {@link CompactorPreviewFeature} adds to a Personal Compactor / Deletor tooltip: a "Contents" label and
 *  one 18-unit square per slot of the item (rows x columns by tier), the configured ones holding their item. Drawn with
 *  plain fills in vanilla's slot colors, so it needs no texture. */
final class CompactorGridComponent implements ClientTooltipComponent {

    private static final int CELL = 18;
    private static final int LABEL_H = 11;
    private static final int PAD = 2;

    private final CompactorPreviewFeature.Contents contents;
    private final Map<Integer, ItemStack> stacks;

    CompactorGridComponent(CompactorPreviewFeature.Contents contents, Map<Integer, ItemStack> stacks) {
        this.contents = contents;
        this.stacks = stacks;
    }

    @Override
    public int getHeight(Font font) {
        return LABEL_H + contents.rows() * CELL + PAD * 2;
    }

    @Override
    public int getWidth(Font font) {
        return Math.max(contents.cols() * CELL, font.width(label())) + PAD * 2;
    }

    private String label() {
        return contents.deletor() ? "Deletes" : "Compacts";
    }

    @Override
    public void extractImage(Font font, int x, int y, int width, int height, GuiGraphicsExtractor graphics) {
        int gridW = contents.cols() * CELL;
        graphics.text(font, label(), x + PAD, y + 1, 0xFFAAAAAA, true);
        int top = y + LABEL_H + PAD;
        for (int row = 0; row < contents.rows(); row++) {
            for (int col = 0; col < contents.cols(); col++) {
                int cx = x + PAD + col * CELL;
                int cy = top + row * CELL;
                // vanilla's sunken slot: dark top/left edge, light bottom/right edge, gray inside
                graphics.fill(cx, cy, cx + CELL, cy + CELL, 0xFF373737);
                graphics.fill(cx + 1, cy + 1, cx + CELL, cy + CELL, 0xFFFFFFFF);
                graphics.fill(cx + 1, cy + 1, cx + CELL - 1, cy + CELL - 1, 0xFF8B8B8B);
                ItemStack stack = stacks.get(row * contents.cols() + col);
                if (stack != null && !stack.isEmpty()) {
                    graphics.item(stack, cx + 1, cy + 1);
                }
            }
        }
    }

    /** Slot count drawn, for the tests. */
    int cells() {
        return contents.rows() * contents.cols();
    }

    /** Which slot numbers hold an item, for the tests. */
    java.util.Set<Integer> filled() {
        return stacks.keySet();
    }
}
