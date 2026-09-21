package com.killer560.hub.inventorytheme;

import com.killer560.hub.cheatutils.CheatUtils;
import com.killer560.hub.inventorytheme.mixin.InventoryThemeGeometryAccessor;
import com.killer560.hub.storageoverlay.StorageOverlayConfig;
import com.killer560.hub.storageoverlay.StorageOverlayFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.Slot;

/**
 * Killer560's item 5.8 ("Custom inventory overlay in the mod's theme") - re-skins the vanilla chest and
 * player-inventory GUI to match this mod's own black+Amber look (see {@code ModScreen}'s header/panel
 * colors, reused here as-is) instead of Minecraft's stone-texture background, the same kind of thing
 * SkyHanni/Odin already do to Hypixel's menus.
 * <p>
 * Every mixin that hooks this feature is a HEAD-level cancel-and-redraw of a single, narrow vanilla
 * method (background, one slot backdrop, one hover highlight, or the title/"Inventory" labels) on
 * exactly the two screen classes in scope - {@link ContainerScreen} (every generic chest-style menu,
 * which is what Hypixel Skyblock's own dungeon/NPC menus actually are under the hood) and
 * {@link InventoryScreen} (the player's own survival inventory). No other container screen type
 * (anvil, enchanting table, beacon, crafting table, merchant) is touched - out of the brief's scope.
 * <p>
 * Draws strictly at/before the vanilla background and slot-render phase, never in
 * {@code extractRenderState}'s TAIL - Storage Overlay, Item Browser and Inventory Search all draw their
 * own UI there (confirmed: Item Browser and Inventory Search via {@code ScreenEvents.afterExtract},
 * Storage Overlay via its own {@code extractRenderState} TAIL inject), so this feature's background/slot
 * work always ends up underneath theirs, never on top - see {@link #isOwnedByStorageOverlay}, the one
 * explicit case (Storage Overlay hides the SAME {@code ContainerScreen.extractBackground}/
 * {@code extractSlot} calls this does) where this feature stands down entirely rather than race it.
 */
public final class InventoryThemeFeature {

    /** Matches {@code ModScreen}'s own panel background fill exactly. */
    private static final int PANEL_BG = 0xFF0D0D0D;
    /** Matches {@code ModScreen}'s own dim panel/header outline. */
    private static final int PANEL_BORDER = 0xFF553311;
    /** Matches {@code SettingsButtonWidget}'s own control background/border, so slot backdrops read as
     *  part of the same control language as every other box in the mod's menu. */
    private static final int SLOT_BG = 0xFF1A1A1A;
    private static final int SLOT_BORDER = 0xFF663D1A;
    /** Matches the dim grey {@code ModScreen} uses for its own secondary text (e.g. the version string). */
    private static final int DIM_TEXT = 0xFF888888;

    private static final int SLOT_SIZE = 18;

    private InventoryThemeFeature() {
    }

    /** @return whether {@code screen} is one of the two in-scope screen types, the feature is on, and
     *  (if scoped to Hypixel) the player is actually on hypixel.net/p3sim.net right now. */
    public static boolean shouldTheme(Screen screen) {
        if (screen == null) {
            return false;
        }
        if (!(screen instanceof ContainerScreen) && !(screen instanceof InventoryScreen)) {
            return false;
        }
        InventoryThemeConfig cfg = InventoryThemeConfig.getInstance();
        if (!cfg.isEnabled()) {
            return false;
        }
        if (cfg.isHypixelOnly() && !CheatUtils.isOnDungeonServer(Minecraft.getInstance())) {
            return false;
        }
        return true;
    }

    /** Storage Overlay already fully re-skins its own tracked Ender Chest/Backpack screens (its own
     *  grid, its own relocated Inventory panel) - per the brief's "make sure your background draws
     *  underneath them" this feature must never also touch the SAME vanilla calls
     *  ({@code ContainerScreen.extractBackground}/{@code AbstractContainerScreen.extractSlot}) Storage
     *  Overlay itself cancels for those titles, so it stands down completely on any screen Storage
     *  Overlay already owns rather than risk a mixin-ordering race between the two. */
    public static boolean isOwnedByStorageOverlay(String title) {
        return StorageOverlayConfig.getInstance().isEnabled() && StorageOverlayFeature.shouldHideVanilla(title);
    }

