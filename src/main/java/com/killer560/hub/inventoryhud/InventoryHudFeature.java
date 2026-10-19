package com.killer560.hub.inventoryhud;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.hud.GuiRects;
import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import com.killer560.hub.compat.McCompat;

/**
 * Inventory HUD - draws the player's 27 main-inventory slots (not the hotbar) as an always-on HUD panel,
 * the "Inventory HUD" section of the Inventory HUD+ mod (armor/potion sections deliberately not ported).
 * Slot order and the main-inventory index math ({@code 9 + row * 9 + col}) follow briansemrau/InventoryHUD's
 * {@code InGameHudMixin}; the option set (mini/normal, horizontal/vertical, background transparency,
 * animation on/off, key-driven show/hide) follows Inventory HUD+'s own documented inventory options.
 * <p>
 * Drawn in game through its own Fabric HUD layer rather than {@code HudInGameRenderer}'s id list: that
 * renderer keeps drawing while the HUD editor is open, which would double-draw this element beneath the
 * editor's own preview (every other listed element avoids that by refusing to draw with any screen open,
 * which this one can't do because "Hide in Screens" is optional). {@link InventoryHudElement#render} is
 * therefore the HUD-editor preview path only.
 * <p>
 * Background Opacity fades the panel, its outline and the slots. Item Opacity fades the items: they are not tinted (item
 * textures cannot be on 26.1.2, see {@link #drawStack}), so each occupied cell gets a veil in the slot colour at
 * (100 - item opacity)% drawn over its item, and an item fades toward the slot colour rather than the world. Not a true
 * per-item alpha.
 * Where it sits when never moved: centred above the hotbar, a little way up ({@link InventoryHudElement#defaultX}).
 */
public final class InventoryHudFeature {

    public static final String ELEMENT_ID = "inventory_hud";

    private static final int MAIN_START = 9;
    private static final int ROWS = 3;
    private static final int COLS = 9;

    private static final int PANEL_BG = 0x101010;
    private static final int PANEL_BORDER = 0xCC6600;
    private static final int SLOT_BG = 0x262626;
    private static final int SLOT_BORDER = 0x3D2A14;

    private static boolean keyWasDown = false;

    private InventoryHudFeature() {
    }

