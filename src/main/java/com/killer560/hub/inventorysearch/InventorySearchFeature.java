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
 * A match is a full fill of the slot in the highlight color, drawn behind the item (killer560, 2026-10-11:
 * "have the item be a full red highlight so its easier to see") from {@code InventorySearchSlotMixin}, inside the
 * slot's own draw, so it sits under the tooltip.
 * <p>
 * The bar always sits at the bottom middle of the screen, also when the Item Browser is on (killer560,
 * 2026-10-11: "Do not move my search bar above the menu the search bar should stay in the bottom middle of my
 * screen like normal") - the Item Browser filters by this same bar and has no box of its own. Since 2026-11-02 it has one
 * fixed spot and width on every screen, and every menu lays out around it (see {@link #barRect}, {@link #reservedRect}).
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
    /** Ctrl+A: the whole query is selected; the next typed character or paste replaces it, Backspace clears it. */
    private static boolean selectedAll = false;
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
        return queryNow();
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
        selectedAll = false; // a Ctrl+A left over from the last screen would make the next typed character wipe the query
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
            if (event.key() == InputConstants.KEY_RETURN) {
                commitQuery();
                return false;
            }
            if (event.key() == InputConstants.KEY_ESCAPE) {
                listening = false;
                return false;
            }
            if (event.hasControlDown() && event.key() == InputConstants.KEY_A) {
                selectedAll = QUERY.length() > 0;
                return false;
            }
            if (event.hasControlDown() && (event.key() == InputConstants.KEY_C || event.key() == InputConstants.KEY_X)) {
                if (QUERY.length() > 0) {
                    net.minecraft.client.Minecraft.getInstance().keyboardHandler.setClipboard(QUERY.toString());
                    if (event.key() == InputConstants.KEY_X) {
                        QUERY.setLength(0);
                        selectedAll = false;
                    }
                }
                return false;
            }
            if (event.hasControlDown() && event.key() == InputConstants.KEY_V) {
                String clip = net.minecraft.client.Minecraft.getInstance().keyboardHandler.getClipboard();
                if (clip != null) {
                    if (selectedAll) {
                        QUERY.setLength(0);
                        selectedAll = false;
                    }
                    QUERY.append(clip.replaceAll("[\\r\\n]", ""));
                }
                return false;
            }
            if (event.key() == InputConstants.KEY_BACKSPACE) {
                if (selectedAll) {
                    QUERY.setLength(0);
                    selectedAll = false;
                } else if (event.hasControlDown()) {
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
            if (selectedAll) {
                // Typing over a select-all replaces the whole query, the way any text box does.
                QUERY.setLength(0);
                selectedAll = false;
            }
            QUERY.append(event.codepointAsString());
            return false;
        });

        // killer560 (2026-09-21): "make it so I can click into that bar as well" - a plain left click
        // inside whichever search box is actually on screen starts typing, same as clicking any real
        // vanilla text field. Only ever claims the click when it's actually inside the box, so normal
        // inventory clicks elsewhere are untouched.
        ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
            if (!anyConsumerEnabled() || com.killer560.hub.compat.McInput.button(event) != 0 || lastBoxBounds == null) {
                return true;
            }
            if (inside(lastBoxBounds, event.x(), event.y())) {
                listening = true;
                return false;
            }
            return true;
        });

        com.killer560.hub.gui.BelowTooltip.register(screen, (s, graphics, mouseX, mouseY, partialTick) ->
                render(containerScreen, graphics));
    }

    /** Enter in the bar (killer560, 2026-10-18: "if they have an equation and press enter it should remove their equation
     *  and only have the answer left there"): typing stops, and a sum in the bar is replaced by its answer as plain
     *  digits; anything that is not a sum is left as it is. Public for the testkit. */
    public static void commitQuery() {
        listening = false;
        String answer = SearchCalculator.answerFor(QUERY.toString());
        if (answer != null) {
            QUERY.setLength(0);
            QUERY.append(answer);
        }
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

    /** Fill alpha of a matching slot - the color setting's RGB at this alpha, behind the item. */
    private static final int MATCH_FILL_ALPHA = 0xC0;

    /** Per-slot match cache for {@link #drawSlotHighlight}: the query it was built for and stack -> matched. Identity
     *  keyed, so a slot whose stack object is replaced is simply asked again. Cleared when the query changes. */
    private static String lastMatchQuery = null;
    private static boolean lastMatchIgnoreCase = true;
    private static boolean lastMatchLore = true;
    private static final java.util.Map<ItemStack, Boolean> MATCH_CACHE = new java.util.IdentityHashMap<>();

    // QUERY as a String and its calculator answer, rebuilt only when the text changes. highlightQuery() runs once per
    // drawn slot per frame (InventorySearchSlotMixin) and barText() once per frame, and each used to copy the builder
    // and tokenize the query again: ~90 tokenizations a frame in a double chest with a query typed (fps pass
    // 2026-10-19). SearchCalculator.resultFor is a pure function of the string, so keying on the text is exact.
    private static String queryText = "";
    private static String calcFor = null;
    private static String calcResult = null;

    /** The live query, the same String object for as long as its text is unchanged. */
    private static String queryNow() {
        if (!queryText.contentEquals(QUERY)) {
            queryText = QUERY.toString();
        }
        return queryText;
    }

    /** {@link SearchCalculator#resultFor} of the live query, worked out once per distinct query. */
    private static String calcResultNow(String query) {
        if (query != calcFor) {
            calcResult = SearchCalculator.resultFor(query);
            calcFor = query;
        }
        return calcResult;
    }

    /** The live query when it should highlight slots (feature on, not blank, not a sum), else null. */
    private static String highlightQuery() {
        if (!InventorySearchConfig.getInstance().isEnabled()) {
            return null;
        }
        String query = queryNow();
        return query.isBlank() || calcResultNow(query) != null ? null : query;
    }

    /**
     * Called from {@code InventorySearchSlotMixin} just before vanilla draws a slot's item, so a match gets a full
     * fill of the highlight color behind the item and under any tooltip. Slot-local coordinates (extractContents
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
        InventorySearchConfig cfg = InventorySearchConfig.getInstance();
        // The cached answers also depend on the two match options, so flipping either drops them (they used to
        // stay stale until the query text next changed).
        if (!query.equals(lastMatchQuery) || cfg.isIgnoreCase() != lastMatchIgnoreCase
                || cfg.isSearchLore() != lastMatchLore) {
            lastMatchQuery = query;
            lastMatchIgnoreCase = cfg.isIgnoreCase();
            lastMatchLore = cfg.isSearchLore();
            MATCH_CACHE.clear();
        }
        if (MATCH_CACHE.size() > 4096) {
            MATCH_CACHE.clear();
        }
        return MATCH_CACHE.computeIfAbsent(stack, st -> matches(st, query, cfg));
    }

    /** The text shown in the bar: the query, plus " = result" in green when it is a sum. Public for the testkit. */
    public static String barText() {
        String query = queryNow();
        if (query.isEmpty()) {
            return "\u00a78Ctrl+F or click to search...";
        }
        String result = calcResultNow(query);
        if (result == null) {
            return query;
        }
        if (query != barFor) {
            barText = query + " \u00a77= \u00a7a" + result;
            barFor = query;
        }
        return barText;
    }

    private static String barFor = null;
    private static String barText = null;

    /** The bar's width and height in its own units, before the Box Scale setting. One fixed width (killer560, 2026-11-02:
     *  the bar no longer grows or moves as text is typed; a long query shows its end). */
    public static final int BAR_W = 160;
    public static final int BAR_H = 16;
    /** Room kept between the bar and anything laid out around it. */
    public static final int GAP = 3;
    /** The hotbar, XP bar and two rows of hearts / armor / food at the bottom middle, which the bar sits just above. */
    private static final int HOTBAR_ZONE = 60;
    /** The lowest bar top that still leaves a double chest's slots (rows 18..213 of its 222) room above it. */
    private static final int MIN_NORMAL_Y = 213 - 18 + 2 + GAP;

    /**
     * The bar's {x, y, w, h} on a screen of this GUI size: the SAME spot on every screen (killer560, 2026-11-02: "no i want
     * it always at the bottom. adjust other menus around it instead"). Bottom middle, just above the hotbar / XP / hearts.
     * Only a window too short for a double chest above it (under about 280 GUI units: 854x480 GUI 2, 1280x720 GUI 3,
     * 1600x900 GUI 4) puts it at the very bottom instead, over the hotbar; only a window narrower than the bar narrows it.
     */
    public static int[] barRect(int screenW, int screenH) {
        float scale = InventorySearchConfig.getInstance().getBoxScale();
        int w = Math.min(Math.round(BAR_W * scale), Math.max(20, screenW - 4));
        int h = Math.round(BAR_H * scale);
        int y = screenH - HOTBAR_ZONE - GAP - h;
        if (y < MIN_NORMAL_Y) {
            y = screenH - 2 - h;
        }
        return new int[]{(screenW - w) / 2, y, w, h};
    }

    /** Whether the bar is drawn on {@code screen}: a container screen, Inventory Search or the Item Browser on, and no
     *  reskin that replaces the whole menu with its own (Bazaar, Auction House, Spirit Leap, Terminal Custom GUI). */
    public static boolean shownOn(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?>)) {
            return false;
        }
        if (!InventorySearchConfig.getInstance().isEnabled() && !ItemBrowserConfig.getInstance().isEnabled()) {
            return false;
        }
        return !com.killer560.hub.bazaar.BazaarReskin.isHiding(screen)
                && !com.killer560.hub.auction.ah.AhReskin.isHiding(screen)
                && !com.killer560.hub.spiritleap.SpiritLeapOverlayFeature.isHiding(screen)
                && !com.killer560.hub.terminals.TerminalSolverFeature.isCustomGuiActive();
    }

    /** The strip every menu the mod lays out keeps clear of on {@code screen}: the bar plus {@link #GAP} all round, as
     *  {x0, y0, x1, y1}; null when the bar is not drawn there. */
    public static int[] reservedRect(Screen screen) {
        if (screen == null || !shownOn(screen)) {
            return null;
        }
        int[] r = barRect(screen.width, screen.height);
        return new int[]{r[0] - GAP, r[1] - GAP, r[0] + r[2] + GAP, r[1] + r[3] + GAP};
    }

    /** {@code y} for a w x h panel at x,y on {@code screen}, moved up just enough to clear the bar's strip when it would
     *  reach it (never above 0). Panels beside the bar keep their y. */
    public static double keepAbove(Screen screen, double x, double y, double w, double h) {
        int[] r = reservedRect(screen);
        return r == null || !(x < r[2] && r[0] < x + w && y < r[3] && r[1] < y + h) ? y : Math.max(0, r[1] - h);
    }

    /**
     * Moves a vanilla menu up at init when it would reach the bar's strip (a double chest at a big GUI scale): the whole
     * panel above the strip when there is room, else as far as keeps every slot above it with the first row still on
     * screen (the title and top border may go off the top). Menus a reskin covers entirely are left alone.
     */
    public static void shiftClearOfBar(AbstractContainerScreen<?> screen) {
        try {
            int[] r = reservedRect(screen);
            if (r == null || !backgroundFollowsTop(screen)
                    || com.killer560.hub.containercover.ContainerCover.coversAll(screen)) {
                return;
            }
            var geo = (com.killer560.hub.inventorytheme.mixin.InventoryThemeGeometryAccessor) screen;
            int left = geo.killer560smod$getLeftPos();
            int top = geo.killer560smod$getTopPos();
            int imageW = geo.killer560smod$getImageWidth();
            int imageH = geo.killer560smod$getImageHeight();
            if (left >= r[2] || left + imageW <= r[0] || top + imageH <= r[1]) {
                return;
            }
            int slotTop = Integer.MAX_VALUE;
            int slotBottom = Integer.MIN_VALUE;
            for (Slot slot : screen.getMenu().slots) {
                slotTop = Math.min(slotTop, slot.y);
                slotBottom = Math.max(slotBottom, slot.y + 16);
            }
            int newTop = r[1] - imageH;
            if (slotTop != Integer.MAX_VALUE && newTop + slotTop < 2) {
                newTop = Math.max(2 - slotTop, r[1] - slotBottom);
            }
            if (newTop < top) {
                ((com.killer560.hub.inventorysearch.mixin.ContainerScreenPositionAccessor) screen).killer560smod$setTopPos(newTop);
            }
        } catch (RuntimeException e) {
            // never let a layout nicety break opening a menu
        }
    }

    /** Whether {@code screen}'s background is drawn at {@code topPos} - so moving {@code topPos} moves the whole menu.
     *  These read {@code topPos} in {@code extractBackground} (javap 26.2 and 26.3); a chest re-centers from
     *  {@code height} and is answered by {@code InventorySearchChestBackgroundMixin}. Other menus (hopper, dispenser,
     *  brewing stand, beacon...) re-center too and are short enough not to need it, so they are never moved. */
    private static boolean backgroundFollowsTop(AbstractContainerScreen<?> screen) {
        return screen instanceof net.minecraft.client.gui.screens.inventory.ContainerScreen
                || screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen
                || screen instanceof net.minecraft.client.gui.screens.inventory.AbstractFurnaceScreen<?>
                || screen instanceof net.minecraft.client.gui.screens.inventory.ItemCombinerScreen<?>
                || screen instanceof net.minecraft.client.gui.screens.inventory.CartographyTableScreen
                || screen instanceof net.minecraft.client.gui.screens.inventory.LoomScreen
                || screen instanceof net.minecraft.client.gui.screens.inventory.StonecutterScreen;
    }

    /** The {@code height} a chest's {@code extractBackground} centers its texture with: the real one, or - for a chest
     *  {@link #shiftClearOfBar} moved up - the one whose centering gives the moved {@code topPos}. */
    public static int backgroundHeight(AbstractContainerScreen<?> screen, int height) {
        try {
            var geo = (com.killer560.hub.inventorytheme.mixin.InventoryThemeGeometryAccessor) screen;
            int top = geo.killer560smod$getTopPos();
            int imageH = geo.killer560smod$getImageHeight();
            return (height - imageH) / 2 == top ? height : 2 * top + imageH;
        } catch (RuntimeException e) {
            return height;
        }
    }

    private static void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics) {
        if (!shownOn(screen)) {
            lastBoxBounds = null;
            return;
        }

        // One bar, one fixed spot, whichever of the two features is on (the Item Browser filters by it too).
        InventorySearchConfig cfg = InventorySearchConfig.getInstance();
        float scale = cfg.getBoxScale();
        int[] rect = barRect(screen.width, screen.height);
        int screenX = rect[0];
        int screenY = rect[1];
        int boxWidth = Math.max(1, Math.round(rect[2] / scale));
        int boxHeight = BAR_H;
        String display = barText();
        var barFont = Minecraft.getInstance().font;
        if (barFont.width(display) > boxWidth - 8) {
            if (QUERY.isEmpty()) {
                display = barFont.width("Ctrl+F to search...") <= boxWidth - 8 ? "§8Ctrl+F to search..." : "§8Search...";
            } else {
                display = barFont.plainSubstrByWidth(display, boxWidth - 8, true); // the end of what is typed
            }
        }
        lastBoxBounds = new int[]{screenX, screenY, rect[2], rect[3]};
        com.killer560.hub.containercover.ContainerCover.coverRect(screen, "inventorysearch.bar", screenX, screenY,
                screenX + lastBoxBounds[2], screenY + lastBoxBounds[3]);

        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate(screenX, screenY);
            graphics.pose().scale(scale, scale);
            graphics.fill(0, 0, boxWidth, boxHeight, 0xCC0D0D0D);
            graphics.outline(0, 0, boxWidth, boxHeight, listening ? 0xFFCC6600 : 0xFF553311);
            if (selectedAll && !QUERY.isEmpty()) {
                // Ctrl+A: the query shows as selected (a blue block behind it, as in any text box).
                int selectedWidth = Math.min(boxWidth - 8, Minecraft.getInstance().font.width(QUERY.toString()));
                graphics.fill(3, 3, 5 + selectedWidth, 13, 0xFF3366CC);
            }
            // the bar's text keeps its visual order between frames (the same sequence vanilla computes, hud/HudTextCache);
            // clipped to the box, which a long query's text could otherwise run past
            graphics.enableScissor(0, 0, boxWidth, boxHeight); // local units: enableScissor applies the pose (javap)
            try {
                graphics.text(Minecraft.getInstance().font, com.killer560.hub.hud.HudTextCache.visualOrder(display), 4, 4,
                        0xFFFFFFFF, false);
            } finally {
                graphics.disableScissor();
            }
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
        slots.stream().filter(slot -> slot.index >= 9 && slot.index < containerSlotCount && !slot.getItem().isEmpty()
                && matches(slot.getItem(), query, cfg)).findFirst()
                .ifPresent(slot -> StorageOverlayFeature.highlightItem(overlayKey, slot.index - 9, OVERLAY_HIGHLIGHT_DURATION_MS));
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
        return lore.lines().stream().map(Component::getString)
                .anyMatch(text -> (cfg.isIgnoreCase() ? text.toLowerCase(Locale.ROOT) : text).contains(needle));
    }

    private static boolean inside(int[] rect, double x, double y) {
        return x >= rect[0] && x < rect[0] + rect[2] && y >= rect[1] && y < rect[1] + rect[3];
    }
}
