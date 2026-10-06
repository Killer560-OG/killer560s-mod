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
}
