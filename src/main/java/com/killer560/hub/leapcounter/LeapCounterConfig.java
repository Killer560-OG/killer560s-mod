package com.killer560.hub.leapcounter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted Leap Counter settings - {@code killer560smod-leapcounter.json}. Master ships OFF. The feature getters
 * AND {@link SkyblockGate#allows()}; the tab reads {@code getEnabledRaw()} so the menu keeps showing the saved value.
 * Saved on every GUI change, reloaded by {@code ProfileManager.reloadAllConfigs()}.
 * <p>
 * Expected-leap defaults are killer560's own numbers (2026-09-16: "For S2 it should be 4, S3 it should only be 3,
 * and S4 it should be 4 again"). He gave none for S1, so S1 defaults to NoammAddons' Simon Says entry (3); Core and
 * Relic default to NoammAddons' CORE_BOX / RELIC_BOX entries (4). A count of 0 turns that section off.
 */
public final class LeapCounterConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("killer560smod-leapcounter.json");

    public static final int MAX_COUNT = 4;
    public static final float MIN_RADIUS = 1f;
    public static final float MAX_RADIUS = 8f;
    public static final float MIN_JUMP_DISTANCE = 3f;
    public static final float MAX_JUMP_DISTANCE = 20f;
    public static final String DEFAULT_ALERT_TEXT = "&aEveryone Leaped!";

    private static LeapCounterConfig instance;

    private boolean enabled = false;

    // ---- expected leaps per section (0 = don't count there) ----
    private int countS1 = 3;
    private int countS2 = 4;
    private int countS3 = 3;
    private int countS4 = 4;
    private int countCore = 4;
    private int countRelic = 4;

    // ---- detection ----
    /** How close (blocks) a teammate must land to you to count. Devonian's spots use 1-3 blocks; 3 is the loosest
     *  of its P3 entries and leaves room for a leaper being nudged off you on landing. */
    private float radius = 3f;
    /** Minimum distance (blocks) a teammate must have covered inside the last few ticks for the arrival to be a
     *  leap rather than a walk-in. Sprinting covers ~0.28 blocks/tick, a sprint-jump ~0.6, so 8 blocks over 5
     *  ticks is unreachable on foot. */
    private float jumpDistance = 8f;

    // ---- alert ----
    private boolean alert = true;
    private String alertText = DEFAULT_ALERT_TEXT;
    private boolean sound = true;

    // ---- HUD ----
    private boolean hud = true;
    /** Show "0/4 Leaped" as soon as you're on a countable section, instead of only once the first leap lands. */
    private boolean hudShowAtZero = false;

    private LeapCounterConfig() {
    }

    public static LeapCounterConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        LeapCounterConfig cfg = new LeapCounterConfig();
        JsonObject obj = null;
        if (Files.exists(CONFIG_PATH)) {
            try {
                obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (Exception e) {
                obj = null;
            }
        }
        if (obj != null) {
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.setCountS1(ConfigJson.getInt(obj, "countS1", 3));
            cfg.setCountS2(ConfigJson.getInt(obj, "countS2", 4));
            cfg.setCountS3(ConfigJson.getInt(obj, "countS3", 3));
            cfg.setCountS4(ConfigJson.getInt(obj, "countS4", 4));
            cfg.setCountCore(ConfigJson.getInt(obj, "countCore", 4));
            cfg.setCountRelic(ConfigJson.getInt(obj, "countRelic", 4));
            cfg.setRadius(ConfigJson.getFloat(obj, "radius", 3f));
            cfg.setJumpDistance(ConfigJson.getFloat(obj, "jumpDistance", 8f));
            cfg.alert = ConfigJson.getBool(obj, "alert", true);
            cfg.setAlertText(ConfigJson.getString(obj, "alertText", DEFAULT_ALERT_TEXT));
            cfg.sound = ConfigJson.getBool(obj, "sound", true);
            cfg.hud = ConfigJson.getBool(obj, "hud", true);
            cfg.hudShowAtZero = ConfigJson.getBool(obj, "hudShowAtZero", false);
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("countS1", countS1);
            obj.addProperty("countS2", countS2);
            obj.addProperty("countS3", countS3);
            obj.addProperty("countS4", countS4);
            obj.addProperty("countCore", countCore);
            obj.addProperty("countRelic", countRelic);
            obj.addProperty("radius", radius);
            obj.addProperty("jumpDistance", jumpDistance);
            obj.addProperty("alert", alert);
            obj.addProperty("alertText", alertText == null ? "" : alertText);
            obj.addProperty("sound", sound);
            obj.addProperty("hud", hud);
            obj.addProperty("hudShowAtZero", hudShowAtZero);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    // ---- master ----
    public boolean isEnabled() { return enabled && SkyblockGate.allows(); }
    public boolean getEnabledRaw() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }

    // ---- expected leaps ----
    public int getCountS1() { return countS1; }
    public void setCountS1(int v) { countS1 = clampCount(v); }

    public int getCountS2() { return countS2; }
    public void setCountS2(int v) { countS2 = clampCount(v); }

    public int getCountS3() { return countS3; }
    public void setCountS3(int v) { countS3 = clampCount(v); }

    public int getCountS4() { return countS4; }
    public void setCountS4(int v) { countS4 = clampCount(v); }

    public int getCountCore() { return countCore; }
    public void setCountCore(int v) { countCore = clampCount(v); }

    public int getCountRelic() { return countRelic; }
    public void setCountRelic(int v) { countRelic = clampCount(v); }

    /** The configured count for a section, before the party-size cap {@link LeapTracker#target()} applies. */
    public int countFor(LeapTracker.Section section) {
        if (section == null) {
            return 0;
        }
        return switch (section) {
            case S1 -> countS1;
            case S2 -> countS2;
            case S3 -> countS3;
            case S4 -> countS4;
            case CORE -> countCore;
            case RELIC -> countRelic;
        };
    }

    private static int clampCount(int v) {
        return Math.max(0, Math.min(MAX_COUNT, v));
    }

    // ---- detection ----
    public float getRadius() { return radius; }
    public void setRadius(float v) {
        radius = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, Math.round(v * 2f) / 2f));
    }

    public float getJumpDistance() { return jumpDistance; }
    public void setJumpDistance(float v) {
        jumpDistance = Math.max(MIN_JUMP_DISTANCE, Math.min(MAX_JUMP_DISTANCE, Math.round(v)));
    }

    // ---- alert ----
    public boolean isAlert() { return alert; }
    public void setAlert(boolean v) { alert = v; }

    public String getAlertText() { return alertText; }
    public void setAlertText(String v) { alertText = v == null ? "" : v; }

    public boolean isSound() { return sound; }
    public void setSound(boolean v) { sound = v; }

    // ---- HUD ----
    public boolean isHud() { return hud; }
    public void setHud(boolean v) { hud = v; }

    public boolean isHudShowAtZero() { return hudShowAtZero; }
    public void setHudShowAtZero(boolean v) { hudShowAtZero = v; }
}