    /** Registers the key tick and the in-game HUD layer. The editor element is registered separately. */
    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("InventoryHudFeature.tick", com.killer560.hub.util.FeatureBits.register("Inventory HUD tick", () -> InventoryHudConfig.getInstance().isEnabledRaw()), InventoryHudFeature::tick));
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("killer560smod", "inventory_hud"),
                (graphics, deltaTracker) -> drawInGame(graphics, deltaTracker.getGameTimeDeltaPartialTick(false)));
    }

    private static void tick(Minecraft client) {
        InventoryHudConfig cfg = InventoryHudConfig.getInstance();
        int code = cfg.getKeyCode();
        if (!cfg.isEnabled() || cfg.getVisibility() != InventoryHudConfig.Visibility.TOGGLE_KEY || code < 0
                || client.player == null || client.getWindow() == null) {
            keyWasDown = false;
            return;
        }
        boolean down = com.killer560.hub.util.KeyUtil.isKeyDown(client.getWindow(), code);
        // Only from in-game so typing the key into chat / a sign never flips it.
        if (down && !keyWasDown && McCompat.screen(client) == null) {
            cfg.setToggledVisible(!cfg.isToggledVisible());
            cfg.save();
        }
        keyWasDown = down;
    }

    private static void drawInGame(GuiGraphicsExtractor graphics, float partialTick) {
        Minecraft client = Minecraft.getInstance();
        InventoryHudConfig cfg = InventoryHudConfig.getInstance();
        Player player = client.player;
        if (!cfg.isEnabled() || player == null || McCompat.hudHidden(client) || !isVisible(client, cfg)) {
            return;
        }
        if (cfg.isHideWhenEmpty() && isMainInventoryEmpty(player.getInventory())) {
            return;
        }
        InventoryHudElement element = InventoryHudElement.INSTANCE;
        int[] pos = com.killer560.hub.hud.HudElementRegistry.resolvePosition(element);
        float scale = com.killer560.hub.hud.HudElementRegistry.resolveScale(element);
        // Past every visibility gate above, so this is the panel really going on screen. The element's own
        // render() is the HUD editor's preview and deliberately does not stamp.
        HudSeen.markDrawn(ELEMENT_ID);
        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate(pos[0], pos[1]);
            graphics.pose().scale(scale, scale);
            drawPanel(graphics, 0, 0, player, cfg, partialTick);
        } catch (RuntimeException e) {
            // A broken frame must never take down the rest of the HUD.
        } finally {
            graphics.pose().popMatrix();
        }
    }

    private static boolean isVisible(Minecraft client, InventoryHudConfig cfg) {
        if (McCompat.screen(client) instanceof HudEditorScreen) {
            return false; // the editor draws its own preview via InventoryHudElement#render
        }
        if (cfg.isHideInScreens() && McCompat.screen(client) != null && !(McCompat.screen(client) instanceof ChatScreen)) {
            return false;
        }
        return switch (cfg.getVisibility()) {
            case ALWAYS -> true;
            case TOGGLE_KEY -> cfg.isToggledVisible();
            case HOLD_KEY -> cfg.getKeyCode() >= 0 && client.getWindow() != null
                    && !(McCompat.screen(client) instanceof ChatScreen)
                    && com.killer560.hub.util.KeyUtil.isKeyDown(client.getWindow(), cfg.getKeyCode());
        };
    }

    private static boolean isMainInventoryEmpty(Inventory inventory) {
        for (int i = MAIN_START; i < MAIN_START + ROWS * COLS; i++) {
            if (!inventory.getItem(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    static int cellSize(InventoryHudConfig cfg) {
        return cfg.isMiniMode() ? 9 : 18;
    }

    static int padding(InventoryHudConfig cfg) {
        return cfg.isMiniMode() ? 1 : 2;
    }

    static int panelWidth(InventoryHudConfig cfg) {
        return (cfg.isVertical() ? ROWS : COLS) * cellSize(cfg) + padding(cfg) * 2;
    }

    static int panelHeight(InventoryHudConfig cfg) {
        return (cfg.isVertical() ? COLS : ROWS) * cellSize(cfg) + padding(cfg) * 2;
    }

    private static int withAlpha(int rgb, int opacityPercent) {
        int alpha = Math.round(opacityPercent * 2.55f);
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }

    /** Draws the whole panel with its top-left at (x, y). {@code player} may be null (editor preview
     *  outside a world), in which case only the background is drawn. */
    static void drawPanel(GuiGraphicsExtractor graphics, int x, int y, Player player, InventoryHudConfig cfg,
                          float partialTick) {
        int w = panelWidth(cfg);
        int h = panelHeight(cfg);
        int cell = cellSize(cfg);
        int pad = padding(cfg);
        int opacity = cfg.getBackgroundOpacity();
        InventoryHudConfig.Background bg = cfg.getBackground();

        // Background Opacity 0 hides the panel entirely, items included (item opacity does not override it).
        //
        // Without this the dimming overlay at the bottom of this method is fully opaque at 0, so dragging the
        // slider to the bottom produced a SOLID BLACK BOX over the world - the exact opposite of what a 0%
        // opacity control implies. Hiding it is what the number means.
        if (opacity <= 0) {
            return;
        }

        if (bg != InventoryHudConfig.Background.NONE) {
            // Menu Theme (2026-10-15): Default keeps the colours below; anything else takes the menu theme's.
            com.killer560.hub.gui.OverlayPalette menu = com.killer560.hub.gui.OverlayThemes.of(com.killer560.hub.gui.OverlayThemes.INVENTORY_HUD);
            // The panel, its outline, the 27 slot backdrops and the slot grid: the same fills in the same order, all
            // before the items, submitted as ONE render-state element (GuiRects) instead of up to 32 that each paid
            // vanilla's intersection scan against the HUD drawn before it, every frame (2026-10-15).
            com.killer560.hub.hud.GuiRects rects = com.killer560.hub.hud.GuiRects.begin(graphics);
            rects.fill(x, y, x + w, y + h, withAlpha(menu == null ? PANEL_BG : menu.panel(), opacity));
            rects.outline(x, y, w, h, withAlpha(menu == null ? PANEL_BORDER : menu.accent(), opacity));
            if (bg == InventoryHudConfig.Background.SLOTS) {
                int slotBg = withAlpha(menu == null ? SLOT_BG : menu.card(), opacity);
                int slotBorder = withAlpha(menu == null ? SLOT_BORDER : menu.border(), opacity);
                for (int row = 0; row < ROWS; row++) {
                    for (int col = 0; col < COLS; col++) {
                        int cx = x + pad + displayCol(cfg, row, col) * cell;
                        int cy = y + pad + displayRow(cfg, row, col) * cell;
                        if (cfg.isMiniMode()) {
                            rects.fill(cx, cy, cx + cell - 1, cy + cell - 1, slotBg);
                        } else {
                            rects.fill(cx + 1, cy + 1, cx + cell - 1, cy + cell - 1, slotBg);
                        }
                    }
                }
                if (!cfg.isMiniMode()) {
                    drawSlotGrid(rects, x + pad, y + pad, cfg.isVertical() ? ROWS : COLS,
                            cfg.isVertical() ? COLS : ROWS, cell, slotBorder);
                }
            }
            rects.submit();
        }

        if (player == null) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        Inventory inventory = player.getInventory();
        int veil = itemVeil(bg, cfg.getItemOpacity());
        // One batch for every occupied cell's veil, submitted after the items so each veil sits over its item and count.
        GuiRects veils = veil == 0 ? null : GuiRects.begin(graphics);
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                ItemStack stack = inventory.getItem(MAIN_START + row * COLS + col);
                if (stack.isEmpty()) {
                    continue;
                }
                int cx = x + pad + displayCol(cfg, row, col) * cell;
                int cy = y + pad + displayRow(cfg, row, col) * cell;
                if (cfg.isMiniMode()) {
                    graphics.pose().pushMatrix();
                    try {
                        graphics.pose().translate(cx + 0.5f, cy + 0.5f);
                        graphics.pose().scale(0.5f, 0.5f);
                        drawStack(graphics, font, stack, 0, 0, cfg, partialTick);
                    } finally {
                        graphics.pose().popMatrix();
                    }
                } else {
                    drawStack(graphics, font, stack, cx + 1, cy + 1, cfg, partialTick);
                }
                if (veils != null) {
                    // The same rectangle the slot backdrop covers (the 16x16 icon in a full slot, the 8x8 one in mini mode).
                    int inset = cfg.isMiniMode() ? 0 : 1;
                    veils.fill(cx + inset, cy + inset, cx + cell - 1, cy + cell - 1, veil);
                }
            }
        }
        if (veils != null) {
            veils.submit();
        }
    }

    /**
     * The veil drawn over each occupied cell after its item, as ARGB at (100 - itemOpacity)%: the colour the panel is drawn
     * behind the items with (the slot backdrop, or the panel fill when there are no slot backdrops), so the item fades
     * toward that colour. Returns 0 (no veil, no extra draw) at 100% item opacity, and with no panel under the items (None),
     * where there is no colour to fade toward. At 0% the veil is opaque, so every item is hidden behind the colour; the
     * panel itself still follows the background opacity (0 there hides the whole panel, see drawPanel).
     */
    private static int itemVeil(InventoryHudConfig.Background bg, int itemOpacity) {
        if (itemOpacity >= 100 || bg == InventoryHudConfig.Background.NONE) {
            return 0;
        }
        com.killer560.hub.gui.OverlayPalette menu = com.killer560.hub.gui.OverlayThemes.of(com.killer560.hub.gui.OverlayThemes.INVENTORY_HUD);
        int rgb;
        if (bg == InventoryHudConfig.Background.SLOTS) {
            rgb = menu == null ? SLOT_BG : menu.card();
        } else {
            rgb = menu == null ? PANEL_BG : menu.panel();
        }
        return withAlpha(rgb, 100 - itemOpacity);
    }

    /**
     * Every slot's 1px outline ({@code cell} x {@code cell} at each grid position, as vanilla {@code outline} draws
     * it: a full-width top and bottom row and the two sides between them) as whole grid lines: one fill per
     * horizontal line (two adjacent slots' bottom and top rows together) and one per vertical segment (two adjacent
     * slots' right and left columns together). The same pixels with no overlaps, so a translucent colour blends
     * the same - (rows + 1) + rows * (cols + 1) fills (34 for the 3x9 grid) instead of 108, each a GUI element
     * that pays vanilla's intersection scan (95-fps-bench JFR, 2026-10-05).
     */
    private static void drawSlotGrid(com.killer560.hub.hud.GuiRects rects, int x0, int y0, int cols, int rows, int cell,
                                     int color) {
        int right = x0 + cols * cell;
        int bottom = y0 + rows * cell;
        // The same 34 fills, added to the panel's one render-state element (GuiRects): every separate fill was
        // intersection-tested against the whole HUD drawn before it, every frame (95-fps-bench JFR, 2026-10-07).
        rects.fill(x0, y0, right, y0 + 1, color);
        for (int r = 1; r < rows; r++) {
            int ly = y0 + r * cell;
            rects.fill(x0, ly - 1, right, ly + 1, color);
        }
        rects.fill(x0, bottom - 1, right, bottom, color);
        for (int r = 0; r < rows; r++) {
            int top = y0 + r * cell + 1;
            int bot = y0 + (r + 1) * cell - 1;
            rects.fill(x0, top, x0 + 1, bot, color);
            for (int c = 1; c < cols; c++) {
                int lx = x0 + c * cell;
                rects.fill(lx - 1, top, lx + 1, bot, color);
            }
            rects.fill(right - 1, top, right, bot, color);
        }
    }

    private static int displayCol(InventoryHudConfig cfg, int row, int col) {
        return cfg.isVertical() ? row : col;
    }

    private static int displayRow(InventoryHudConfig cfg, int row, int col) {
        return cfg.isVertical() ? col : row;
    }

    /** One 16x16 item at (x, y): vanilla hotbar pop animation, then count / durability decorations. The item is drawn
     *  at full alpha; the Opacity fade is {@link #itemVeil}, drawn by {@link #drawPanel} over this method's output. */
    private static void drawStack(GuiGraphicsExtractor graphics, Font font, ItemStack stack, int x, int y,
                                  InventoryHudConfig cfg, float partialTick) {
        // Why the item itself is not faded (checked in the 26.1.2 and 26.2 merged jars, 2026-10-18): RenderSystem has no
        // setShaderColor any more, and GuiGraphicsExtractor.item(ItemStack, int, int, int) only takes a position and a
        // seed. It builds a TrackingItemStackRenderState and adds a GuiItemRenderState (pose, state, x, y, scissor),
        // which carries no colour either. The only per-item colour is the tints array on submitItem, which a mixin
        // would have to rewrite, and blending of its alpha has not been verified in game. So the fade is the veil
        // drawn by drawPanel (see itemVeil): an approximation, not a true per-item alpha.
        try {
            float pop = cfg.isPickupAnimation() ? stack.getPopTime() - partialTick : 0f;
            if (pop > 0f) {
                // Same squash-and-stretch as vanilla Gui#extractSlot.
                float s = 1.0f + pop / 5.0f;
                graphics.pose().pushMatrix();
                try {
                    graphics.pose().translate(x + 8, y + 12);
                    graphics.pose().scale(1.0f / s, (s + 1.0f) / 2.0f);
                    graphics.pose().translate(-(x + 8), -(y + 12));
                    graphics.item(stack, x, y);
                } finally {
                    graphics.pose().popMatrix();
                }
            } else {
                graphics.item(stack, x, y);
            }

            if (cfg.isShowDurability()) {
                // Empty text suppresses the count while keeping the durability bar / cooldown overlay.
                graphics.itemDecorations(font, stack, x, y, cfg.isShowCounts() ? null : "");
            } else if (cfg.isShowCounts() && stack.getCount() != 1) {
                String count = String.valueOf(stack.getCount());
                graphics.text(font, count, x + 19 - 2 - font.width(count), y + 6 + 3, 0xFFFFFFFF, true);
            }
        } finally {
            // (nothing to restore - see the note above; no global colour is being set any more)
        }
    }

    /** HUD-editor entry (movable/scalable, position persisted in {@code HudConfig}). */
    public static final class InventoryHudElement implements HudElement {

        public static final InventoryHudElement INSTANCE = new InventoryHudElement();

        @Override
        public String id() {
            return ELEMENT_ID;
        }

        @Override
        public String displayName() {
            return "Inventory HUD";
        }

        @Override
        public int defaultX() {
            // Centred above the hotbar (killer560, 2026-10-18). Computed from the live window like the Storage Overlay's;
            // resolvePosition clamps it on screen and re-asks whenever the window or the scale changes. A position he
            // saved in the HUD editor is never replaced by this.
            var window = Minecraft.getInstance().getWindow();
            if (window == null) {
                return 10;
            }
            float scale = com.killer560.hub.hud.HudElementRegistry.resolveScale(this);
            return Math.max(0, Math.round((window.getGuiScaledWidth() - width() * scale) / 2f));
        }

        /** The panel's bottom edge sits this far above the bottom of the screen: the hotbar (22) and a little way up. */
        static final int DEFAULT_BOTTOM_GAP = 66;

        @Override
        public int defaultY() {
            var window = Minecraft.getInstance().getWindow();
            if (window == null) {
                return 10;
            }
            float scale = com.killer560.hub.hud.HudElementRegistry.resolveScale(this);
            return Math.max(0, Math.round(window.getGuiScaledHeight() - DEFAULT_BOTTOM_GAP - height() * scale));
        }

        @Override
        public int width() {
            return panelWidth(InventoryHudConfig.getInstance());
        }

        @Override
        public int height() {
            return panelHeight(InventoryHudConfig.getInstance());
        }

        @Override
        public boolean isEnabledInSettings() {
            return InventoryHudConfig.getInstance().isEnabled();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            InventoryHudConfig cfg = InventoryHudConfig.getInstance();
            Minecraft client = Minecraft.getInstance();
            // Editor preview only - in game this element is drawn by InventoryHudFeature's own HUD layer.
            if (!cfg.isEnabled() || !(McCompat.screen(client) instanceof HudEditorScreen)) {
                return;
            }
            try {
                drawPanel(graphics, x, y, client.player, cfg, 0f);
            } catch (RuntimeException e) {
                // Same guard as the in-game layer: a bad item render must not crash the HUD editor.
            }
        }
    }
}
