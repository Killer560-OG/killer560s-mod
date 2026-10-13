package com.killer560.hub.gui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The mod menu's own settings (2026-10-12, Option A menu): the features starred as Favourites, in the order they were
 * starred. A feature is named by its tab class's simple name ({@code HealthAndManaBarsTab}), the same id the testing
 * build's Mark tested uses, so renaming a label never loses a star. Saved in {@code killer560smod-menu.json}; profiles
 * carry it like every other setting file.
 */
public final class MenuConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-menu.json");

    private static MenuConfig instance;

    private final Set<String> favourites = new LinkedHashSet<>();
    /** The colour preset the menu draws in (killer560, 2026-10-12): Amber by default. */
    private MenuTheme theme = MenuTheme.AMBER;
    /** The preset the Custom colours were started from (what Reset puts back). */
    private MenuTheme customBase = MenuTheme.AMBER;
    /** Custom's five colours, in {@link MenuTheme.Slot} order. */
    private final int[] custom = MenuTheme.AMBER.base();
    /** Bumped on every theme change, so the drawing side knows to re-derive its palette. */
    private static int version;

    private MenuConfig() {
    }

    public static MenuConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        MenuConfig cfg = new MenuConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                if (obj.has("theme")) {
                    cfg.theme = MenuTheme.parse(obj.get("theme").getAsString());
                }
                if (obj.has("customBase")) {
                    MenuTheme b = MenuTheme.parse(obj.get("customBase").getAsString());
                    cfg.customBase = b == MenuTheme.CUSTOM ? MenuTheme.AMBER : b;
                }
                System.arraycopy(cfg.customBase.base(), 0, cfg.custom, 0, cfg.custom.length);
                if (obj.has("custom") && obj.get("custom").isJsonObject()) {
                    JsonObject c = obj.getAsJsonObject("custom");
                    for (MenuTheme.Slot slot : MenuTheme.Slot.values()) {
                        String k = slot.name().toLowerCase(java.util.Locale.ROOT);
                        if (c.has(k)) {
                            cfg.custom[slot.ordinal()] = 0xFF000000 | (int) Long.parseLong(
                                    c.get(k).getAsString().replace("#", ""), 16);
                        }
                    }
                }
                if (obj.has("favourites") && obj.get("favourites").isJsonArray()) {
                    for (JsonElement e : obj.getAsJsonArray("favourites")) {
                        if (e.isJsonPrimitive() && !e.getAsString().isBlank()) {
                            cfg.favourites.add(e.getAsString().trim());
                        }
                    }
                }
            } catch (Exception ignored) {
                // A broken file reads as the defaults, like every other config.
            }
        }
        instance = cfg;
        version++;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            JsonArray arr = new JsonArray();
            for (String id : favourites) {
                arr.add(id);
            }
            obj.add("favourites", arr);
            obj.addProperty("theme", theme.name());
            obj.addProperty("customBase", customBase.name());
            JsonObject c = new JsonObject();
            for (MenuTheme.Slot slot : MenuTheme.Slot.values()) {
                c.addProperty(slot.name().toLowerCase(java.util.Locale.ROOT),
                        String.format("#%06X", custom[slot.ordinal()] & 0xFFFFFF));
            }
            obj.add("custom", c);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public static Path path() {
        return CONFIG_PATH;
    }

    /** Changes whenever the theme or a custom colour changes (or the file is re-read). */
    public static int version() {
        return version;
    }

    public MenuTheme getTheme() {
        return theme;
    }

    /** Picks a theme. Choosing Custom from a preset starts Custom from that preset's colours (killer560's
     *  "a Custom option starting from the active preset"); Custom keeps its own colours after that. */
    public void setTheme(MenuTheme t) {
        MenuTheme next = t == null ? MenuTheme.AMBER : t;
        if (next == MenuTheme.CUSTOM && theme != MenuTheme.CUSTOM) {
            startCustomFrom(theme);
        }
        theme = next;
        version++;
    }

    public MenuTheme getCustomBase() {
        return customBase;
    }

    /** The five colours the menu draws from right now (the preset's, or Custom's). */
    public int[] activeColours() {
        return theme == MenuTheme.CUSTOM ? custom.clone() : theme.base();
    }

    public int getCustomColour(MenuTheme.Slot slot) {
        return custom[slot.ordinal()];
    }

    /** Sets one Custom colour and switches the menu to Custom (started from the preset in use, if it was one). */
    public void setCustomColour(MenuTheme.Slot slot, int argb) {
        if (theme != MenuTheme.CUSTOM) {
            startCustomFrom(theme);
            theme = MenuTheme.CUSTOM;
        }
        custom[slot.ordinal()] = 0xFF000000 | (argb & 0xFFFFFF);
        version++;
    }

    /** Custom's colours back to the preset it was started from. */
    public void resetCustom() {
        System.arraycopy(customBase.base(), 0, custom, 0, custom.length);
        version++;
    }

    private void startCustomFrom(MenuTheme preset) {
        if (preset == MenuTheme.CUSTOM) {
            return;
        }
        customBase = preset;
        System.arraycopy(preset.base(), 0, custom, 0, custom.length);
    }

    /** The starred feature ids, oldest first. */
    public List<String> getFavourites() {
        return new ArrayList<>(favourites);
    }

    public void setFavourites(List<String> ids) {
        favourites.clear();
        if (ids != null) {
            for (String id : ids) {
                if (id != null && !id.isBlank()) {
                    favourites.add(id.trim());
                }
            }
        }
    }

    public boolean isFavourite(String id) {
        return favourites.contains(id);
    }

    public void setFavourite(String id, boolean on) {
        if (id == null || id.isBlank()) {
            return;
        }
        if (on) {
            favourites.add(id);
        } else {
            favourites.remove(id);
        }
    }
}