    private static int leftPos(AbstractContainerScreen<?> screen) {
        return ((InventoryThemeGeometryAccessor) screen).killer560smod$getLeftPos();
    }

    private static int topPos(AbstractContainerScreen<?> screen) {
        return ((InventoryThemeGeometryAccessor) screen).killer560smod$getTopPos();
    }

    private static int imageWidth(AbstractContainerScreen<?> screen) {
        return ((InventoryThemeGeometryAccessor) screen).killer560smod$getImageWidth();
    }

    private static int imageHeight(AbstractContainerScreen<?> screen) {
        return ((InventoryThemeGeometryAccessor) screen).killer560smod$getImageHeight();
    }

    /** Replaces the vanilla background texture with one flat Amber-bordered panel spanning the whole
     *  image area (container rows + relocated player inventory + hotbar all live in that one rect for
     *  every in-scope screen, same as vanilla's own single background sprite did). No per-frame
     *  allocation - every value here is either a constant or a primitive read off the screen. */
    public static void drawBackground(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen) {
        int x0 = leftPos(screen);
        int y0 = topPos(screen);
        int w = imageWidth(screen);
        int h = imageHeight(screen);
        InventoryThemeConfig cfg = InventoryThemeConfig.getInstance();
        int alpha = Math.round(cfg.getBackgroundOpacity() * 255f) << 24;
        graphics.fill(x0, y0, x0 + w, y0 + h, alpha | (PANEL_BG & 0x00FFFFFF));
        graphics.outline(x0, y0, w, h, PANEL_BORDER);
        // Thin accent underline, echoing ModScreen's own header/body divider.
        graphics.fill(x0, y0, x0 + w, y0 + 1, cfg.getAccentColor());
    }

    /** Themed backdrop for one real slot, drawn immediately before vanilla draws that slot's item icon
     *  (see {@code InventoryThemeSlotMixin} for exactly why that injection point, not {@code HEAD}, was
     *  chosen) - without this, cancelling the whole background texture above would leave every item
     *  floating with no square behind it at all. */
    public static void drawSlotBackdrop(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen, Slot slot) {
        int x = leftPos(screen) + slot.x - 1;
        int y = topPos(screen) + slot.y - 1;
        graphics.fill(x, y, x + SLOT_SIZE, y + SLOT_SIZE, SLOT_BG);
        graphics.outline(x, y, SLOT_SIZE, SLOT_SIZE, SLOT_BORDER);
    }

    /** Themed replacement for vanilla's white hover-highlight box, drawn once (in place of vanilla's own
     *  back+front pass - see {@code InventoryThemeHighlightMixin}) for whichever slot is currently
     *  hovered. */
    public static void drawSlotHighlight(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen, Slot hoveredSlot) {
        if (hoveredSlot == null) {
            return;
        }
        int x = leftPos(screen) + hoveredSlot.x - 1;
        int y = topPos(screen) + hoveredSlot.y - 1;
        int accent = InventoryThemeConfig.getInstance().getAccentColor();
        int glow = (0x55 << 24) | (accent & 0x00FFFFFF);
        graphics.fill(x, y, x + SLOT_SIZE, y + SLOT_SIZE, glow);
        graphics.outline(x, y, SLOT_SIZE, SLOT_SIZE, accent);
    }

    /** Replacement for vanilla's grey title/"Inventory" label text, drawn at the exact same positions
     *  vanilla already computed ({@code titleLabelX/Y}, {@code inventoryLabelX/Y}, all read via
     *  {@link InventoryThemeGeometryAccessor}) - only the color changes, so no layout logic is
     *  duplicated here. */
    public static void drawLabels(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen) {
        InventoryThemeGeometryAccessor geo = (InventoryThemeGeometryAccessor) screen;
        Font font = screen.getFont();
        int accent = InventoryThemeConfig.getInstance().getAccentColor();
        graphics.text(font, screen.getTitle(), geo.killer560smod$getTitleLabelX(), geo.killer560smod$getTitleLabelY(), accent, false);
        graphics.text(font, geo.killer560smod$getPlayerInventoryTitle(),
                geo.killer560smod$getInventoryLabelX(), geo.killer560smod$getInventoryLabelY(), DIM_TEXT, false);
    }
}
