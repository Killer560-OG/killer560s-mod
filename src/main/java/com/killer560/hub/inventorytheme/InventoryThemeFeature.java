package com.killer560.hub.inventorytheme;

import com.killer560.hub.cheatutils.CheatUtils;
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

    /**
     * The panel, in vanilla's own shape (killer560, 2026-10-12: "change inventory up to essentially be a reskin of the
     * original with its coloring"): a 1-unit outline with its corners cut, a 2-unit raised bevel - light along the top and
     * left, dark along the bottom and right - and the fill, in the preset's colours ({@link InventoryPalette}), drawn
     * procedurally (vanilla's texture is neither copied nor tinted). The player inventory also gets the sunken
     * player-model window and the crafting arrow. Opaque in every preset (killer560, 2026-10-12: "make the inventories
     * not transparent"). GUI units, pixel-exact.
     */
    public static void drawBackground(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen) {
        int x0 = leftPos(screen);
        int y0 = topPos(screen);
        int w = imageWidth(screen);
        int h = imageHeight(screen);
        InventoryThemeConfig cfg = InventoryThemeConfig.getInstance();
        InventoryPalette p = cfg.getPalette();
        int alpha = 0xFF;
        PixelRects r = PixelRects.begin(graphics);
        try {
            panel(r, x0, y0, w, h, p, alpha);
            if (screen instanceof InventoryScreen) {
                // The player-model window (InventoryScreen.extractBackground passes leftPos+26..75, topPos+8..78 to
                // extractEntityInInventoryFollowsMouse): sunken like a slot, its fill vanilla's black.
                sunken(r, x0 + MODEL_X0 - 1, y0 + MODEL_Y0 - 1, x0 + MODEL_X1 + 1, y0 + MODEL_Y1 + 1, p.modelFill(), p);
                arrow(r, x0 + 135, y0 + 29, p.arrow());
            }
        } finally {
            r.submit();
        }
    }

    /** The player-model window's inside, in units from leftPos/topPos (vanilla's own model box). */
    public static final int MODEL_X0 = 26;
    public static final int MODEL_X1 = 75;
    public static final int MODEL_Y0 = 8;
    public static final int MODEL_Y1 = 78;

    /** One GUI-unit rectangle {@code (gx0,gy0)..(gx1,gy1)} in pixels. */
    static void box(PixelRects r, float gx0, float gy0, float gx1, float gy1, int argb) {
        r.fill(r.x(gx0), r.y(gy0), r.x(gx1), r.y(gy1), argb);
    }

    private static int withAlpha(int argb, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (argb & 0xFFFFFF);
    }

    /** Vanilla's raised panel over {@code (x,y)..(x+w,y+h)}: outline with cut corners, light top-left / dark bottom-right
     *  bevel two units wide, the two meeting corners left in the fill colour like vanilla's. */
    static void panel(PixelRects r, int x, int y, int w, int h, InventoryPalette p, int alpha) {
        int fill = withAlpha(p.panel(), alpha);
        int light = withAlpha(p.light(), alpha);
        int dark = withAlpha(p.dark(), alpha);
        int border = withAlpha(p.border(), alpha);
        box(r, x + 1, y + 1, x + w - 1, y + h - 1, fill);
        // outline: four edges stopping short of the corners, plus one pixel stepping each corner in
        box(r, x + 2, y, x + w - 2, y + 1, border);
        box(r, x + 2, y + h - 1, x + w - 2, y + h, border);
        box(r, x, y + 2, x + 1, y + h - 2, border);
        box(r, x + w - 1, y + 2, x + w, y + h - 2, border);
        box(r, x + 1, y + 1, x + 2, y + 2, border);
        box(r, x + w - 2, y + 1, x + w - 1, y + 2, border);
        box(r, x + 1, y + h - 2, x + 2, y + h - 1, border);
        box(r, x + w - 2, y + h - 2, x + w - 1, y + h - 1, border);
        // raised bevel
        box(r, x + 2, y + 1, x + w - 3, y + 3, light);
        box(r, x + 1, y + 2, x + 3, y + h - 3, light);
        box(r, x + 3, y + h - 3, x + w - 2, y + h - 1, dark);
        box(r, x + w - 3, y + 3, x + w - 1, y + h - 2, dark);
    }

    /** Vanilla's sunken square over {@code (x0,y0)..(x1,y1)} (the outer edge): a one-unit dark line along the top and left,
     *  a light one along the bottom and right, the top-right and bottom-left corners in the fill, the inside filled. */
    static void sunken(PixelRects r, int x0, int y0, int x1, int y1, int fill, InventoryPalette p) {
        box(r, x0, y0, x1, y1, fill);
        box(r, x0, y0, x1 - 1, y0 + 1, p.slotDark());
        box(r, x0, y0 + 1, x0 + 1, y1 - 1, p.slotDark());
        box(r, x0 + 1, y1 - 1, x1, y1, p.slotLight());
        box(r, x1 - 1, y0 + 1, x1, y1 - 1, p.slotLight());
    }

    /** The crafting arrow (vanilla's sits at leftPos+135, topPos+29 in the player inventory): a shaft and a head. */
    private static void arrow(PixelRects r, int x, int y, int argb) {
        box(r, x, y + 4, x + 10, y + 8, argb);
        for (int k = 0; k < 7; k++) {
            box(r, x + 9 + k, y + k, x + 10 + k, y + 12 - k, argb);
        }
    }

    /** A slot's square, drawn immediately before vanilla draws its item (see {@code InventoryThemeSlotMixin}): vanilla's
     *  sunken 18-unit square around the 16-unit item at slot.x/slot.y, so the item fills its inside exactly. Slot-local
     *  coordinates - extractContents has already translated by leftPos/topPos (docs/LESSONS-GUI.md). One render-state
     *  element per slot (PixelRects). */
    public static void drawSlotBackdrop(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen, Slot slot) {
        InventoryPalette p = InventoryThemeConfig.getInstance().getPalette();
        PixelRects r = PixelRects.begin(graphics);
        try {
            sunken(r, slot.x - 1, slot.y - 1, slot.x + SLOT_SIZE - 1, slot.y + SLOT_SIZE - 1, p.slotFill(), p);
        } finally {
            r.submit();
        }
    }

    /**
     * The inside of a themed slot is exactly the item's 16-unit box, the same square the Item Rarity Background already
     * fills, so there is nothing to widen (the flat line theme needed this; the sunken square does not). Kept so callers
     * need not know which look is drawn. @return always false: the caller draws its plain 16x16 square.
     */
    public static boolean fillSlotInner(GuiGraphicsExtractor graphics, int slotX, int slotY, int color, int ring) {
        return false;
    }

    /** No grid lines any more (the vanilla-shaped theme has none); 0 for the testkit's older line probes. */
    public static int lineShift(int t) {
        return 0;
    }

    /** The hover highlight over the hovered slot's inside (the item's 16-unit box), like vanilla's own white wash, in the
     *  palette's accent. Drawn once in place of vanilla's back and front passes ({@code InventoryThemeHighlightMixin}). */
    public static void drawSlotHighlight(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen, Slot hoveredSlot) {
        // Offhand is hidden below (InventoryThemeSlotMixin) - never draw a highlight over empty space.
        if (hoveredSlot == null || isOffhandSlot(hoveredSlot)) {
            return;
        }
        InventoryPalette p = InventoryThemeConfig.getInstance().getPalette();
        PixelRects r = PixelRects.begin(graphics);
        try {
            box(r, hoveredSlot.x, hoveredSlot.y, hoveredSlot.x + 16, hoveredSlot.y + 16, withAlpha(p.accent(), 0x80));
        } finally {
            r.submit();
        }
    }

    /** Replacement for vanilla's grey title label text, drawn at the exact same position vanilla
     *  already computed ({@code titleLabelX/Y}, read via {@link InventoryThemeGeometryAccessor}) - only
     *  the color changes, so no layout logic is duplicated here. killer560: "remove ... the inventory
     *  text" - the separate "Inventory" sub-label vanilla draws below/beside it is deliberately no
     *  longer drawn at all (used to render here via {@code inventoryLabelX/Y} in a dim grey). */
    public static void drawLabels(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen) {
        InventoryThemeGeometryAccessor geo = (InventoryThemeGeometryAccessor) screen;
        Font font = screen.getFont();
        int colour = InventoryThemeConfig.getInstance().getPalette().text();
        graphics.text(font, screen.getTitle(), geo.killer560smod$getTitleLabelX(), geo.killer560smod$getTitleLabelY(), colour, false);
    }
}
