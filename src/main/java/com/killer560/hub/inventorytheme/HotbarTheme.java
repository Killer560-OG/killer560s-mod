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
     * Colours from {@link InventoryPalette}, all opaque (killer560, 2026-10-12: "make the inventories not transparent").
     * Pixel-exact through
     * {@link PixelRects}, also under Hotbar Scale.
     */
    private static void drawBackground(GuiGraphicsExtractor graphics, InventoryThemeConfig cfg, Player player, int cx,
                                       int bottom) {
        if (cfg.getPreset() == InventoryThemeConfig.Preset.DEFAULT) {
            drawVanillaBar(graphics, player, cx, bottom);
            return;
        }
        InventoryPalette p = cfg.getPalette();
        int alpha = 0xFF; // opaque in every preset (Background Opacity no longer applies)
        int x0 = cx - 91;
        PixelRects r = PixelRects.begin(graphics);
        try {
            int u = r.unit();
            int[] bar = barBox(r, cx, bottom);
            r.fill(bar[0], bar[1], bar[2], bar[3], withAlpha(p.panel(), alpha));
            r.outline(bar[0], bar[1], bar[2], bar[3], u, withAlpha(p.border(), alpha));
            for (int i = 0; i < 9; i++) {
                int sx = x0 + 3 + 20 * i;
                InventoryThemeFeature.slotSquare(r, sx - 1, bottom - 20, sx + 17, bottom - 2, p.slotFill(), p);
            }
            // Vanilla's ring in every preset, its light band in the preset's colour (killer560, 2026-10-12: "Mimic that for
            // all presets"): Mod Theme and Custom in the accent, Dark in a muted grey so it does not glare.
            int ringMain = cfg.getPreset() == InventoryThemeConfig.Preset.DARK && !cfg.isUseCustomAccent()
                    ? DARK_RING : withAlpha(p.accent(), 0xFF);
            int frameDark = withAlpha(p.panel(), 0xFF);
            selectionRing(r, x0, bottom, player.getInventory().getSelectedSlot(), withAlpha(p.border(), 0xFF),
                    withAlpha(p.border(), 0xFF), ringMain, mix(ringMain, frameDark, 0.55f), mix(ringMain, frameDark, 0.25f));

            ItemStack offhand = player.getOffhandItem();
            if (!offhand.isEmpty()) {
                boolean left = player.getMainArm().getOpposite() == HumanoidArm.LEFT;
                int itemX = left ? cx - 91 - 26 : cx + 91 + 10;
                int ox0 = r.x(itemX - 3);
                int ox1 = r.x(itemX - 3 + 22);
                r.fill(ox0, bar[1], ox1, bar[3], withAlpha(p.panel(), alpha));
                r.outline(ox0, bar[1], ox1, bar[3], u, withAlpha(p.border(), alpha));
                InventoryThemeFeature.slotSquare(r, itemX - 1, bottom - 20, itemX + 17, bottom - 2, p.slotFill(), p);
            }
        } finally {
            r.submit();
        }
    }

    // Vanilla's hotbar shades (read off the game's own hotbar, 26.1.2). Vanilla's slot insides are translucent; here they
    // are opaque (killer560, 2026-10-12: "make the inventories not transparent") in a dark grey close to how vanilla's read.
    private static final int V_BORDER = 0xFF000000;
    private static final int V_TOP_LIGHT = 0xFF939393;
    private static final int V_LIGHT = 0xFF7E7E7E;
    private static final int V_DARK = 0xFF5D5D5D;
    private static final int V_END_DARK = 0xFF474747;
    public static final int V_INSIDE = 0xFF2E2E2E;
    public static final int V_INSIDE_EDGE = 0xFF1E1E1E;
    // Vanilla's selection ring, read off the game's own (26.1.2): a black top, dark sides, then light / mid / faint bands.
    private static final int V_RING_TOP = 0xFF000000;
    private static final int V_RING_SIDE = 0xFF2B2B2B;
    private static final int V_RING_LIGHT = 0xFFFFFFFF;
    private static final int V_RING_MID = 0xFFA1A1A1;
    private static final int V_RING_FAINT = 0xFF5F5F5F;

    /**
     * The Default preset's hotbar: vanilla's own look, drawn by the mod (killer560, 2026-10-12: Default is "the normal"
     * vanilla look). The 182 x 22 bar has a black outline, a light top row and left column, then a dark row / column, and
     * a light-then-dark pair along the bottom and right end. Between two slots there are four columns, light, dark, light,
     * dark, so each slot's inside is exactly its 16-unit item box. The insides are an opaque dark grey, a shade darker
     * along their top and left. The selection ring is vanilla's (see {@link #selectionRing}). Coordinates are GUI units through {@link PixelRects}; the bottom outline is
     * kept on the window when the GUI's last row hangs off it.
     */
    private static void drawVanillaBar(GuiGraphicsExtractor graphics, Player player, int cx, int bottom) {
        int x0 = cx - 91;
        int top = bottom - 22;
        int winH = Minecraft.getInstance().getWindow().getHeight();
        PixelRects r = PixelRects.begin(graphics);
        try {
            int u = r.unit();
            // frame
            InventoryThemeFeature.box(r, x0 + 1, top + 1, x0 + 181, top + 2, V_TOP_LIGHT);
            InventoryThemeFeature.box(r, x0 + 1, top + 2, x0 + 2, bottom - 1, V_TOP_LIGHT);
            InventoryThemeFeature.box(r, x0 + 2, top + 2, x0 + 179, top + 3, V_DARK);
            InventoryThemeFeature.box(r, x0 + 2, top + 3, x0 + 3, bottom - 2, V_DARK);
            InventoryThemeFeature.box(r, x0 + 2, bottom - 3, x0 + 179, bottom - 2, V_LIGHT);
            InventoryThemeFeature.box(r, x0 + 2, bottom - 2, x0 + 180, bottom - 1, V_DARK);
            InventoryThemeFeature.box(r, x0 + 179, top + 2, x0 + 180, bottom - 2, V_LIGHT);
            InventoryThemeFeature.box(r, x0 + 180, top + 2, x0 + 181, bottom - 1, V_END_DARK);
            for (int i = 0; i < 8; i++) {
                int gx = x0 + 19 + 20 * i; // right after slot i's 16-unit inside
                InventoryThemeFeature.box(r, gx, top + 3, gx + 1, bottom - 3, V_LIGHT);
                InventoryThemeFeature.box(r, gx + 1, top + 3, gx + 2, bottom - 3, V_DARK);
                InventoryThemeFeature.box(r, gx + 2, top + 3, gx + 3, bottom - 3, V_LIGHT);
                InventoryThemeFeature.box(r, gx + 3, top + 3, gx + 4, bottom - 3, V_DARK);
            }
            for (int i = 0; i < 9; i++) {
                int sx = x0 + 3 + 20 * i;
                InventoryThemeFeature.box(r, sx, top + 3, sx + 16, top + 4, V_INSIDE_EDGE);
                InventoryThemeFeature.box(r, sx, top + 4, sx + 1, bottom - 3, V_INSIDE_EDGE);
                InventoryThemeFeature.box(r, sx + 1, top + 4, sx + 16, bottom - 3, V_INSIDE);
            }
            // outline, the bottom row pulled onto the window if the last GUI row hangs off it
            int px0 = r.x(x0);
            int px1 = r.x(x0 + 182);
            int py0 = r.y(top);
            int py1 = Math.min(r.y(bottom), winH);
            r.fill(px0, py0, px1, py0 + u, V_BORDER);
            r.fill(px0, py1 - u, px1, py1, V_BORDER);
            r.fill(px0, py0 + u, px0 + u, py1 - u, V_BORDER);
            r.fill(px1 - u, py0 + u, px1, py1 - u, V_BORDER);
            selectionRing(r, x0, bottom, player.getInventory().getSelectedSlot(), V_RING_TOP, V_RING_SIDE, V_RING_LIGHT,
                    V_RING_MID, V_RING_FAINT);

            ItemStack offhand = player.getOffhandItem();
            if (!offhand.isEmpty()) {
                boolean left = player.getMainArm().getOpposite() == HumanoidArm.LEFT;
                int itemX = left ? cx - 91 - 26 : cx + 91 + 10;
                int ox = itemX - 3;
                r.fill(r.x(ox), py0, r.x(ox + 22), py1, V_BORDER);
                InventoryThemeFeature.box(r, ox + 1, top + 1, ox + 21, top + 2, V_TOP_LIGHT);
                InventoryThemeFeature.box(r, ox + 1, top + 2, ox + 2, bottom - 1, V_TOP_LIGHT);
                InventoryThemeFeature.box(r, ox + 2, top + 2, ox + 21, bottom - 1, V_DARK);
                InventoryThemeFeature.box(r, ox + 19, top + 3, ox + 20, bottom - 2, V_LIGHT);
                InventoryThemeFeature.box(r, ox + 2, bottom - 3, ox + 20, bottom - 2, V_LIGHT);
                InventoryThemeFeature.box(r, itemX, top + 3, itemX + 16, bottom - 3, V_INSIDE);
            }
        } finally {
            r.submit();
        }
    }

    /** Dark preset's ring colour: a muted light grey, well below white. */
    private static final int DARK_RING = 0xFF8C8C92;
    /** {@code a} blended toward {@code b} by {@code 1 - t} (t = 1 gives a), opaque. */
    static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) * t + ((b >> 16) & 0xFF) * (1 - t));
        int g = Math.round(((a >> 8) & 0xFF) * t + ((b >> 8) & 0xFF) * (1 - t));
        int bl = Math.round((a & 0xFF) * t + (b & 0xFF) * (1 - t));
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /**
     * The selected slot's ring, vanilla's own structure in every preset (killer560, 2026-10-12: "it should have an orange
     * that kind of fades it into that outer border ... Mimic that for all presets"), read pixel by pixel off the game's
     * selection sprite: a 24 x 24 square from one unit left of / above the slot's 20-unit cell, four one-unit frames from
     * the outside in -
     * <ol>
     *   <li>the outline: {@code top} along the top (and the off-screen bottom), {@code side} down both sides - the bar's own
     *   edge colour, so the ring reads as the bar's frame bulging round the slot;</li>
     *   <li>light along the top and left, faint along the right and bottom (mid where they meet);</li>
     *   <li>mid all round;</li>
     *   <li>faint along the top and left, light along the right and bottom (mid at the top-right corner) - the band that
     *   fades into the slot.</li>
     * </ol>
     * The inside (the slot's 16-unit item box) is never touched. Like vanilla's sprite the bottom outline lies one unit
     * below the GUI's bottom, off screen.
     */
    private static void selectionRing(PixelRects r, int x0, int bottom, int sel, int top, int side, int light, int mid,
                                      int faint) {
        int sx = x0 - 1 + 20 * sel;
        int ty = bottom - 23;
        // 1: outline
        InventoryThemeFeature.box(r, sx, ty, sx + 24, ty + 1, top);
        InventoryThemeFeature.box(r, sx, ty + 1, sx + 1, bottom + 1, side);
        InventoryThemeFeature.box(r, sx + 23, ty + 1, sx + 24, bottom + 1, side);
        InventoryThemeFeature.box(r, sx, bottom, sx + 24, bottom + 1, top);
        // 2: light top / left, faint right / bottom
        InventoryThemeFeature.box(r, sx + 1, ty + 1, sx + 22, ty + 2, light);
        InventoryThemeFeature.box(r, sx + 22, ty + 1, sx + 23, ty + 2, mid);
        InventoryThemeFeature.box(r, sx + 1, ty + 2, sx + 2, bottom - 1, light);
        InventoryThemeFeature.box(r, sx + 1, bottom - 1, sx + 2, bottom, mid);
        InventoryThemeFeature.box(r, sx + 22, ty + 2, sx + 23, bottom, faint);
        InventoryThemeFeature.box(r, sx + 2, bottom - 1, sx + 22, bottom, faint);
        // 3: mid all round
        InventoryThemeFeature.box(r, sx + 2, ty + 2, sx + 22, ty + 3, mid);
        InventoryThemeFeature.box(r, sx + 2, ty + 3, sx + 3, bottom - 1, mid);
        InventoryThemeFeature.box(r, sx + 21, ty + 3, sx + 22, bottom - 1, mid);
        InventoryThemeFeature.box(r, sx + 3, bottom - 2, sx + 21, bottom - 1, mid);
        // 4: faint top / left, light right / bottom
        InventoryThemeFeature.box(r, sx + 3, ty + 3, sx + 20, ty + 4, faint);
        InventoryThemeFeature.box(r, sx + 20, ty + 3, sx + 21, ty + 4, mid);
        InventoryThemeFeature.box(r, sx + 3, ty + 4, sx + 4, bottom - 3, faint);
        InventoryThemeFeature.box(r, sx + 20, ty + 4, sx + 21, bottom - 2, light);
        InventoryThemeFeature.box(r, sx + 3, bottom - 3, sx + 20, bottom - 2, light);
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
