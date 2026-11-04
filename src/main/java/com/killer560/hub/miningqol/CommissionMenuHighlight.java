package com.killer560.hub.miningqol;

import com.killer560.hub.croesus.DungeonChestValuer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Completed Commissions (killer560, 2026-11-03: "also have it highlight completed commissions"): in Hypixel's
 * "Commissions" menu (the King's and the Emissaries' container, title seen in his log 2026-11-02), a commission whose item
 * says it is finished gets a green slot background and border, drawn behind the item like Croesus' Highlight Runs (called
 * from {@code mixin/CommissionMenuSlotMixin} at the {@code Slot.isFake()} call in {@code extractSlot}). Draw-only: no
 * click, no packet.
 *
 * <p>Finished means a lore line that is the word "COMPLETED" (upper case, as Hypixel writes a state), a "Click to claim"
 * line, or a progress line reading exactly 100%. Only the menu's own slots are read, never his inventory below it.
 */
public final class CommissionMenuHighlight {

    public static final String TITLE = "Commissions";
    private static final int FILL = 0x6055FF55;
    private static final int BORDER = 0xE055FF55;
    private static final Pattern COMPLETED = Pattern.compile("(?:^|[^A-Za-z])COMPLETED(?:$|[^A-Za-z])");
    private static final Pattern FULL = Pattern.compile("(?i)^(?:progress:?\\s*)?100(?:\\.0{1,2})?%$");

    private static Object cachedScreen;
    private static ItemStack[] cachedStacks = new ItemStack[0];
    private static boolean[] cachedDone = new boolean[0];

    private CommissionMenuHighlight() {
    }

    /** Whether lore lines (codes stripped) say the commission is finished (also the testkit's hook). */
    public static boolean completed(List<String> lore) {
        for (String raw : lore) {
            String l = raw.trim();
            if (COMPLETED.matcher(l).find() || FULL.matcher(l).matches()
                    || l.toLowerCase(Locale.ROOT).contains("click to claim")) {
                return true;
            }
        }
        return false;
    }

    public static boolean isMenu(AbstractContainerScreen<?> screen) { return TITLE.equals(screen.getTitle().getString()); }

    /** Whether {@code slot} of {@code screen} is drawn green now. */
    public static boolean highlighted(AbstractContainerScreen<?> screen, Slot slot) {
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        if (!cfg.isCommissionMenuHighlight() || !cfg.isCommissionHud() || !isMenu(screen)) return false;
        int top = screen.getMenu().slots.size() - 36;
        if (slot.index < 0 || slot.index >= top || slot.container instanceof net.minecraft.world.entity.player.Inventory) {
            return false;
        }
        if (cachedScreen != screen || cachedStacks.length != top) {
            cachedScreen = screen;
            cachedStacks = new ItemStack[top];
            cachedDone = new boolean[top];
        }
        ItemStack stack = slot.getItem();
        if (cachedStacks[slot.index] != stack) {
            cachedStacks[slot.index] = stack;
            cachedDone[slot.index] = !stack.isEmpty() && completed(DungeonChestValuer.cleanLore(stack));
        }
        return cachedDone[slot.index];
    }

    public static void drawSlot(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen, Slot slot) {
        if (!highlighted(screen, slot)) return;
        drawGreen(graphics, slot.x, slot.y, FILL, BORDER);
        drawn++;
    }

    /** A filled 16x16 slot with a 1 px border inside it (shared with the loadout highlight). */
    public static void drawGreen(GuiGraphicsExtractor graphics, int x, int y, int fill, int border) {
        graphics.fill(x, y, x + 16, y + 16, fill);
        graphics.fill(x, y, x + 16, y + 1, border);
        graphics.fill(x, y + 15, x + 16, y + 16, border);
        graphics.fill(x, y + 1, x + 1, y + 15, border);
        graphics.fill(x + 15, y + 1, x + 16, y + 15, border);
    }

    private static long drawn;

    /** Testkit: slot draws so far. */
    public static long drawnCount() { return drawn; }

    /** Testkit: the menu slots of {@code screen} drawn green now. */
    public static List<Integer> highlightedSlots(AbstractContainerScreen<?> screen) {
        List<Integer> out = new java.util.ArrayList<>();
        for (Slot s : screen.getMenu().slots) {
            if (highlighted(screen, s)) {
                out.add(s.index);
            }
        }
        return out;
    }
}
