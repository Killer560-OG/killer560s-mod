package com.killer560.hub.inventorytheme;

import com.killer560.hub.cheatutils.CheatUtils;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * The Inventory Theme on the in-game hotbar - killer560, 2026-10-07: "Make the custom inventory also apply to my normal
 * toolbar", plus "a custom scale option, an option to recolor it, and a line width slider".
 * <p>
 * <b>How.</b> Vanilla's hotbar is a Fabric HUD layer ({@code VanillaHudElements.HOTBAR}, which wraps
 * {@code Gui.extractItemHotbar} on 26.1.2 and {@code Hud.extractItemHotbar} on 26.2 - javap of fabric-rendering-v1 23.3.1
 * and 25.3.3). This wraps that layer, the same no-version-specific-mixin way the Custom Crosshair wraps its own: it draws
 * the themed panel, slot squares, selected slot and offhand square, then runs VANILLA'S layer with its four background
 * sprites dropped ({@link com.killer560.hub.inventorytheme.mixin.InventoryThemeHotbarSpriteMixin}). So the items, their
 * pickup "pop", stack counts, durability bars, cooldown overlays and the hotbar attack indicator are vanilla's own
 * pixels, not a copy that could drift. Scale is a pose transform about the hotbar's bottom centre around both halves.
 * <p>
 * Geometry is vanilla's (javap, identical on both versions): bar 182x22 at (w/2 - 91, h - 22); item i at
 * (w/2 - 90 + 20i + 2, h - 19); offhand item at (w/2 - 91 - 26, h - 19) on the left or (w/2 + 91 + 10, h - 19) on the right,
 * drawn only when the offhand holds something.
 */
public final class HotbarTheme {

    private static final Identifier HOTBAR = Identifier.withDefaultNamespace("hud/hotbar");
    private static final Identifier HOTBAR_SELECTION = Identifier.withDefaultNamespace("hud/hotbar_selection");
    private static final Identifier OFFHAND_LEFT = Identifier.withDefaultNamespace("hud/hotbar_offhand_left");
    private static final Identifier OFFHAND_RIGHT = Identifier.withDefaultNamespace("hud/hotbar_offhand_right");

    /** True only while vanilla's hotbar layer runs inside {@link #extract}; read by the sprite mixin. Render thread. */
    public static boolean suppressingSprites;

    /** Frames drawn themed / handed to vanilla untouched - testkit evidence of which path ran. */
    public static volatile long themedFrames;
    public static volatile long vanillaFrames;

    private HotbarTheme() {
    }

    public static void register() {
        HudElementRegistry.replaceElement(VanillaHudElements.HOTBAR,
                vanilla -> (graphics, deltaTracker) -> extract(graphics, deltaTracker, vanilla));
    }

    public static boolean isHotbarSprite(Identifier sprite) {
        return HOTBAR.equals(sprite) || HOTBAR_SELECTION.equals(sprite) || OFFHAND_LEFT.equals(sprite)
                || OFFHAND_RIGHT.equals(sprite);
    }

    /** Inventory Theme on, Hotbar on, and (when scoped to Hypixel) on Hypixel/p3sim. */
    public static boolean active(Minecraft mc) {
        InventoryThemeConfig cfg = InventoryThemeConfig.getInstance();
        if (!cfg.isEnabled() || !cfg.isThemeHotbar()) {
            return false;
        }
        return !cfg.isHypixelOnly() || CheatUtils.isOnDungeonServer(mc);
    }

    private static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, HudElement vanilla) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        boolean themed;
        try {
            themed = player != null && active(mc);
        } catch (RuntimeException e) {
            themed = false;
        }
        if (!themed) {
            vanillaFrames++;
            vanilla.extractRenderState(graphics, deltaTracker);
            return;
        }
        themedFrames++;
        InventoryThemeConfig cfg = InventoryThemeConfig.getInstance();
        int cx = graphics.guiWidth() / 2;
        int bottom = graphics.guiHeight();
        float scale = cfg.getHotbarScale();
        graphics.pose().pushMatrix();
        try {
            if (scale != 1.0f) {
                graphics.pose().translate(cx, bottom);
                graphics.pose().scale(scale, scale);
                graphics.pose().translate(-cx, -bottom);
            }
            try {
                drawBackground(graphics, cfg, player, cx, bottom);
            } catch (RuntimeException e) {
                // A broken theme draw must never take the hotbar's items with it - fall through to vanilla's pass.
            }
            suppressingSprites = true;
            try {
                vanilla.extractRenderState(graphics, deltaTracker);
            } finally {
                suppressingSprites = false;
            }
        } finally {
            graphics.pose().popMatrix();
        }
    }

    /** The themed panel, nine slot squares, the selected slot and (when the offhand holds something) its square. */
    private static void drawBackground(GuiGraphicsExtractor graphics, InventoryThemeConfig cfg, Player player, int cx,
                                       int bottom) {
        int accent = cfg.getAccentColor();
        int slotColor = cfg.getSlotColor();
        int panel = (Math.round(cfg.getBackgroundOpacity() * 255f) << 24) | (cfg.getPanelColor() & 0x00FFFFFF);
        int line = cfg.getLineWidth();
        int x0 = cx - 91;
        int y0 = bottom - 22;

        com.killer560.hub.hud.GuiRects rects = com.killer560.hub.hud.GuiRects.begin(graphics);
        rects.fill(x0, y0, x0 + 182, bottom, panel);
        InventoryThemeFeature.outline(rects, x0, y0, 182, 22, line, accent);
        int selected = player.getInventory().getSelectedSlot();
        for (int i = 0; i < 9; i++) {
            // The slot square sits one unit outside the 16x16 item, like a menu slot's backdrop.
            int sx = cx - 90 + i * 20 + 2 - 1;
            int sy = bottom - 19 - 1;
            rects.fill(sx, sy, sx + 18, sy + 18, slotColor);
            if (i != selected) {
                InventoryThemeFeature.outline(rects, sx, sy, 18, 18, line, accent);
            }
        }
        // Selected slot: the hover glow over the square and a frame one unit heavier than the lines (always visible,
        // even at line width 0), in place of vanilla's white selection sprite.
        int sx = cx - 90 + selected * 20 + 2 - 1;
        int sy = bottom - 19 - 1;
        rects.fill(sx, sy, sx + 18, sy + 18, (0x55 << 24) | (accent & 0x00FFFFFF));
        InventoryThemeFeature.outline(rects, sx - 1, sy - 1, 20, 20, line + 1, accent);

        ItemStack offhand = player.getOffhandItem();
        if (!offhand.isEmpty()) {
            boolean left = player.getMainArm().getOpposite() == HumanoidArm.LEFT;
            int itemX = left ? cx - 91 - 26 : cx + 91 + 10;
            int ox = itemX - 3;
            rects.fill(ox, y0, ox + 22, bottom, panel);
            InventoryThemeFeature.outline(rects, ox, y0, 22, 22, line, accent);
            rects.fill(itemX - 1, bottom - 20, itemX + 17, bottom - 2, slotColor);
            InventoryThemeFeature.outline(rects, itemX - 1, bottom - 20, 18, 18, line, accent);
        }
        rects.submit();
    }
}
