package com.killer560.hub.inventoryhud;

import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.hud.HudElement;
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
        ClientTickEvents.END_CLIENT_TICK.register(InventoryHudFeature::tick);
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
        boolean down = InputConstants.isKeyDown(client.getWindow(), code);
        // Only from in-game so typing the key into chat / a sign never flips it.
        if (down && !keyWasDown && client.screen == null) {
            cfg.setToggledVisible(!cfg.isToggledVisible());
            cfg.save();
        }
        keyWasDown = down;
    }

    private static void drawInGame(GuiGraphicsExtractor graphics, float partialTick) {
        Minecraft client = Minecraft.getInstance();
        InventoryHudConfig cfg = InventoryHudConfig.getInstance();
        Player player = client.player;
        if (!cfg.isEnabled() || player == null || client.options.hideGui || !isVisible(client, cfg)) {
            return;
        }
        if (cfg.isHideWhenEmpty() && isMainInventoryEmpty(player.getInventory())) {
            return;
        }
        InventoryHudElement element = InventoryHudElement.INSTANCE;
        int[] pos = com.killer560.hub.hud.HudElementRegistry.resolvePosition(element);
        float scale = com.killer560.hub.hud.HudElementRegistry.resolveScale(element);
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
        if (client.screen instanceof HudEditorScreen) {
            return false; // the editor draws its own preview via InventoryHudElement#render
        }
        if (cfg.isHideInScreens() && client.screen != null && !(client.screen instanceof ChatScreen)) {
            return false;
        }
        return switch (cfg.getVisibility()) {
            case ALWAYS -> true;
            case TOGGLE_KEY -> cfg.isToggledVisible();
            case HOLD_KEY -> cfg.getKeyCode() >= 0 && client.getWindow() != null
                    && !(client.screen instanceof ChatScreen)
                    && InputConstants.isKeyDown(client.getWindow(), cfg.getKeyCode());
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

        if (bg != InventoryHudConfig.Background.NONE && opacity > 0) {
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
                    graphics.pose().translate(cx + 0.5f, cy + 0.5f);
                    graphics.pose().scale(0.5f, 0.5f);
                    drawStack(graphics, font, stack, 0, 0, cfg, partialTick);
                    graphics.pose().popMatrix();
                } else {
                    drawStack(graphics, font, stack, cx + 1, cy + 1, cfg, partialTick);
                }
            }
        }
    }

    private static int displayCol(InventoryHudConfig cfg, int row, int col) {
        return cfg.isVertical() ? row : col;
    }

    private static int displayRow(InventoryHudConfig cfg, int row, int col) {
        return cfg.isVertical() ? col : row;
    }

    /** One 16x16 item at (x, y): vanilla hotbar pop animation, then count / durability decorations. */
    private static void drawStack(GuiGraphicsExtractor graphics, Font font, ItemStack stack, int x, int y,
                                  InventoryHudConfig cfg, float partialTick) {
        float pop = cfg.isPickupAnimation() ? stack.getPopTime() - partialTick : 0f;
        if (pop > 0f) {
            // Same squash-and-stretch as vanilla Gui#extractSlot.
            float s = 1.0f + pop / 5.0f;
            graphics.pose().pushMatrix();
            graphics.pose().translate(x + 8, y + 12);
            graphics.pose().scale(1.0f / s, (s + 1.0f) / 2.0f);
            graphics.pose().translate(-(x + 8), -(y + 12));
            graphics.item(stack, x, y);
            graphics.pose().popMatrix();
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
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            InventoryHudConfig cfg = InventoryHudConfig.getInstance();
            Minecraft client = Minecraft.getInstance();
            // Editor preview only - in game this element is drawn by InventoryHudFeature's own HUD layer.
            if (!cfg.isEnabled() || !(client.screen instanceof HudEditorScreen)) {
                return;
            }
            drawPanel(graphics, x, y, client.player, cfg, 0f);
        }
    }
}
