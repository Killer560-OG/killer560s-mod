package com.killer560.hub.livemap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Live Map + Interactive Map settings - see {@link LiveMapFeature} and {@link InteractiveMapFeature}.
 *  Everything new ships disabled / unbound. Teleport pathing and Auto Blood Rush are cheat-build only. */
public final class LiveMapConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-livemap.json");

    private static LiveMapConfig instance;

    // ---- HUD live map ----
    private boolean enabled = false;
    private boolean showTeammates = true;
    private boolean classRecolorTeammates = true;
    private int cellSize = 8;
    /** 0 Off, 1 Checkmarks, 2 Secrets, 3 Room Name, 4 Room Name + Secrets - NoammAddons' Checkmark Style. */
    private int roomLabels = 1;
    private int peekKeyCode = -1;
    private float peekScale = 2.0f;

    // ---- Interactive map (QUOI InteractiveMap visuals + NoammAddons icon options) ----
    private boolean interactiveMapEnabled = false;
    private int openKeyCode = -1;
    /** QUOI "Close on": false = Release, true = Repress. */
    private boolean closeOnRepress = false;
    private boolean openFromHudClick = false;
    /** QUOI "Map scale" 1..10 (default 5). */
    private float mapScale = 5f;
    /** QUOI "Font scale" 0.5..3. */
    private float fontScale = 1f;
    private boolean textShadow = false;
    /** QUOI "Highlight colour" (room the player is in), GREY at 50% alpha. */
    private int highlightColor = 0x80808080;
    private int mapRoomLabels = 3;
    private boolean playerHeads = false;
    private boolean classBorderColour = false;
    /** 0 Off, 1 Holding Leap, 2 Always. */
    private int playerNames = 0;
    private float iconScale = 1f;

    // ---- Teleport pathing (cheat) ----
    private boolean pathingEnabled = false;
    private int startKeyCode = -1;
    private int lockedDoorKeyCode = -1;
    private boolean faceDoorOnArrival = false;
    private boolean keepChunksLoaded = false;
    /** QUOI PathSettings defaults. */
    private float yawStep = 6f;
    private float pitchStep = 7f;
    private double hWeight = 6.7;
    private int threads = 6;
    private int timeoutMs = 670;

    // ---- Auto Blood Rush (cheat) ----
    private boolean bloodRushEnabled = false;
    private int bloodRushKeyCode = -1;
    private boolean bloodRushClickDoor = false;
    private int bloodRushDoorTimeoutSec = 15;

    private LiveMapConfig() {
    }

    public static LiveMapConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        LiveMapConfig cfg = new LiveMapConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
                cfg.showTeammates = ConfigJson.getBool(obj, "showTeammates", true);
                cfg.classRecolorTeammates = ConfigJson.getBool(obj, "classRecolorTeammates", true);
                cfg.setCellSize(ConfigJson.getInt(obj, "cellSize", 8));
                cfg.setRoomLabels(ConfigJson.getInt(obj, "roomLabels", 1));
                cfg.peekKeyCode = ConfigJson.getInt(obj, "peekKeyCode", -1);
                cfg.setPeekScale(ConfigJson.getFloat(obj, "peekScale", 2f));

                cfg.interactiveMapEnabled = ConfigJson.getBool(obj, "interactiveMapEnabled", false);
                cfg.openKeyCode = ConfigJson.getInt(obj, "openKeyCode", -1);
                cfg.closeOnRepress = ConfigJson.getBool(obj, "closeOnRepress", false);
                cfg.openFromHudClick = ConfigJson.getBool(obj, "openFromHudClick", false);
                cfg.setMapScale(ConfigJson.getFloat(obj, "mapScale", 5f));
                cfg.setFontScale(ConfigJson.getFloat(obj, "fontScale", 1f));
                cfg.textShadow = ConfigJson.getBool(obj, "textShadow", false);
                cfg.highlightColor = ConfigJson.getInt(obj, "highlightColor", 0x80808080);
                cfg.setMapRoomLabels(ConfigJson.getInt(obj, "mapRoomLabels", 3));
                cfg.playerHeads = ConfigJson.getBool(obj, "playerHeads", false);
                cfg.classBorderColour = ConfigJson.getBool(obj, "classBorderColour", false);
                cfg.setPlayerNames(ConfigJson.getInt(obj, "playerNames", 0));
                cfg.setIconScale(ConfigJson.getFloat(obj, "iconScale", 1f));

                cfg.pathingEnabled = ConfigJson.getBool(obj, "pathingEnabled", false);
                cfg.startKeyCode = ConfigJson.getInt(obj, "startKeyCode", -1);
                cfg.lockedDoorKeyCode = ConfigJson.getInt(obj, "lockedDoorKeyCode", -1);
                cfg.faceDoorOnArrival = ConfigJson.getBool(obj, "faceDoorOnArrival", false);
                cfg.keepChunksLoaded = ConfigJson.getBool(obj, "keepChunksLoaded", false);
                cfg.yawStep = clamp(ConfigJson.getFloat(obj, "yawStep", 6f), 2f, 10f);
                cfg.pitchStep = clamp(ConfigJson.getFloat(obj, "pitchStep", 7f), 2f, 10f);
                cfg.hWeight = Math.max(1.0, Math.min(15.0, ConfigJson.getDouble(obj, "hWeight", 6.7)));
                cfg.setThreads(ConfigJson.getInt(obj, "threads", 6));
                cfg.setTimeoutMs(ConfigJson.getInt(obj, "timeoutMs", 670));

                cfg.bloodRushEnabled = ConfigJson.getBool(obj, "bloodRushEnabled", false);
                cfg.bloodRushKeyCode = ConfigJson.getInt(obj, "bloodRushKeyCode", -1);
                cfg.bloodRushClickDoor = ConfigJson.getBool(obj, "bloodRushClickDoor", false);
                cfg.setBloodRushDoorTimeoutSec(ConfigJson.getInt(obj, "bloodRushDoorTimeoutSec", 15));
            } catch (Exception ignored) {
                // per-key readers above never throw; only an unreadable/non-object file lands here
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("showTeammates", showTeammates);
            obj.addProperty("classRecolorTeammates", classRecolorTeammates);
            obj.addProperty("cellSize", cellSize);
            obj.addProperty("roomLabels", roomLabels);
            obj.addProperty("peekKeyCode", peekKeyCode);
            obj.addProperty("peekScale", peekScale);

            obj.addProperty("interactiveMapEnabled", interactiveMapEnabled);
            obj.addProperty("openKeyCode", openKeyCode);
            obj.addProperty("closeOnRepress", closeOnRepress);
            obj.addProperty("openFromHudClick", openFromHudClick);
            obj.addProperty("mapScale", mapScale);
            obj.addProperty("fontScale", fontScale);
            obj.addProperty("textShadow", textShadow);
            obj.addProperty("highlightColor", highlightColor);
            obj.addProperty("mapRoomLabels", mapRoomLabels);
            obj.addProperty("playerHeads", playerHeads);
            obj.addProperty("classBorderColour", classBorderColour);
            obj.addProperty("playerNames", playerNames);
            obj.addProperty("iconScale", iconScale);

            obj.addProperty("pathingEnabled", pathingEnabled);
            obj.addProperty("startKeyCode", startKeyCode);
            obj.addProperty("lockedDoorKeyCode", lockedDoorKeyCode);
            obj.addProperty("faceDoorOnArrival", faceDoorOnArrival);
            obj.addProperty("keepChunksLoaded", keepChunksLoaded);
            obj.addProperty("yawStep", yawStep);
            obj.addProperty("pitchStep", pitchStep);
            obj.addProperty("hWeight", hWeight);
            obj.addProperty("threads", threads);
            obj.addProperty("timeoutMs", timeoutMs);

            obj.addProperty("bloodRushEnabled", bloodRushEnabled);
            obj.addProperty("bloodRushKeyCode", bloodRushKeyCode);
            obj.addProperty("bloodRushClickDoor", bloodRushClickDoor);
            obj.addProperty("bloodRushDoorTimeoutSec", bloodRushDoorTimeoutSec);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static boolean gate() {
        return com.killer560.hub.util.SkyblockGate.allows();
    }

    private static boolean cheatGate() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && com.killer560.hub.util.SkyblockGate.allows();
    }

    // ---------------------------------------------------------------- HUD live map

    public boolean isEnabled() {
        return enabled && gate();
    }

    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isShowTeammates() {
        return showTeammates;
    }

    public void setShowTeammates(boolean showTeammates) {
        this.showTeammates = showTeammates;
    }

    public boolean isClassRecolorTeammates() {
        return classRecolorTeammates;
    }

    public void setClassRecolorTeammates(boolean classRecolorTeammates) {
        this.classRecolorTeammates = classRecolorTeammates;
    }

    public int getCellSize() {
        return cellSize;
    }

    public void setCellSize(int cellSize) {
        this.cellSize = Math.max(4, Math.min(16, cellSize));
    }

    public static final String[] ROOM_LABEL_NAMES = {"Off", "Checkmarks", "Secrets", "Room Name", "Room Name + Secrets"};

    public int getRoomLabels() {
        return roomLabels;
    }

    public void setRoomLabels(int roomLabels) {
        this.roomLabels = roomLabels < 0 || roomLabels >= ROOM_LABEL_NAMES.length ? 1 : roomLabels;
    }

    public int getPeekKeyCode() {
        return peekKeyCode;
    }

    public void setPeekKeyCode(int peekKeyCode) {
        this.peekKeyCode = peekKeyCode;
    }

    public float getPeekScale() {
        return peekScale;
    }

    public void setPeekScale(float peekScale) {
        this.peekScale = clamp(peekScale, 1.25f, 4f);
    }

    // ---------------------------------------------------------------- interactive map

    public boolean isInteractiveMapEnabled() {
        return interactiveMapEnabled && gate();
    }

    public boolean isInteractiveMapEnabledRaw() {
        return interactiveMapEnabled;
    }

    public void setInteractiveMapEnabled(boolean v) {
        this.interactiveMapEnabled = v;
    }

    public int getOpenKeyCode() {
        return openKeyCode;
    }

    public void setOpenKeyCode(int v) {
        this.openKeyCode = v;
    }

    public boolean isCloseOnRepress() {
        return closeOnRepress;
    }

    public void setCloseOnRepress(boolean v) {
        this.closeOnRepress = v;
    }

    public boolean isOpenFromHudClick() {
        return openFromHudClick;
    }

    public void setOpenFromHudClick(boolean v) {
        this.openFromHudClick = v;
    }

    public float getMapScale() {
        return mapScale;
    }

    public void setMapScale(float v) {
        this.mapScale = clamp(v, 1f, 10f);
    }

    public float getFontScale() {
        return fontScale;
    }

    public void setFontScale(float v) {
        this.fontScale = clamp(v, 0.5f, 3f);
    }

    public boolean isTextShadow() {
        return textShadow;
    }

    public void setTextShadow(boolean v) {
        this.textShadow = v;
    }

    public int getHighlightColor() {
        return highlightColor;
    }

    public void setHighlightColor(int v) {
        this.highlightColor = v;
    }

    public int getMapRoomLabels() {
        return mapRoomLabels;
    }

    public void setMapRoomLabels(int v) {
        this.mapRoomLabels = v < 0 || v >= ROOM_LABEL_NAMES.length ? 3 : v;
    }

    public boolean isPlayerHeads() {
        return playerHeads;
    }

    public void setPlayerHeads(boolean v) {
        this.playerHeads = v;
    }

    public boolean isClassBorderColour() {
        return classBorderColour;
    }

    public void setClassBorderColour(boolean v) {
        this.classBorderColour = v;
    }

    public static final String[] PLAYER_NAME_MODES = {"Off", "Holding Leap", "Always"};

    public int getPlayerNames() {
        return playerNames;
    }

    public void setPlayerNames(int v) {
        this.playerNames = v < 0 || v >= PLAYER_NAME_MODES.length ? 0 : v;
    }

    public float getIconScale() {
        return iconScale;
    }

    public void setIconScale(float v) {
        this.iconScale = clamp(v, 0.5f, 3f);
    }

    // ---------------------------------------------------------------- teleport pathing (cheat)

    public boolean isPathingEnabled() {
        return pathingEnabled && cheatGate();
    }

    public boolean isPathingEnabledRaw() {
        return pathingEnabled;
    }

    public void setPathingEnabled(boolean v) {
        this.pathingEnabled = v;
    }

    public int getStartKeyCode() {
        return startKeyCode;
    }

    public void setStartKeyCode(int v) {
        this.startKeyCode = v;
    }

    public int getLockedDoorKeyCode() {
        return lockedDoorKeyCode;
    }

    public void setLockedDoorKeyCode(int v) {
        this.lockedDoorKeyCode = v;
    }

    public boolean isFaceDoorOnArrival() {
        return faceDoorOnArrival;
    }

    public void setFaceDoorOnArrival(boolean v) {
        this.faceDoorOnArrival = v;
    }

    /** Read off the network thread by the chunk-forget mixin. */
    public boolean isKeepChunksLoaded() {
        return keepChunksLoaded && (pathingEnabled || bloodRushEnabled) && cheatGate();
    }

    public boolean isKeepChunksLoadedRaw() {
        return keepChunksLoaded;
    }

    public void setKeepChunksLoaded(boolean v) {
        this.keepChunksLoaded = v;
    }

    public float getYawStep() {
        return yawStep;
    }

    public float getPitchStep() {
        return pitchStep;
    }

    public double getHWeight() {
        return hWeight;
    }

    public int getThreads() {
        return threads;
    }

    public void setThreads(int v) {
        this.threads = Math.max(1, Math.min(16, v));
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int v) {
        this.timeoutMs = Math.max(200, Math.min(1000, v));
    }

    // ---------------------------------------------------------------- auto blood rush (cheat)

    public boolean isBloodRushEnabled() {
        return bloodRushEnabled && cheatGate();
    }

    public boolean isBloodRushEnabledRaw() {
        return bloodRushEnabled;
    }

    public void setBloodRushEnabled(boolean v) {
        this.bloodRushEnabled = v;
    }

    public int getBloodRushKeyCode() {
        return bloodRushKeyCode;
    }

    public void setBloodRushKeyCode(int v) {
        this.bloodRushKeyCode = v;
    }

    public boolean isBloodRushClickDoor() {
        return bloodRushClickDoor;
    }

    public void setBloodRushClickDoor(boolean v) {
        this.bloodRushClickDoor = v;
    }

    public int getBloodRushDoorTimeoutSec() {
        return bloodRushDoorTimeoutSec;
    }

    public void setBloodRushDoorTimeoutSec(int v) {
        this.bloodRushDoorTimeoutSec = Math.max(5, Math.min(60, v));
    }
}
