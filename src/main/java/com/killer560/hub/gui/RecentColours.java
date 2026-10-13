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
import java.util.List;

/**
 * The colour picker's "Recent" row (killer560, 2026-10-13): the last {@link #MAX} colours picked, copied or pasted in any
 * picker, newest first, so a colour moves between menus without the OS clipboard. Saved as {@code #RRGGBB} /
 * {@code #AARRGGBB} strings in {@code killer560smod-colours.json}.
 */
public final class RecentColours {

    public static final int MAX = 8;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-colours.json");

    private static RecentColours instance;

    private final List<Integer> recent = new ArrayList<>();

    private RecentColours() {
    }

    public static RecentColours getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        RecentColours cfg = new RecentColours();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                if (obj.has("recent") && obj.get("recent").isJsonArray()) {
                    for (JsonElement e : obj.getAsJsonArray("recent")) {
                        ColourHex.Parsed p = e.isJsonPrimitive() ? ColourHex.parse(e.getAsString()) : null;
                        if (p != null && cfg.recent.size() < MAX && !cfg.recent.contains(p.argb())) {
                            cfg.recent.add(p.argb());
                        }
                    }
                }
            } catch (Exception ignored) {
                cfg = new RecentColours();
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            JsonArray arr = new JsonArray();
            for (int c : recent) {
                arr.add(ColourHex.format(c));
            }
            obj.add("recent", arr);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** Newest first, at most {@link #MAX}. */
    public List<Integer> getRecent() {
        return List.copyOf(recent);
    }

    /** Replaces the row (newest first; duplicates and anything past {@link #MAX} dropped) and saves. */
    public void setRecent(List<Integer> colours) {
        recent.clear();
        for (Integer c : colours) {
            if (c != null && recent.size() < MAX && !recent.contains(c)) {
                recent.add(c);
            }
        }
        save();
    }

    /** Puts {@code argb} at the front (moving it if it is already there) and saves. */
    public void add(int argb) {
        recent.remove(Integer.valueOf(argb));
        recent.add(0, argb);
        while (recent.size() > MAX) {
            recent.remove(recent.size() - 1);
        }
        save();
    }

    public void clear() {
        recent.clear();
        save();
    }
}
