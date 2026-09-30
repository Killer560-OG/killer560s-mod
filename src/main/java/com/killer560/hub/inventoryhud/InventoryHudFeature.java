package com.killer560.hub.inventoryhud;

import com.killer560.hub.util.FeatureGuard;
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
 * The Opacity setting fades the background/slots AND, since 2026-09-27, the items too - not by tinting
 * each item (still impossible, see {@link #drawStack}'s doc for why) but by drawing a translucent black
 * dimming quad over the whole panel, on top of the items, in {@link #drawPanel}. killer560 (2026-09-27):
 * "the opacity doesn't affect the items opacity and it should ... For the item opactiy find a workaround.
 * Dim the whole menu or something." This is that workaround.
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
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("InventoryHudFeature.tick", InventoryHudFeature::tick));
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

        // Opacity 0 hides the panel entirely, items included.
        //
        // Without this the dimming overlay at the bottom of this method is fully opaque at 0, so dragging the
        // slider to the bottom produced a SOLID BLACK BOX over the world - the exact opposite of what a 0%
        // opacity control implies. Hiding it is what the number means.
        if (opacity <= 0) {
            return;
        }

        if (bg != InventoryHudConfig.Background.NONE) {
            graphics.fill(x, y, x + w, y + h, withAlpha(PANEL_BG, opacity));
            graphics.outline(x, y, w, h, withAlpha(PANEL_BORDER, opacity));
            if (bg == InventoryHudConfig.Background.SLOTS) {
                int slotBg = withAlpha(SLOT_BG, opacity);
                int slotBorder = withAlpha(SLOT_BORDER, opacity);
                for (int row = 0; row < ROWS; row++) {
                    for (int col = 0; col < COLS; col++) {
                        int cx = x + pad + displayCol(cfg, row, col) * cell;
                        int cy = y + pad + displayRow(cfg, row, col) * cell;
                        if (cfg.isMiniMode()) {
                            graphics.fill(cx, cy, cx + cell - 1, cy + cell - 1, slotBg);
                        } else {
                            graphics.fill(cx + 1, cy + 1, cx + cell - 1, cy + cell - 1, slotBg);
                            graphics.outline(cx, cy, cell, cell, slotBorder);
                        }
                    }
                }
            }
        }

        if (player == null) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        Inventory inventory = player.getInventory();
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
            }
        }

        // Item-opacity workaround. killer560 (2026-09-27): "the opacity doesn't affect the items opacity
        // and it should ... For the item opactiy find a workaround. Dim the whole menu or something."
        //
        // We can't tint the items that were just drawn above (no shader colour to lean on any more - see
        // drawStack's doc), so instead this draws a second, translucent-black quad across the SAME area,
        // on top of everything drawn so far including the items. It's the same Opacity slider, just
        // inverted: at Opacity 100 the overlay alpha is 0 (a fully "opaque" panel looks exactly like it did
        // before this change), and it gets darker as Opacity drops, so lowering the slider now visibly
        // dims the icons too, not just the panel behind them.
        //
        // This is drawn LAST, after the background/slot fill above and after the item loop just above it -
        // both of those are the only other things this method draws, so "last" here really does mean
        // "on top of the items". Composing with the background fade: the background fill already faded
        // out toward Opacity 0, and would look like it's fading into nothing rather than dimming, which is
        // the opposite of what was asked for. Reusing the SAME (100 - opacity) alpha for this quad means
        // the background/slot area (which already has its own fill under it) ends up darker overall than
        // the item area at any given slider position - e.g. at Opacity 50 the slot fill is already ~50%
        // black and this quad adds another ~50% black on top of that, while an item cell only ever gets the
        // one ~50% black pass. That reads as "the frame darkens faster than the icons", which was the
        // decision made here rather than trying to exempt the background pixels from the overlay (that
        // would mean punching item-shaped holes in the quad, i.e. right back to needing per-item alpha).
        // It does not go muddy - both layers are the same flat black, so it only ever compounds toward
        // darker, never toward a mixed colour.
        // Capped at 80% darkening rather than scaling all the way to opaque. At full strength the icons stop
        // being readable at all, which is worse than useless for a HUD whose job is telling him what he is
        // holding - and the fully-hidden case is already handled by the early return above, so nothing is
        // lost by never reaching solid black here.
        if (opacity < 100) {
            graphics.fill(x, y, x + w, y + h, withAlpha(0x000000, (100 - opacity) * 4 / 5));
        }
    }

    private static int displayCol(InventoryHudConfig cfg, int row, int col) {
        return cfg.isVertical() ? row : col;
    }

    private static int displayRow(InventoryHudConfig cfg, int row, int col) {
        return cfg.isVertical() ? col : row;
    }

    /** One 16x16 item at (x, y): vanilla hotbar pop animation, then count / durability decorations.
     *  <p>
     *  killer560 (2026-09-27): "the opacity doesn't affect the items opacity and it should." Item
     *  rendering ({@code graphics.item}) ignores a plain ARGB colour the way text/fill do - it draws the
     *  item's own texture at full alpha regardless, so this method still cannot tint what it draws. See
     *  the comment below for why, and see {@link #drawPanel}'s dimming-quad comment for the workaround
     *  that now compensates for it from outside this method instead. */
    private static void drawStack(GuiGraphicsExtractor graphics, Font font, ItemStack stack, int x, int y,
                                  InventoryHudConfig cfg, float partialTick) {
        // TRUE PER-ITEM OPACITY IS STILL NOT IMPLEMENTED HERE, and this is the honest place to say why.
        //
        // killer560 (2026-09-27): "For inventory hud the opacity doesn't affect the items opacity and it should."
        // He is right that it should. The obvious way - RenderSystem.setShaderColor(1,1,1,alpha) around the item
        // draw - is how it was done for years and does NOT exist in 26.1.2: the method is gone from RenderSystem
        // entirely (javap-checked against the mapped jar), because item rendering now goes through the submit /
        // render-pipeline path rather than a global shader colour. Tinting an item here would mean a custom
        // RenderPipeline or a mixin into the item render layer, which is a real piece of work and exactly the kind
        // of change that quietly breaks every item this mod draws if it is got wrong.
        //
        // That part of the ask is still blocked for the reason above. What's no longer blocked is the actual
        // complaint - the items looking untouched by the Opacity slider. killer560's own fallback ("dim the whole
        // menu or something") is implemented one level up: drawPanel draws a translucent black quad over the whole
        // panel, including every item this method draws, right after this method returns. So the ITEMS this method
        // draws are still opaque textures with no alpha of their own; they just get dimmed from above afterwards.
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
            return 10;
        }

        @Override
        public int defaultY() {
            return 10;
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
