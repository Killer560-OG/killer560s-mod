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
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public static Path path() {
        return CONFIG_PATH;
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
