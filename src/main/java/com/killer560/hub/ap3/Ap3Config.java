package com.killer560.hub.ap3;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.KeyUtil;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Persisted AP3 settings ({@code killer560smod-ap3-settings.json}). Cheat build only; ships disabled. Kept SEPARATE
 * from the chains files ({@link Ap3Store}) so sharing your chains never hands over your keybinds and colours, and
 * someone else's chains file never overwrites your settings. Which chains file is in use IS a setting
 * ({@link #getChainsFile()}), so it survives a restart.
 * <p>
 * Same split as {@code autoroutes/AutoRoutesConfig}: every getter that can make the feature act is gated on
 * {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} and {@link com.killer560.hub.util.SkyblockGate},
 * the {@code ...Raw()} getters are for tab labels. Per-key {@link ConfigJson} readers, defaults on any bad key.
 * Every keybind is stored under a stable id ({@link #KEYBIND_IDS}) AND exposed as a typed getter/setter pair, all
 * defaulting to unbound ({@link KeyUtil#NONE}).
 */
public final class Ap3Config {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-ap3-settings.json");

    /** Stable keybind ids, one per {@code /ap3} command - all default to unbound. The ids of renamed commands are
     *  kept (add_line = Add Align, add_axisline = Add Axis Align, add_leapdetector = Add Leap Counter, delete = the
     *  nearest-node delete) so a key bound before the 2026-09-20 rework still works. */
    public static final String KEY_ADD_ALIGN = "add_line";
    public static final String KEY_ADD_AXIS_ALIGN = "add_axisline";
    public static final String KEY_ADD_WALK = "add_walk";
    public static final String KEY_ADD_RUN = "add_run";
    public static final String KEY_ADD_LEAP = "add_leap";
    public static final String KEY_ADD_LEAP_COUNTER = "add_leapdetector";
    public static final String KEY_ADD_TERMINAL = "add_terminal";
    public static final String KEY_ADD_STOP = "add_stop";
    public static final String KEY_ADD_LOOK = "add_look";
    public static final String KEY_ADD_BOOM = "add_boom";
    public static final String KEY_ADD_STOPWATCH = "add_stopwatch";
    public static final String KEY_LIST = "list";
    public static final String KEY_UNDO = "undo";
    public static final String KEY_DELETE = "delete";
    public static final String KEY_CLEAR = "clear";
    public static final String KEY_RELOAD = "reload";
    public static final String KEY_START = "start";
    public static final String KEY_STOP = "stop";
    public static final String KEY_TEST_MODE = "testmode";
    /** Re-places the LAST node at your position/look (the key-shaped half of {@code /ap3 replace <n>}). */
    public static final String KEY_REPLACE_LAST = "replace_last";

    public static final List<String> KEYBIND_IDS = List.of(
            KEY_ADD_ALIGN, KEY_ADD_AXIS_ALIGN, KEY_ADD_WALK, KEY_ADD_RUN, KEY_ADD_LEAP, KEY_ADD_LEAP_COUNTER,
            KEY_ADD_TERMINAL, KEY_ADD_STOP, KEY_ADD_LOOK, KEY_ADD_BOOM, KEY_ADD_STOPWATCH, KEY_LIST, KEY_UNDO,
            KEY_DELETE, KEY_REPLACE_LAST, KEY_CLEAR, KEY_RELOAD, KEY_START, KEY_STOP, KEY_TEST_MODE);

    public static final float MIN_THICKNESS = 1f;
    public static final float MAX_THICKNESS = 8f;
    public static final float MIN_HEIGHT = 0.1f;
    public static final float MAX_HEIGHT = 1f;
    /** "Align Tolerance": how far from the point, per axis, an align may settle. 0.0005 = the coordinate reads the
     *  exact 3-decimal value (killer560: "down to 3 decimals of perfect"). AP3 lands there by movement input alone -
     *  it never writes position, Hypixel lags that back - solving vanilla's own step exactly ({@code Ap3AlignMath}). */
    public static final double MIN_ALIGN_TOLERANCE = 0.0001;
    public static final double MAX_ALIGN_TOLERANCE = 0.1;
    /** 0.001 = the worst case killer560 accepts ("if it can get to .001 as the worst it ever does ... good enough");
     *  the discrete planner typically lands far inside it. */
    public static final double DEFAULT_ALIGN_TOLERANCE = 0.001;
    public static final int MIN_ALIGN_TIMEOUT = 20;
    public static final int MAX_ALIGN_TIMEOUT = 400;
    public static final int MIN_MOVE_TIMEOUT = 20;
    public static final int MAX_MOVE_TIMEOUT = 600;
    public static final double MIN_LEAP_RADIUS = 2.0;
    public static final double MAX_LEAP_RADIUS = 12.0;
    /** Same bounds Posmsg's per-waypoint text scale / height use ({@code posmsg/PosmsgEntry}). */
    public static final float MIN_LABEL_SCALE = 0.25f;
    public static final float MAX_LABEL_SCALE = 4f;
    public static final float MIN_LABEL_HEIGHT = 0f;
    public static final float MAX_LABEL_HEIGHT = 5f;
    /** White: readable over every node colour, and the colour the labels always had before it was a setting. */
    public static final int DEFAULT_LABEL_COLOR = 0xFFFFFFFF;

    private static Ap3Config instance;

    private boolean enabled = false;
    private boolean chatFeedback = true;
    /**
     * killer560 (2026-09-21): "there should only be a 45 degree strafe toggle" - the old "45 degree Walk Angle"
     * (the 1.00 W+A speed) and "Server Strafe Angle" (the server-side yaw at the strafe angle) rolled into one.
     * A held walk ALWAYS locks the yaw the server receives to the walk now; this decides what that yaw is: ON =
     * walk direction +-45 with W and A/D held (a real sprinting diagonal strafe, 1.00 speed), OFF = the walk
     * direction exactly with W only (0.98). See {@code Ap3Executor#tickStrafe} / {@code #writeMove}. Loaded from
     * the two old keys when the new one is absent: either old one on = this one on.
     */
    private boolean strafe45 = false;
    /** DEV BUILDS ONLY ({@code BuildVariant.DEV_TOOLS}): time every align from box entry to fully aligned and say
     *  so in chat. Default ON because that is what he asked for; never shipped in a {@code -Prelease=true} jar. */
    private boolean alignTimerDev = true;
    /** The class filter new nodes go into ({@code /ap3 add ...} / the tab); null = the class-less chain. */
    private DungeonClass editClassFilter = null;
    /** The chains file in use, by file name inside {@link Ap3Store#directory()} - "Choose AP3 Config" (killer560,
     *  2026-09-21: "select any of the ap3's in my folder, and create new ones"). Always a plain {@code *.json} name
     *  that {@link Ap3Store#validateConfigName} accepts; anything else in the file falls back to the default. */
    private String chainsFile = Ap3Store.DEFAULT_CONFIG_NAME;
    /** The two collapsible sections on the tab (Colors, Keybinds) - closed by default, remembered across restarts. */
    private boolean colorsSectionOpen = false;
    private boolean keybindsSectionOpen = false;
    /** STOPWATCH nodes always print to chat; this also shows the running / last time on the HUD (default OFF). */
    private boolean stopwatchHud = false;
    private boolean uniformColor = false;
    /** The mod's own amber, so a uniform-coloured chain matches the menu. */
    public static final int DEFAULT_UNIFORM_COLOR = 0xFFFFA040;
    private int uniformColorArgb = DEFAULT_UNIFORM_COLOR;
    private int activeColorArgb = 0xFFFFFFFF;
    private final Map<Ap3Node.Type, Integer> nodeColors = new EnumMap<>(Ap3Node.Type.class);
    private float thickness = 3f;
    private float height = 0.1f;
    private boolean showLabels = true;
    /**
     * World labels (killer560, 2026-09-16: "for ap3 specifically have it label nodes that i make. For instance the
     * very first node is 1 the second is 2 and so on. It should be toggleable for color and if it shows"). The
     * number is ON by default because that is what he asked for; type and details default ON so a label looks
     * exactly as it did before these toggles existed ("#3 Line 4.0 x 1.0").
     */
    private boolean showNodeNumbers = true;
    private boolean showNodeType = true;
    private boolean showNodeDetails = true;
    /** "toggleable for color": ON = each label in its own node's colour (the number matches the box it sits on),
     *  OFF = every label in {@link #labelColorArgb}. ON is how labels were always drawn. */
    private boolean labelUseNodeColor = true;
    private int labelColorArgb = DEFAULT_LABEL_COLOR;
    /** Multiplier on the label size (1 = the size it has always been) and blocks lifted above the marker (0 =
     *  where it has always sat) - the same two knobs Posmsg's waypoints got. */
    private float labelScale = 1f;
    private float labelHeightOffset = 0f;
    /** Alignment (ALIGN / AXIS_ALIGN) is done within this many blocks of the target (see the constants). */
    private double alignTolerance = DEFAULT_ALIGN_TOLERANCE;
    private int alignTimeoutTicks = 100;
    /** WALK / RUN: no progress toward the end of the travel for this many ticks and the chain gives up. */
    private int moveTimeoutTicks = 100;
    /** LEAP_COUNTER: a teammate who teleports to within this many blocks of you counts as having leapt to you. */
    private double leapDetectRadius = 5.0;
    private final Map<String, Integer> keybinds = new LinkedHashMap<>();

    private Ap3Config() {
        for (Ap3Node.Type t : Ap3Node.Type.values()) {
            nodeColors.put(t, defaultNodeColor(t));
        }
        for (String id : KEYBIND_IDS) {
            keybinds.put(id, KeyUtil.NONE);
        }
    }

    /** Orange theme for the GUI-ish types, meaningful colours only where they carry meaning (STOP red, RUN green). */
    public static int defaultNodeColor(Ap3Node.Type type) {
        return switch (type) {
            case ALIGN -> 0xFFFFA040;
            case AXIS_ALIGN -> 0xFFCC6600;
            case WALK -> 0xFFFFFFFF;
            case RUN -> 0xFF55FF55;
            case LEAP -> 0xFF00FFFF;
            case LEAP_COUNTER -> 0xFF3B82F6;
            case TERMINAL -> 0xFFA855F7;
            case STOP -> 0xFFFF5555;
            case LOOK -> 0xFFFFFF00;
            case BOOM -> 0xFFFF8800;
            case STOPWATCH -> 0xFF9A8C80;
        };
    }

    /** The pre-rework name a type's colour was saved under (2026-09-20: LINE -> ALIGN, AXIS_LINE -> AXIS_ALIGN,
     *  LEAP_DETECTOR -> LEAP_COUNTER); the type's own name when it was never renamed. */
    private static String legacyColorKey(Ap3Node.Type t) {
        return switch (t) {
            case ALIGN -> "LINE";
            case AXIS_ALIGN -> "AXIS_LINE";
            case LEAP_COUNTER -> "LEAP_DETECTOR";
            default -> t.name();
        };
    }

    public static Ap3Config getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        Ap3Config cfg = new Ap3Config();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(o, "enabled", cfg.enabled);
                cfg.chatFeedback = ConfigJson.getBool(o, "chatFeedback", cfg.chatFeedback);
                // Migration (2026-09-21): a file from before the merge has "diagonalWalk" / "serverStrafeAngle"
                // and no "strafe45" - either of those on means the merged toggle is on.
                boolean legacyStrafe = ConfigJson.getBool(o, "diagonalWalk", false)
                        || ConfigJson.getBool(o, "serverStrafeAngle", false);
                cfg.strafe45 = ConfigJson.getBool(o, "strafe45", legacyStrafe);
                cfg.alignTimerDev = ConfigJson.getBool(o, "alignTimerDev", cfg.alignTimerDev);
                cfg.editClassFilter = DungeonClass.byName(ConfigJson.getString(o, "editClassFilter", ""));
                cfg.setChainsFile(ConfigJson.getString(o, "chainsFile", cfg.chainsFile));
                cfg.colorsSectionOpen = ConfigJson.getBool(o, "colorsSectionOpen", cfg.colorsSectionOpen);
                cfg.keybindsSectionOpen = ConfigJson.getBool(o, "keybindsSectionOpen", cfg.keybindsSectionOpen);
                cfg.stopwatchHud = ConfigJson.getBool(o, "stopwatchHud", cfg.stopwatchHud);
                cfg.uniformColor = ConfigJson.getBool(o, "uniformColor", cfg.uniformColor);
                cfg.uniformColorArgb = ConfigJson.getInt(o, "uniformColorArgb", cfg.uniformColorArgb);
                cfg.activeColorArgb = ConfigJson.getInt(o, "activeColorArgb", cfg.activeColorArgb);
                JsonObject colors = ConfigJson.getObject(o, "nodeColors");
                if (colors != null) {
                    for (Ap3Node.Type t : Ap3Node.Type.values()) {
                        // A settings file from before the rename still has the old keys; read those as a fallback
                        // so a colour he picked for "Line" is the colour "Align" shows up in.
                        int fallback = ConfigJson.getInt(colors, legacyColorKey(t), cfg.nodeColors.get(t));
                        cfg.nodeColors.put(t, ConfigJson.getInt(colors, t.name(), fallback));
                    }
                }
                cfg.setThickness(ConfigJson.getFloat(o, "thickness", cfg.thickness));
                cfg.setHeight(ConfigJson.getFloat(o, "height", cfg.height));
                cfg.showLabels = ConfigJson.getBool(o, "showLabels", cfg.showLabels);
                cfg.showNodeNumbers = ConfigJson.getBool(o, "showNodeNumbers", cfg.showNodeNumbers);
                cfg.showNodeType = ConfigJson.getBool(o, "showNodeType", cfg.showNodeType);
                cfg.showNodeDetails = ConfigJson.getBool(o, "showNodeDetails", cfg.showNodeDetails);
                cfg.labelUseNodeColor = ConfigJson.getBool(o, "labelUseNodeColor", cfg.labelUseNodeColor);
                cfg.labelColorArgb = ConfigJson.getInt(o, "labelColorArgb", cfg.labelColorArgb);
                cfg.setLabelScale(ConfigJson.getFloat(o, "labelScale", cfg.labelScale));
                cfg.setLabelHeightOffset(ConfigJson.getFloat(o, "labelHeightOffset", cfg.labelHeightOffset));
                // Saved under a new key: the previous build's "alignTolerance" was a loose 0.03 default, and aligns must now
                // land to 3 decimals, so that old value is deliberately ignored once (killer560, 2026-09-21).
                cfg.setAlignTolerance(ConfigJson.getDouble(o, "alignToleranceExact", cfg.alignTolerance));
                cfg.setAlignTimeoutTicks(ConfigJson.getInt(o, "alignTimeoutTicks", cfg.alignTimeoutTicks));
                cfg.setMoveTimeoutTicks(ConfigJson.getInt(o, "moveTimeoutTicks", cfg.moveTimeoutTicks));
                cfg.setLeapDetectRadius(ConfigJson.getDouble(o, "leapDetectRadius", cfg.leapDetectRadius));
                JsonObject keys = ConfigJson.getObject(o, "keybinds");
                if (keys != null) {
                    for (String id : keys.keySet()) {
                        cfg.keybinds.put(canonical(id), KeyUtil.sanitize(ConfigJson.getInt(keys, id, KeyUtil.NONE)));
                    }
                }
            } catch (Exception e) {
                // unreadable file (not just a bad key) - keep defaults; the next explicit save() rewrites it
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("enabled", enabled);
            o.addProperty("chatFeedback", chatFeedback);
            o.addProperty("strafe45", strafe45);
            o.addProperty("alignTimerDev", alignTimerDev);
            o.addProperty("editClassFilter", editClassFilter == null ? "" : editClassFilter.name());
            o.addProperty("chainsFile", chainsFile);
            o.addProperty("colorsSectionOpen", colorsSectionOpen);
            o.addProperty("keybindsSectionOpen", keybindsSectionOpen);
            o.addProperty("stopwatchHud", stopwatchHud);
            o.addProperty("uniformColor", uniformColor);
            o.addProperty("uniformColorArgb", uniformColorArgb);
            o.addProperty("activeColorArgb", activeColorArgb);
            JsonObject colors = new JsonObject();
            for (Ap3Node.Type t : Ap3Node.Type.values()) {
                colors.addProperty(t.name(), nodeColors.get(t));
            }
            o.add("nodeColors", colors);
            o.addProperty("thickness", thickness);
            o.addProperty("height", height);
            o.addProperty("showLabels", showLabels);
            o.addProperty("showNodeNumbers", showNodeNumbers);
            o.addProperty("showNodeType", showNodeType);
            o.addProperty("showNodeDetails", showNodeDetails);
            o.addProperty("labelUseNodeColor", labelUseNodeColor);
            o.addProperty("labelColorArgb", labelColorArgb);
            o.addProperty("labelScale", labelScale);
            o.addProperty("labelHeightOffset", labelHeightOffset);
            o.addProperty("alignToleranceExact", alignTolerance);
            o.addProperty("alignTimeoutTicks", alignTimeoutTicks);
            o.addProperty("moveTimeoutTicks", moveTimeoutTicks);
            o.addProperty("leapDetectRadius", leapDetectRadius);
            JsonObject keys = new JsonObject();
            for (Map.Entry<String, Integer> e : keybinds.entrySet()) {
                keys.addProperty(e.getKey(), e.getValue());
            }
            o.add("keybinds", keys);
            Files.writeString(CONFIG_PATH, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------------------------------- master

    /** The legit jar can never report true here, even from a copied cheat-build config file. Force Dungeon
     *  ({@link Ap3Feature#isForceDungeon()}, session only) opens AP3's own Skyblock / p3sim gate - and only AP3's. */
    public boolean isEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && enabled
                && (com.killer560.hub.util.SkyblockGate.allows() || Ap3Feature.isForceDungeon());
    }

    public boolean isEnabledRaw() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }

    public boolean isChatFeedback() { return chatFeedback; }
    public void setChatFeedback(boolean v) { chatFeedback = v; }

    /** See the field doc. Only ever acted on inside {@link #isEnabled()}'s gate (the executor is never ticked
     *  otherwise), so no extra cheat-build check is needed here. */
    public boolean isStrafe45() { return strafe45; }
    public void setStrafe45(boolean v) { strafe45 = v; }

    /** The legit-vs-cheat gate does not matter here; the DEV_TOOLS gate does - a release jar can never time. */
    public boolean isAlignTimerDev() { return com.killer560.hub.BuildVariant.DEV_TOOLS && alignTimerDev; }
    public boolean isAlignTimerDevRaw() { return alignTimerDev; }
    public void setAlignTimerDev(boolean v) { alignTimerDev = v; }

    /** null = the class-less chain. */
    public DungeonClass getEditClassFilter() { return editClassFilter; }
    public void setEditClassFilter(DungeonClass v) { editClassFilter = v; }

    /** File name (inside {@link Ap3Store#directory()}) of the chains file in use - never a path. */
    public String getChainsFile() { return chainsFile; }

    /** Rejects anything {@link Ap3Store#validateConfigName} would (path separators, "..", odd characters) rather
     *  than let a hand-edited settings file point the store outside its folder. */
    public void setChainsFile(String name) {
        String clean = Ap3Store.normalizeConfigName(name);
        chainsFile = clean != null && Ap3Store.validateConfigName(clean) == null ? clean : Ap3Store.DEFAULT_CONFIG_NAME;
    }

    public boolean isColorsSectionOpen() { return colorsSectionOpen; }
    public void setColorsSectionOpen(boolean v) { colorsSectionOpen = v; }

    public boolean isKeybindsSectionOpen() { return keybindsSectionOpen; }
    public void setKeybindsSectionOpen(boolean v) { keybindsSectionOpen = v; }

    public boolean isStopwatchHud() { return stopwatchHud; }
    public void setStopwatchHud(boolean v) { stopwatchHud = v; }

    // ------------------------------------------------------------------------------------------- colours

    public boolean isUniformColor() { return uniformColor; }
    public void setUniformColor(boolean v) { uniformColor = v; }

    public int getUniformColorArgb() { return uniformColorArgb; }
    public void setUniformColorArgb(int argb) { uniformColorArgb = argb; }

    public int getActiveColorArgb() { return activeColorArgb; }
    public void setActiveColorArgb(int argb) { activeColorArgb = argb; }

    public int getNodeColorArgb(Ap3Node.Type type) {
        Integer c = type == null ? null : nodeColors.get(type);
        return c == null ? 0xFFFFFFFF : c;
    }

    public void setNodeColorArgb(Ap3Node.Type type, int argb) {
        if (type != null) {
            nodeColors.put(type, argb);
        }
    }

    /** The colour a node should render with: its own override, else the uniform colour, else its type's. */
    public int colorFor(Ap3Node node) {
        if (node != null && node.colour != null) {
            return node.colour;
        }
        if (uniformColor) {
            return uniformColorArgb;
        }
        return getNodeColorArgb(node == null ? null : node.type);
    }

    // ------------------------------------------------------------------------------------------- rendering

    public float getThickness() { return thickness; }
    public void setThickness(float v) {
        if (Float.isFinite(v)) {
            thickness = Math.max(MIN_THICKNESS, Math.min(MAX_THICKNESS, Math.round(v * 2f) / 2f));
        }
    }

    public float getHeight() { return height; }
    public void setHeight(float v) {
        if (Float.isFinite(v)) {
            height = Math.max(MIN_HEIGHT, Math.min(MAX_HEIGHT, Math.round(v * 10f) / 10f));
        }
    }

    /** Master switch for the world labels; the three parts below pick what a label says. */
    public boolean isShowLabels() { return showLabels; }
    public void setShowLabels(boolean v) { showLabels = v; }

    public boolean isShowNodeNumbers() { return showNodeNumbers; }
    public void setShowNodeNumbers(boolean v) { showNodeNumbers = v; }

    public boolean isShowNodeType() { return showNodeType; }
    public void setShowNodeType(boolean v) { showNodeType = v; }

    public boolean isShowNodeDetails() { return showNodeDetails; }
    public void setShowNodeDetails(boolean v) { showNodeDetails = v; }

    public boolean isLabelUseNodeColor() { return labelUseNodeColor; }
    public void setLabelUseNodeColor(boolean v) { labelUseNodeColor = v; }

    public int getLabelColorArgb() { return labelColorArgb; }
    public void setLabelColorArgb(int argb) { labelColorArgb = argb; }

    /** The colour a node's label is drawn in: the node's own marker colour, or the one fixed label colour. Always
     *  opaque - a translucent marker colour must not fade its number out. */
    public int labelColorFor(Ap3Node node, int markerArgb) {
        return (labelUseNodeColor ? markerArgb : labelColorArgb) | 0xFF000000;
    }

    public float getLabelScale() { return labelScale; }
    public void setLabelScale(float v) {
        if (Float.isFinite(v)) {
            labelScale = Math.max(MIN_LABEL_SCALE, Math.min(MAX_LABEL_SCALE, Math.round(v * 20f) / 20f));
        }
    }

    public float getLabelHeightOffset() { return labelHeightOffset; }
    public void setLabelHeightOffset(float v) {
        if (Float.isFinite(v)) {
            labelHeightOffset = Math.max(MIN_LABEL_HEIGHT, Math.min(MAX_LABEL_HEIGHT, Math.round(v * 20f) / 20f));
        }
    }

    // ------------------------------------------------------------------------------------------- executor

    public double getAlignTolerance() { return alignTolerance; }
    public void setAlignTolerance(double v) {
        if (Double.isFinite(v)) {
            alignTolerance = Math.max(MIN_ALIGN_TOLERANCE, Math.min(MAX_ALIGN_TOLERANCE, Math.round(v * 10000.0) / 10000.0));
        }
    }

    public int getAlignTimeoutTicks() { return alignTimeoutTicks; }
    public void setAlignTimeoutTicks(int v) { alignTimeoutTicks = Math.max(MIN_ALIGN_TIMEOUT, Math.min(MAX_ALIGN_TIMEOUT, v)); }

    public int getMoveTimeoutTicks() { return moveTimeoutTicks; }
    public void setMoveTimeoutTicks(int v) { moveTimeoutTicks = Math.max(MIN_MOVE_TIMEOUT, Math.min(MAX_MOVE_TIMEOUT, v)); }

    public double getLeapDetectRadius() { return leapDetectRadius; }
    public void setLeapDetectRadius(double v) {
        if (Double.isFinite(v)) {
            leapDetectRadius = Math.max(MIN_LEAP_RADIUS, Math.min(MAX_LEAP_RADIUS, Math.round(v * 2.0) / 2.0));
        }
    }

    // ------------------------------------------------------------------------------------------- keybinds

    /** GLFW key code for a command's keybind ({@link KeyUtil#NONE} when unbound). Ids are matched
     *  case-insensitively so {@code "addLine"} and {@code "addline"} are the same bind. */
    public int getKeybind(String id) {
        if (id == null) {
            return KeyUtil.NONE;
        }
        Integer code = keybinds.get(canonical(id));
        return code == null ? KeyUtil.NONE : code;
    }

    public void setKeybind(String id, int code) {
        if (id != null) {
            keybinds.put(canonical(id), KeyUtil.sanitize(code));
        }
    }

    private static String canonical(String id) {
        String t = id.trim();
        for (String known : KEYBIND_IDS) {
            if (known.equalsIgnoreCase(t)) {
                return known;
            }
        }
        return t.toLowerCase(Locale.ROOT);
    }

    // Typed pairs, one per command, for the UI agent (all delegate to the id map above).
    public int getAddAlignKey() { return getKeybind(KEY_ADD_ALIGN); }
    public void setAddAlignKey(int code) { setKeybind(KEY_ADD_ALIGN, code); }
    public int getAddAxisAlignKey() { return getKeybind(KEY_ADD_AXIS_ALIGN); }
    public void setAddAxisAlignKey(int code) { setKeybind(KEY_ADD_AXIS_ALIGN, code); }
    public int getAddWalkKey() { return getKeybind(KEY_ADD_WALK); }
    public void setAddWalkKey(int code) { setKeybind(KEY_ADD_WALK, code); }
    public int getAddRunKey() { return getKeybind(KEY_ADD_RUN); }
    public void setAddRunKey(int code) { setKeybind(KEY_ADD_RUN, code); }
    public int getAddLeapKey() { return getKeybind(KEY_ADD_LEAP); }
    public void setAddLeapKey(int code) { setKeybind(KEY_ADD_LEAP, code); }
    public int getAddLeapCounterKey() { return getKeybind(KEY_ADD_LEAP_COUNTER); }
    public void setAddLeapCounterKey(int code) { setKeybind(KEY_ADD_LEAP_COUNTER, code); }
    public int getAddTerminalKey() { return getKeybind(KEY_ADD_TERMINAL); }
    public void setAddTerminalKey(int code) { setKeybind(KEY_ADD_TERMINAL, code); }
    public int getAddStopKey() { return getKeybind(KEY_ADD_STOP); }
    public void setAddStopKey(int code) { setKeybind(KEY_ADD_STOP, code); }
    public int getAddLookKey() { return getKeybind(KEY_ADD_LOOK); }
    public void setAddLookKey(int code) { setKeybind(KEY_ADD_LOOK, code); }
    public int getAddBoomKey() { return getKeybind(KEY_ADD_BOOM); }
    public void setAddBoomKey(int code) { setKeybind(KEY_ADD_BOOM, code); }
    public int getAddStopwatchKey() { return getKeybind(KEY_ADD_STOPWATCH); }
    public void setAddStopwatchKey(int code) { setKeybind(KEY_ADD_STOPWATCH, code); }
    public int getListKey() { return getKeybind(KEY_LIST); }
    public void setListKey(int code) { setKeybind(KEY_LIST, code); }
    public int getUndoKey() { return getKeybind(KEY_UNDO); }
    public void setUndoKey(int code) { setKeybind(KEY_UNDO, code); }
    public int getDeleteKey() { return getKeybind(KEY_DELETE); }
    public void setDeleteKey(int code) { setKeybind(KEY_DELETE, code); }
    public int getReplaceLastKey() { return getKeybind(KEY_REPLACE_LAST); }
    public void setReplaceLastKey(int code) { setKeybind(KEY_REPLACE_LAST, code); }
    public int getClearKey() { return getKeybind(KEY_CLEAR); }
    public void setClearKey(int code) { setKeybind(KEY_CLEAR, code); }
    public int getReloadKey() { return getKeybind(KEY_RELOAD); }
    public void setReloadKey(int code) { setKeybind(KEY_RELOAD, code); }
    public int getStartKey() { return getKeybind(KEY_START); }
    public void setStartKey(int code) { setKeybind(KEY_START, code); }
    public int getStopKey() { return getKeybind(KEY_STOP); }
    public void setStopKey(int code) { setKeybind(KEY_STOP, code); }
    public int getTestModeKey() { return getKeybind(KEY_TEST_MODE); }
    public void setTestModeKey(int code) { setKeybind(KEY_TEST_MODE, code); }
}
