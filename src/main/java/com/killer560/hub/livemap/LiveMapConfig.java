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

/** Persisted Dungeon Map + Interactive Map settings - see {@link LiveMapFeature} and {@link InteractiveMapFeature}.
 *  Everything new ships disabled / unbound.
 *  <p>
 *  The whole Interactive Map is cheat-build only as of 2026-09-20 - killer560: "the interactive map is the one where
 *  I click on a room and it etherwarps me to that room. and it can also start my secret route by clicking on it
 *  again and whatnot. That is a cheat." Auto Blood Rush, which only runs from that screen, is already cheat-gated.
 *  The HUD Dungeon Map stays legit, and on the legit jar it paints only what the vanilla dungeon map item has
 *  revealed (see {@link MapPainter}).
 *  <p>
 *  killer560, 2026-09-27: "the entire portion of interactive map is the teleport pathing" - Teleport Pathing's old
 *  separate on/off is gone; {@link #isInteractiveMapEnabled()} is now the only gate its settings need. */
public final class LiveMapConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-livemap.json");

    private static LiveMapConfig instance;

    // ---- HUD dungeon map ----
    private boolean enabled = false;
    private boolean showTeammates = true;
    private boolean classRecolorTeammates = true;
    /** killer560s-mod-relay task (2026-09-21): dim/mark a room or door PartyRoomIntel reported but this
     *  client has not scanned itself. Default ON, unlike most new settings - it only ever adds what a
     *  teammate's own mod legitimately shared, same as the merge itself. */
    private boolean markReportedRooms = true;
    /** Pixels per 16-unit room. The map is 116 units square (6 rooms + 5 gaps), so the HUD map is
     *  {@code 116 * roomPx / 16} pixels wide - see {@link MapPainter}. Replaced the old uniform "cellSize". */
    private int roomPx = 16;
    /** 0 Off, 1 Checkmarks, 2 Secrets, 3 Room Name, 4 Room Name + Secrets - NoammAddons' Checkmark Style. */
    private int roomLabels = 1;
    private int peekKeyCode = -1;
    private float peekScale = 2.0f;

    // ---- shared map appearance (HUD + interactive map) ----
    // Defaults are the real dungeon map's own colours, decoded from Hypixel's MapColor bytes by NoammAddons'
    // RoomType.kt / DoorType.kt - see MapPainter. killer560, 2026-09-17: ours drew every room the same grey.
    private boolean colourByType = true;
    private int colorNormal = 0xFF724318;
    private int colorEntrance = 0xFF00FF00;
    private int colorPuzzle = 0xFFB24CD8;
    private int colorTrap = 0xFFD87F33;
    private int colorMiniboss = 0xFFE5E533;
    private int colorFairy = 0xFFF27FA5;
    private int colorBlood = 0xFFFF0000;
    private int colorRare = 0xFFB2B2B2;
    private int colorUnopened = 0xFF414141;
    private int colorWitherDoor = 0xFF101010;
    private float darkenUnopened = 0.4f;
    private int mapBackground = 0x99000000;
    private int mapBorderColor = 0xFFCC6600;
    private boolean checkmarkSprites = true;

    // ---- map themes (killer560, 2026-09-20: "map themes: switchable, including a custom one matching the mod's
    // amber look. the recolour feature should be able to save a theme") - Real/Amber overwrite the 10 colours above
    // with a fixed preset; Custom loads back whatever was last explicitly saved with saveCurrentAsCustomTheme(). ----
    private int mapTheme = THEME_REAL;
    private int customColorNormal = 0xFF724318;
    private int customColorEntrance = 0xFF00FF00;
    private int customColorPuzzle = 0xFFB24CD8;
    private int customColorTrap = 0xFFD87F33;
    private int customColorMiniboss = 0xFFE5E533;
    private int customColorFairy = 0xFFF27FA5;
    private int customColorBlood = 0xFFFF0000;
    private int customColorRare = 0xFFB2B2B2;
    private int customColorUnopened = 0xFF414141;
    private int customColorWitherDoor = 0xFF101010;

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
    private int mapRoomLabels = 3;
    private boolean classBorderColour = false;
    /** 0 Off, 1 Holding Leap, 2 Always. */
    private int playerNames = 0;
    private float iconScale = 1f;
    /** killer560, 2026-09-27: "for the extra info it needs to show things like bat, mimic, prince. Also it
     *  needs to tell me s+ secrets ..." - the Interactive Map legend's own readout of what
     *  {@code ScoreCalculatorFeature} already tracks for the run. Default ON like Mark Reported Rooms - it
     *  only ever adds information, never changes what's drawn. */
    private boolean showExtraInfo = true;

    // ---- Teleport pathing / automation (cheat) ----
    // killer560, 2026-09-27: "You do not need teleport pathing. The entire portion of interactive map is the
    // teleport pathing. Add into interactive map as a whole the settings under the pathing section of it." -
    // there is no separate on/off for this any more; whatever ran while "Teleport Pathing" was on now just
    // runs whenever Interactive Map itself (isInteractiveMapEnabled()) is on. The settings below moved under
    // the Interactive Map tab's own Automation section, unchanged otherwise.
    private int startKeyCode = -1;
    private int lockedDoorKeyCode = -1;
    /** killer560, 2026-09-29: "Make a third button bind for go to a room and secret it." - a separate bind that
     *  does what the double press does (path to the room's Auto Routes START node, then run its secrets) on a
     *  single press. Unbound by default so it cannot clash with a key he already uses. */
    private int goSecretKeyCode = -1;
    private boolean faceDoorOnArrival = false;
    /** killer560, 2026-09-27: "Keep the keep chunks loaded section on by default." */
    private boolean keepChunksLoaded = true;
    /** QUOI PathSettings defaults. */
    private float yawStep = 6f;
    private float pitchStep = 7f;
    private double hWeight = 6.7;
    private int timeoutMs = 670;
    /** killer560, 2026-09-27: "it shows a small circle at each etherwarp spot ... and a line from one spot to
     *  another. Make it a toggleable section." Off by default like every other new toggle. */
    private boolean showEtherwarpPath = false;
    /** killer560, 2026-09-29: "If I click a different room mid path then it goes doesn't have to be a double
     *  click same with dooring." - a press on another room (or door) while a path is already running cancels
     *  that path and re-plans to the new goal. Without it the press is swallowed: {@code ClearExecutor.etherPath}
     *  refuses a second search while one is in flight, and a path planned from a position you have since warped
     *  away from can never execute ({@code ClearNode.inside} wants you within 0.32 blocks of its first hop). */
    private boolean mapRetargetMidPath = true;
    /** killer560, 2026-09-29: "If I double click a room then it should auto pathfind to the start node to start
     *  secreting." - a second press on the same room inside {@link #mapDoublePressMs} goes to that room's Auto
     *  Routes START node instead of to the room's own standing spot. */
    private boolean mapDoublePressStartNode = true;
    /** How long after a map press a second press on the same room still counts as a double press. */
    private int mapDoublePressMs = 400;

    // ---- Auto Blood Rush (cheat) ----
    private boolean bloodRushEnabled = false;
    private int bloodRushKeyCode = -1;
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
                cfg.markReportedRooms = ConfigJson.getBool(obj, "markReportedRooms", true);
                // Migration: the old uniform "cellSize" (4..16, default 8) was one grid step; a room is now two
                // of those, so an existing config keeps roughly the map size it had.
                cfg.setRoomPx(ConfigJson.getInt(obj, "roomPx", ConfigJson.getInt(obj, "cellSize", 8) * 2));
                cfg.setRoomLabels(ConfigJson.getInt(obj, "roomLabels", 1));
                cfg.peekKeyCode = ConfigJson.getInt(obj, "peekKeyCode", -1);
                cfg.setPeekScale(ConfigJson.getFloat(obj, "peekScale", 2f));

                cfg.colourByType = ConfigJson.getBool(obj, "colourByType", true);
                cfg.colorNormal = ConfigJson.getInt(obj, "colorNormal", 0xFF724318);
                cfg.colorEntrance = ConfigJson.getInt(obj, "colorEntrance", 0xFF00FF00);
                cfg.colorPuzzle = ConfigJson.getInt(obj, "colorPuzzle", 0xFFB24CD8);
                cfg.colorTrap = ConfigJson.getInt(obj, "colorTrap", 0xFFD87F33);
                cfg.colorMiniboss = ConfigJson.getInt(obj, "colorMiniboss", 0xFFE5E533);
                cfg.colorFairy = ConfigJson.getInt(obj, "colorFairy", 0xFFF27FA5);
                cfg.colorBlood = ConfigJson.getInt(obj, "colorBlood", 0xFFFF0000);
                cfg.colorRare = ConfigJson.getInt(obj, "colorRare", 0xFFB2B2B2);
                cfg.colorUnopened = ConfigJson.getInt(obj, "colorUnopened", 0xFF414141);
                cfg.colorWitherDoor = ConfigJson.getInt(obj, "colorWitherDoor", 0xFF101010);
                cfg.setDarkenUnopened(ConfigJson.getFloat(obj, "darkenUnopened", 0.4f));
                cfg.mapBackground = ConfigJson.getInt(obj, "mapBackground", 0x99000000);
                cfg.mapBorderColor = ConfigJson.getInt(obj, "mapBorderColor", 0xFFCC6600);
                cfg.checkmarkSprites = ConfigJson.getBool(obj, "checkmarkSprites", true);

                cfg.mapTheme = ConfigJson.getInt(obj, "mapTheme", THEME_REAL);
                if (cfg.mapTheme < 0 || cfg.mapTheme >= THEME_NAMES.length) {
                    cfg.mapTheme = THEME_REAL;
                }
                cfg.customColorNormal = ConfigJson.getInt(obj, "customColorNormal", 0xFF724318);
                cfg.customColorEntrance = ConfigJson.getInt(obj, "customColorEntrance", 0xFF00FF00);
                cfg.customColorPuzzle = ConfigJson.getInt(obj, "customColorPuzzle", 0xFFB24CD8);
                cfg.customColorTrap = ConfigJson.getInt(obj, "customColorTrap", 0xFFD87F33);
                cfg.customColorMiniboss = ConfigJson.getInt(obj, "customColorMiniboss", 0xFFE5E533);
                cfg.customColorFairy = ConfigJson.getInt(obj, "customColorFairy", 0xFFF27FA5);
                cfg.customColorBlood = ConfigJson.getInt(obj, "customColorBlood", 0xFFFF0000);
                cfg.customColorRare = ConfigJson.getInt(obj, "customColorRare", 0xFFB2B2B2);
                cfg.customColorUnopened = ConfigJson.getInt(obj, "customColorUnopened", 0xFF414141);
                cfg.customColorWitherDoor = ConfigJson.getInt(obj, "customColorWitherDoor", 0xFF101010);

                cfg.interactiveMapEnabled = ConfigJson.getBool(obj, "interactiveMapEnabled", false);
                cfg.openKeyCode = com.killer560.hub.util.KeyUtil.sanitizeBind(ConfigJson.getInt(obj, "openKeyCode", -1));
                cfg.closeOnRepress = ConfigJson.getBool(obj, "closeOnRepress", false);
                cfg.openFromHudClick = ConfigJson.getBool(obj, "openFromHudClick", false);
                cfg.setMapScale(ConfigJson.getFloat(obj, "mapScale", 5f));
                cfg.setFontScale(ConfigJson.getFloat(obj, "fontScale", 1f));
                cfg.textShadow = ConfigJson.getBool(obj, "textShadow", false);
                cfg.setMapRoomLabels(ConfigJson.getInt(obj, "mapRoomLabels", 3));
                cfg.classBorderColour = ConfigJson.getBool(obj, "classBorderColour", false);
                cfg.setPlayerNames(ConfigJson.getInt(obj, "playerNames", 0));
                cfg.setIconScale(ConfigJson.getFloat(obj, "iconScale", 1f));
                cfg.showExtraInfo = ConfigJson.getBool(obj, "showExtraInfo", true);

                cfg.startKeyCode = com.killer560.hub.util.KeyUtil.sanitizeBind(ConfigJson.getInt(obj, "startKeyCode", -1));
                cfg.lockedDoorKeyCode = com.killer560.hub.util.KeyUtil.sanitizeBind(ConfigJson.getInt(obj, "lockedDoorKeyCode", -1));
                cfg.goSecretKeyCode = com.killer560.hub.util.KeyUtil.sanitizeBind(ConfigJson.getInt(obj, "goSecretKeyCode", -1));
                cfg.faceDoorOnArrival = ConfigJson.getBool(obj, "faceDoorOnArrival", false);
                // Migration: files saved before 2026-09-27 have an explicit "keepChunksLoaded" (usually false,
                // the old default) - honour it. A file with no key at all (fresh install) now ships true.
                cfg.keepChunksLoaded = ConfigJson.getBool(obj, "keepChunksLoaded", true);
                cfg.yawStep = clamp(ConfigJson.getFloat(obj, "yawStep", 6f), 2f, 10f);
                cfg.pitchStep = clamp(ConfigJson.getFloat(obj, "pitchStep", 7f), 2f, 10f);
                cfg.hWeight = Math.max(1.0, Math.min(15.0, ConfigJson.getDouble(obj, "hWeight", 6.7)));
                cfg.setTimeoutMs(ConfigJson.getInt(obj, "timeoutMs", 670));
                cfg.showEtherwarpPath = ConfigJson.getBool(obj, "showEtherwarpPath", false);
                cfg.mapRetargetMidPath = ConfigJson.getBool(obj, "mapRetargetMidPath", true);
                cfg.mapDoublePressStartNode = ConfigJson.getBool(obj, "mapDoublePressStartNode", true);
                cfg.setMapDoublePressMs(ConfigJson.getInt(obj, "mapDoublePressMs", 400));

                cfg.bloodRushEnabled = ConfigJson.getBool(obj, "bloodRushEnabled", false);
                cfg.bloodRushKeyCode = com.killer560.hub.util.KeyUtil.sanitizeBind(ConfigJson.getInt(obj, "bloodRushKeyCode", -1));
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
            obj.addProperty("markReportedRooms", markReportedRooms);
            obj.addProperty("roomPx", roomPx);
            obj.addProperty("roomLabels", roomLabels);
            obj.addProperty("peekKeyCode", peekKeyCode);
            obj.addProperty("peekScale", peekScale);

            obj.addProperty("colourByType", colourByType);
            obj.addProperty("colorNormal", colorNormal);
            obj.addProperty("colorEntrance", colorEntrance);
            obj.addProperty("colorPuzzle", colorPuzzle);
            obj.addProperty("colorTrap", colorTrap);
            obj.addProperty("colorMiniboss", colorMiniboss);
            obj.addProperty("colorFairy", colorFairy);
            obj.addProperty("colorBlood", colorBlood);
            obj.addProperty("colorRare", colorRare);
            obj.addProperty("colorUnopened", colorUnopened);
            obj.addProperty("colorWitherDoor", colorWitherDoor);
            obj.addProperty("darkenUnopened", darkenUnopened);
            obj.addProperty("mapBackground", mapBackground);
            obj.addProperty("mapBorderColor", mapBorderColor);
            obj.addProperty("checkmarkSprites", checkmarkSprites);

            obj.addProperty("mapTheme", mapTheme);
            obj.addProperty("customColorNormal", customColorNormal);
            obj.addProperty("customColorEntrance", customColorEntrance);
            obj.addProperty("customColorPuzzle", customColorPuzzle);
            obj.addProperty("customColorTrap", customColorTrap);
            obj.addProperty("customColorMiniboss", customColorMiniboss);
            obj.addProperty("customColorFairy", customColorFairy);
            obj.addProperty("customColorBlood", customColorBlood);
            obj.addProperty("customColorRare", customColorRare);
            obj.addProperty("customColorUnopened", customColorUnopened);
            obj.addProperty("customColorWitherDoor", customColorWitherDoor);

            obj.addProperty("interactiveMapEnabled", interactiveMapEnabled);
            obj.addProperty("openKeyCode", openKeyCode);
            obj.addProperty("closeOnRepress", closeOnRepress);
            obj.addProperty("openFromHudClick", openFromHudClick);
            obj.addProperty("mapScale", mapScale);
            obj.addProperty("fontScale", fontScale);
            obj.addProperty("textShadow", textShadow);
            obj.addProperty("mapRoomLabels", mapRoomLabels);
            obj.addProperty("classBorderColour", classBorderColour);
            obj.addProperty("playerNames", playerNames);
            obj.addProperty("iconScale", iconScale);
            obj.addProperty("showExtraInfo", showExtraInfo);

            obj.addProperty("startKeyCode", startKeyCode);
            obj.addProperty("lockedDoorKeyCode", lockedDoorKeyCode);
            obj.addProperty("goSecretKeyCode", goSecretKeyCode);
            obj.addProperty("faceDoorOnArrival", faceDoorOnArrival);
            obj.addProperty("keepChunksLoaded", keepChunksLoaded);
            obj.addProperty("yawStep", yawStep);
            obj.addProperty("pitchStep", pitchStep);
            obj.addProperty("hWeight", hWeight);
            obj.addProperty("timeoutMs", timeoutMs);
            obj.addProperty("showEtherwarpPath", showEtherwarpPath);
            obj.addProperty("mapRetargetMidPath", mapRetargetMidPath);
            obj.addProperty("mapDoublePressStartNode", mapDoublePressStartNode);
            obj.addProperty("mapDoublePressMs", mapDoublePressMs);

            obj.addProperty("bloodRushEnabled", bloodRushEnabled);
            obj.addProperty("bloodRushKeyCode", bloodRushKeyCode);
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

    public boolean isMarkReportedRooms() {
        return markReportedRooms;
    }

    public void setMarkReportedRooms(boolean markReportedRooms) {
        this.markReportedRooms = markReportedRooms;
    }

    public int getRoomPx() {
        return roomPx;
    }

    public void setRoomPx(int roomPx) {
        this.roomPx = Math.max(8, Math.min(40, roomPx));
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

    // ---------------------------------------------------------------- shared map appearance

    public boolean isColourByType() {
        return colourByType;
    }

    public void setColourByType(boolean v) {
        this.colourByType = v;
    }

    public int getColorNormal() {
        return colorNormal;
    }

    public void setColorNormal(int v) {
        this.colorNormal = v;
    }

    public int getColorEntrance() {
        return colorEntrance;
    }

    public void setColorEntrance(int v) {
        this.colorEntrance = v;
    }

    public int getColorPuzzle() {
        return colorPuzzle;
    }

    public void setColorPuzzle(int v) {
        this.colorPuzzle = v;
    }

    public int getColorTrap() {
        return colorTrap;
    }

    public void setColorTrap(int v) {
        this.colorTrap = v;
    }

    public int getColorMiniboss() {
        return colorMiniboss;
    }

    public void setColorMiniboss(int v) {
        this.colorMiniboss = v;
    }

    public int getColorFairy() {
        return colorFairy;
    }

    public void setColorFairy(int v) {
        this.colorFairy = v;
    }

    public int getColorBlood() {
        return colorBlood;
    }

    public void setColorBlood(int v) {
        this.colorBlood = v;
    }

    public int getColorRare() {
        return colorRare;
    }

    public void setColorRare(int v) {
        this.colorRare = v;
    }

    public int getColorUnopened() {
        return colorUnopened;
    }

    public void setColorUnopened(int v) {
        this.colorUnopened = v;
    }

    public int getColorWitherDoor() {
        return colorWitherDoor;
    }

    public void setColorWitherDoor(int v) {
        this.colorWitherDoor = v;
    }

    public float getDarkenUnopened() {
        return darkenUnopened;
    }

    public void setDarkenUnopened(float v) {
        this.darkenUnopened = clamp(v, 0f, 0.9f);
    }

    public int getMapBackground() {
        return mapBackground;
    }

    public void setMapBackground(int v) {
        this.mapBackground = v;
    }

    public int getMapBorderColor() {
        return mapBorderColor;
    }

    public void setMapBorderColor(int v) {
        this.mapBorderColor = v;
    }

    public boolean isCheckmarkSprites() {
        return checkmarkSprites;
    }

    public void setCheckmarkSprites(boolean v) {
        this.checkmarkSprites = v;
    }

    /** Puts every room/door colour back to the real dungeon map's own values. */
    public void resetMapColours() {
        colorNormal = 0xFF724318;
        colorEntrance = 0xFF00FF00;
        colorPuzzle = 0xFFB24CD8;
        colorTrap = 0xFFD87F33;
        colorMiniboss = 0xFFE5E533;
        colorFairy = 0xFFF27FA5;
        colorBlood = 0xFFFF0000;
        colorRare = 0xFFB2B2B2;
        colorUnopened = 0xFF414141;
        colorWitherDoor = 0xFF101010;
    }

    // ---------------------------------------------------------------- map themes

    public static final int THEME_REAL = 0;
    public static final int THEME_AMBER = 1;
    public static final int THEME_CUSTOM = 2;
    public static final String[] THEME_NAMES = {"Real Map", "Amber", "Custom"};

    public int getMapTheme() {
        return mapTheme;
    }

    /** Recolouring subheader "Map Theme" cycle - Real and Amber overwrite the 10 room/door colours with a fixed
     *  preset; Custom loads back whatever was last saved with {@link #saveCurrentAsCustomTheme()} (the real map's
     *  own colours the first time, before anything has been saved). Caller still calls {@link #save()}. */
    public void applyTheme(int theme) {
        mapTheme = theme < 0 || theme >= THEME_NAMES.length ? THEME_REAL : theme;
        switch (mapTheme) {
            case THEME_AMBER -> {
                // killer560, 2026-09-20: "a custom one matching the mod's amber look" - the mod's own accent/panel
                // colours (SettingsButtonWidget/ModScreen: #CC6600, #FFAA00, #0D0D0D...) instead of the real map's
                // rainbow of room-type colours. Blood stays reddish and trap stays warning-red so danger still reads
                // as danger; everything else is a shade of amber/brown/gold.
                colorNormal = 0xFF6B3A11;
                colorEntrance = 0xFFFFAA00;
                colorPuzzle = 0xFFCC6600;
                colorTrap = 0xFFFF5555;
                colorMiniboss = 0xFFFFDD88;
                colorFairy = 0xFFF2C9A0;
                colorBlood = 0xFF8B0000;
                colorRare = 0xFFB2872B;
                colorUnopened = 0xFF3D332B;
                colorWitherDoor = 0xFF1A1A1A;
            }
            case THEME_CUSTOM -> {
                colorNormal = customColorNormal;
                colorEntrance = customColorEntrance;
                colorPuzzle = customColorPuzzle;
                colorTrap = customColorTrap;
                colorMiniboss = customColorMiniboss;
                colorFairy = customColorFairy;
                colorBlood = customColorBlood;
                colorRare = customColorRare;
                colorUnopened = customColorUnopened;
                colorWitherDoor = customColorWitherDoor;
            }
            default -> resetMapColours(); // Real Map
        }
    }

    /** Recolouring subheader "Save Theme": snapshots the live palette into the Custom slot, so Real/Amber can be
     *  tried without losing hand-picked colours - killer560, 2026-09-20: "the recolour feature should be able to
     *  save a theme". */
    public void saveCurrentAsCustomTheme() {
        customColorNormal = colorNormal;
        customColorEntrance = colorEntrance;
        customColorPuzzle = colorPuzzle;
        customColorTrap = colorTrap;
        customColorMiniboss = colorMiniboss;
        customColorFairy = colorFairy;
        customColorBlood = colorBlood;
        customColorRare = colorRare;
        customColorUnopened = colorUnopened;
        customColorWitherDoor = colorWitherDoor;
        mapTheme = THEME_CUSTOM;
    }

    /** A single room/door swatch was hand-edited directly - the palette is no longer exactly the Real or Amber
     *  preset, so the theme cycle should say Custom (its saved colours are untouched until Save Theme is pressed). */
    public void markCustomTheme() {
        mapTheme = THEME_CUSTOM;
    }

    // ---------------------------------------------------------------- interactive map

    public boolean isInteractiveMapEnabled() {
        return interactiveMapEnabled && cheatGate();
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
        this.openKeyCode = com.killer560.hub.util.KeyUtil.sanitizeBind(v);
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

    public int getMapRoomLabels() {
        return mapRoomLabels;
    }

    public void setMapRoomLabels(int v) {
        this.mapRoomLabels = v < 0 || v >= ROOM_LABEL_NAMES.length ? 3 : v;
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

    public boolean isShowExtraInfo() {
        return showExtraInfo;
    }

    public void setShowExtraInfo(boolean v) {
        this.showExtraInfo = v;
    }

    // ---------------------------------------------------------------- teleport pathing / automation (cheat)
    // killer560: "the entire portion of interactive map is the teleport pathing" - no separate enabled flag;
    // every getter below is gated on Interactive Map's own cheatGate() instead of a second toggle.

    public int getStartKeyCode() {
        return startKeyCode;
    }

    public void setStartKeyCode(int v) {
        this.startKeyCode = com.killer560.hub.util.KeyUtil.sanitizeBind(v);
    }

    public int getLockedDoorKeyCode() {
        return lockedDoorKeyCode;
    }

    public void setLockedDoorKeyCode(int v) {
        this.lockedDoorKeyCode = com.killer560.hub.util.KeyUtil.sanitizeBind(v);
    }

    public int getGoSecretKeyCode() {
        return goSecretKeyCode;
    }

    public void setGoSecretKeyCode(int v) {
        this.goSecretKeyCode = com.killer560.hub.util.KeyUtil.sanitizeBind(v);
    }

    public boolean isFaceDoorOnArrival() {
        return faceDoorOnArrival;
    }

    public void setFaceDoorOnArrival(boolean v) {
        this.faceDoorOnArrival = v;
    }

    /** Read off the network thread by the chunk-forget mixin. */
    public boolean isKeepChunksLoaded() {
        return keepChunksLoaded && (interactiveMapEnabled || bloodRushEnabled) && cheatGate();
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

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int v) {
        this.timeoutMs = Math.max(200, Math.min(1000, v));
    }

    public boolean isShowEtherwarpPath() {
        return showEtherwarpPath && cheatGate();
    }

    public boolean isShowEtherwarpPathRaw() {
        return showEtherwarpPath;
    }

    public void setShowEtherwarpPath(boolean v) {
        this.showEtherwarpPath = v;
    }

    public boolean isMapRetargetMidPath() {
        return mapRetargetMidPath;
    }

    public void setMapRetargetMidPath(boolean v) {
        this.mapRetargetMidPath = v;
    }

    public boolean isMapDoublePressStartNode() {
        return mapDoublePressStartNode;
    }

    public void setMapDoublePressStartNode(boolean v) {
        this.mapDoublePressStartNode = v;
    }

    public int getMapDoublePressMs() {
        return mapDoublePressMs;
    }

    /** Clamped to the slider's own 150-1000 range, which contains the 400 default - a setter that clamps to a
     *  range its field's default falls outside makes the default unreachable once the setter has run (see the
     *  Breaker Aura cooldown bug, fixed 2026-09-27). */
    public void setMapDoublePressMs(int v) {
        this.mapDoublePressMs = Math.max(150, Math.min(1000, v));
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
        this.bloodRushKeyCode = com.killer560.hub.util.KeyUtil.sanitizeBind(v);
    }

    public int getBloodRushDoorTimeoutSec() {
        return bloodRushDoorTimeoutSec;
    }

    public void setBloodRushDoorTimeoutSec(int v) {
        this.bloodRushDoorTimeoutSec = Math.max(5, Math.min(60, v));
    }
}
