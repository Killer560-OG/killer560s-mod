package com.killer560.hub.compactorpreview;

import com.killer560.hub.croesus.DungeonChestValuer;
import com.killer560.hub.miningqol.CommissionMenuHighlight;
import com.killer560.hub.util.SkyblockGate;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Compactor menu highlight (killer560, 2026-11-17): in "(2/3) Personal Mining Compactor", "Personal Husbandry Compactor",
 * (skill compactors ONLY, killer560 11-18: never the plain "Personal Compactor", the Deletor or Sacks; titles from his logger capture) every entry whose lore has a whole
 * line "ENABLED" gets a green slot, one with a whole line "DISABLED" a red one (SkyBlock 0.27.3 writes the state as its
 * own line under "Click to enable!/disable!"). Anything else (arrows, barrier, filler, a description that merely says the
 * word) gets nothing. The state is read once per item (cached by stack identity: the server resends the item after a
 * click, so the color flips when the new one arrives, never predicted), drawn behind the item from
 * {@code CommissionMenuSlotMixin}. Draw-only.
 */
public final class CompactorMenuHighlight {

    private static final Pattern TITLE = Pattern.compile("^(?:\\(\\d+/\\d+\\) )?Personal [A-Za-z]+ Compactor$");
    // Fills differ in brightness (green bright, red darker) so the two stay apart without telling hues.
    private static final int GREEN_FILL = 0xA055FF55, GREEN_BORDER = 0xFF55FF55;
    private static final int RED_FILL = 0x80AA0000, RED_BORDER = 0xFFFF5555;

    private static Object cachedScreen;
    private static ItemStack[] cachedStacks = new ItemStack[0];
    private static byte[] cachedState = new byte[0];

    private CompactorMenuHighlight() {
    }

    public static boolean isMenu(AbstractContainerScreen<?> screen) {
        return TITLE.matcher(screen.getTitle().getString()).matches();
    }

    /** 1 enabled, -1 disabled, 0 neither: whole lore lines (codes stripped), also the testkit's hook. */
    public static int stateOf(List<String> lore) {
        for (String raw : lore) {
            String l = raw.trim();
            if (l.equals("ENABLED")) return 1;
            if (l.equals("DISABLED")) return -1;
        }
        return 0;
    }

    /** The slot's state now (1, -1 or 0), 0 while the feature is off or this is not a compactor menu. */
    public static int state(AbstractContainerScreen<?> screen, Slot slot) {
        CompactorPreviewConfig cfg = CompactorPreviewConfig.getInstance();
        if (!cfg.isEnabledRaw() || !cfg.isHighlight() || !isMenu(screen)
                || cfg.isSkyblockOnly() && !SkyblockGate.allows()) {
            return 0;
        }
        int top = screen.getMenu().slots.size() - 36;
        if (slot.index < 0 || slot.index >= top || slot.container instanceof Inventory) return 0;
        if (cachedScreen != screen || cachedStacks.length != top) {
            cachedScreen = screen;
            cachedStacks = new ItemStack[top];
            cachedState = new byte[top];
        }
        ItemStack stack = slot.getItem();
        if (cachedStacks[slot.index] != stack) {
            cachedStacks[slot.index] = stack;
            cachedState[slot.index] = (byte) (stack.isEmpty() ? 0 : stateOf(DungeonChestValuer.cleanLore(stack)));
        }
        return cachedState[slot.index];
    }

    public static void drawSlot(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen, Slot slot) {
        int s = state(screen, slot);
        if (s == 0) return;
        if (s > 0) {
            CommissionMenuHighlight.drawGreen(graphics, slot.x, slot.y, GREEN_FILL, GREEN_BORDER);
        } else {
            CommissionMenuHighlight.drawGreen(graphics, slot.x, slot.y, RED_FILL, RED_BORDER);
        }
        drawn++;
    }

    private static long drawn;

    /** Testkit: slot draws so far. */
    public static long drawnCount() { return drawn; }

    /** Testkit: slot index -> 1 / -1 for every slot highlighted now. */
    public static List<int[]> highlighted(AbstractContainerScreen<?> screen) {
        List<int[]> out = new ArrayList<>();
        for (Slot s : screen.getMenu().slots) {
            int st = state(screen, s);
            if (st != 0) out.add(new int[]{s.index, st});
        }
        return out;
    }
}
