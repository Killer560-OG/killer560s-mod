package com.killer560.hub.autosecret;

import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.ModPaths;

import java.nio.file.Path;

/**
 * Persisted Auto Secret settings - see {@link AutoSecretFeature}. Cheat build only. Whether Auto Secret is RUNNING is
 * not a setting: it is off at every start and is switched on per run, by its key or the tab's button.
 */
public final class AutoSecretConfig {

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-autosecret.json");

    public static final int MIN_PUZZLE_WAIT_S = 0;
    public static final int MAX_PUZZLE_WAIT_S = 120;
    public static final int MIN_STALL_S = 5;
    public static final int MAX_STALL_S = 60;

    private static AutoSecretConfig instance;

    private int toggleKey = KeyUtil.NONE;
    /** Before secreting, warp into rooms through entries the Insta Clear tracker says clear them. */
    private boolean instaClear = true;
    /** Go to Ice Fill early and let Auto Ice Fill do it (only when Auto Ice Fill is on). */
    private boolean iceFillFirst = true;
    /** Start the Quiz first and answer each question the moment it opens, doing other work in between (Auto Quiz on). */
    private boolean quizFirst = true;
    /** Endgame: hand an uncleared room to Auto Clear instead of stopping there (off by default). */
    private boolean autoClearRooms = false;
    /** Endgame: how long to stand in an unfinished puzzle for it to be done before moving on. */
    private int puzzleWaitSeconds = 30;
    /** A route with no node begun for this long is waiting on a walk nobody will make: stopped and moved on from. */
    private int routeStallSeconds = 20;
    private boolean chatFeedback = true;

    // ---- Dungeons Autopilot (apmenu-1121, killer560 2026-11-21: one switch, one key, a few modes) ----
    /** The one feature's mode. Replaces Run Mode + Room Plan + the three separate starts. */
    public enum Mode {
        /** The planner: the fastest way for this one player to reach score 300. */
        SOLO_300("Solo 300"),
        /** Every mob room cleared first, then the secrets. */
        CLEAR_THEN_SECRET("Clear Then Secret"),
        /** Every room's secrets first, then the rooms left uncleared. */
        SECRET_THEN_CLEAR("Secret Then Clear"),
        /** The planner, going for the party's secrets. */
        PARTY_SECRETS("Party Secrets");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** What happens before the mode starts: nothing, a rush to the Blood door clearing the rooms on the way, or doors only. */
    public enum BloodRush {
        NONE("None"), CLEAR("Clear"), DOOR("Door");

        private final String label;

