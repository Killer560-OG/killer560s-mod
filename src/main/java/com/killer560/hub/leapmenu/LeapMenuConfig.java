package com.killer560.hub.leapmenu;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.dungeonclass.DungeonClass;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Leap Order settings: per class YOU are playing, which teammate goes in each of the 4 leap menu spots, plus the
 *  class last picked in the editor. (The old sort/display/assign/customize/GUI-scale settings were removed
 *  2026-09-15 with the class-first Leap Order redo.) */
public final class LeapMenuConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-leapmenu.json");

    private static LeapMenuConfig instance;

    /** Class name -&gt; the teammate name in each of the 4 spots (top-left, top-right, bottom-left, bottom-right);
     *  "" = spot left for auto-fill. */
    private final Map<String, List<String>> classOrders = new LinkedHashMap<>();
    private String lastEditedClass = null;

    private LeapMenuConfig() {
    }

    public static LeapMenuConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        LeapMenuConfig cfg = new LeapMenuConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject root = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                if (root.has("classOrders") && root.get("classOrders").isJsonObject()) {
                    JsonObject orders = root.getAsJsonObject("classOrders");
                    for (String key : orders.keySet()) {
                        try {
                            List<String> slots = new ArrayList<>();
                            for (var el : orders.getAsJsonArray(key)) {
                                slots.add(el.isJsonPrimitive() ? el.getAsString() : "");
                            }
                            cfg.classOrders.put(key, normalizeSlots(slots));
                        } catch (RuntimeException ignored) {
                        }
                    }
                }
                if (root.has("lastEditedClass") && root.get("lastEditedClass").isJsonPrimitive()) {
                    cfg.lastEditedClass = root.get("lastEditedClass").getAsString();
                }
            } catch (Exception ignored) {
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject root = new JsonObject();
            JsonObject orders = new JsonObject();
            classOrders.forEach((key, slots) -> {
                JsonArray a = new JsonArray();
                slots.forEach(a::add);
                orders.add(key, a);
            });
            root.add("classOrders", orders);
            if (lastEditedClass != null) {
                root.addProperty("lastEditedClass", lastEditedClass);
            }
            Files.writeString(CONFIG_PATH, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static List<String> normalizeSlots(List<String> slots) {
        List<String> out = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            out.add(i < slots.size() && slots.get(i) != null ? slots.get(i) : "");
        }
        return out;
    }

    /** @return the 4 saved spots for this class ("" = auto-fill); never null. */
    public List<String> getClassOrder(DungeonClass playing) {
        List<String> slots = playing == null ? null : classOrders.get(playing.name());
        return slots == null ? normalizeSlots(List.of()) : new ArrayList<>(slots);
    }

    public void setClassOrder(DungeonClass playing, List<String> slots) {
        if (playing != null) {
            classOrders.put(playing.name(), normalizeSlots(slots));
        }
    }

    public void clearClassOrder(DungeonClass playing) {
        if (playing != null) {
            classOrders.remove(playing.name());
        }
    }

    public DungeonClass getLastEditedClass() {
        return lastEditedClass == null ? null : DungeonClass.byName(lastEditedClass);
    }

    public void setLastEditedClass(DungeonClass playing) {
        this.lastEditedClass = playing == null ? null : playing.name();
    }

    /** A teammate's class from the dungeon tab list (read by the leap menu colours and the Live Map). */
    public DungeonClass getAssignedClass(String playerName) {
        return PartyTracker.classOf(playerName);
    }
}
