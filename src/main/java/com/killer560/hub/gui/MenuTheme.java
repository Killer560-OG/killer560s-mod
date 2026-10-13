package com.killer560.hub.gui;

/**
 * The mod menu's colour presets (killer560, 2026-10-12): Amber (the orange look, the default), Dark (neutral greys),
 * Light (light panels, dark text) and Custom (five colours of his own, started from a preset). Every colour the menu
 * draws is derived from five base colours - accent, panel background, card background, text and the on-switch - by
 * {@link MenuStyle#use}, so a custom set gets the same bevels, wells, shadows and hover shades as a preset; a panel
 * lighter than mid grey gets the light treatment (grey outlines, soft shadows, no text shadow).
 */
public enum MenuTheme {

    AMBER("Amber", 0xFFFF8A1F, 0xFF1C1712, 0xFF251E17, 0xFFE9E2D8, 0xFFFF8A1F),
    DARK("Dark", 0xFFC9CED8, 0xFF18181B, 0xFF242428, 0xFFE6E6EA, 0xFF8A9BB0),
    LIGHT("Light", 0xFF2F6DB0, 0xFFE4E1DC, 0xFFF8F6F3, 0xFF25221F, 0xFF2F6DB0),
    CUSTOM("Custom", 0, 0, 0, 0, 0);

    public final String label;
    final int accent;
    final int panel;
    final int card;
    final int text;
    final int switchOn;

    MenuTheme(String label, int accent, int panel, int card, int text, int switchOn) {
        this.label = label;
        this.accent = accent;
        this.panel = panel;
        this.card = card;
        this.text = text;
        this.switchOn = switchOn;
    }

    /** The five editable colours, in {@link Slot} order. */
    public int[] base() {
        return new int[]{accent, panel, card, text, switchOn};
    }

    /** One of the colours a Custom theme sets. */
    public enum Slot {
        ACCENT("Accent"),
        PANEL("Panel Background"),
        CARD("Card Background"),
        TEXT("Text"),
        SWITCH_ON("Switch On");

        public final String label;

        Slot(String label) {
            this.label = label;
        }
    }

    /** A saved name; anything unknown is Amber. */
    public static MenuTheme parse(String s) {
        if (s != null) {
            for (MenuTheme t : values()) {
                if (t.name().equalsIgnoreCase(s.trim())) {
                    return t;
                }
            }
        }
        return AMBER;
    }

    /** The presets only (Custom excluded). */
    public static MenuTheme[] presets() {
        return new MenuTheme[]{AMBER, DARK, LIGHT};
    }
}
