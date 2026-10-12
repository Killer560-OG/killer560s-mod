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

    /**
     * The hotbar in vanilla's shape, recoloured (killer560, 2026-10-12: "a reskin of the original with its coloring"):
     * the 182 x 22 bar with a one-unit outline and the panel fill, each of the nine slots a sunken square exactly round
     * its item (item x - 1 .. x + 17, the same square the inventory draws), the selected slot ringed two units wide in the
     * accent where vanilla puts its 24 x 24 selection frame, and the offhand's own square when it holds something.
     * Colours from {@link InventoryPalette}; Background Opacity scales the bar's alpha. Pixel-exact through
     * {@link PixelRects}, also under Hotbar Scale.
     */
    private static void drawBackground(GuiGraphicsExtractor graphics, InventoryThemeConfig cfg, Player player, int cx,
                                       int bottom) {
        InventoryPalette p = cfg.getPalette();
        int alpha = Math.round(cfg.getBackgroundOpacity() * 255f);
        int x0 = cx - 91;
        int winH = Minecraft.getInstance().getWindow().getHeight();
        PixelRects r = PixelRects.begin(graphics);
        try {
            int u = r.unit();
            int[] bar = barBox(r, cx, bottom);
            r.fill(bar[0], bar[1], bar[2], bar[3], withAlpha(p.panel(), alpha));
            r.outline(bar[0], bar[1], bar[2], bar[3], u, withAlpha(p.border(), alpha));
            for (int i = 0; i < 9; i++) {
                int sx = x0 + 3 + 20 * i;
                InventoryThemeFeature.sunken(r, sx - 1, bottom - 20, sx + 17, bottom - 2, p.slotFill(), p);
            }
            int selected = player.getInventory().getSelectedSlot();
            int fx = x0 - 1 + 20 * selected;
            r.outline(r.x(fx), r.y(bottom - 23), r.x(fx + 24), Math.min(r.y(bottom + 1), winH), 2 * u, p.accent());

            ItemStack offhand = player.getOffhandItem();
            if (!offhand.isEmpty()) {
                boolean left = player.getMainArm().getOpposite() == HumanoidArm.LEFT;
                int itemX = left ? cx - 91 - 26 : cx + 91 + 10;
                int ox0 = r.x(itemX - 3);
                int ox1 = r.x(itemX - 3 + 22);
                r.fill(ox0, bar[1], ox1, bar[3], withAlpha(p.panel(), alpha));
                r.outline(ox0, bar[1], ox1, bar[3], u, withAlpha(p.border(), alpha));
                InventoryThemeFeature.sunken(r, itemX - 1, bottom - 20, itemX + 17, bottom - 2, p.slotFill(), p);
            }
        } finally {
            r.submit();
        }
    }

    private static int withAlpha(int argb, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (argb & 0xFFFFFF);
    }

    /**
     * The bar's pixel box {x0, y0, x1, y1}: vanilla's 182 x 22. killer560, 2026-10-12: "the orange outlines are missing on
     * the bottom". The GUI height is the window height divided by the GUI scale ROUNDED UP (Window adds one when it does
     * not divide), so on a window whose height is not a multiple of the scale the last GUI row hangs off the screen and
     * the bottom edge drawn on it was cut off whole. The bottom edge is pulled back onto the window and the top edge down
     * by the same amount, so the bar keeps equal room above and below its slots.
     */
    private static int[] barBox(PixelRects r, int cx, int bottom) {
        int x0 = cx - 91;
        int py0 = r.y(bottom - 22);
        int py1 = r.y(bottom);
        int clip = Math.max(0, py1 - Minecraft.getInstance().getWindow().getHeight());
        return new int[]{r.x(x0), py0 + clip, r.x(x0 + 182), py1 - clip};
    }

    /**
     * A hotbar slot's inside is exactly its item's 16-unit box (the sunken square sits round it), the same square the
     * Item Rarity Background fills, so there is nothing to widen. @return always false: the caller draws its 16x16 square.
     */
    public static boolean fillCell(GuiGraphicsExtractor graphics, int itemX, int itemY, int color, int ring) {
        return false;
    }

}
