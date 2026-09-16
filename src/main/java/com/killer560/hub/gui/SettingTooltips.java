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
        // FolderTab draws its accordion rows as "▶ <name>" / "▼ <name>", so drop that prefix - otherwise a
        // category row's key is "▶ live map" and no description can ever match it (2026-09-16 tooltip sweep).
        // Prefix only, and never the whole label: Custom Scoreboard's reorder buttons ARE labelled "▲"/"▼" and
        // have their own descriptions, which a blanket strip would turn into the empty key.
        if (plain.length() > 1 && (plain.charAt(0) == '▶' || plain.charAt(0) == '▼')) {
            plain = plain.substring(1);
        }
        int colon = plain.indexOf(':');
        if (colon > 0) {
            plain = plain.substring(0, colon);
        }
        return plain.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Which sub-tab built each widget, so a label can be scoped to the feature it belongs to rather than
     * only to the top-level folder. Identity-keyed and rebuilt every time the screen rebuilds.
     * <p>
     * Added 2026-09-16 after killer560 hit the failure directly: Pack Disabler's "Mode" button was showing
     * Auto Routes' Legit/Obvious description. Both tabs live in the New folder, so both wanted the key
     * {@code "new/mode"} - and the second {@code d.put} simply overwrote the first, silently, for every
     * "Mode" button in the whole folder. Scoping only by top-level tab makes that collision inevitable
     * with 60+ sub-tabs sharing generic labels like Mode, Style, Delete and Color.
     */
    private static final java.util.Map<net.minecraft.client.gui.components.AbstractWidget, String> SCOPES =
            new java.util.IdentityHashMap<>();

    /** Called by {@code FolderTab} for every widget a sub-tab produced. First writer wins, so a nested
     *  folder's inner (more specific) sub-tab keeps the scope rather than the outer folder overwriting it. */
    public static void scope(net.minecraft.client.gui.components.AbstractWidget widget, String subTabName) {
        if (widget != null && subTabName != null) {
            SCOPES.putIfAbsent(widget, subTabName);
        }
    }

    /** Called by {@code ModScreen} before a rebuild - the widgets themselves are thrown away each time. */
    public static void clearScopes() {
        SCOPES.clear();
    }

    /** @return the description for a widget label shown in {@code tabName}, or null if there is none. */
    public static String describe(String tabName, String label) {
        return describe(tabName, null, label);
    }

    /**
     * Most specific wins: the sub-tab that actually built the widget, then the top-level folder, then the
     * bare label. So "Mode" inside Auto Routes can say something different from "Mode" inside Pack
     * Disabler without either needing a uniquely-worded button.
     */
    public static String describe(String tabName, net.minecraft.client.gui.components.AbstractWidget widget,
                                   String label) {
        String k = key(label);
        if (k.isEmpty()) {
            return null;
        }
        String subTab = widget == null ? null : SCOPES.get(widget);
        if (subTab != null) {
            String scoped = DESCRIPTIONS.get(subTab.trim().toLowerCase(Locale.ROOT) + "/" + k);
            if (scoped != null) {
                return scoped;
            }
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