        BloodRush(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** The room right before the Blood room (the one whose door opens into it), for secrets. */
    public enum RoomBeforeBlood {
        /** Its secrets are done last (what the planner has always done). */
        SAVE_FOR_LAST("Save For Last"),
        /** Its secrets are left out. */
        SKIP("Skip");

        private final String label;

        RoomBeforeBlood(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private Mode mode = Mode.SOLO_300;
    private BloodRush bloodRush = BloodRush.NONE;
    private RoomBeforeBlood roomBeforeBlood = RoomBeforeBlood.SAVE_FOR_LAST;
    /** Switch the feature off once the Blood Rush part is done. */
    private boolean disableAfterBloodRush = false;
    /** Clear a room in the same visit before doing its secrets (killer560, 2026-11-21 ~11:15 PM). */
    private boolean clearBeforeSecret = false;
    /** Clear every room of the dungeon before any secrets (killer560, 2026-11-21: a separate option). */
    private boolean clearFirst = false;

    // ---- Dungeon Autopilot (Auto Secret + Auto Clear + Auto Puzzles under one planner) ----
    /** What the autopilot optimizes for (derived from {@link #getMode()} since apmenu-1121). */
    public enum RunMode {
        SOLO("Solo (300 score)"), PARTY("Party (secrets)");

        private final String label;

        RunMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * Auto Secret's Blood First (killer560, 2026-10-17: "add a blood first where you can select door camp or full blood
     * rush first then it does only secrets"). Not the Autopilot's {@link #bloodFirst}, which is its own switch.
     */
    public enum SecretBloodFirst {
        /** Secrets from the start, as always. */
        OFF("Off"),
        /** Straight to the blood door, opening the wither doors on the way (keys picked up where they lie), and open it. */
        DOOR_CAMP("Door Camp"),
        /** Auto Clear clears the Blood Rush Split rooms in path order and the doors are opened, as Autopilot's Blood First. */
        FULL_BLOOD_RUSH("Full Blood Rush");

        private final String label;

        SecretBloodFirst(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** The mode kept while the switch is off; {@link #secretBloodFirstOn} is the real switch. */
    private SecretBloodFirst secretBloodFirst = SecretBloodFirst.DOOR_CAMP;
    private boolean secretBloodFirstOn;

    /**
     * The order Auto Secret takes the rooms in (killer560, 2026-10-18: "For auto secret make two modes. One is largest to
     * smallest secret rooms and the other is as few etherwarps possible pathfinding the whole dungeon no room order
     * preference"), and (same day) "once only rooms with 3 or less secrets remain it will use the least total etherwarp
     * mode no matter what is selected" ({@link #SMALL_ROOM_SECRETS}). Either way the room in front of the blood door is
     * always the last one.
     */
    public enum SecretOrder {
        /** The rooms with the most secrets left first, nearer first on a tie. */
        LARGEST_TO_SMALLEST("Largest To Smallest"),
        /** The order through every room with the fewest warps in all ({@code TourPlanner.shortest}). */
        LEAST_ETHERWARPS("Least Etherwarps");

        private final String label;

        SecretOrder(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Once every room left has this many unfound secrets or fewer, the order is Least Etherwarps whatever is chosen. */
    public static final int SMALL_ROOM_SECRETS = 3;

    private SecretOrder secretOrder = SecretOrder.LEAST_ETHERWARPS;

    /**
     * How Auto Secret fits the room clears in with the secrets (killer560, 2026-10-19: "It doesn't need to clear a room
     * first or secret it first; it can do either, or skip secreting a room it deems slow to secret in exchange for
     * clearing or secreting other rooms, so it can get 300 faster").
     */
    public enum RoomPlan {
        /** Each room's clear goes in the same visit as its secrets, before or after them by the estimated ticks; a room
         *  with nothing but a clear is a stop on the tour like any other. */
        FASTEST_TOTAL("Fastest Total"),
        /** Every room's secrets first, then the uncleared rooms nearest first (the order before 2026-10-20). */
        SECRETS_THEN_CLEARS("Secrets Then Clears");

        private final String label;

        RoomPlan(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private RoomPlan roomPlan = RoomPlan.FASTEST_TOTAL;
    /** Fastest Total: leave out the secrets an S+ does not need (by the Score Calculator), slowest first. Off by default
     *  until a run has shown the S+ is still reached with it (coordinator, 2026-10-20). */
    private boolean skipUnneededSecrets = false;

    private int autopilotKey = KeyUtil.NONE;
    private RunMode runMode = RunMode.SOLO;
    /** First push the Blood Rush Split path until the blood door is open, then the rest. */
    private boolean bloodFirst = false;
    /** Turn to look at the wither or blood door on arriving at it (as the Interactive Map's Face Door on Arrival; killer560, 2026-11-26). */
    private boolean faceDoorOnArrival = false;
    /** Armed in the green room: etherwarp to the stand by the gate instead of walking (GreenRoomStart; killer560, 2026-11-26).
     *  Off by default: nothing new defaults on unless he asks (killer560, 2026-11-26). */
    private boolean greenRoomWarp = false;
    /** Treat puzzles like rooms (only those whose Auto Puzzles auto is on). */
    private boolean doPuzzles = true;
    /** The one Status HUD every auto clear shares (AutoStatusHud, 2026-10-15) - Auto Clear, Auto Secret, Autopilot. */
    private boolean statusHud = true;
    /** The base key pickup range in blocks (unverified - see DungeonKeys.DEFAULT_BASE_RANGE). */
    private double keyBaseRange = com.killer560.hub.doorkeys.DungeonKeys.DEFAULT_BASE_RANGE;
    public static final double MIN_KEY_BASE = 0.5;
    public static final double MAX_KEY_BASE = 5.0;

    private AutoSecretConfig() {
    }

    public static AutoSecretConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        AutoSecretConfig cfg = new AutoSecretConfig();
        JsonObject o = ConfigJson.read(CONFIG_PATH);
        if (o != null) {
            cfg.toggleKey = KeyUtil.sanitizeBind(ConfigJson.getInt(o, "toggleKey", cfg.toggleKey));
            cfg.instaClear = ConfigJson.getBool(o, "instaClear", cfg.instaClear);
            cfg.iceFillFirst = ConfigJson.getBool(o, "iceFillFirst", cfg.iceFillFirst);
            cfg.quizFirst = ConfigJson.getBool(o, "quizFirst", cfg.quizFirst);
            cfg.autoClearRooms = ConfigJson.getBool(o, "autoClearRooms", cfg.autoClearRooms);
            cfg.setPuzzleWaitSeconds(ConfigJson.getInt(o, "puzzleWaitSeconds", cfg.puzzleWaitSeconds));
            cfg.setRouteStallSeconds(ConfigJson.getInt(o, "routeStallSeconds", cfg.routeStallSeconds));
            cfg.chatFeedback = ConfigJson.getBool(o, "chatFeedback", cfg.chatFeedback);
            cfg.autopilotKey = KeyUtil.sanitizeBind(ConfigJson.getInt(o, "autopilotKey", cfg.autopilotKey));
            String mode = ConfigJson.getString(o, "runMode", cfg.runMode.name());
            for (RunMode m : RunMode.values()) {
                if (m.name().equals(mode)) {
                    cfg.runMode = m;
                }
            }
            cfg.bloodFirst = ConfigJson.getBool(o, "bloodFirst", cfg.bloodFirst);
            cfg.faceDoorOnArrival = ConfigJson.getBool(o, "faceDoorOnArrival", cfg.faceDoorOnArrival);
            cfg.greenRoomWarp = ConfigJson.getBool(o, "greenRoomWarp", cfg.greenRoomWarp);
            String sbf = ConfigJson.getString(o, "secretBloodFirst", cfg.secretBloodFirst.name());
            for (SecretBloodFirst m : SecretBloodFirst.values()) {
                if (m != SecretBloodFirst.OFF && m.name().equals(sbf)) {
                    cfg.secretBloodFirst = m;
                }
            }
            // A file from the selector era says OFF (switch off) or a mode (switch on).
            cfg.secretBloodFirstOn = ConfigJson.getBool(o, "secretBloodFirstOn", !"OFF".equals(sbf) && o.has("secretBloodFirst"));
            String so = ConfigJson.getString(o, "secretOrder", cfg.secretOrder.name());
            for (SecretOrder m : SecretOrder.values()) {
                if (m.name().equals(so)) {
                    cfg.secretOrder = m;
                }
            }
            // The names of the first version (2026-10-18, a few hours old): LARGEST_FIRST, FEWEST_WARPS.
            if ("LARGEST_FIRST".equals(so)) {
                cfg.secretOrder = SecretOrder.LARGEST_TO_SMALLEST;
            } else if ("FEWEST_WARPS".equals(so)) {
                cfg.secretOrder = SecretOrder.LEAST_ETHERWARPS;
            }
            String rp = ConfigJson.getString(o, "roomPlan", cfg.roomPlan.name());
            for (RoomPlan m : RoomPlan.values()) {
                if (m.name().equals(rp)) {
                    cfg.roomPlan = m;
                }
            }
            cfg.skipUnneededSecrets = ConfigJson.getBool(o, "skipUnneededSecrets", cfg.skipUnneededSecrets);
            cfg.doPuzzles = ConfigJson.getBool(o, "doPuzzles", cfg.doPuzzles);
            // Before 2026-10-15 this was "autopilotHud" (Autopilot HUD); a file from then keeps its choice.
            cfg.statusHud = ConfigJson.getBool(o, "statusHud", ConfigJson.getBool(o, "autopilotHud", cfg.statusHud));
            // getDouble drops a non-finite value (a hand-edited NaN made key pickup never reach).
            cfg.setKeyBaseRange(ConfigJson.getDouble(o, "keyBaseRange", cfg.keyBaseRange));
            cfg.roomBeforeBlood = ConfigJson.getEnum(o, "roomBeforeBlood", RoomBeforeBlood.class, cfg.roomBeforeBlood);
            cfg.disableAfterBloodRush = ConfigJson.getBool(o, "disableAfterBloodRush", cfg.disableAfterBloodRush);
            cfg.clearBeforeSecret = ConfigJson.getBool(o, "clearBeforeSecret", cfg.clearBeforeSecret);
            cfg.clearFirst = ConfigJson.getBool(o, "clearFirst", cfg.clearFirst);
        }
        // A file from before the one feature (no "mode") takes its mode from Run Mode / Room Plan.
        cfg.mode = o != null && o.has("mode") ? ConfigJson.getEnum(o, "mode", Mode.class, cfg.mode)
                : cfg.runMode == RunMode.PARTY ? Mode.PARTY_SECRETS
                : cfg.roomPlan == RoomPlan.SECRETS_THEN_CLEARS ? Mode.SECRET_THEN_CLEAR : Mode.SOLO_300;
        cfg.bloodRush = o != null && o.has("bloodRush") ? ConfigJson.getEnum(o, "bloodRush", BloodRush.class, cfg.bloodRush)
                : migratedBloodRush(cfg);
        instance = cfg;
    }

    /**
     * Blood Rush for a file from before it existed: the old Auto Blood Rush switch (doors only) or Auto Clear's Door Only
     * gives Door, Auto Clear's Blood Rush Split mode gives Clear, else Auto Secret's own Blood First (Door Camp = Door,
     * Full Blood Rush = Clear). The old files are read raw: no other config class is loaded from here.
     */
    private static BloodRush migratedBloodRush(AutoSecretConfig cfg) {
        JsonObject map = ConfigJson.read(ModPaths.config("killer560smod-livemap.json"));
        JsonObject clear = ConfigJson.read(ModPaths.config("killer560smod-autoclear.json"));
        if ((map != null && ConfigJson.getBool(map, "bloodRushEnabled", false))
                || (clear != null && ConfigJson.getBool(clear, "doorOnly", false))) {
            return BloodRush.DOOR;
        }
        if (clear != null && "BLOOD_RUSH_SPLIT".equals(ConfigJson.getString(clear, "mode", ""))) {
            return BloodRush.CLEAR;
        }
        if (cfg.secretBloodFirstOn) {
            return cfg.secretBloodFirst == SecretBloodFirst.FULL_BLOOD_RUSH ? BloodRush.CLEAR : BloodRush.DOOR;
        }
        return BloodRush.NONE;
    }

    /** The first bound of the old start keys (Dungeon Autopilot's, then Auto Secret's), or NONE; for the one key. */
    public int oldStartKey() {
        return autopilotKey != KeyUtil.NONE ? autopilotKey : toggleKey;
    }

    public void save() {
        JsonObject o = new JsonObject();
        o.addProperty("toggleKey", toggleKey);
        o.addProperty("instaClear", instaClear);
        o.addProperty("iceFillFirst", iceFillFirst);
        o.addProperty("quizFirst", quizFirst);
        o.addProperty("autoClearRooms", autoClearRooms);
        o.addProperty("puzzleWaitSeconds", puzzleWaitSeconds);
        o.addProperty("routeStallSeconds", routeStallSeconds);
        o.addProperty("chatFeedback", chatFeedback);
        o.addProperty("autopilotKey", autopilotKey);
        o.addProperty("runMode", getRunMode().name());
        o.addProperty("bloodFirst", bloodFirst);
        o.addProperty("faceDoorOnArrival", faceDoorOnArrival);
        o.addProperty("greenRoomWarp", greenRoomWarp);
        o.addProperty("secretBloodFirst", secretBloodFirst.name());
        o.addProperty("secretBloodFirstOn", secretBloodFirstOn);
        o.addProperty("secretOrder", secretOrder.name());
        o.addProperty("roomPlan", getRoomPlan().name());
        o.addProperty("skipUnneededSecrets", skipUnneededSecrets);
        o.addProperty("doPuzzles", doPuzzles);
        o.addProperty("statusHud", statusHud);
        o.addProperty("keyBaseRange", keyBaseRange);
        o.addProperty("mode", mode.name());
        o.addProperty("bloodRush", bloodRush.name());
        o.addProperty("roomBeforeBlood", roomBeforeBlood.name());
        o.addProperty("disableAfterBloodRush", disableAfterBloodRush);
        o.addProperty("clearBeforeSecret", clearBeforeSecret);
        o.addProperty("clearFirst", clearFirst);
        ConfigJson.write(CONFIG_PATH, o);
    }

    /** The legit jar can never run it, even from a copied cheat-build config. */
    public static boolean allowed() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && com.killer560.hub.util.SkyblockGate.allows();
    }

    /** NONE since apmenu-1121: the one Dungeons Autopilot key (ModuleKeybinds) starts everything; the saved value only
     *  migrates into it ({@link #oldStartKey}). */
    public int getToggleKey() { return KeyUtil.NONE; }
    public void setToggleKey(int v) { toggleKey = KeyUtil.sanitizeBind(v); }

    public boolean isInstaClear() { return instaClear; }
    public void setInstaClear(boolean v) { instaClear = v; }

    public boolean isIceFillFirst() { return iceFillFirst; }
    public void setIceFillFirst(boolean v) { iceFillFirst = v; }
    public boolean isQuizFirst() { return quizFirst; }
    public void setQuizFirst(boolean v) { quizFirst = v; }

    /** Always on (killer560, 2026-10-17: "just have it do it by default every time"); the saved key is ignored. */
    public boolean isAutoClearRooms() { return true; }
    public void setAutoClearRooms(boolean v) { autoClearRooms = v; }

    public int getPuzzleWaitSeconds() { return puzzleWaitSeconds; }
    public void setPuzzleWaitSeconds(int v) { puzzleWaitSeconds = Math.max(MIN_PUZZLE_WAIT_S, Math.min(MAX_PUZZLE_WAIT_S, v)); }

    public int getRouteStallSeconds() { return routeStallSeconds; }
    public void setRouteStallSeconds(int v) { routeStallSeconds = Math.max(MIN_STALL_S, Math.min(MAX_STALL_S, v)); }

    public boolean isChatFeedback() { return chatFeedback; }
    public void setChatFeedback(boolean v) { chatFeedback = v; }

    /** NONE since apmenu-1121 (see {@link #getToggleKey}). */
    public int getAutopilotKey() { return KeyUtil.NONE; }
    public void setAutopilotKey(int v) { autopilotKey = KeyUtil.sanitizeBind(v); }

    /** Derived from {@link #getMode()}: Party for Party Secrets. */
    public RunMode getRunMode() { return mode == Mode.PARTY_SECRETS ? RunMode.PARTY : RunMode.SOLO; }
    public void setRunMode(RunMode v) {
        runMode = v == null ? RunMode.SOLO : v;
        if (runMode == RunMode.PARTY) {
            mode = Mode.PARTY_SECRETS;
        } else if (mode == Mode.PARTY_SECRETS) {
            mode = Mode.SOLO_300;
        }
    }

    public boolean isBloodFirst() { return bloodFirst; }
    public void setBloodFirst(boolean v) { bloodFirst = v; }
    public boolean isFaceDoorOnArrival() { return faceDoorOnArrival; }
    public void setFaceDoorOnArrival(boolean v) { faceDoorOnArrival = v; }
    public boolean isGreenRoomWarp() { return greenRoomWarp; }
    public void setGreenRoomWarp(boolean v) { greenRoomWarp = v; }

    /** What the run uses: OFF while the switch is off. */
    /** OFF since apmenu-1121: the rush to the Blood door is the one feature's Blood Rush part, done before the mode starts. */
    public SecretBloodFirst getSecretBloodFirst() { return SecretBloodFirst.OFF; }
    public void setSecretBloodFirst(SecretBloodFirst v) {
        secretBloodFirstOn = v != null && v != SecretBloodFirst.OFF;
        if (secretBloodFirstOn) {
            secretBloodFirst = v;
        }
    }
    public boolean isSecretBloodFirstOn() { return secretBloodFirstOn; }
    public void setSecretBloodFirstOn(boolean v) { secretBloodFirstOn = v; }
    public SecretBloodFirst getSecretBloodFirstMode() { return secretBloodFirst; }
    public void setSecretBloodFirstMode(SecretBloodFirst v) {
        if (v != null && v != SecretBloodFirst.OFF) {
            secretBloodFirst = v;
        }
    }

    public SecretOrder getSecretOrder() { return secretOrder; }
    public void setSecretOrder(SecretOrder v) { secretOrder = v == null ? SecretOrder.LEAST_ETHERWARPS : v; }

    /** Derived from {@link #getMode()}: Secrets Then Clears for Secret Then Clear. */
    public RoomPlan getRoomPlan() { return mode == Mode.SECRET_THEN_CLEAR ? RoomPlan.SECRETS_THEN_CLEARS : RoomPlan.FASTEST_TOTAL; }
    public void setRoomPlan(RoomPlan v) { roomPlan = v == null ? RoomPlan.FASTEST_TOTAL : v; }

    public Mode getMode() { return mode; }
    public void setMode(Mode v) { mode = v == null ? Mode.SOLO_300 : v; }
    public BloodRush getBloodRush() { return bloodRush; }
    public void setBloodRush(BloodRush v) { bloodRush = v == null ? BloodRush.NONE : v; }
    public RoomBeforeBlood getRoomBeforeBlood() { return roomBeforeBlood; }
    public void setRoomBeforeBlood(RoomBeforeBlood v) { roomBeforeBlood = v == null ? RoomBeforeBlood.SAVE_FOR_LAST : v; }
    public boolean isDisableAfterBloodRush() { return disableAfterBloodRush; }
    public void setDisableAfterBloodRush(boolean v) { disableAfterBloodRush = v; }
    public boolean isClearBeforeSecret() { return clearBeforeSecret; }
    public void setClearBeforeSecret(boolean v) { clearBeforeSecret = v; }
    public boolean isClearFirst() { return clearFirst; }
    /** Clear First, or the Clear Then Secret mode: every clear room before any secrets (the planner's clears-only phase). */
    public boolean clearsFirst() { return clearFirst || mode == Mode.CLEAR_THEN_SECRET; }
    public void setClearFirst(boolean v) { clearFirst = v; }

    public boolean isSkipUnneededSecrets() { return skipUnneededSecrets; }
    public void setSkipUnneededSecrets(boolean v) { skipUnneededSecrets = v; }

    public boolean isDoPuzzles() { return doPuzzles; }
    public void setDoPuzzles(boolean v) { doPuzzles = v; }

    public boolean isStatusHud() { return statusHud; }
    public void setStatusHud(boolean v) { statusHud = v; }

    public double getKeyBaseRange() { return keyBaseRange; }
    public void setKeyBaseRange(double v) {
        if (Double.isNaN(v)) {
            return;   // Math.max/min pass NaN straight through
        }
        keyBaseRange = Math.max(MIN_KEY_BASE, Math.min(MAX_KEY_BASE, v));
    }

    /** The key pickup range these settings give (DungeonKeys.pickupRange). */
    public double keyPickupRange() {
        return com.killer560.hub.doorkeys.DungeonKeys.pickupRange(keyBaseRange);
    }
}
