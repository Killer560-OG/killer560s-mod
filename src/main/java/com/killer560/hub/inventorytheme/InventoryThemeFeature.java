package com.killer560.hub.inventorytheme;

import com.killer560.hub.cheatutils.CheatUtils;
import com.killer560.hub.hud.GuiRects;
import com.killer560.hub.inventorytheme.mixin.InventoryThemeGeometryAccessor;
import com.killer560.hub.inventorytheme.mixin.InventoryThemeImageButtonSpritesAccessor;
import com.killer560.hub.storageoverlay.StorageOverlayConfig;
import com.killer560.hub.storageoverlay.StorageOverlayFeature;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * Killer560's item 5.8 ("Custom inventory overlay in the mod's theme") - re-skins the vanilla chest and
 * player-inventory GUI to match this mod's own black+Amber look (see {@code ModScreen}'s header/panel
 * colors, reused here as-is) instead of Minecraft's stone-texture background, the same kind of thing
 * SkyHanni/Odin already do to Hypixel's menus.
 * <p>
 * Every mixin that hooks this feature is a HEAD-level cancel-and-redraw of a single, narrow vanilla
 * method (background, one slot backdrop, one hover highlight, or the title label) on exactly the two
 * screen classes in scope - {@link ContainerScreen} (every generic chest-style menu, which is what
 * Hypixel Skyblock's own dungeon/NPC menus actually are under the hood) and {@link InventoryScreen} (the
 * player's own survival inventory). No other container screen type (anvil, enchanting table, beacon,
 * crafting table, merchant) is touched - out of the brief's scope.
 * <p>
 * Draws strictly at/before the vanilla background and slot-render phase, never in
 * {@code extractRenderState}'s TAIL - Storage Overlay, Item Browser and Inventory Search all draw their
 * own UI there (confirmed: Item Browser and Inventory Search via {@code ScreenEvents.afterExtract},
 * Storage Overlay via its own {@code extractRenderState} TAIL inject), so this feature's background/slot
 * work always ends up underneath theirs, never on top - see {@link #isOwnedByStorageOverlay}, the one
 * explicit case (Storage Overlay hides the SAME {@code ContainerScreen.extractBackground}/
 * {@code extractSlot} calls this does) where this feature stands down entirely rather than race it.
 * <p>
 * Real bug found and fixed (2026-09-27), killer560: "it does this weird second inventory hud to the
 * bottom right of my screen whenever I turn it on." Cause, javap-verified against the 26.1.2 merged jar:
 * {@code InventoryScreen}'s recipe book ({@code RecipeBookComponent}) initializes its own
 * {@code visible} field from {@code isVisibleAccordingToBookData()} - i.e. whatever the player's real
 * recipe book was left open/closed as, completely unrelated to this feature - so anyone who has ever
 * left it open sees the full recipe list/search panel pop up beside the newly-reskinned inventory the
 * first time they open it. Worse, {@code AbstractRecipeBookScreen#extractRenderState} skips the normal
 * background+slots draw entirely and draws ONLY the flat background whenever
 * {@code recipeBookComponent.isVisible() && widthTooNarrow} - on a narrow window the real 36-slot grid
 * doesn't render AT ALL while that panel is open, i.e. exactly "a second inventory hud" replacing the
 * real one. Fixed by {@link com.killer560.hub.inventorytheme.mixin.InventoryThemeRecipeBookVisibilityMixin}
 * forcing {@code isVisible()} false (not just cancelling its render) so the caller always takes the
 * normal path this feature's own mixins already handle - which doubles as killer560's separate "remove
 * ... the recipe book" ask, alongside {@link #register()}'s button hide for the case where it was never
 * open to begin with.
 * <p>
 * That was not the whole story: the "second inventory to the bottom right" came back (2026-10-04
 * screenshot, recipe book closed). The real cause was {@link #drawSlotBackdrop}/{@link #drawSlotHighlight}
 * adding leftPos/topPos inside a pose {@code extractContents} had already translated by them, so every
 * slot square landed at twice the panel's offset. Both now draw slot-local.
 */
public final class InventoryThemeFeature {

    // Panel and slot colours come from the theme (PanelTheme: Amber's are ModScreen's panel fill 0xFF0D0D0D and
    // SettingsButtonWidget's control fill 0xFF1A1A1A, the constants this used before themes) or the Slot Color setting.

    private static final int SLOT_SIZE = 18;

    private InventoryThemeFeature() {
    }

    /** killer560: "remove ... the recipe book" from the custom inventory - registers the one
     *  event-based (non-mixin) hook this feature needs, since {@code RecipeBookComponent} is not an
     *  {@link AbstractWidget} and so isn't reachable through any of the extract-method mixins below.
     *  Same {@code ScreenEvents.AFTER_INIT} + {@code Screens.getWidgets} + sprite-identity technique
     *  {@code ObjectHiderFeature}'s own "Hide Recipe Book Button" option already uses in this repo - a
     *  separate local copy (see {@link InventoryThemeImageButtonSpritesAccessor}) rather than sharing
     *  its accessor, matching this repo's existing per-feature accessor convention. */
    public static void register() {
        ScreenEvents.AFTER_INIT.register(InventoryThemeFeature::onScreenInit);
    }

    private static void onScreenInit(Minecraft client, Screen screen, int scaledWidth, int scaledHeight) {
        if (!shouldTheme(screen)) {
            return;
        }
        for (AbstractWidget widget : Screens.getWidgets(screen)) {
            if (widget instanceof ImageButton button
                    && ((InventoryThemeImageButtonSpritesAccessor) button).killer560smod$getSprites()
                            == RecipeBookComponent.RECIPE_BUTTON_SPRITES) {
                button.visible = false;
            }
        }
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

    /** {@link #isOwnedByStorageOverlay(String)} for a screen's title, without flattening it for every slot. */
    public static boolean isOwnedByStorageOverlay(net.minecraft.network.chat.Component title) {
        return StorageOverlayConfig.getInstance().isEnabled() && StorageOverlayFeature.shouldHideVanilla(title);
    }

    /** killer560: "remove the offhand slot ... for now." True for the player's real offhand slot
     *  (container index {@link Inventory#SLOT_OFFHAND}) wherever it's embedded in an in-scope screen. */
    public static boolean isOffhandSlot(Slot slot) {
        return slot.container instanceof Inventory && slot.getContainerSlot() == Inventory.SLOT_OFFHAND;
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
        int line = cfg.getLineWidth();
        GuiRects rects = GuiRects.begin(graphics);
        rects.fill(x0, y0, x0 + w, y0 + h, alpha | (cfg.getPanelColor() & 0x00FFFFFF));
        // killer560: "for the border it is extremely faint all around the inventory and it doesn't
        // change except the very top bar with the custom option" - the whole outline used to be a
        // hardcoded dim constant (0xFF553311, barely readable against PANEL_BG) while only this one top
        // strip used the real accent color. Now the accent color IS the border, on all four sides, so
        // it's both visible by default and actually responds to Accent Source/Accent Color.
        outline(rects, x0, y0, w, h, line, cfg.getAccentColor());
        if (screen instanceof InventoryScreen) {
            // The player-model window vanilla's texture used to frame (InventoryScreen.extractBackground
            // passes leftPos+26..75, topPos+8..78 to extractEntityInInventoryFollowsMouse).
            rects.fill(x0 + 26, y0 + 8, x0 + 75, y0 + 78, cfg.getSlotColor());
            outline(rects, x0 + 26, y0 + 8, 49, 70, line, cfg.getAccentColor());
        }
        rects.submit();
    }

    /** Themed backdrop for one real slot, drawn immediately before vanilla draws that slot's item icon
     *  (see {@code InventoryThemeSlotMixin} for exactly why that injection point, not {@code HEAD}, was
     *  chosen) - without this, cancelling the whole background texture above would leave every item
     *  floating with no square behind it at all. */
    public static void drawSlotBackdrop(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen, Slot slot) {
        // SLOT-LOCAL coordinates, no leftPos/topPos. AbstractContainerScreen.extractContents has already
        // pushed translate(leftPos, topPos) before it calls extractSlotHighlightBack/extractSlots (javap,
        // 26.1.2 and 26.2), exactly as vanilla's own extractSlot draws its item at plain (slot.x, slot.y).
        // Adding leftPos here applied it twice and put the whole grid of backdrops at (2*leftPos, 2*topPos):
        // the "empty orange inventory grid at the bottom right" with the real panel left slot-less.
        int x = slot.x - 1;
        int y = slot.y - 1;
        // killer560: "I can no longer see the lines between slots" - same root cause as the outer
        // border above (a hardcoded dim brown, 0xFF663D1A, that barely read against SLOT_BG). Per-slot
        // outlines now use the same accent color as the rest of the border, so the grid lines between
        // slots are back and consistent with the rest of the panel.
        // One render-state element, not five: the same fill and outline, but a menu's 450 separate fills were each
        // intersection-tested against every item already drawn - 8% of the render thread with a chest open (GuiRects).
        InventoryThemeConfig cfg = InventoryThemeConfig.getInstance();
        GuiRects rects = GuiRects.begin(graphics).fill(x, y, x + SLOT_SIZE, y + SLOT_SIZE, cfg.getSlotColor());
        outline(rects, x, y, SLOT_SIZE, SLOT_SIZE, cfg.getLineWidth(), cfg.getAccentColor());
        rects.submit();
    }

    /** Themed replacement for vanilla's white hover-highlight box, drawn once (in place of vanilla's own
     *  back+front pass - see {@code InventoryThemeHighlightMixin}) for whichever slot is currently
     *  hovered. */
    public static void drawSlotHighlight(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen, Slot hoveredSlot) {
        // Offhand is hidden below (InventoryThemeSlotMixin) - never draw a highlight over empty space.
        if (hoveredSlot == null || isOffhandSlot(hoveredSlot)) {
            return;
        }
        // Slot-local for the same reason as drawSlotBackdrop: this runs inside extractContents' translated pose.
        int x = hoveredSlot.x - 1;
        int y = hoveredSlot.y - 1;
        InventoryThemeConfig cfg = InventoryThemeConfig.getInstance();
        int accent = cfg.getAccentColor();
        int glow = (0x55 << 24) | (accent & 0x00FFFFFF);
        GuiRects rects = GuiRects.begin(graphics).fill(x, y, x + SLOT_SIZE, y + SLOT_SIZE, glow);
        // At least one unit, so the hovered slot still shows with Line Width at 0.
        outline(rects, x, y, SLOT_SIZE, SLOT_SIZE, Math.max(1, cfg.getLineWidth()), accent);
        rects.submit();
    }

    /**
     * An outline {@code width} units thick drawn INSIDE the box (Line Width, killer560 2026-10-07), as four
     * non-overlapping strips so a translucent colour never blends twice. Width 1 is exactly {@code GuiRects.outline}'s
     * four fills, in the same order - the look before the slider existed. 0 draws nothing; a width over half the box
     * is capped there.
     */
    public static void outline(GuiRects rects, int x, int y, int w, int h, int width, int color) {
        int t = Math.min(width, Math.min(w, h) / 2);
        if (t <= 0) {
            return;
        }
        rects.fill(x, y, x + w, y + t, color);
        rects.fill(x, y + h - t, x + w, y + h, color);
        rects.fill(x, y + t, x + t, y + h - t, color);
        rects.fill(x + w - t, y + t, x + w, y + h - t, color);
    }

    /** Replacement for vanilla's grey title label text, drawn at the exact same position vanilla
     *  already computed ({@code titleLabelX/Y}, read via {@link InventoryThemeGeometryAccessor}) - only
     *  the color changes, so no layout logic is duplicated here. killer560: "remove ... the inventory
     *  text" - the separate "Inventory" sub-label vanilla draws below/beside it is deliberately no
     *  longer drawn at all (used to render here via {@code inventoryLabelX/Y} in a dim grey). */
    public static void drawLabels(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen) {
        InventoryThemeGeometryAccessor geo = (InventoryThemeGeometryAccessor) screen;
        Font font = screen.getFont();
        int accent = InventoryThemeConfig.getInstance().getAccentColor();
        graphics.text(font, screen.getTitle(), geo.killer560smod$getTitleLabelX(), geo.killer560smod$getTitleLabelY(), accent, false);
    }
}
