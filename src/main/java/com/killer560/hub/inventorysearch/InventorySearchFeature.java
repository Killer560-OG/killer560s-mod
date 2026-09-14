package com.killer560.hub.inventorysearch;

import com.killer560.hub.inventorysearch.mixin.ContainerScreenPositionAccessor;
import com.killer560.hub.itembrowser.ItemBrowserConfig;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.Locale;

/**
 * Real inventory item search + highlight, ported from Noamm's own {@code InventorySearch.kt} - "if I
 * type something and do ctrl f it'll highlight items in my inventory that meet the description"
 * (killer560's own description). Works over any real {@link AbstractContainerScreen} (player inventory,
 * chests, storage, Auction House, Bazaar, anything with real slots) - Ctrl+F toggles typing a search
 * query, and every real slot whose item name (or lore, if enabled) contains the query gets a colored
 * outline drawn over it. Purely a client-side visual aid - never touches, moves, or clicks any item.
 * <p>
 * Deliberately left out Noamm's own math-expression evaluator (typing "5+3" to compute a value in the
 * search box) - that's a separate quality-of-life feature bolted onto the same text box in his version,
 * not part of what killer560 actually asked for here, and skipping it keeps this feature's own scope
 * (and testing surface) to exactly the real search-and-highlight behavior described.
 */
public final class InventorySearchFeature {

    private static final StringBuilder QUERY = new StringBuilder();
    private static boolean listening = false;

    private InventorySearchFeature() {
    }

    /** @return the real live search query, shared with {@link com.killer560.hub.itembrowser.ItemBrowserFeature}
     *  so typing once drives both the real-inventory highlight here and the NEU-style panel's own item
     *  filter - killer560's own "have searching in that bar effectively search through the not enough
     *  items... then if I type something and do ctrl f it'll highlight items in my inventory" describes
     *  one shared query feeding both, not two separate search boxes. */
    public static String getQuery() {
        return QUERY.toString();
    }

    public static boolean isListening() {
        return listening;
    }

    /** Ctrl+F/typing is shared between this feature's own real-inventory highlight and
     *  {@link com.killer560.hub.itembrowser.ItemBrowserFeature}'s NEU-style panel filter - it should
     *  work whenever EITHER one is turned on, not only when this specific feature's own toggle is,
     *  since killer560 described one shared search box driving both. */
    private static boolean anyConsumerEnabled() {
        return InventorySearchConfig.getInstance().isEnabled() || ItemBrowserConfig.getInstance().isEnabled();
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register(InventorySearchFeature::onScreenInit);
    }

    private static void onScreenInit(Minecraft client, Screen screen, int width, int height) {
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return;
        }
        listening = false;

        ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
            if (!anyConsumerEnabled()) {
                return true;
            }
            if (event.key() == InputConstants.KEY_F && event.hasControlDown()) {
                listening = !listening;
                return false;
            }
            if (!listening) {
                return true;
            }
            if (event.key() == InputConstants.KEY_ESCAPE || event.key() == InputConstants.KEY_RETURN) {
                listening = false;
                return false;
            }
            if (event.key() == InputConstants.KEY_BACKSPACE) {
                if (QUERY.length() > 0) {
                    QUERY.deleteCharAt(QUERY.length() - 1);
                }
                return false;
            }
            // Swallow every other key while typing so real inventory shortcuts (hotbar swap, drop,
            // etc.) never fire from what's meant to be search text.
            return false;
        });

        ScreenKeyboardEvents.allowCharType(screen).register((s, event) -> {
            if (!anyConsumerEnabled() || !listening) {
                return true;
            }
            QUERY.append(event.codepointAsString());
            return false;
        });

        ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, partialTick) ->
                render(containerScreen, graphics));
    }

    private static void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics) {
        boolean highlightEnabled = InventorySearchConfig.getInstance().isEnabled();
        // The NEU-style panel (when on) already has its own search bar built into its header, sharing
        // this same query/listening state - drawing this standalone floating box too would just be a
        // second, redundant search box on screen at the same time.
        boolean panelHasOwnBox = ItemBrowserConfig.getInstance().isEnabled();
        if (!highlightEnabled && !panelHasOwnBox) {
            return;
        }

        if (!panelHasOwnBox) {
            int screenWidth = screen.width;
            int screenHeight = screen.height;
            int boxWidth = 140;
            int boxHeight = 16;
            int boxX = (screenWidth - boxWidth) / 2;
            int boxY = screenHeight - 30;

            String boxQuery = QUERY.toString();
            graphics.fill(boxX, boxY, boxX + boxWidth, boxY + boxHeight, 0xCC0D0D0D);
            graphics.outline(boxX, boxY, boxWidth, boxHeight, listening ? 0xFFCC6600 : 0xFF553311);
            String display = boxQuery.isEmpty() ? "§8Ctrl+F to search..." : boxQuery;
            graphics.text(Minecraft.getInstance().font, display, boxX + 4, boxY + 4, 0xFFFFFFFF, false);
        }

        if (!highlightEnabled) {
            return;
        }
        InventorySearchConfig cfg = InventorySearchConfig.getInstance();
        String query = QUERY.toString();
        if (query.isBlank()) {
            return;
        }

        ContainerScreenPositionAccessor accessor = (ContainerScreenPositionAccessor) screen;
        int leftPos = accessor.killer560smod$getLeftPos();
        int topPos = accessor.killer560smod$getTopPos();

        for (Slot slot : screen.getMenu().slots) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !matches(stack, query, cfg)) {
                continue;
            }
            int x = leftPos + slot.x;
            int y = topPos + slot.y;
            graphics.outline(x - 1, y - 1, 18, 18, cfg.getHighlightColor());
        }
    }

    private static boolean matches(ItemStack stack, String query, InventorySearchConfig cfg) {
        String needle = cfg.isIgnoreCase() ? query.toLowerCase(Locale.ROOT) : query;

        String name = stack.getHoverName().getString();
        if ((cfg.isIgnoreCase() ? name.toLowerCase(Locale.ROOT) : name).contains(needle)) {
            return true;
        }

        if (!cfg.isSearchLore()) {
            return false;
        }
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return false;
        }
        for (Component line : lore.lines()) {
            String text = line.getString();
            if ((cfg.isIgnoreCase() ? text.toLowerCase(Locale.ROOT) : text).contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
