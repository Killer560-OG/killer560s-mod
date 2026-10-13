package com.killer560.hub.gui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ModPaths;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Each themed screen's own Theme setting (killer560's to-do, 2026-10-13: "have each theme option start on theme default
 * which copies it, then you can toggle through different selected themes for each menu"). A screen id maps to
 * {@link #DEFAULT} (follow the mod menu's theme, the default), a {@link MenuTheme} name (a preset, or Custom: the menu's
 * custom colours), or a screen's own older style ({@link #INVENTORY}: the Inventory Theme look the Bazaar and Auction
 * House had before). {@link MenuTheme#forScreen} resolves it. Saved in {@code killer560smod-screenthemes.json}.
 */
public final class ScreenThemeConfig {

    /** Follow the mod menu's theme. */
    public static final String DEFAULT = "DEFAULT";
    /** The Bazaar / Auction House look from before (Inventory Theme: Amber, Dark or Light). */
    public static final String INVENTORY = "INVENTORY";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-screenthemes.json");

    private static ScreenThemeConfig instance;

    private final Map<String, String> choices = new LinkedHashMap<>();

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
                        cfg.choices.put(e.getKey(), e.getValue().getAsString().trim().toUpperCase(Locale.ROOT));
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
            for (Map.Entry<String, String> e : choices.entrySet()) {
                obj.addProperty(e.getKey(), e.getValue());
            }
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public static Path path() {
        return CONFIG_PATH;
    }

    /** The screen's choice: {@link #DEFAULT}, {@link #INVENTORY} or a {@link MenuTheme} name. */
    public String get(String screenId) {
        String c = choices.get(screenId);
        return c == null || !choicesFor(screenId).contains(c) ? DEFAULT : c;
    }

    public void set(String screenId, String choice) {
        if (choice == null || DEFAULT.equals(choice)) {
            choices.remove(screenId);
        } else {
            choices.put(screenId, choice.toUpperCase(Locale.ROOT));
        }
    }

    /** Screens that had their own older style keep it as a choice. */
    public static boolean hasInventoryStyle(String screenId) {
        return "bazaar".equals(screenId) || "auction_house".equals(screenId);
    }

    /** Every choice for a screen, in cycling order: Theme Default, each preset, Custom, then any older style. */
    public static List<String> choicesFor(String screenId) {
        List<String> out = new ArrayList<>();
        out.add(DEFAULT);
        for (MenuTheme t : MenuTheme.values()) {
            out.add(t.name());
        }
        if (hasInventoryStyle(screenId)) {
            out.add(INVENTORY);
        }
        return out;
    }

    public static String label(String choice) {
        if (DEFAULT.equals(choice)) {
            return "Theme Default";
        }
        if (INVENTORY.equals(choice)) {
            return "Inventory Theme";
        }
        return MenuTheme.parse(choice).label;
    }

    /** The cycling "Screen Theme" row for a feature's tab (left click forward, right click back, saved at once). */
    public static SettingsButtonWidget button(String screenId, int x, int y, int w, int h) {
        return SettingsButtonWidget.cycling(text(screenId), (btn, dir) -> {
            ScreenThemeConfig cfg = getInstance();
            List<String> all = choicesFor(screenId);
            int i = all.indexOf(cfg.get(screenId));
            cfg.set(screenId, all.get(SettingsButtonWidget.step(Math.max(0, i), dir, all.size())));
            cfg.save();
            btn.setMessage(text(screenId));
        }).bounds(x, y, w, h).build();
    }

    private static Component text(String screenId) {
        return Component.literal("Screen Theme: " + label(getInstance().get(screenId)));
    }
}
