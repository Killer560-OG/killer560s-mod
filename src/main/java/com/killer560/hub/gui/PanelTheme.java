package com.killer560.hub.gui;

import java.util.Locale;

/**
 * The three looks the Storage Overlay and the Inventory Theme (screens and hotbar) can take - killer560, 2026-10-07:
 * "have the themes be light, dark and amber, where light and dark will need to be different to not really use much
 * orange at all, and amber is the current dark theme."
 * <p>
 * {@link #AMBER} is, value for value, what the Storage Overlay's old "Dark" mode and the Inventory Theme drew before
 * this existed, so a saved old dark setting migrates to it with no visible change. {@link #DARK} keeps Amber's dark
 * fills but swaps every orange/brown line and accent for neutral greys; {@link #LIGHT} is the old Storage Overlay
 * "Light" mode with its one orange (the hover border) replaced, plus a light Inventory Theme to match.
 */
public enum PanelTheme {

    //            storage overlay ............................................................................. inventory theme ........................
    //            panelBg     border      hover       active      text        cellBg      cellLine    viewport    btnHover    accentRgb   invPanel    invSlot     invAccent
    AMBER("Amber", 0xCC101010, 0xFF553311, 0xFFCC6600, 0xFFCC6600, 0xFFFFFFFF, 0xFF1E1E22, 0xFF37373C, 0xD0000000, 0xE0262626, 0xFF8C1A, 0xFF0D0D0D, 0xFF1A1A1A, 0xFFCC6600),
    DARK("Dark", 0xCC101010, 0xFF3A3A3E, 0xFFB4B4BA, 0xFFD2D2D8, 0xFFFFFFFF, 0xFF1E1E22, 0xFF37373C, 0xD0000000, 0xE0262626, 0xE6E6EC, 0xFF0D0D0D, 0xFF1A1A1A, 0xFF5E5E64),
    LIGHT("Light", 0xCCE8E8E8, 0xFFAAAAAA, 0xFF505050, 0xFF3C3C3C, 0xFF101010, 0xFFD8D8D8, 0xFF999999, 0xD0FFFFFF, 0xE0FFFFFF, 0x2A2A2A, 0xFFE4E4E4, 0xFFC8C8C8, 0xFF666666);

    public final String label;
    /** Storage Overlay: each storage panel's fill. */
    public final int panelBg;
    /** Storage Overlay: a panel's outline. */
    public final int border;
    /** Storage Overlay: a hovered side button's outline. */
    public final int hoverBorder;
    /** Storage Overlay: the outline (and label colour) of the page that is open right now. */
    public final int activeBorder;
    public final int text;
    public final int cellBg;
    public final int cellLine;
    /** Storage Overlay: the fill behind the whole grid. */
    public final int viewportBg;
    public final int buttonHoverBg;
    /** RGB (no alpha) of the Storage Item Search hit pulse. */
    public final int highlightRgb;
    /** Inventory Theme: the panel fill behind a menu / the hotbar (alpha comes from Background Opacity). */
    public final int invPanelBg;
    /** Inventory Theme: one slot's backdrop. */
    public final int invSlotBg;
    /** Inventory Theme: borders, grid lines, hover, selected hotbar slot and title text, unless a custom accent is set. */
    public final int invAccent;

    PanelTheme(String label, int panelBg, int border, int hoverBorder, int activeBorder, int text, int cellBg,
               int cellLine, int viewportBg, int buttonHoverBg, int highlightRgb, int invPanelBg, int invSlotBg,
               int invAccent) {
        this.label = label;
        this.panelBg = panelBg;
        this.border = border;
        this.hoverBorder = hoverBorder;
        this.activeBorder = activeBorder;
        this.text = text;
        this.cellBg = cellBg;
        this.cellLine = cellLine;
        this.viewportBg = viewportBg;
        this.buttonHoverBg = buttonHoverBg;
        this.highlightRgb = highlightRgb;
        this.invPanelBg = invPanelBg;
        this.invSlotBg = invSlotBg;
        this.invAccent = invAccent;
    }

    /** Amber, Dark, Light, then Amber again - what a click on the setting's button does. */
    public PanelTheme next() {
        PanelTheme[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    /** A saved name, case-insensitive; null or anything unknown gives {@code fallback}. Never throws. */
    public static PanelTheme parse(String name, PanelTheme fallback) {
        if (name == null) {
            return fallback;
        }
        String n = name.trim().toUpperCase(Locale.ROOT);
        for (PanelTheme t : values()) {
            if (t.name().equals(n)) {
                return t;
            }
        }
        return fallback;
    }
}
