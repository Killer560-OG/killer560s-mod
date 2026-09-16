package com.killer560.hub.quiver;

import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudVisibility;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real Skyblock quiver-arrow-count display, ported from Odin's own {@code QuiverDisplay.kt}. Real
 * arrow/quiver items (Flint Arrow, Spirit Bow ammo, etc.) show their remaining count on a real visible
 * lore line ("Arrows Remaining: N") rather than exposing it any other way. Scans the player's own real
 * inventory (hotbar, main inventory, and off hand) each tick for a Feather- or Arrow-type item carrying
 * that lore line - simpler and more robust than Odin's own fixed-slot-index check, which depends on
 * exactly which menu is open and isn't something this session could confirm still matches a specific
 * slot number in this Minecraft version.
 */
public final class QuiverDisplayFeature {

    private static final Pattern ARROWS_REMAINING = Pattern.compile("^Arrows Remaining: ([\\d,]+)$");

    private static String cachedName = null;
    private static String cachedCount = null;

    private QuiverDisplayFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(QuiverDisplayFeature::tick);
    }

    private static void tick(Minecraft client) {
        if (!QuiverDisplayConfig.getInstance().isEnabled() || client.player == null) {
            return;
        }
        Player player = client.player;
        ItemStack found = null;
        // Real 36-slot hotbar+main-inventory bound, same convention already established elsewhere in
        // this mod (e.g. AutoLeapFeature's own bounded slot search) - Inventory#items is private in
        // this version, getItem(int) is the real public accessor.
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (isQuiverItem(stack)) {
                found = stack;
                break;
            }
        }
        if (found == null && isQuiverItem(player.getOffhandItem())) {
            found = player.getOffhandItem();
        }
        if (found == null) {
            diagState("no Feather/Arrow item in inventory");
            cachedName = null;
            cachedCount = null;
            return;
        }
        String count = readArrowsRemaining(found);
        if (count == null) {
            diagState("found \"" + found.getHoverName().getString() + "\" but no 'Arrows Remaining' lore line (keeping last value)");
            return;
        }
        cachedName = found.getHoverName().getString();
        cachedCount = count;
        diagState("tracking \"" + cachedName + "\"");
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-quiver");
    private static String diagLastState;

    /** Diagnostic only (2026-09-14) - logs when WHAT is being tracked changes, never per arrow shot. */
    private static void diagState(String state) {
        if (!state.equals(diagLastState)) {
            LOGGER.info("[Quiver] {} (count={})", state, cachedCount);
            diagLastState = state;
        }
    }

    private static boolean isQuiverItem(ItemStack stack) {
        return !stack.isEmpty() && (stack.is(Items.FEATHER) || stack.is(Items.ARROW));
    }

    private static String readArrowsRemaining(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return null;
        }
        for (Component line : lore.lines()) {
            Matcher matcher = ARROWS_REMAINING.matcher(line.getString());
            if (matcher.matches()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    public static final class QuiverHudElement implements HudElement {
        @Override
        public String id() {
            return "quiver_display";
        }

        @Override
        public String displayName() {
            return "Quiver Display";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            return 420;
        }

        @Override
        public int width() {
            return 150;
        }

        @Override
        public int height() {
            return 12;
        }

        @Override
        public boolean isRelevantNow() {
            return QuiverDisplayConfig.getInstance().isEnabled();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            if (!QuiverDisplayConfig.getInstance().isEnabled() || HudVisibility.hidesHud()
                    || cachedCount == null) {
                return;
            }
            String text = (QuiverDisplayConfig.getInstance().isShowName() && cachedName != null
                    ? cachedName + " §8x" : "§8x") + "§a" + cachedCount;
            graphics.text(Minecraft.getInstance().font, text, x, y, 0xFFFFFFFF, false);
        }
    }
}
