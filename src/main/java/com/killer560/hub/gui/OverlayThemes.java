package com.killer560.hub.gui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The overlays that draw over the game with a look of their own, and the one place their Menu Theme is resolved
 * (killer560, 2026-10-15: "for other features in the mod like storage overlay and things like that they also need an option
 * for the new menu themes and have it default to the default one").
 * <p>
 * Each overlay has a setting stored in {@link ScreenThemes} under its id here: Default (its own look, exactly as before),
 * a menu preset, Custom, or any theme he saved ({@link ScreenThemes#overlayButton}; the list is read live, so a theme made
 * a second ago is in it). The overlay's drawing asks {@link #of} for its {@link OverlayPalette}: null means "draw as you
 * always did", anything else is the colours to use. Nothing else about an overlay changes.
 * <p>
 * {@link #ids} lists every overlay for the testkit, which walks them all.
 */
public final class OverlayThemes {

    private OverlayThemes() {
    }

    public static final String STORAGE_OVERLAY = "storage_overlay";
    public static final String INVENTORY_THEME = "inventory_theme";
    public static final String INVENTORY_HUD = "inventory_hud";
    public static final String CHEST_PROFIT = "chest_profit";
    public static final String LEAP_MENU = "leap_menu";
    public static final String ITEM_BROWSER = "item_browser";
    public static final String TERMINAL_SOLVER = "terminal_solver";
    public static final String START_BUTTONS = "start_buttons";

    /** id -> the feature's name, in the order the testkit and the docs list them. */
    private static final Map<String, String> NAMES = new LinkedHashMap<>();

    static {
        NAMES.put(STORAGE_OVERLAY, "Storage Overlay");
        NAMES.put(INVENTORY_THEME, "Inventory Theme");
        NAMES.put(INVENTORY_HUD, "Inventory HUD");
        NAMES.put(CHEST_PROFIT, "Croesus chest profit panel");
        NAMES.put(START_BUTTONS, "Croesus and Experiments start buttons");
        NAMES.put(LEAP_MENU, "Leap Menu");
        NAMES.put(ITEM_BROWSER, "Item Browser");
        NAMES.put(TERMINAL_SOLVER, "Terminal Solver panels");
    }

    /** Every overlay with a Menu Theme setting. */
    public static List<String> ids() {
        return new ArrayList<>(NAMES.keySet());
    }

    /** The feature an overlay id belongs to, for messages. */
    public static String name(String id) {
        return NAMES.getOrDefault(id, id);
    }

    private record Cached(int[] colours, OverlayPalette palette) {
    }

    private static final Map<String, Cached> CACHE = new HashMap<>();

    /**
     * The colours the overlay should draw in, or null when its Menu Theme is Default (draw the overlay's own look).
     * Re-derived whenever the choice, the menu's Custom colours or a saved theme changes.
     */
    public static synchronized OverlayPalette of(String id) {
        int[] c = ScreenThemes.overlayColours(id);
        if (c == null) {
            return null;
        }
        Cached hit = CACHE.get(id);
        if (hit != null && Arrays.equals(hit.colours, c)) {
            return hit.palette;
        }
        OverlayPalette p = MenuStyle.snapshot(c);
        CACHE.put(id, new Cached(c.clone(), p));
        return p;
    }

    private static int menuVersion = Integer.MIN_VALUE;
    private static OverlayPalette menuPalette;

    /**
     * The menu's own theme as a palette, for a HUD that simply follows the menu (no per-overlay choice): re-derived only
     * when {@link MenuConfig#version()} moves, so reading it every frame allocates nothing.
     */
    public static synchronized OverlayPalette menu() {
        int v = MenuConfig.version();
        if (menuPalette == null || v != menuVersion) {
            menuPalette = MenuStyle.snapshot(MenuConfig.getInstance().activeColours());
            menuVersion = v;
        }
        return menuPalette;
    }

    /** The Menu Theme button for {@code id}, placed like any settings row. */
    public static SettingsButtonWidget button(String id, int x, int y, int w, int h) {
        return ScreenThemes.overlayButton(id, x, y, w, h);
    }
}
