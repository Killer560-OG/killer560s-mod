package com.killer560.hub.melody;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.StainedGlassPaneBlock;

import java.util.List;

/**
 * Read-only Floor 7 Melody terminal board parsing - a plain per-tick scan of the container's current items,
 * never a click. This is a DUPLICATE of the minimal read half of
 * {@code com.killer560.hub.terminals.TerminalSolverFeature#tickMelodyAutoClick} (the cheat-only Auto Melody
 * click loop), not a shared call into it: that method's board-reading fields
 * ({@code melodyButtonRow}/{@code melodyCurrentColumn}/{@code melodyCorrectColumn}) are private, only
 * populated while Auto Melody itself runs (cheat build, {@code isAutoMelodyEnabled()}), and folded into the
 * same method that also fires real clicks - there is no seam to call into without either changing Auto
 * Melody's own behaviour or reaching into its private state. {@link MelodyTrackerFeature} needs to work in
 * BOTH builds (legit included) and must never click anything, so this package duplicates only the read math,
 * ported byte-for-byte from that class's own comments (which cite Odin's decompiled {@code MelodyMessage}):
 * <ul>
 * <li>The moving lime-pane indicator's row is {@code floor(limeSlot / 9)} and its column within that row is
 * {@code limeSlot % 9 - 1} (real formulas confirmed via decompile, same ones {@code TerminalSolverFeature}
 * already ships).</li>
 * <li>The target marker pane can render as either {@code DyeColor.MAGENTA} or a purple close enough to read
 * as the same colour but is really a distinct DyeColor (see that class's {@code isMelodyEndpointColor}) -
 * both count here too. Its column is {@code targetSlot % 9 - 1}.</li>
 * <li>These two formulas already land in Odin's own {@code type=2}/{@code type=5} column range (0-4,
 * {@code BridgeTables.MELODY_MIN_COLUMN..MELODY_MAX_COLUMN}) - confirmed against
 * {@code com.killer560.hub.bridge.BridgeTables}'s own resolved {@code mapToRange} math, so nothing here needs
 * to re-derive or guess that mapping.</li>
 * <li>"Which rows are complete" (Odin's {@code type=1}, clay/terracotta) is NOT something
 * {@code TerminalSolverFeature} ever reads (it only ever looks at the moving lime PANE, never the clay
 * BUTTON's own item) - ported instead straight from {@code BridgeTables}' Odin research: Odin's real client
 * sends {@code type=1} exactly when one of the four clay slots (16/25/34/43, same slots
 * {@code TerminalSolverFeature.MELODY_CLAY_SLOTS} clicks) becomes {@code Items.LIME_TERRACOTTA} - so this
 * scans those same four slots each tick for that exact item, never a click, and reports the highest row
 * currently lit. <b>Not verified live</b> - see the staging notes' risk section.</li>
 * </ul>
 */
final class MelodySlotReader {

    /** Same four clay/terracotta button slots {@code TerminalSolverFeature.MELODY_CLAY_SLOTS} clicks and
     *  {@code BridgeTables}' Odin research names - index 0 = row 1, ... index 3 = row 4. */
    private static final int[] CLAY_SLOTS = {16, 25, 34, 43};

    /** @param highestLitClayRow 1-4 (Odin's own clay row numbering), or -1 if none of the four are lit yet.
     *  @param target            0-4 target/purple column, or -1 if no target pane is visible this scan.
     *  @param current           0-4 moving/lime column, or -1 if no lime pane is visible this scan. */
    record Result(int highestLitClayRow, int target, int current) {
        static final Result EMPTY = new Result(-1, -1, -1);
    }

    private MelodySlotReader() {
    }

    static Result read(List<ItemStack> items) {
        Integer limeSlot = null;
        Integer targetSlot = null;
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (stack.getItem() == Items.LIME_STAINED_GLASS_PANE) {
                limeSlot = i;
                continue;
            }
            DyeColor pane = paneDyeColor(stack);
            if (pane != null && isEndpointColor(pane)) {
                targetSlot = i;
            }
        }
        int current = limeSlot == null ? -1 : limeSlot % 9 - 1;
        int target = targetSlot == null ? -1 : targetSlot % 9 - 1;

        int highestLit = -1;
        for (int row = 0; row < CLAY_SLOTS.length; row++) {
            int slot = CLAY_SLOTS[row];
            if (slot < items.size() && items.get(slot).getItem() == Items.LIME_TERRACOTTA) {
                highestLit = row + 1; // Odin's own clay row numbering is 1-4, not the 0-based array index.
            }
        }
        return new Result(highestLit, target, current);
    }

    /** Same ambiguity {@code TerminalSolverFeature#isMelodyEndpointColor} documents. */
    private static boolean isEndpointColor(DyeColor pane) {
        return pane == DyeColor.MAGENTA || pane == DyeColor.PURPLE;
    }

    private static DyeColor paneDyeColor(ItemStack item) {
        if (!(item.getItem() instanceof BlockItem blockItem)) {
            return null;
        }
        if (!(blockItem.getBlock() instanceof StainedGlassPaneBlock pane)) {
            return null;
        }
        return pane.getColor();
    }
}
