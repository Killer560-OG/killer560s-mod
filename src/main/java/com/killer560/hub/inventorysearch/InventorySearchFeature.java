package com.killer560.hub.inventorysearch;

import com.killer560.hub.inventorysearch.mixin.ContainerScreenPositionAccessor;
import com.killer560.hub.itembrowser.ItemBrowserConfig;
import com.killer560.hub.storageoverlay.StorageOverlayConfig;
import com.killer560.hub.storageoverlay.StorageOverlayFeature;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
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
 * <p>
 * killer560 (2026-09-21): "make it so I can click into that bar as well and adjust the scale of it.
 * Also make ctrl backspace work as normal ctrl backspace does. Also you need to adjust how it works in
 * the custom storage to actually adjust to the new slots things are in for that gui." - see
 * {@link #onScreenInit} for the click-to-focus and Ctrl+Backspace handling, and
 * {@link #renderOverlayHighlight} for the Storage Overlay slot-remapping fix.
 */
public final class InventorySearchFeature {

    /** How long a Storage Overlay grid highlight (see {@link #renderOverlayHighlight}) stays armed once
     *  fired - matches the pulse duration Storage Item Search already uses for the same real API. */
    private static final long OVERLAY_HIGHLIGHT_DURATION_MS = 4000L;

    private static final StringBuilder QUERY = new StringBuilder();
    private static boolean listening = false;

    /** Real screen bounds of the standalone floating search box from the most recent render (null
     *  whenever it isn't drawn - i.e. the Item Browser panel is showing its own header box instead) -
     *  used by the click-to-focus handler below. */
    private static int[] lastBoxBounds = null;

    /** Tracks the query/key this feature last pushed into the Storage Overlay grid's own single-slot
     *  highlight, so it's only re-armed when the match actually changes instead of fighting his own
     *  manual scrolling on the grid every single frame - see {@link #renderOverlayHighlight}. */
    private static String lastOverlayQuery = null;
    private static String lastOverlayKey = null;

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

    /** Starts (or stops) typing without needing the Ctrl+F chord - killer560 (2026-09-21): "make it so I
     *  can click into that bar as well", so a plain left click on either search box focuses it exactly
     *  like clicking into any real text field, same shared state Ctrl+F already toggles. */
    public static void setListening(boolean value) {
        listening = value;
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
        lastBoxBounds = null;
        lastOverlayQuery = null;
        lastOverlayKey = null;

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
                if (event.hasControlDown()) {
                    deletePreviousWord();
                } else if (QUERY.length() > 0) {
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

        // killer560 (2026-09-21): "make it so I can click into that bar as well" - a plain left click
        // inside whichever search box is actually on screen starts typing, same as clicking any real
        // vanilla text field. Only ever claims the click when it's actually inside the box, so normal
        // inventory clicks elsewhere are untouched.
        ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
            if (!anyConsumerEnabled() || event.button() != 0 || lastBoxBounds == null) {
                return true;
            }
            if (inside(lastBoxBounds, event.x(), event.y())) {
                listening = true;
                return false;
            }
            return true;
        });

        ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, partialTick) ->
                render(containerScreen, graphics));
    }

    /** Standard Ctrl+Backspace: deletes back through any trailing spaces, then through the word behind
     *  them - killer560 (2026-09-21): "make ctrl backspace work as normal ctrl backspace does" (the
     *  plain Backspace handler above only ever deleted one character). */
    private static void deletePreviousWord() {
        int end = QUERY.length();
        int i = end;
        while (i > 0 && QUERY.charAt(i - 1) == ' ') {
            i--;
        }
        while (i > 0 && QUERY.charAt(i - 1) != ' ') {
            i--;
        }
        QUERY.delete(i, end);
    }

    private static void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics) {
        boolean highlightEnabled = InventorySearchConfig.getInstance().isEnabled();
        // The NEU-style panel (when on) already has its own search bar built into its header, sharing
        // this same query/listening state - drawing this standalone floating box too would just be a
        // second, redundant search box on screen at the same time.
        boolean panelHasOwnBox = ItemBrowserConfig.getInstance().isEnabled();
        if (!highlightEnabled && !panelHasOwnBox) {
            lastBoxBounds = null;
            return;
        }

        if (!panelHasOwnBox) {
            InventorySearchConfig cfg = InventorySearchConfig.getInstance();
            float scale = cfg.getBoxScale();
            int boxWidth = 140;
            int boxHeight = 16;
            int screenX = (screen.width - Math.round(boxWidth * scale)) / 2;
            int screenY = screen.height - Math.round(boxHeight * scale) - 30;
            lastBoxBounds = new int[]{screenX, screenY, Math.round(boxWidth * scale), Math.round(boxHeight * scale)};

            graphics.pose().pushMatrix();
            try {
                graphics.pose().translate(screenX, screenY);
                graphics.pose().scale(scale, scale);

                String boxQuery = QUERY.toString();
                graphics.fill(0, 0, boxWidth, boxHeight, 0xCC0D0D0D);
                graphics.outline(0, 0, boxWidth, boxHeight, listening ? 0xFFCC6600 : 0xFF553311);
                String display = boxQuery.isEmpty() ? "§8Ctrl+F or click to search..." : boxQuery;
                graphics.text(Minecraft.getInstance().font, display, 4, 4, 0xFFFFFFFF, false);
            } finally {
                graphics.pose().popMatrix();
            }
        } else {
            lastBoxBounds = null;
        }

        if (!highlightEnabled) {
            return;
        }
        InventorySearchConfig cfg = InventorySearchConfig.getInstance();
        String query = QUERY.toString();
        if (query.isBlank()) {
            lastOverlayQuery = null;
            lastOverlayKey = null;
            return;
        }

        String title = screen.getTitle().getString();
        // killer560 (2026-09-21): "you need to adjust how it works in the custom storage to actually
        // adjust to the new slots things are in for that gui" - with the Storage Overlay on, every real
        // slot of a tracked Ender Chest page/Backpack is hidden and redrawn in that overlay's own grid
        // (see StorageOverlayFeature), so an outline drawn at the real vanilla slot position (below)
        // lands on an invisible slot. Reusing StorageOverlayFeature.highlightItem - the exact same real
        // hook Storage Item Search already uses for this - instead of drawing a second, separate mapping.
        boolean overlayHiding = StorageOverlayConfig.getInstance().isEnabled() && StorageOverlayFeature.shouldHideVanilla(title);
        String overlayKey = overlayHiding ? StorageOverlayFeature.storageKeyForTitle(title) : null;
        if (overlayHiding && overlayKey != null) {
            renderOverlayHighlight(screen, overlayKey, query, cfg);
        } else {
            lastOverlayQuery = null;
            lastOverlayKey = null;
        }

        ContainerScreenPositionAccessor accessor = (ContainerScreenPositionAccessor) screen;
        int leftPos = accessor.killer560smod$getLeftPos();
        int topPos = accessor.killer560smod$getTopPos();

        for (Slot slot : screen.getMenu().slots) {
            if (overlayHiding) {
                // Every real slot on a tracked overlay screen is invisible at its own vanilla position -
                // see renderOverlayHighlight above for where the highlight actually gets drawn instead.
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !matches(stack, query, cfg)) {
                continue;
            }
            int x = leftPos + slot.x;
            int y = topPos + slot.y;
            graphics.outline(x - 1, y - 1, 18, 18, cfg.getHighlightColor());
        }
    }

    /** Points the Storage Overlay grid at the first real match inside the currently open tracked page's
     *  own contents (real container slots 9..N, matching {@code StorageOverlayFeature.captureIfChanged}'s
     *  own real capture range 1:1) whenever the query actually changes - not every frame, since
     *  {@code highlightItem} also re-arms a one-shot scroll-to-it each time, which would otherwise fight
     *  his own manual scrolling on the grid while he keeps typing.
     * <p>
     * Known real limit: {@code StorageOverlayFeature.highlightItem} only tracks one slot at a time (the
     * same single-highlight API Storage Item Search itself uses), so with several matches in the same
     * page only the first is pointed at - see the staging notes for why a second, independent
     * multi-highlight mapping wasn't built to work around that. Matches inside the relocated Inventory
     * panel at the bottom of the overlay aren't covered either - that panel has no equivalent public
     * highlight hook to reuse (its own bounds are private to StorageOverlayFeature). */
    private static void renderOverlayHighlight(AbstractContainerScreen<?> screen, String overlayKey, String query,
                                                InventorySearchConfig cfg) {
        if (query.equals(lastOverlayQuery) && overlayKey.equals(lastOverlayKey)) {
            return;
        }
        lastOverlayQuery = query;
        lastOverlayKey = overlayKey;

        var slots = screen.getMenu().slots;
        int containerSlotCount = Math.max(0, slots.size() - 36);
        for (Slot slot : slots) {
            if (slot.index < 9 || slot.index >= containerSlotCount) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !matches(stack, query, cfg)) {
                continue;
            }
            StorageOverlayFeature.highlightItem(overlayKey, slot.index - 9, OVERLAY_HIGHLIGHT_DURATION_MS);
            return;
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

    private static boolean inside(int[] rect, double x, double y) {
        return x >= rect[0] && x < rect[0] + rect[2] && y >= rect[1] && y < rect[1] + rect[3];
    }
}
