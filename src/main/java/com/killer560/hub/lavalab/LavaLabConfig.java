package com.killer560.hub.lavalab;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted settings for Lava Lab ({@code killer560smod-lavalab.json}) - see {@link LavaLabFeature} and
 * {@link LavaLabRecorder}. Ships OFF like every new feature.
 * <p>
 * This is a data-collection tool, not an automation one: killer560 wants real numbers on how lava bounces
 * behave ("you bounce once you hit lava", "you go higher looking up", "holding movement tends to make you
 * not bounce") so a movement model can be fit later. It only ever reads game state and writes a log - see
 * {@link LavaLabRecorder}'s class doc for why that keeps it safe in the legit build too.
 * <p>
 * Settings-only file; the recorded CSVs live in the separate {@code killer560smod-lavalab/} data folder
 * (same split as {@code RunSummaryConfig.json} vs {@code killer560smod-runs/} - see
 * {@code RunHistoryStore}'s class doc for why: {@code ProfileManager} snapshots/overwrites every
 * {@code killer560smod-*.json} settings file, and recorded run data must never be wiped by that.
 */
public final class LavaLabConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-lavalab.json");

    public static final int MIN_AUTO_ARM_TAIL_TICKS = 5;
    public static final int MAX_AUTO_ARM_TAIL_TICKS = 600;
    public static final int DEFAULT_AUTO_ARM_TAIL_TICKS = 60;

    private static LavaLabConfig instance;

    private boolean enabled = false;
    /** Ticks to keep recording after the last tick touching lava, so a session also captures the exit
     *  and whatever happens right after (killer560 wants entry, bounce AND exit in the same data). */
    private int autoArmTailTicks = DEFAULT_AUTO_ARM_TAIL_TICKS;

    private LavaLabConfig() {
    }

    public static LavaLabConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        LavaLabConfig cfg = new LavaLabConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(o, "enabled", cfg.enabled);
                cfg.autoArmTailTicks = clampTail(ConfigJson.getInt(o, "autoArmTailTicks", cfg.autoArmTailTicks));
            } catch (Exception ignored) {
                // Unparseable file: keep defaults for everything.
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("enabled", enabled);
            o.addProperty("autoArmTailTicks", autoArmTailTicks);
            Files.writeString(CONFIG_PATH, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static int clampTail(int v) {
        return Math.max(MIN_AUTO_ARM_TAIL_TICKS, Math.min(MAX_AUTO_ARM_TAIL_TICKS, v));
    }

    /** Read-only observation tool, so it doesn't need the cheat-build gate - only the master switch and
     *  the same "paused outside Skyblock/p3sim" gate every other feature respects. */
    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        enabled = v;
    }

    public int getAutoArmTailTicks() {
        return autoArmTailTicks;
    }

    public void setAutoArmTailTicks(int v) {
        autoArmTailTicks = clampTail(v);
    }
}
