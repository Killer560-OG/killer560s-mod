package com.killer560.hub.secretwaypoints;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Secret Waypoints settings - see {@link SecretWaypointsFeature}. Ships disabled by
 *  default. */
public final class SecretWaypointsConfig {

    public enum Style { FILL, OUTLINE, FILL_OUTLINE }

    /** killer560 (change 62): "add an option for a full block waypoint vs a hitbox only waypoint."
     *  FULL_BLOCK is the 1x1x1 block the secret sits in (what this feature always drew); HITBOX is the real
     *  vanilla hitbox of the thing you are actually looking for (chest shape / dropped item / bat). */
    public enum BoxSize { FULL_BLOCK, HITBOX }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-secretwaypoints.json");

    public static final int MIN_RENDER_DISTANCE = 16;
    public static final int MAX_RENDER_DISTANCE = 256;

    private static SecretWaypointsConfig instance;

    private boolean enabled = false;
    private Style style = Style.FILL_OUTLINE;
    /** killer560 (change 62): "draw them through walls" - that is the whole point of a preloaded waypoint,
     *  so it ships on; the toggle exists for anyone who wants the old depth-tested boxes back. */
    private boolean throughWalls = true;
    private BoxSize boxSize = BoxSize.HITBOX;
    /** Blocks. Secrets further away than this are not built and not drawn (2026-09-20 FPS pass: this
     *  feature used to draw every secret of every identified room in the dungeon, every frame). */
    private int renderDistance = 64;
    // NoammAddons' DungeonWaypoints defaults (killer560, 2026-09-21: "I would like the colors by default to match
    // noamm's coloring style"): chest MAGENTA, item its favoriteColor (0,134,255), bat GREEN, essence BLACK, key RED.
    private int chestColor = 0xFFFF00FF;
    private int itemColor = 0xFF0086FF;
    private int witherColor = 0xFF000000;
    private int batColor = 0xFF00FF00;
    private int redstoneKeyColor = 0xFFFF0000;
    /** Draw the secret's name (Chest, Item, Bat, Wither Essence, Redstone Key) above its waypoint. */
    private boolean showNames = false;

    private SecretWaypointsConfig() {
    }

    public static SecretWaypointsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new SecretWaypointsConfig();
            SecretWaypointsFeature.invalidateCache();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            SecretWaypointsConfig cfg = new SecretWaypointsConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.style = ConfigJson.getEnum(obj, "style", Style.class, Style.FILL_OUTLINE);
            cfg.throughWalls = ConfigJson.getBool(obj, "throughWalls", true);
            cfg.boxSize = ConfigJson.getEnum(obj, "boxSize", BoxSize.class, BoxSize.HITBOX);
            cfg.showNames = ConfigJson.getBool(obj, "showNames", false);
            cfg.renderDistance = clampDistance(ConfigJson.getInt(obj, "renderDistance", 64));
            cfg.chestColor = ConfigJson.getInt(obj, "chestColor", cfg.chestColor);
            cfg.itemColor = ConfigJson.getInt(obj, "itemColor", cfg.itemColor);
            cfg.witherColor = ConfigJson.getInt(obj, "witherColor", cfg.witherColor);
            cfg.batColor = ConfigJson.getInt(obj, "batColor", cfg.batColor);
            cfg.redstoneKeyColor = ConfigJson.getInt(obj, "redstoneKeyColor", cfg.redstoneKeyColor);
            if (!obj.has("noammDefaultsV1")) {
                // One-time: older files saved the old colours and the Full Block size as plain values, so they are
                // moved onto the new defaults once (colours match NoammAddons, boxes sized to the object).
                SecretWaypointsConfig fresh = new SecretWaypointsConfig();
                cfg.chestColor = fresh.chestColor;
                cfg.itemColor = fresh.itemColor;
                cfg.witherColor = fresh.witherColor;
                cfg.batColor = fresh.batColor;
                cfg.redstoneKeyColor = fresh.redstoneKeyColor;
                cfg.boxSize = BoxSize.HITBOX;
            }
            instance = cfg;
        } catch (Exception e) {
            instance = new SecretWaypointsConfig();
        }
        SecretWaypointsFeature.invalidateCache();
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("style", style.name());
            obj.addProperty("throughWalls", throughWalls);
            obj.addProperty("boxSize", boxSize.name());
            obj.addProperty("renderDistance", renderDistance);
            obj.addProperty("showNames", showNames);
            obj.addProperty("noammDefaultsV1", true);
            obj.addProperty("chestColor", chestColor);
            obj.addProperty("itemColor", itemColor);
            obj.addProperty("witherColor", witherColor);
            obj.addProperty("batColor", batColor);
            obj.addProperty("redstoneKeyColor", redstoneKeyColor);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
        // Everything on this page feeds the cached waypoint snapshot; rebuild it on the next tick.
        SecretWaypointsFeature.invalidateCache();
    }

    private static int clampDistance(int value) {
        return Math.max(MIN_RENDER_DISTANCE, Math.min(MAX_RENDER_DISTANCE, value));
    }

    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Style getStyle() {
        return style;
    }

    public void setStyle(Style style) {
        this.style = style;
    }

    public boolean isThroughWalls() {
        return throughWalls;
    }

    public void setThroughWalls(boolean throughWalls) {
        this.throughWalls = throughWalls;
    }

    public BoxSize getBoxSize() {
        return boxSize;
    }

    public void setBoxSize(BoxSize boxSize) {
        this.boxSize = boxSize;
    }

    public int getRenderDistance() {
        return renderDistance;
    }

    public void setRenderDistance(int renderDistance) {
        this.renderDistance = clampDistance(renderDistance);
    }

    public boolean isShowNames() {
        return showNames;
    }

    public void setShowNames(boolean v) {
        showNames = v;
    }

    public int getChestColor() {
        return chestColor;
    }

    public int getItemColor() {
        return itemColor;
    }

    public int getWitherColor() {
        return witherColor;
    }

    public int getBatColor() {
        return batColor;
    }

    public int getRedstoneKeyColor() {
        return redstoneKeyColor;
    }
}
