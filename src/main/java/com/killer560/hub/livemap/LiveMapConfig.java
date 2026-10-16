package com.killer560.hub.livemap;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;

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
            ModPaths.config("killer560smod-livemap.json");

    private static LiveMapConfig instance;

    // ---- HUD dungeon map ----
    private boolean enabled = false;
    private boolean showTeammates = true;
    /** Teammates' names by their markers on this map while he holds a Spirit Leap or Infinileap (killer560, 2026-10-13:
     *  "if i am holding my spirit leap i can choose if someones name on my map shows up or doesnt via a toggle"). */
    private boolean leapNames = false;
    private boolean classRecolorTeammates = true;
    /** killer560s-mod-relay task (2026-09-21): dim/mark a room or door PartyRoomIntel reported but this
     *  client has not scanned itself. Default ON, unlike most new settings - it only ever adds what a
     *  teammate's own mod legitimately shared, same as the merge itself. */
    private boolean markReportedRooms = true;
    /** killer560, 2026-10-07: "make an option to have people's symbols be by player head for the default ones. It
     *  should work on your own head as well." Both maps draw each player as their skin's face (with an orbiting
     *  heading tick) instead of the arrow; a player whose skin is not known falls back to the arrow. Default OFF.
     *  Saved under a NEW key: the old {@code playerHeads} option was removed on 2026-09-20, and an ancient config
     *  still carrying it must not switch this on by itself. */
    private boolean playerHeads = false;
    /** Pixels per 16-unit room. The map is 116 units square (6 rooms + 5 gaps), so the HUD map is
     *  {@code 116 * roomPx / 16} pixels wide - see {@link MapPainter}. Replaced the old uniform "cellSize". */
    private int roomPx = 16;
    /** 0 Off, 1 Checkmarks, 2 Secrets, 3 Room Name, 4 Room Name + Secrets - NoammAddons' Checkmark Style.
     *  ONE setting for both maps: the Dungeon Map HUD and the Interactive Map both read this, and both settings
     *  tabs' "Room Labels" buttons write it (2026-10-05; the Interactive Map had its own copy until then). */
    private int roomLabels = 1;
    private int peekKeyCode = -1;
    private float peekScale = 2.0f;

    // ---- shared map appearance (HUD + interactive map) ----
    // Defaults are the real dungeon map's own colours, decoded from Hypixel's MapColor bytes by NoammAddons'
    // RoomType.kt / DoorType.kt - see MapPainter. killer560, 2026-09-17: ours drew every room the same grey.
    private boolean colourByType = true;
    private float darkenUnopened = 0.4f;
    private int mapBackground = 0x99000000;
    private int mapBorderColor = 0xFFCC6600;
    /** killer560, 2026-10-15: "Change checkmarks sprites to custom checkmark sprite so by default if it is off it uses
     *  hypixels sprites. If it is on then it uses the one you created or they can make their own custom sprite." Off:
     *  Hypixel's own marks (read off the map item, see {@link MapMarks}). On: {@link #checkmarkSource}. */
    private boolean customCheckmarks = false;
    /** With Custom Checkmark Sprite on: 0 the mod's own sprites, 1 his own PNGs ({@link MapMarks}). */
    private int checkmarkSource = 0;

    // ---- the cheat build's Funny Map (killer560, 2026-10-15: "Funnymap the toggle should be in cheat version only and
    // that means it scans for rooms ... if on it should have show mimic room as a toggle and a recolor option for it
    // there. In the normal map it cannot scan for mimic") ----
    private boolean funnyMap = false;
    private boolean showMimicRoom = true;
    /** Blended over the mimic room's own colour at this colour's alpha; 0x33 = NoammAddons' 20% red. */
    private int mimicColor = 0x33FF0000;

    // ---- map themes (killer560, 2026-10-15, see MapThemes): Real Map, each menu preset, Match Menu Theme, Custom. The
    // room colours are not settings of their own any more; they are the active theme's palette. Only Custom's are his,
    // as named schemes edited in MapSchemeEditorScreen. ----
    private MapThemes.Theme mapTheme = MapThemes.Theme.REAL;
    private final java.util.List<MapThemes.Scheme> schemes = new java.util.ArrayList<>();
    private int activeScheme = 0;

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
    /** Whether the Interactive Map's side panel shows the full Controls list or just its one-line hint
     *  (2026-10-05: "See how cluttered that right side is, clean it up a bit"). Collapsed by default. */
    private boolean mapControlsExpanded = false;
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
    /** Client ticks from one etherwarp hop to the next (killer560, 2026-10-09: "add an amount of ticks to make the
     *  etherwarps as well. I want to be sure it is actually etherwarping every tick"). 1 = a hop every tick. */
    private int etherwarpTickGap = 1;
    public static final int MIN_ETHERWARP_TICK_GAP = 1;
    public static final int MAX_ETHERWARP_TICK_GAP = 10;
    /** killer560, 2026-09-27: "it shows a small circle at each etherwarp spot ... and a line from one spot to
     *  another. Make it a toggleable section." Off by default like every other new toggle. */
    private boolean showEtherwarpPath = false;
    /** killer560, 2026-09-29: "If I click a different room mid path then it goes doesn't have to be a double
     *  click same with dooring." - a press on another room (or door) while a path is already running cancels
     *  that path and re-plans to the new goal. Without it the press is swallowed: {@code ClearExecutor.etherPath}
     *  refuses a second search while one is in flight, and a path planned from a position you have since warped
     *  away from can never execute ({@code ClearNode.inside} wants you within 0.32 blocks of its first hop). */
    private boolean mapRetargetMidPath = true;
    /** Interactive Map trips planned ahead of any press (livemap/autoclear/TripCache), killer560 2026-10-15. */
    private boolean precomputeTrips = true;
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
                cfg.leapNames = ConfigJson.getBool(obj, "leapNames", false);
                cfg.classRecolorTeammates = ConfigJson.getBool(obj, "classRecolorTeammates", true);
                cfg.markReportedRooms = ConfigJson.getBool(obj, "markReportedRooms", true);
                cfg.playerHeads = ConfigJson.getBool(obj, "playerHeadMarkers", false);
                // Migration: the old uniform "cellSize" (4..16, default 8) was one grid step; a room is now two
                // of those, so an existing config keeps roughly the map size it had.
                cfg.setRoomPx(ConfigJson.getInt(obj, "roomPx", ConfigJson.getInt(obj, "cellSize", 8) * 2));
                cfg.setRoomLabels(ConfigJson.getInt(obj, "roomLabels", 1));
                cfg.peekKeyCode = ConfigJson.getInt(obj, "peekKeyCode", -1);
                cfg.setPeekScale(ConfigJson.getFloat(obj, "peekScale", 2f));

                cfg.colourByType = ConfigJson.getBool(obj, "colourByType", true);
                cfg.setDarkenUnopened(ConfigJson.getFloat(obj, "darkenUnopened", 0.4f));
                cfg.mapBackground = ConfigJson.getInt(obj, "mapBackground", 0x99000000);
                cfg.mapBorderColor = ConfigJson.getInt(obj, "mapBorderColor", 0xFFCC6600);
                // "checkmarkSprites" (sprites vs font glyphs) is gone: the marks are Hypixel's unless he picks his own.
                cfg.customCheckmarks = ConfigJson.getBool(obj, "customCheckmarks", false);
                cfg.setCheckmarkSource(ConfigJson.getInt(obj, "checkmarkSource", 0));

                cfg.funnyMap = ConfigJson.getBool(obj, "funnyMap", false);
                cfg.showMimicRoom = ConfigJson.getBool(obj, "showMimicRoom", true);
                cfg.mimicColor = ConfigJson.getInt(obj, "mimicColor", 0x33FF0000);

                loadThemes(cfg, obj);

                cfg.interactiveMapEnabled = ConfigJson.getBool(obj, "interactiveMapEnabled", false);
                cfg.openKeyCode = com.killer560.hub.util.KeyUtil.sanitizeBind(ConfigJson.getInt(obj, "openKeyCode", -1));
                cfg.closeOnRepress = ConfigJson.getBool(obj, "closeOnRepress", false);
                cfg.openFromHudClick = ConfigJson.getBool(obj, "openFromHudClick", false);
                cfg.setMapScale(ConfigJson.getFloat(obj, "mapScale", 5f));
                cfg.setFontScale(ConfigJson.getFloat(obj, "fontScale", 1f));
                cfg.textShadow = ConfigJson.getBool(obj, "textShadow", false);
                // "mapRoomLabels" (the Interactive Map's own copy of Room Labels) is gone: killer560 (2026-10-05)
                // "have the map have the same settings for room name having the secrets ... as the regular map,
                // they should be the same and synced". Both maps read roomLabels; an old key is simply ignored.
                cfg.mapControlsExpanded = ConfigJson.getBool(obj, "mapControlsExpanded", false);
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
                cfg.setEtherwarpTickGap(ConfigJson.getInt(obj, "etherwarpTickGap", 1));
                cfg.showEtherwarpPath = ConfigJson.getBool(obj, "showEtherwarpPath", false);
                cfg.mapRetargetMidPath = ConfigJson.getBool(obj, "mapRetargetMidPath", true);
                cfg.precomputeTrips = ConfigJson.getBool(obj, "precomputeTrips", true);
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
        migrateRoomPx(cfg);
    }

    /**
     * killer560, 2026-10-15: "dont have a room size slider since they scroll to adjust it" - the Room Size slider is
     * gone and the map is sized only by the HUD editor's scroll (its own HUD scale). A config still carrying a Room
     * Size other than 16 has that folded into the map's HUD scale once, so the map stays the size he had it, and Room
     * Size goes back to 16 where nothing can change it.
     */
    private static void migrateRoomPx(LiveMapConfig cfg) {
        if (cfg.roomPx == 16) {
            return;
        }
        try {
            com.killer560.hub.hud.HudConfig hud = com.killer560.hub.hud.HudConfig.getInstance();
            float scale = hud.getScale("live_map", 1f) * cfg.roomPx / 16f;
            hud.setScale("live_map", Math.max(0.25f, Math.min(6f, scale)));
            hud.save();
        } catch (RuntimeException ignored) {
            // HUD config unreadable: the map is simply drawn at Room Size 16
        }
        cfg.roomPx = 16;
        cfg.save();
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("showTeammates", showTeammates);
            obj.addProperty("leapNames", leapNames);
            obj.addProperty("classRecolorTeammates", classRecolorTeammates);
            obj.addProperty("markReportedRooms", markReportedRooms);
            obj.addProperty("playerHeadMarkers", playerHeads);
            obj.addProperty("roomPx", roomPx);
            obj.addProperty("roomLabels", roomLabels);
            obj.addProperty("peekKeyCode", peekKeyCode);
            obj.addProperty("peekScale", peekScale);

            obj.addProperty("colourByType", colourByType);
            obj.addProperty("darkenUnopened", darkenUnopened);
            obj.addProperty("mapBackground", mapBackground);
            obj.addProperty("mapBorderColor", mapBorderColor);
            obj.addProperty("customCheckmarks", customCheckmarks);
            obj.addProperty("checkmarkSource", checkmarkSource);
            obj.addProperty("funnyMap", funnyMap);
            obj.addProperty("showMimicRoom", showMimicRoom);
            obj.addProperty("mimicColor", mimicColor);

            obj.addProperty("mapThemeName", mapTheme.name());
            com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
            for (MapThemes.Scheme s : schemes) {
                JsonObject o = new JsonObject();
                o.addProperty("name", s.name);
                com.google.gson.JsonArray cols = new com.google.gson.JsonArray();
                for (int c : s.colors) {
                    cols.add(c);
                }
                o.add("colors", cols);
                arr.add(o);
            }
            obj.add("mapSchemes", arr);
            obj.addProperty("activeScheme", activeScheme);

            obj.addProperty("interactiveMapEnabled", interactiveMapEnabled);
            obj.addProperty("openKeyCode", openKeyCode);
            obj.addProperty("closeOnRepress", closeOnRepress);
            obj.addProperty("openFromHudClick", openFromHudClick);
            obj.addProperty("mapScale", mapScale);
            obj.addProperty("fontScale", fontScale);
            obj.addProperty("textShadow", textShadow);
            obj.addProperty("mapControlsExpanded", mapControlsExpanded);
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
            obj.addProperty("etherwarpTickGap", etherwarpTickGap);
            obj.addProperty("showEtherwarpPath", showEtherwarpPath);
            obj.addProperty("mapRetargetMidPath", mapRetargetMidPath);
            obj.addProperty("precomputeTrips", precomputeTrips);
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

    public boolean isLeapNames() {
        return leapNames;
    }

    public void setLeapNames(boolean v) {
        this.leapNames = v;
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

    public boolean isPlayerHeads() {
        return playerHeads;
    }

    public void setPlayerHeads(boolean playerHeads) {
        this.playerHeads = playerHeads;
    }

    public int getRoomPx() {
        return roomPx;
    }

    /** Load-only: Room Size is no longer a setting (see {@link #migrateRoomPx}), so nothing else may set it. */
    private void setRoomPx(int roomPx) {
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

    /** The palette the maps paint with right now: the active theme's, or the active Custom scheme's. Never changed by
     *  the caller. */
    public int[] palette() {
        if (mapTheme == MapThemes.Theme.CUSTOM) {
            MapThemes.Scheme s = activeSchemeOrNull();
            return s == null ? MapThemes.REAL : s.colors;
        }
        return MapThemes.preset(mapTheme);
    }

    public int getColorNormal() {
        return palette()[MapThemes.NORMAL];
    }

    public int getColorEntrance() {
        return palette()[MapThemes.ENTRANCE];
    }

    public int getColorPuzzle() {
        return palette()[MapThemes.PUZZLE];
    }

    public int getColorTrap() {
        return palette()[MapThemes.TRAP];
    }

    public int getColorMiniboss() {
        return palette()[MapThemes.MINIBOSS];
    }

    public int getColorFairy() {
        return palette()[MapThemes.FAIRY];
    }

    public int getColorBlood() {
        return palette()[MapThemes.BLOOD];
    }

    public int getColorRare() {
        return palette()[MapThemes.RARE];
    }

    public int getColorUnopened() {
        return palette()[MapThemes.UNOPENED];
    }

    public int getColorWitherDoor() {
        return palette()[MapThemes.WITHER_DOOR];
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

    public boolean isCustomCheckmarks() {
        return customCheckmarks;
    }

    public void setCustomCheckmarks(boolean v) {
        this.customCheckmarks = v;
    }

    public static final String[] CHECKMARK_SOURCE_NAMES = {"Mod Sprites", "Your Own"};

    public int getCheckmarkSource() {
        return checkmarkSource;
    }

    public void setCheckmarkSource(int v) {
        this.checkmarkSource = v < 0 || v >= CHECKMARK_SOURCE_NAMES.length ? 0 : v;
    }

    // ---------------------------------------------------------------- funny map (cheat)

    /** The setting as saved, whichever build reads it. */
    public boolean isFunnyMapRaw() {
        return funnyMap;
    }

    public void setFunnyMap(boolean v) {
        this.funnyMap = v;
    }

    /** Whether the Dungeon Map may draw what the world scan found beyond what the dungeon map item shows. Cheat build
     *  only: on the legit jar javac folds this to false. */
    public boolean isFunnyMap() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && funnyMap;
    }

    public boolean isShowMimicRoomRaw() {
        return showMimicRoom;
    }

    public void setShowMimicRoom(boolean v) {
        this.showMimicRoom = v;
    }

    /** The cheat build's mimic SCAN (a trapped chest found before anyone opened it): Funny Map and Show Mimic Room. */
    public boolean isMimicScan() {
        return isFunnyMap() && showMimicRoom;
    }

    /** The mimic room's tint. The legit jar has no setting for it and always paints NoammAddons' 20% red. */
    public int getMimicColor() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED ? mimicColor : 0x33FF0000;
    }

    public int getMimicColorRaw() {
        return mimicColor;
    }

    public void setMimicColor(int v) {
        this.mimicColor = v;
    }

    // ---------------------------------------------------------------- map themes

    public MapThemes.Theme getMapTheme() {
        return mapTheme;
    }

    /** Picks a theme. Custom with no scheme saved yet starts one from the palette on screen. Caller saves. */
    public void setMapTheme(MapThemes.Theme theme) {
        MapThemes.Theme next = theme == null ? MapThemes.Theme.REAL : theme;
        if (next == MapThemes.Theme.CUSTOM && schemes.isEmpty()) {
            schemes.add(new MapThemes.Scheme("Scheme 1", palette()));
            activeScheme = 0;
        }
        mapTheme = next;
    }

    /** Test and settings hook: the theme by its enum name ("REAL", "OCEAN", "CUSTOM"...). */
    public void setMapThemeName(String name) {
        setMapTheme(MapThemes.Theme.parse(name));
    }

    public java.util.List<MapThemes.Scheme> getSchemes() {
        return java.util.Collections.unmodifiableList(schemes);
    }

    public int getActiveScheme() {
        return schemes.isEmpty() ? -1 : Math.max(0, Math.min(activeScheme, schemes.size() - 1));
    }

    public MapThemes.Scheme activeSchemeOrNull() {
        int i = getActiveScheme();
        return i < 0 ? null : schemes.get(i);
    }

    /** Makes scheme {@code i} the one Custom paints with, and switches to Custom. Caller saves. */
    public void useScheme(int i) {
        if (i >= 0 && i < schemes.size()) {
            activeScheme = i;
            mapTheme = MapThemes.Theme.CUSTOM;
        }
    }

    /** "New Custom Scheme": a new scheme starting from the palette on screen, made active (Custom). Caller saves.
     *  @return its index */
    public int newScheme() {
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (MapThemes.Scheme s : schemes) {
            taken.add(s.name);
        }
        int n = schemes.size() + 1;
        while (taken.contains("Scheme " + n)) {
            n++;
        }
        schemes.add(new MapThemes.Scheme("Scheme " + n, palette()));
        activeScheme = schemes.size() - 1;
        mapTheme = MapThemes.Theme.CUSTOM;
        return activeScheme;
    }

    public void renameScheme(int i, String name) {
        if (i >= 0 && i < schemes.size()) {
            String n = MapThemes.cleanName(name);
            schemes.get(i).name = n.isEmpty() ? "Scheme " + (i + 1) : n;
        }
    }

    public void setSchemeColor(int i, int slot, int argb) {
        if (i >= 0 && i < schemes.size() && slot >= 0 && slot < MapThemes.COUNT) {
            schemes.get(i).colors[slot] = argb | 0xFF000000;
        }
    }

    /** Scheme {@code i}'s colours back to Hypixel's own. Caller saves. */
    public void resetScheme(int i) {
        if (i >= 0 && i < schemes.size()) {
            System.arraycopy(MapThemes.REAL, 0, schemes.get(i).colors, 0, MapThemes.COUNT);
        }
    }

    /** Deletes scheme {@code i}; with none left the map goes back to Real Map. Caller saves. */
    public void deleteScheme(int i) {
        if (i < 0 || i >= schemes.size()) {
            return;
        }
        schemes.remove(i);
        if (activeScheme >= i && activeScheme > 0) {
            activeScheme--;
        }
        if (schemes.isEmpty()) {
            activeScheme = 0;
            if (mapTheme == MapThemes.Theme.CUSTOM) {
                mapTheme = MapThemes.Theme.REAL;
            }
        }
    }

    /**
     * Reads the theme and the saved schemes. A config from before 2026-10-15 has {@code mapTheme} 0 Real, 1 Amber or
     * 2 Custom, the live colours as {@code colorNormal}... and the old Custom slot as {@code customColorNormal}...: a
     * file whose theme was Custom, or whose live colours were hand-edited away from both stock presets, keeps them as
     * "Scheme 1" (and a different saved Custom slot as "Saved Custom"), so nothing he picked is lost.
     */
    private static void loadThemes(LiveMapConfig cfg, JsonObject obj) {
        if (obj.has("mapSchemes") && obj.get("mapSchemes").isJsonArray()) {
            for (com.google.gson.JsonElement e : obj.getAsJsonArray("mapSchemes")) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject o = e.getAsJsonObject();
                int[] cols = MapThemes.real();
                if (o.has("colors") && o.get("colors").isJsonArray()) {
                    com.google.gson.JsonArray a = o.getAsJsonArray("colors");
                    for (int k = 0; k < Math.min(a.size(), MapThemes.COUNT); k++) {
                        try {
                            cols[k] = a.get(k).getAsInt();
                        } catch (RuntimeException ignored) {
                            // a hand-edited entry that is not a number keeps Hypixel's colour
                        }
                    }
                }
                String name = o.has("name") && o.get("name").isJsonPrimitive() ? o.get("name").getAsString() : "";
                name = MapThemes.cleanName(name);
                cfg.schemes.add(new MapThemes.Scheme(name.isEmpty() ? "Scheme " + (cfg.schemes.size() + 1) : name, cols));
            }
            cfg.activeScheme = ConfigJson.getInt(obj, "activeScheme", 0);
            cfg.mapTheme = MapThemes.Theme.parse(ConfigJson.getString(obj, "mapThemeName", "REAL"));
        } else {
            String[] keys = {"Normal", "Entrance", "Puzzle", "Trap", "Miniboss", "Fairy", "Blood", "Rare", "Unopened",
                    "WitherDoor"};
            int old = ConfigJson.getInt(obj, "mapTheme", 0);
            int[] live = MapThemes.real();
            int[] custom = MapThemes.real();
            boolean anyCustom = false;
            for (int k = 0; k < keys.length; k++) {
                live[k] = ConfigJson.getInt(obj, "color" + keys[k], live[k]);
                custom[k] = ConfigJson.getInt(obj, "customColor" + keys[k], custom[k]);
                anyCustom |= custom[k] != MapThemes.REAL[k];
            }
            boolean liveIsStock = java.util.Arrays.equals(live, MapThemes.REAL)
                    || java.util.Arrays.equals(live, MapThemes.AMBER);
            if (old == 2 || !liveIsStock) {
                cfg.schemes.add(new MapThemes.Scheme("Scheme 1", live));
                cfg.mapTheme = MapThemes.Theme.CUSTOM;
            } else {
                cfg.mapTheme = old == 1 ? MapThemes.Theme.AMBER : MapThemes.Theme.REAL;
            }
            if (anyCustom && !java.util.Arrays.equals(custom, live)) {
                cfg.schemes.add(new MapThemes.Scheme("Saved Custom", custom));
            }
        }
        if (cfg.mapTheme == MapThemes.Theme.CUSTOM && cfg.schemes.isEmpty()) {
            cfg.mapTheme = MapThemes.Theme.REAL;
        }
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

    public boolean isMapControlsExpanded() {
        return mapControlsExpanded;
    }

    public void setMapControlsExpanded(boolean v) {
        this.mapControlsExpanded = v;
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

    public int getEtherwarpTickGap() {
        return etherwarpTickGap;
    }

    public void setEtherwarpTickGap(int v) {
        this.etherwarpTickGap = Math.max(MIN_ETHERWARP_TICK_GAP, Math.min(MAX_ETHERWARP_TICK_GAP, v));
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

    public boolean isPrecomputeTrips() {
        return precomputeTrips;
    }

    public void setPrecomputeTrips(boolean v) {
        this.precomputeTrips = v;
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
