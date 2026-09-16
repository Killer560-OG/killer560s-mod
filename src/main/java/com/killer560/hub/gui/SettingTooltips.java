package com.killer560.hub.gui;

import net.minecraft.ChatFormatting;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Hover descriptions for settings in the mod menu (2026-09-15, killer560: "if I hover a setting ... it'll show a
 * text box explaining what it does briefly").
 * <p>
 * Keyed by the widget's label with formatting stripped, cut at the first ':' (so "Depth Check: ON" and
 * "Depth Check: OFF" share one entry), trimmed and lower-cased. A tab-specific entry ("tab name/label") wins over
 * a plain one, for generic labels like "Enabled" that mean different things in different tabs.
 * Descriptions live in {@link SettingTooltipsData}.
 */
public final class SettingTooltips {

    private static final Map<String, String> DESCRIPTIONS = new HashMap<>();

    static {
        SettingTooltipsData.register(DESCRIPTIONS);
    }

    private SettingTooltips() {
    }

    public static String key(String label) {
        if (label == null) {
            return "";
        }
        String plain = ChatFormatting.stripFormatting(label);
        if (plain == null) {
            plain = label;
        }
        // FolderTab draws its accordion rows as "▶ <name>" / "▼ <name>", so strip the arrow - otherwise a
        // category row's key is "▶ live map" and no description can ever match it (2026-09-16 tooltip sweep).
        plain = plain.replace('▶', ' ').replace('▼', ' ');
        int colon = plain.indexOf(':');
        if (colon > 0) {
            plain = plain.substring(0, colon);
        }
        return plain.trim().toLowerCase(Locale.ROOT);
    }

    /** @return the description for a widget label shown in {@code tabName}, or null if there is none. */
    public static String describe(String tabName, String label) {
        String k = key(label);
        if (k.isEmpty()) {
            return null;
        }
        if (tabName != null) {
            String scoped = DESCRIPTIONS.get(tabName.trim().toLowerCase(Locale.ROOT) + "/" + k);
            if (scoped != null) {
                return scoped;
            }
        }
        return DESCRIPTIONS.get(k);
    }
}
