package com.killer560.hub.routes;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Persisted Waypoint Routes settings ({@code killer560smod-waypointroutes.json}). Routes themselves live in
 *  {@link RouteStore}'s own file. Ships disabled. */
public final class WaypointRoutesConfig {

    public static final int KEY_ADD = 0;
    public static final int KEY_REMOVE_LAST = 1;
    public static final int KEY_CLEAR = 2;
    public static final int KEY_NEXT = 3;
    public static final int KEY_PREVIOUS = 4;
    public static final String[] KEY_NAMES = {"Add Point", "Remove Last", "Clear Route", "Skip Point", "Previous Point"};
    private static final String[] KEY_JSON = {"addKey", "removeLastKey", "clearKey", "nextKey", "previousKey"};

    public static final int DEFAULT_TARGET_COLOR = 0xFF55FF55;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-waypointroutes.json");

    private static WaypointRoutesConfig instance;

    private boolean enabled = false;
    private final int[] keyCodes = {-1, -1, -1, -1, -1};
    private boolean lineToNext = true;
    private boolean routeLines = true;
    private boolean showNumbers = true;
    private boolean showDistance = true;
    private boolean startAtNearest = true;
    private float textScale = 1.0f;
    private float lineThickness = 2.0f;
    private int targetColor = DEFAULT_TARGET_COLOR;
    private String selectedRouteId = null;
    /** Area key ({@link SkyblockArea#key()}) -> active route id. */
    private final Map<String, String> activeByArea = new LinkedHashMap<>();

    private WaypointRoutesConfig() {
    }

    public static WaypointRoutesConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        WaypointRoutesConfig cfg = new WaypointRoutesConfig();
        instance = cfg;
        if (!Files.exists(CONFIG_PATH)) {
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            cfg.enabled = getBool(obj, "enabled", false);
            for (int i = 0; i < KEY_JSON.length; i++) {
                cfg.keyCodes[i] = obj.has(KEY_JSON[i]) ? obj.get(KEY_JSON[i]).getAsInt() : -1;
            }
            cfg.lineToNext = getBool(obj, "lineToNext", true);
            cfg.routeLines = getBool(obj, "routeLines", true);
            cfg.showNumbers = getBool(obj, "showNumbers", true);
            cfg.showDistance = getBool(obj, "showDistance", true);
            cfg.startAtNearest = getBool(obj, "startAtNearest", true);
            cfg.setTextScale(obj.has("textScale") ? obj.get("textScale").getAsFloat() : 1.0f);
            cfg.setLineThickness(obj.has("lineThickness") ? obj.get("lineThickness").getAsFloat() : 2.0f);
            cfg.targetColor = obj.has("targetColor") ? obj.get("targetColor").getAsInt() : DEFAULT_TARGET_COLOR;
            cfg.selectedRouteId = obj.has("selectedRouteId") ? obj.get("selectedRouteId").getAsString() : null;
            if (obj.has("activeByArea")) {
                for (Map.Entry<String, com.google.gson.JsonElement> e : obj.getAsJsonObject("activeByArea").entrySet()) {
                    cfg.activeByArea.put(e.getKey(), e.getValue().getAsString());
                }
            }
        } catch (Exception e) {
            instance = new WaypointRoutesConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            for (int i = 0; i < KEY_JSON.length; i++) {
                obj.addProperty(KEY_JSON[i], keyCodes[i]);
            }
            obj.addProperty("lineToNext", lineToNext);
            obj.addProperty("routeLines", routeLines);
            obj.addProperty("showNumbers", showNumbers);
            obj.addProperty("showDistance", showDistance);
            obj.addProperty("startAtNearest", startAtNearest);
            obj.addProperty("textScale", textScale);
            obj.addProperty("lineThickness", lineThickness);
            obj.addProperty("targetColor", targetColor);
            if (selectedRouteId != null) {
                obj.addProperty("selectedRouteId", selectedRouteId);
            }
            JsonObject active = new JsonObject();
            activeByArea.forEach(active::addProperty);
            obj.add("activeByArea", active);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static boolean getBool(JsonObject obj, String key, boolean def) {
        return obj.has(key) ? obj.get(key).getAsBoolean() : def;
    }

    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getKeyCode(int which) {
        return keyCodes[which];
    }

    public void setKeyCode(int which, int code) {
        keyCodes[which] = code;
    }

    public boolean isLineToNext() {
        return lineToNext;
    }

    public void setLineToNext(boolean lineToNext) {
        this.lineToNext = lineToNext;
    }

    public boolean isRouteLines() {
        return routeLines;
    }

    public void setRouteLines(boolean routeLines) {
        this.routeLines = routeLines;
    }

    public boolean isShowNumbers() {
        return showNumbers;
    }

    public void setShowNumbers(boolean showNumbers) {
        this.showNumbers = showNumbers;
    }

    public boolean isShowDistance() {
        return showDistance;
    }

    public void setShowDistance(boolean showDistance) {
        this.showDistance = showDistance;
    }

    public boolean isStartAtNearest() {
        return startAtNearest;
    }

    public void setStartAtNearest(boolean startAtNearest) {
        this.startAtNearest = startAtNearest;
    }

    public float getTextScale() {
        return textScale;
    }

    public void setTextScale(float textScale) {
        this.textScale = Math.max(0.5f, Math.min(3.0f, textScale));
    }

    public float getLineThickness() {
        return lineThickness;
    }

    public void setLineThickness(float lineThickness) {
        this.lineThickness = Math.max(1.0f, Math.min(6.0f, lineThickness));
    }

    public int getTargetColor() {
        return targetColor;
    }

    public void setTargetColor(int targetColor) {
        this.targetColor = targetColor;
    }

    public String getSelectedRouteId() {
        return selectedRouteId;
    }

    public void setSelectedRouteId(String selectedRouteId) {
        this.selectedRouteId = selectedRouteId;
    }

    public String getActiveRouteId(String areaKey) {
        return activeByArea.get(areaKey);
    }

    public void setActiveRouteId(String areaKey, String routeId) {
        if (routeId == null) {
            activeByArea.remove(areaKey);
        } else {
            activeByArea.put(areaKey, routeId);
        }
    }

    /** Drops every area binding pointing at a deleted route. */
    public void forgetRoute(String routeId) {
        activeByArea.values().removeIf(id -> id.equals(routeId));
        if (routeId.equals(selectedRouteId)) {
            selectedRouteId = null;
        }
    }

    public Map<String, String> getActiveByArea() {
        return activeByArea;
    }
}
