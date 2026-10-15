package com.killer560.hub.gui;

/**
 * The Green menu theme's name and five base colours (killer560, 2026-10-15: "make a default viridian green style theme
 * and call it green for now, we will rename it in a bit"). The shown name is {@link #NAME} and nothing else: renaming the
 * theme is one edit here. Its saved id is the enum constant {@code MenuTheme.GREEN}, so a rename never breaks a saved
 * setting. Viridian is #40826D; the panel and card are deep green-teals, the accent is a brighter viridian that reads on
 * them, the text a mint-tinted off white.
 */
public final class GreenTheme {

    /** What the theme is called in every list and label. */
    public static final String NAME = "Green";

    public static final int ACCENT = 0xFF56C29E;
    public static final int PANEL = 0xFF0E1A17;
    public static final int CARD = 0xFF162622;
    public static final int TEXT = 0xFFE2F2EB;
    public static final int SWITCH_ON = 0xFF40826D;

    private GreenTheme() {
    }
}
