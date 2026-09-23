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
    /** Renamed to Fast Align 2026-09-22; the id stays as it shipped so a bound key survives the rename. */
    public static final String KEY_ADD_FAST_ALIGN = "add_testalign";
    public static final String KEY_ADD_PATH = "add_path";
    public static final String KEY_ADD_NO_GO = "add_nogo";
    public static final String KEY_ADD_TERM_AURA = "add_termaura";
    public static final String KEY_ADD_WALK = "add_walk";
    public static final String KEY_ADD_RUN = "add_run";
    public static final String KEY_ADD_LEAP = "add_leap";
    public static final String KEY_ADD_LEAP_COUNTER = "add_leapdetector";
    public static final String KEY_ADD_TERMINAL = "add_terminal";
    public static final String KEY_ADD_STOP = "add_stop";
    public static final String KEY_ADD_LOOK = "add_look";
    public static final String KEY_ADD_BOOM = "add_boom";
    public static final String KEY_ADD_STOPWATCH = "add_stopwatch";
    public static final String KEY_ADD_JUMP = "add_jump";
    public static final String KEY_ADD_EDGE = "add_edge";
    public static final String KEY_ADD_BLOCK = "add_block";
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
    public static final String KEY_FREEZE_STATE = "freezestate";
    public static final String KEY_REWIND_TICK = "rewind_tick";
    public static final String KEY_FORWARD_TICK = "forward_tick";

    public static final List<String> KEYBIND_IDS = List.of(
            KEY_ADD_ALIGN, KEY_ADD_AXIS_ALIGN, KEY_ADD_WALK, KEY_ADD_RUN, KEY_ADD_LEAP, KEY_ADD_LEAP_COUNTER,
            KEY_ADD_TERMINAL, KEY_ADD_STOP, KEY_ADD_LOOK, KEY_ADD_BOOM, KEY_ADD_STOPWATCH, KEY_ADD_JUMP, KEY_ADD_EDGE, KEY_ADD_BLOCK, KEY_ADD_FAST_ALIGN, KEY_ADD_PATH, KEY_ADD_NO_GO, KEY_ADD_TERM_AURA, KEY_LIST, KEY_UNDO,
            KEY_DELETE, KEY_REPLACE_LAST, KEY_CLEAR, KEY_RELOAD, KEY_START, KEY_STOP, KEY_TEST_MODE,
            KEY_FREEZE_STATE, KEY_REWIND_TICK, KEY_FORWARD_TICK);

    public static final float MIN_THICKNESS = 1f;
    public static final float MAX_THICKNESS = 8f;
    public static final float MIN_HEIGHT = 0.1f;
    public static final float MAX_HEIGHT = 1f;
    /** "Align Tolerance": how far from the point, per axis, an align may settle. 0.0005 = the coordinate reads the
     *  exact 3-decimal value (killer560: "down to 3 decimals of perfect"). AP3 lands there by movement input alone -
     *  it never writes position, Hypixel lags that back - solving vanilla's own step exactly ({@code Ap3AlignMath}). */
    public static final double MIN_ALIGN_TOLERANCE = 0.0001;
    public static final double MAX_ALIGN_TOLERANCE = 0.1;
    /**
     * How an ALIGN lands (killer560, 2026-09-21, after Hypixel corrected every tap made under a sent yaw that differed
     * from the camera): three methods he can A/B on an alt, each reporting its server corrections in the dev line.
     */
    public enum AlignMethod {
        /** RSA-style: the camera yaw only, sent yaw == camera always; discrete keys + sneak taps planned exhaustively
         *  over a short horizon - as close as a keyboard gets (a few thousandths), nothing hidden. */
        KEYS_SNEAK("Keys + Sneak"),
        /** The two-tap planner steering the yaw the SERVER receives (the strafe lock) while the camera stays put -
         *  exact, but Hypixel corrected it on the first tap in his test. */
        SENT_YAW("Sent-Yaw Planner"),
        /** The same planner turning his REAL camera (bounded deltas on the live yaw) so the sent yaw always equals the
         *  camera - exact, and 8/8 with no corrections on Hypixel (2026-09-21). DEFAULT. */
        CAMERA("Camera Planner");

        public final String label;

        AlignMethod(String label) {
            this.label = label;
        }

        public AlignMethod next() {
            AlignMethod[] all = values();
            return all[(ordinal() + 1) % all.length];
        }
    }

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
    /** How much the chat says when a node is added (and whether "Queued ..." lines show) - killer560, 2026-09-21:
     *  "by default it should just be added 1 align ... Detailed should be as it is now. If it is in detailed then
     *  show the queued as well. Otherwise hide it. Also have an option to show none." */
    private MessageDetail messageDetail = MessageDetail.SIMPLE;

    public enum MessageDetail {
        NONE("None"), SIMPLE("Simple"), DETAILED("Detailed");

        public final String label;

        MessageDetail(String label) {
            this.label = label;
        }

        public MessageDetail next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }
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
    /** Post a finished stopwatch to party chat ("s3 took 12.345s") - killer560, 2026-09-21. Off by default. */
    private boolean stopwatchToParty = false;
    /** The line joining consecutive nodes (killer560, 2026-09-21: "an option to hide the lines going from one node to
     *  another"). On by default - it is what was always drawn. */
    private boolean showChainLines = true;
    /** Draw the nodes at all (killer560, 2026-09-21: "add an option to hide nodes entirely"). Only the drawing -
     *  nodes still fire. On by default. */
    private boolean showNodes = true;
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
    /** Route optimiser: may the plan use jumps? At Skyblock speed a jump is slower (the air's 0.026 input cannot
     *  sustain a 550-speed run), so the planner only uses them where they actually win - this is the off switch. */
    private boolean routeAllowJumps = true;
    /** How many states per tick layer the route search keeps: higher finds more, costs more planning time. */
    private int routeBeam = 400;
    /** Longest the route search may think, milliseconds (it runs off the client thread). */
    private long routeBudgetMs = 1500;
    /** How far past the route the world is read and the heuristic grid built - see Ap3RoutePlanner.Options.scanPad. */
    private double routeScanPad = 32.0;
    /** Take the sprint key's real state for an align's model rather than learning it - see Ap3Executor.modelFor. */
    private boolean alignSprintFromKey = true;
    /** How close an "exact" Path node has to be hit - what matters is staying on the same side of the block edge. */
    private double routeExactTolerance = 0.02;
    /** A Path node marked "term": how long to wait for the terminal screen before giving up on it. */
    private int routeTermWaitTicks = 60;
    /** A route that has not finished in this many ticks fails, like every other node's timeout. */
    private int routeTimeoutTicks = 400;
    /** Draw the planned route through the world. */
    private boolean showPlannedPath = true;

    /** Alignment (ALIGN / AXIS_ALIGN) is done within this many blocks of the target (see the constants). */
    private double alignTolerance = DEFAULT_ALIGN_TOLERANCE;
    private AlignMethod alignMethod = AlignMethod.CAMERA;
    /** Camera Planner only: his screen keeps the view he had while the real yaw does the planner's turns
     *  (killer560, 2026-09-21: "do the same freecam style we used for walk nodes"). */
    private boolean alignFreezeView = true;
    /** Freeze State: how many ticks of position history are kept for stepping back (20 ticks = 1 second). */
    private int rewindTicks = DEFAULT_REWIND_TICKS;
    /** The box every newly placed node gets (killer560, 2026-09-21: "a default size option so I can set the default
     *  nodes to .5 of a block or 1 block"); w/l modifiers on the add command still override it. */
    private double defaultNodeSize = 0.5;
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
            case JUMP -> 0xFF7DD3FC;
            case EDGE -> 0xFF22D3EE;
            case BLOCK -> 0xFFB45309;
            case FAST_ALIGN -> 0xFFFFD27F;
            case PATH -> 0xFFFFB347;
            case NO_GO -> 0xFFFF3355;
            case TERM_AURA -> 0xFFA855F7;
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
                cfg.messageDetail = ConfigJson.getEnum(o, "messageDetail", MessageDetail.class, cfg.messageDetail);
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
                cfg.stopwatchToParty = ConfigJson.getBool(o, "stopwatchToParty", cfg.stopwatchToParty);
                cfg.showChainLines = ConfigJson.getBool(o, "showChainLines", cfg.showChainLines);
                cfg.showNodes = ConfigJson.getBool(o, "showNodes", cfg.showNodes);
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
                // New key: Camera Planner won the 2026-09-21 Hypixel A/B (8/8 exact, no corrections) and is the default
                // now, so whatever was cycled to during that test is dropped once.
                cfg.alignMethod = ConfigJson.getEnum(o, "alignMethodV2", AlignMethod.class, cfg.alignMethod);
                cfg.alignFreezeView = ConfigJson.getBool(o, "alignFreezeView", cfg.alignFreezeView);
                cfg.setRewindTicks(ConfigJson.getInt(o, "rewindTicks", cfg.rewindTicks));
                cfg.setDefaultNodeSize(ConfigJson.getDouble(o, "defaultNodeSize", cfg.defaultNodeSize));
                cfg.setAlignTimeoutTicks(ConfigJson.getInt(o, "alignTimeoutTicks", cfg.alignTimeoutTicks));
                cfg.setMoveTimeoutTicks(ConfigJson.getInt(o, "moveTimeoutTicks", cfg.moveTimeoutTicks));
                cfg.setRouteAllowJumps(ConfigJson.getBool(o, "routeAllowJumps", cfg.routeAllowJumps));
                cfg.setRouteBeam(ConfigJson.getInt(o, "routeBeam", cfg.routeBeam));
                cfg.setRouteBudgetMs(ConfigJson.getInt(o, "routeBudgetMs", (int) cfg.routeBudgetMs));
                cfg.setRouteScanPad(ConfigJson.getInt(o, "routeScanPad", (int) cfg.routeScanPad));
                cfg.alignSprintFromKey = ConfigJson.getBool(o, "alignSprintFromKey", cfg.alignSprintFromKey);
                cfg.setRouteExactTolerance(ConfigJson.getDouble(o, "routeExactTolerance", cfg.routeExactTolerance));
                cfg.setRouteTermWaitTicks(ConfigJson.getInt(o, "routeTermWaitTicks", cfg.routeTermWaitTicks));
                cfg.setRouteTimeoutTicks(ConfigJson.getInt(o, "routeTimeoutTicks", cfg.routeTimeoutTicks));
                cfg.setShowPlannedPath(ConfigJson.getBool(o, "showPlannedPath", cfg.showPlannedPath));
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
            o.addProperty("messageDetail", messageDetail.name());
            o.addProperty("strafe45", strafe45);
            o.addProperty("alignTimerDev", alignTimerDev);
            o.addProperty("editClassFilter", editClassFilter == null ? "" : editClassFilter.name());
            o.addProperty("chainsFile", chainsFile);
            o.addProperty("colorsSectionOpen", colorsSectionOpen);
            o.addProperty("keybindsSectionOpen", keybindsSectionOpen);
            o.addProperty("stopwatchHud", stopwatchHud);
            o.addProperty("stopwatchToParty", stopwatchToParty);
            o.addProperty("showChainLines", showChainLines);
            o.addProperty("showNodes", showNodes);
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
            o.addProperty("alignMethodV2", alignMethod.name());
            o.addProperty("alignFreezeView", alignFreezeView);
            o.addProperty("rewindTicks", rewindTicks);
            o.addProperty("defaultNodeSize", defaultNodeSize);
            o.addProperty("alignTimeoutTicks", alignTimeoutTicks);
            o.addProperty("moveTimeoutTicks", moveTimeoutTicks);
            o.addProperty("routeAllowJumps", routeAllowJumps);
            o.addProperty("routeBeam", routeBeam);
            o.addProperty("routeBudgetMs", routeBudgetMs);
            o.addProperty("routeScanPad", routeScanPad);
            o.addProperty("alignSprintFromKey", alignSprintFromKey);
            o.addProperty("routeExactTolerance", routeExactTolerance);
            o.addProperty("routeTermWaitTicks", routeTermWaitTicks);
            o.addProperty("routeTimeoutTicks", routeTimeoutTicks);
            o.addProperty("showPlannedPath", showPlannedPath);
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

    // ---- route optimiser (Path nodes) ----
    public boolean isRouteAllowJumps() { return routeAllowJumps; }
    public void setRouteAllowJumps(boolean v) { routeAllowJumps = v; }
    public int getRouteBeam() { return routeBeam; }
    public void setRouteBeam(int v) { routeBeam = Math.max(50, Math.min(2000, v)); }
    public long getRouteBudgetMs() { return routeBudgetMs; }
    public void setRouteBudgetMs(long v) { routeBudgetMs = Math.max(100, Math.min(10_000, v)); }
    public boolean isAlignSprintFromKey() { return alignSprintFromKey; }
    public void setAlignSprintFromKey(boolean v) { alignSprintFromKey = v; }
    public double getRouteScanPad() { return routeScanPad; }
    public void setRouteScanPad(double v) { routeScanPad = Math.max(8, Math.min(64, v)); }
    public double getRouteExactTolerance() { return routeExactTolerance; }
    public void setRouteExactTolerance(double v) { routeExactTolerance = Math.max(0.001, Math.min(0.2, v)); }
    public int getRouteTermWaitTicks() { return routeTermWaitTicks; }
    public void setRouteTermWaitTicks(int v) { routeTermWaitTicks = Math.max(5, Math.min(200, v)); }
    public int getRouteTimeoutTicks() { return routeTimeoutTicks; }
    public void setRouteTimeoutTicks(int v) { routeTimeoutTicks = Math.max(40, Math.min(1200, v)); }
    public boolean isShowPlannedPath() { return showPlannedPath; }
    public void setShowPlannedPath(boolean v) { showPlannedPath = v; }
    public MessageDetail getMessageDetail() { return messageDetail; }
    public void setMessageDetail(MessageDetail d) { messageDetail = d == null ? MessageDetail.SIMPLE : d; }
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
    public boolean isStopwatchToParty() { return stopwatchToParty; }
    public boolean isShowChainLines() { return showChainLines; }
    public boolean isShowNodes() { return showNodes; }
    public void setShowNodes(boolean v) { showNodes = v; }
    public void setShowChainLines(boolean v) { showChainLines = v; }
    public void setStopwatchToParty(boolean v) { stopwatchToParty = v; }
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

    /** Fixed (killer560, 2026-09-21: "No longer have the align method"): every align is the combined planner - the
     *  real camera turns, any key combination, sneak - which is the Camera Planner path. The other two stay in the
     *  code only as history; nothing selects them. */
    public AlignMethod getAlignMethod() { return AlignMethod.CAMERA; }
    public void setAlignMethod(AlignMethod m) { alignMethod = m == null ? AlignMethod.CAMERA : m; }

    public boolean isAlignFreezeView() { return alignFreezeView; }

    public static final int DEFAULT_REWIND_TICKS = 200;
    public static final int MIN_REWIND_TICKS = 20;
    public static final int MAX_REWIND_TICKS = 2400;
    public int getRewindTicks() { return rewindTicks; }
    public void setRewindTicks(int v) { rewindTicks = Math.max(MIN_REWIND_TICKS, Math.min(MAX_REWIND_TICKS, v)); }

    public double getDefaultNodeSize() { return defaultNodeSize; }
    /** Only the two sizes he asked for: half a block or a whole block. */
    public void setDefaultNodeSize(double v) { defaultNodeSize = v >= 0.75 ? 1.0 : 0.5; }
    public void setAlignFreezeView(boolean v) { alignFreezeView = v; }

    /** Fixed, not a setting (killer560: "Remove the sliders as a whole and keep them fixed"). */
    public double getAlignTolerance() { return DEFAULT_ALIGN_TOLERANCE; }
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
