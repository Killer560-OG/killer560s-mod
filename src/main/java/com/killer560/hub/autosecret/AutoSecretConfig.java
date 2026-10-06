package com.killer560.hub.autosecret;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted Auto Secret settings - see {@link AutoSecretFeature}. Cheat build only. Whether Auto Secret is RUNNING is
 * not a setting: it is off at every start and is switched on per run, by its key or the tab's button.
 */
public final class AutoSecretConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
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
    /** Endgame: hand an uncleared room to Auto Clear instead of stopping there (off by default). */
    private boolean autoClearRooms = false;
    /** Endgame: how long to stand in an unfinished puzzle for it to be done before moving on. */
    private int puzzleWaitSeconds = 30;
    /** A route with no node begun for this long is waiting on a walk nobody will make: stopped and moved on from. */
    private int routeStallSeconds = 20;
    private boolean chatFeedback = true;

    // ---- Dungeon Autopilot (Auto Secret + Auto Clear + Auto Puzzles under one planner) ----
    /** What the autopilot optimises for. */
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

    private int autopilotKey = KeyUtil.NONE;
    private RunMode runMode = RunMode.SOLO;
    /** First push the Blood Rush Split path until the blood door is open, then the rest. */
    private boolean bloodFirst = false;
    /** Treat puzzles like rooms (only those whose Auto Puzzles auto is on). */
    private boolean doPuzzles = true;
    private boolean autopilotHud = true;

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
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.toggleKey = KeyUtil.sanitizeBind(ConfigJson.getInt(o, "toggleKey", cfg.toggleKey));
                cfg.instaClear = ConfigJson.getBool(o, "instaClear", cfg.instaClear);
                cfg.iceFillFirst = ConfigJson.getBool(o, "iceFillFirst", cfg.iceFillFirst);
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
                cfg.doPuzzles = ConfigJson.getBool(o, "doPuzzles", cfg.doPuzzles);
                cfg.autopilotHud = ConfigJson.getBool(o, "autopilotHud", cfg.autopilotHud);
            } catch (Exception e) {
                // unreadable file - keep defaults
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("toggleKey", toggleKey);
            o.addProperty("instaClear", instaClear);
            o.addProperty("iceFillFirst", iceFillFirst);
            o.addProperty("autoClearRooms", autoClearRooms);
            o.addProperty("puzzleWaitSeconds", puzzleWaitSeconds);
            o.addProperty("routeStallSeconds", routeStallSeconds);
            o.addProperty("chatFeedback", chatFeedback);
            o.addProperty("autopilotKey", autopilotKey);
            o.addProperty("runMode", runMode.name());
            o.addProperty("bloodFirst", bloodFirst);
            o.addProperty("doPuzzles", doPuzzles);
            o.addProperty("autopilotHud", autopilotHud);
            Files.writeString(CONFIG_PATH, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** The legit jar can never run it, even from a copied cheat-build config. */
    public static boolean allowed() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && com.killer560.hub.util.SkyblockGate.allows();
    }

    public int getToggleKey() { return toggleKey; }
    public void setToggleKey(int v) { toggleKey = KeyUtil.sanitizeBind(v); }

    public boolean isInstaClear() { return instaClear; }
    public void setInstaClear(boolean v) { instaClear = v; }

    public boolean isIceFillFirst() { return iceFillFirst; }
    public void setIceFillFirst(boolean v) { iceFillFirst = v; }

    public boolean isAutoClearRooms() { return autoClearRooms; }
    public void setAutoClearRooms(boolean v) { autoClearRooms = v; }

    public int getPuzzleWaitSeconds() { return puzzleWaitSeconds; }
    public void setPuzzleWaitSeconds(int v) { puzzleWaitSeconds = Math.max(MIN_PUZZLE_WAIT_S, Math.min(MAX_PUZZLE_WAIT_S, v)); }

    public int getRouteStallSeconds() { return routeStallSeconds; }
    public void setRouteStallSeconds(int v) { routeStallSeconds = Math.max(MIN_STALL_S, Math.min(MAX_STALL_S, v)); }

    public boolean isChatFeedback() { return chatFeedback; }
    public void setChatFeedback(boolean v) { chatFeedback = v; }

    public int getAutopilotKey() { return autopilotKey; }
    public void setAutopilotKey(int v) { autopilotKey = KeyUtil.sanitizeBind(v); }

    public RunMode getRunMode() { return runMode; }
    public void setRunMode(RunMode v) { runMode = v == null ? RunMode.SOLO : v; }

    public boolean isBloodFirst() { return bloodFirst; }
    public void setBloodFirst(boolean v) { bloodFirst = v; }

    public boolean isDoPuzzles() { return doPuzzles; }
    public void setDoPuzzles(boolean v) { doPuzzles = v; }

    public boolean isAutopilotHud() { return autopilotHud; }
    public void setAutopilotHud(boolean v) { autopilotHud = v; }
}
