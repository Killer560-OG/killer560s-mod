package com.killer560.hub.inventorytheme;

/**
 * The shades the vanilla-shaped Inventory Theme is drawn in (killer560, 2026-10-12: "change inventory up to essentially
 * be a reskin of the original with its coloring"). Every shape is vanilla's own - the beveled panel, each slot's sunken
 * square, the player-model window, the crafting arrow, the hotbar - drawn procedurally (no texture is copied or tinted),
 * and each part takes one of these colors, mapped from vanilla's own light / mid / dark grays:
 * <ul>
 *   <li>{@code border} - the panel's 1-unit outline (vanilla black).</li>
 *   <li>{@code panel}, {@code light}, {@code dark} - the panel fill and its raised bevel: light top-left, dark bottom-right
 *   (vanilla #C6C6C6, #FFFFFF, #555555).</li>
 *   <li>{@code slotFill}, {@code slotDark}, {@code slotLight} - a slot's inside and its sunken bevel: dark top-left, light
 *   bottom-right (vanilla #8B8B8B, #373737, #FFFFFF).</li>
 *   <li>{@code modelFill} - the player-model window (vanilla black).</li>
 *   <li>{@code arrow} - the crafting arrow (vanilla #8B8B8B).</li>
 *   <li>{@code accent} - the hover highlight and the selected hotbar slot (vanilla white).</li>
 *   <li>{@code text} - the menu title (vanilla #404040).</li>
 * </ul>
 * All are opaque RGB with alpha FF, and drawn opaque (no Background Opacity since 2026-10-12).
 * <p>
 * {@code bevelSlots}: true only for Default, whose slots keep vanilla's sunken bevel (its dark top / left line stands
 * out against the light gray panel). Every other preset outlines each slot on all four sides in {@code slotLight}: their
 * {@code slotDark} sits within a few levels of the panel and the slot fill, so the top and left of every slot had no
 * visible line (killer560, 2026-10-13: "make a full outline for the left side of my inventory slots and the top side").
 */
public record InventoryPalette(int border, int panel, int light, int dark, int slotFill, int slotDark, int slotLight,
                               int modelFill, int arrow, int accent, int text, boolean bevelSlots) {

    /** Vanilla's own colors. */
    public static final InventoryPalette DEFAULT = new InventoryPalette(0xFF000000, 0xFFC6C6C6, 0xFFFFFFFF, 0xFF555555,
            0xFF8B8B8B, 0xFF373737, 0xFFFFFFFF, 0xFF000000, 0xFF8B8B8B, 0xFFFFFFFF, 0xFF404040, true);

    /** The Custom preset: the panel and slot colors of the chosen theme / Slot Color, with the accent where vanilla
     *  has its light shades and a darker accent for its dark ones. */
    public static InventoryPalette custom(int panel, int slot, int accent) {
        int dark = scale(accent, 0.38f);
        return new InventoryPalette(0xFF000000, opaque(panel), opaque(accent), dark, opaque(slot), scale(slot, 0.45f),
                opaque(accent), 0xFF000000, opaque(accent), opaque(accent), opaque(accent), false);
    }

    /** The menu theme's look (Menu Theme setting, 2026-10-15), shaped like Mod Theme: the panel and card fills, the accent
     *  where vanilla has its light shades, the menu's text for titles so a light theme stays readable. */
    public static InventoryPalette fromMenu(com.killer560.hub.gui.OverlayPalette m) {
        int accent = opaque(m.accent());
        int fill = opaque(m.card());
        return new InventoryPalette(opaque(m.outline()), opaque(m.panel()), accent, scale(accent, 0.38f), fill,
                scale(fill, 0.45f), accent, opaque(m.well()), accent, accent, opaque(m.text()), false);
    }

    private static int opaque(int argb) {
        return 0xFF000000 | (argb & 0xFFFFFF);
    }

    private static int scale(int argb, float f) {
        int r = Math.round(((argb >> 16) & 0xFF) * f);
        int g = Math.round(((argb >> 8) & 0xFF) * f);
        int b = Math.round((argb & 0xFF) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
