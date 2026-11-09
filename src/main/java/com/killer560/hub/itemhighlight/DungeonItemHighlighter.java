package com.killer560.hub.itemhighlight;

import com.killer560.hub.cheatutils.CheatUtils;
import com.killer560.hub.pricetooltip.PriceData;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Salvage Helper and the sell highlight (featF-1108; Skyblocker's SalvageHelper): in the "Salvage Items" menu and the
 * "Trades" menu, marks every slot (menu and your inventory) holding dungeon gear that is cheap, so what is safe to
 * salvage or sell stands out. Gear = a lore line with "DUNGEON" not followed by " ITEM" (so "LEGENDARY DUNGEON SWORD"
 * matches, "DUNGEON ITEM" does not); cheap = its lowest BIN is known and under the Limit. Cost: one screen-title check
 * per opened screen and one verdict per stack, kept by identity until the screen changes or prices refresh.
 */
public final class DungeonItemHighlighter {

    private static final Pattern GEAR = Pattern.compile("DUNGEON(?! ITEM)");
    /** Translucent full-slot fills: yellow to salvage, green to sell. */
    private static final int SALVAGE_FILL = 0x90FFDD33;
    private static final int SELL_FILL = 0x9033DD55;

    private enum Mode { NONE, SALVAGE, SELL }

    private static AbstractContainerScreen<?> lastScreen;
    private static Mode mode = Mode.NONE;
    private static long verdictsPrices = -1;
    private static final Map<ItemStack, Boolean> VERDICTS = new IdentityHashMap<>();
    private static int marked;

    private DungeonItemHighlighter() {
    }

    /** Called from the slot mixin before vanilla draws a slot's item (slot-local coordinates). */
    public static void drawSlotHighlight(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen, Slot slot) {
        DungeonItemHighlightConfig cfg = DungeonItemHighlightConfig.getInstance();
        if (!cfg.isEnabled()) {
            return;
        }
        if (screen != lastScreen) {
            lastScreen = screen;
            String title = screen.getTitle().getString();
            mode = cfg.isSalvage() && title.startsWith("Salvage Items") ? Mode.SALVAGE
                    : cfg.isSell() && title.equals("Trades") ? Mode.SELL : Mode.NONE;
            VERDICTS.clear();
            if (mode != Mode.NONE) {
                PriceData.touchFromOtherFeature();
            }
        }
        if (mode == Mode.NONE) {
            return;
        }
        ItemStack stack = slot.getItem();
        if (stack.isEmpty()) {
            return;
        }
        long gen = PriceData.generation();
        if (gen != verdictsPrices || VERDICTS.size() > 256) {
            verdictsPrices = gen;
            VERDICTS.clear();
        }
        Boolean cheap = VERDICTS.get(stack);
        if (cheap == null) {
            cheap = cheapGear(stack, cfg.getLimit().coins);
            VERDICTS.put(stack, cheap);
        }
        if (cheap) {
            marked++;
            graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, mode == Mode.SALVAGE ? SALVAGE_FILL : SELL_FILL);
        }
    }

    /** Dungeon gear whose lowest BIN is known and below {@code limit}. Public for the testkit. */
    public static boolean cheapGear(ItemStack stack, long limit) {
        boolean gear = false;
        for (String line : CheatUtils.lore(stack)) {
            if (GEAR.matcher(line).find()) {
                gear = true;
                break;
            }
        }
        if (!gear) {
            return false;
        }
        Double bin = PriceData.lowestBin(CheatUtils.skyblockId(stack));
        return bin != null && bin > 0 && bin < limit;
    }

    /** Slots filled since start (the testkit asserts the highlight drew). */
    public static int marked() { return marked; }
}
