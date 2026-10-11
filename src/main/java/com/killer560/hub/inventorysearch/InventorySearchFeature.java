package com.killer560.hub.inventorysearch;

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
 * The bar is also a calculator (killer560, 2026-10-11: "It should also be able to do math in that bar.
 * reference neu for how to do it"): a query that parses as a sum shows " = result" after it, in NEU's
 * language - see {@link SearchCalculator}. A sum highlights nothing.
 * <p>
 * A match is a full fill of the slot in the highlight colour, drawn behind the item (killer560, 2026-10-11:
 * "have the item be a full red highlight so its easier to see") from {@code InventorySearchSlotMixin}, inside the
 * slot's own draw, so it sits under the tooltip.
 * <p>
 * The bar always sits at the bottom middle of the screen, also when the Item Browser is on (killer560,
 * 2026-10-11: "Do not move my search bar above the menu the search bar should stay in the bottom middle of my
 * screen like normal") - the Item Browser filters by this same bar and has no box of its own.
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
        lastMatchQuery = null;
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

    /** Fill alpha of a matching slot - the colour setting's RGB at this alpha, behind the item. */
    private static final int MATCH_FILL_ALPHA = 0xC0;

    /** Per-slot match cache for {@link #drawSlotHighlight}: the query it was built for and stack -> matched. Identity
     *  keyed, so a slot whose stack object is replaced is simply asked again. Cleared when the query changes. */
    private static String lastMatchQuery = null;
    private static final java.util.Map<ItemStack, Boolean> MATCH_CACHE = new java.util.IdentityHashMap<>();

    /** The live query when it should highlight slots (feature on, not blank, not a sum), else null. */
    private static String highlightQuery() {
        if (!InventorySearchConfig.getInstance().isEnabled()) {
            return null;
        }
        String query = QUERY.toString();
        if (query.isBlank() || SearchCalculator.resultFor(query) != null) {
            return null;
        }
        return query;
    }

    /**
     * Called from {@code InventorySearchSlotMixin} just before vanilla draws a slot's item, so a match gets a full
     * fill of the highlight colour behind the item and under any tooltip. Slot-local coordinates (extractContents
     * has already translated by leftPos/topPos). Public for the testkit.
     */
    public static void drawSlotHighlight(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen, Slot slot) {
        if (com.killer560.hub.bazaar.BazaarReskin.isHiding(screen) || com.killer560.hub.auction.ah.AhReskin.isHiding(screen)) {
            return;
        }
        if (!slotMatches(slot.getItem())) {
            return;
        }
        int color = InventorySearchConfig.getInstance().getHighlightColor();
        graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, (MATCH_FILL_ALPHA << 24) | (color & 0xFFFFFF));
    }

    /** @return whether {@code stack} is highlighted by the live query right now. Public for the testkit. */
    public static boolean slotMatches(ItemStack stack) {
        String query = highlightQuery();
        if (query == null || stack == null || stack.isEmpty()) {
            return false;
        }
        if (!query.equals(lastMatchQuery)) {
            lastMatchQuery = query;
            MATCH_CACHE.clear();
        }
        if (MATCH_CACHE.size() > 4096) {
            MATCH_CACHE.clear();
        }
        return MATCH_CACHE.computeIfAbsent(stack, st -> matches(st, query, InventorySearchConfig.getInstance()));
    }

    /** The text shown in the bar: the query, plus " = result" in green when it is a sum. Public for the testkit. */
    public static String barText() {
        String query = QUERY.toString();
        if (query.isEmpty()) {
            return "\u00a78Ctrl+F or click to search...";
        }
        String result = SearchCalculator.resultFor(query);
        return result == null ? query : query + " \u00a77= \u00a7a" + result;
    }

    private static void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics) {
        if (com.killer560.hub.bazaar.BazaarReskin.isHiding(screen)
                || com.killer560.hub.auction.ah.AhReskin.isHiding(screen)) {
            return; // a reskinned Bazaar or Auction House menu covers the chest; nothing of this belongs on top of it
        }
        boolean highlightEnabled = InventorySearchConfig.getInstance().isEnabled();
        if (!highlightEnabled && !ItemBrowserConfig.getInstance().isEnabled()) {
            lastBoxBounds = null;
            return;
        }

        // One bar, bottom middle, whichever of the two features is on (the Item Browser filters by it too).
        InventorySearchConfig cfg = InventorySearchConfig.getInstance();
        float scale = cfg.getBoxScale();
        String display = barText();
        int boxWidth = Math.max(140, Minecraft.getInstance().font.width(display) + 8);
        int boxHeight = 16;
        int screenX = (screen.width - Math.round(boxWidth * scale)) / 2;
        int screenY = screen.height - Math.round(boxHeight * scale) - 30;
        lastBoxBounds = new int[]{screenX, screenY, Math.round(boxWidth * scale), Math.round(boxHeight * scale)};

        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate(screenX, screenY);
            graphics.pose().scale(scale, scale);
            graphics.fill(0, 0, boxWidth, boxHeight, 0xCC0D0D0D);
            graphics.outline(0, 0, boxWidth, boxHeight, listening ? 0xFFCC6600 : 0xFF553311);
            graphics.text(Minecraft.getInstance().font, display, 4, 4, 0xFFFFFFFF, false);
        } finally {
            graphics.pose().popMatrix();
        }

        String query = highlightQuery();
        if (query == null) {
            lastOverlayQuery = null;
            lastOverlayKey = null;
            return;
        }

        String title = screen.getTitle().getString();
        // killer560 (2026-09-21): "you need to adjust how it works in the custom storage to actually
        // adjust to the new slots things are in for that gui" - with the Storage Overlay on, every real
        // slot of a tracked Ender Chest page/Backpack is hidden and redrawn in that overlay's own grid
        // (see StorageOverlayFeature), so the per-slot fill never draws there. Reusing
        // StorageOverlayFeature.highlightItem - the same hook Storage Item Search uses - instead.
        boolean overlayHiding = StorageOverlayConfig.getInstance().isEnabled() && StorageOverlayFeature.shouldHideVanilla(title);
        String overlayKey = overlayHiding ? StorageOverlayFeature.storageKeyForTitle(title) : null;
        if (overlayHiding && overlayKey != null) {
            renderOverlayHighlight(screen, overlayKey, query, cfg);
        } else {
            lastOverlayQuery = null;
            lastOverlayKey = null;
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
