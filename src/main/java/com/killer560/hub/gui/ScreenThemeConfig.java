package com.killer560.hub.gui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Each themed screen's own Theme choice (killer560's to-do, 2026-10-13: "have each theme option start on theme default
 * which copies it, then you can toggle through different selected themes for each menu"). A screen id maps to a
 * {@link MenuTheme}; no entry is "Theme Default", which follows the main menu's theme. {@link MenuTheme#CUSTOM} is the
 * menu's saved Custom colours. Read through {@link MenuTheme#forScreen}; saved in killer560smod-screenthemes.json.
 */
public final class ScreenThemeConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-screenthemes.json");

    private static ScreenThemeConfig instance;

    private final Map<String, MenuTheme> chosen = new LinkedHashMap<>();

    private ScreenThemeConfig() {
    }

    public static ScreenThemeConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        ScreenThemeConfig cfg = new ScreenThemeConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                    if (e.getValue().isJsonPrimitive()) {
                        String name = e.getValue().getAsString().trim();
                        for (MenuTheme t : MenuTheme.values()) {
                            if (t.name().equalsIgnoreCase(name)) {
                                cfg.chosen.put(e.getKey(), t);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                // A broken file reads as every screen on Theme Default.
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            chosen.forEach((id, t) -> obj.addProperty(id, t.name()));
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** The screen's own theme, or null for Theme Default. */
    public MenuTheme get(String screenId) {
        return chosen.get(screenId);
    }

    /** Sets a screen's theme; null puts it back on Theme Default. */
    public void set(String screenId, MenuTheme theme) {
        if (theme == null) {
            chosen.remove(screenId);
        } else {
            chosen.put(screenId, theme);
        }
    }

    /** Theme Default, then every theme in order, wrapping both ways: what a left (+1) / right (-1) click on the
     *  setting does. */
    public static MenuTheme step(MenuTheme current, int dir) {
        MenuTheme[] all = MenuTheme.values();
        int idx = current == null ? 0 : current.ordinal() + 1;
        idx = Math.floorMod(idx + dir, all.length + 1);
        return idx == 0 ? null : all[idx - 1];
    }

    /** "Theme: Theme Default" / "Theme: Light" - the label of a screen's Theme setting. */
    public static String label(String screenId) {
        MenuTheme t = getInstance().get(screenId);
        return "Theme: §6" + (t == null ? "Theme Default" : t.label);
    }

    /**
     * The Theme setting for a screen, as a cycling button: left click forward, right click back, saved at once. Put it
     * in that feature's settings tab.
     */
    public static SettingsButtonWidget button(String screenId, int x, int y, int w, int h) {
        return SettingsButtonWidget.cycling(net.minecraft.network.chat.Component.literal(label(screenId)), (b, dir) -> {
            ScreenThemeConfig cfg = getInstance();
            cfg.set(screenId, step(cfg.get(screenId), dir));
            cfg.save();
            b.setMessage(net.minecraft.network.chat.Component.literal(label(screenId)));
        }).bounds(x, y, w, h).build();
    }

    /**
     * Adds the Theme setting for {@code screenId} under everything already in {@code widgets} (one row below the lowest
     * widget), so no existing row moves. {@code width} is capped at 220.
     */
    public static void append(java.util.List<net.minecraft.client.gui.components.AbstractWidget> widgets, String screenId,
                              int x, int width, int fallbackY) {
        int y = fallbackY;
        for (net.minecraft.client.gui.components.AbstractWidget w : widgets) {
            y = Math.max(y, w.getY() + w.getHeight() + 6);
        }
        widgets.add(button(screenId, x, y, Math.min(220, width), 20));
    }
}
