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

/** Persisted Leap Menu settings - sort mode, name-vs-class display, a custom GUI scale independent of
 *  Minecraft's own, which of the two assignment modes is active, the actual class-per-player
 *  assignments (also read by future teammate ESP/map-color features per killer560's own note that
 *  those don't exist yet but should read from the same source), and the freeform member ordering used
 *  by Customize mode. */
public final class LeapMenuConfig {

    public enum SortMode {
        PARTY_ORDER("Party Order"),
        ALPHABETICAL("Alphabetical"),
        BY_CLASS("By Class"),
        BY_DISTANCE("By Distance");

        public final String label;

        SortMode(String label) {
            this.label = label;
        }

        public SortMode next() {
            SortMode[] v = values();
            return v[(ordinal() + 1) % v.length];
        }
    }

    public enum DisplayMode {
        NAME("By Name"), CLASS("By Class");

        public final String label;

        DisplayMode(String label) {
            this.label = label;
        }

        public DisplayMode next() {
            DisplayMode[] v = values();
            return v[(ordinal() + 1) % v.length];
        }
    }

    public enum AssignMode {
        CLASS("Assign by Class"), CUSTOMIZE("Customize Order");

        public final String label;

        AssignMode(String label) {
            this.label = label;
        }

        public AssignMode next() {
            AssignMode[] v = values();
            return v[(ordinal() + 1) % v.length];
        }
    }

    public static final float MIN_SCALE = 0.5f;
    public static final float MAX_SCALE = 2.0f;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-leapmenu.json");

    private static LeapMenuConfig instance;

    private SortMode sortMode = SortMode.PARTY_ORDER;
    private DisplayMode displayMode = DisplayMode.NAME;
    private AssignMode assignMode = AssignMode.CLASS;
    private float guiScale = 1.0f;
    /** Player name (lowercase) -&gt; DungeonClass name, or absent for "no class assigned". */
    private final Map<String, String> classAssignments = new LinkedHashMap<>();
    /** Player names (lowercase) in the order Customize mode placed them; anyone not listed falls back
     *  to whatever order they show up in the party. */
    private final List<String> customOrder = new ArrayList<>();

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
                String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                cfg.sortMode = enumOr(root, "sortMode", SortMode.class, SortMode.PARTY_ORDER);
                cfg.displayMode = enumOr(root, "displayMode", DisplayMode.class, DisplayMode.NAME);
                cfg.assignMode = enumOr(root, "assignMode", AssignMode.class, AssignMode.CLASS);
                cfg.guiScale = root.has("guiScale") ? root.get("guiScale").getAsFloat() : 1.0f;
                if (root.has("classAssignments")) {
                    JsonObject map = root.getAsJsonObject("classAssignments");
                    for (String key : map.keySet()) {
                        cfg.classAssignments.put(key, map.get(key).getAsString());
                    }
                }
                if (root.has("customOrder")) {
                    JsonArray arr = root.getAsJsonArray("customOrder");
                    for (var el : arr) {
                        cfg.customOrder.add(el.getAsString());
                    }
                }
            } catch (Exception ignored) {
            }
        }
        instance = cfg;
    }

    private static <E extends Enum<E>> E enumOr(JsonObject obj, String key, Class<E> type, E fallback) {
        if (!obj.has(key)) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, obj.get(key).getAsString());
        } catch (Exception e) {
            return fallback;
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("sortMode", sortMode.name());
            root.addProperty("displayMode", displayMode.name());
            root.addProperty("assignMode", assignMode.name());
            root.addProperty("guiScale", guiScale);
            JsonObject map = new JsonObject();
            classAssignments.forEach(map::addProperty);
            root.add("classAssignments", map);
            JsonArray arr = new JsonArray();
            customOrder.forEach(arr::add);
            root.add("customOrder", arr);
            Files.writeString(CONFIG_PATH, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public SortMode getSortMode() {
        return sortMode;
    }

    public void setSortMode(SortMode sortMode) {
        this.sortMode = sortMode;
    }

    public DisplayMode getDisplayMode() {
        return displayMode;
    }

    public void setDisplayMode(DisplayMode displayMode) {
        this.displayMode = displayMode;
    }

    public AssignMode getAssignMode() {
        return assignMode;
    }

    public void setAssignMode(AssignMode assignMode) {
        this.assignMode = assignMode;
    }

    public float getGuiScale() {
        return guiScale;
    }

    public void setGuiScale(float guiScale) {
        this.guiScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, guiScale));
    }

    /** @return the class assigned to this player, or null if none - the single source of truth future
     *  teammate ESP/map-dot recoloring should read from once those features exist. */
    public DungeonClass getAssignedClass(String playerName) {
        String value = classAssignments.get(playerName.toLowerCase(java.util.Locale.US));
        return value == null ? null : DungeonClass.byName(value);
    }

    public void setAssignedClass(String playerName, DungeonClass dungeonClass) {
        String key = playerName.toLowerCase(java.util.Locale.US);
        if (dungeonClass == null) {
            classAssignments.remove(key);
        } else {
            classAssignments.put(key, dungeonClass.name());
        }
    }

    public List<String> getCustomOrder() {
        return customOrder;
    }

    public void moveInCustomOrder(List<String> fullNamesLowercase, String playerName, int delta) {
        // Ensure every currently-visible name has an entry in customOrder (appended at the end, in
        // whatever order they were already being shown) before reordering, so "put members wherever"
        // works even the very first time Customize mode is used this run.
        for (String name : fullNamesLowercase) {
            if (!customOrder.contains(name)) {
                customOrder.add(name);
            }
        }
        int index = customOrder.indexOf(playerName.toLowerCase(java.util.Locale.US));
        int target = index + delta;
        if (index < 0 || target < 0 || target >= customOrder.size()) {
            return;
        }
        String tmp = customOrder.get(index);
        customOrder.set(index, customOrder.get(target));
        customOrder.set(target, tmp);
    }
}
