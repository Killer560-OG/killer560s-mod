package com.killer560.hub.zpew;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Zero Ping Etherwarp settings - see {@link ZeroPingEtherwarp}. Off by default. */
public final class ZeroPingEtherwarpConfig {

    public static final int MIN_TIMEOUT_MS = 250;
    public static final int MAX_TIMEOUT_MS = 3000;
    /** Warps Per Tick (cheat build): 1 = off, up to this many etherwarps sent in one client tick per use. */
    public static final int MAX_WARPS_PER_TICK = 10;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-zpew.json");

    private static ZeroPingEtherwarpConfig instance;

    private boolean enabled = false;
    /** How long a predicted landing waits for the server's teleport before the camera goes back. */
    private int timeoutMs = 1000;
    /** A warp sent while another is still pending is predicted from that one's landing (chained warps). */
    private boolean chain = true;
    /** The Etherwarp Overlay highlights from the predicted landing while a warp is pending. */
    private boolean overlayFromPrediction = true;
    /** Warps Per Tick (cheat build only): each of your own etherwarps becomes this many in the same tick, at the same
     *  look, each from the last one's predicted landing (see {@link ZeroPingEtherwarp#flushExtraWarps}). 1 = off. */
    private int warpsPerTick = 1;
    /** Aimed Warps (cheat build only): several clicks of his inside one tick, each at the look it was clicked at, all go
     *  out in that tick - one banked Blink Charge tick per extra look (see {@link ZeroPingEtherwarp#aimedBurst}). Off. */
    private boolean aimedWarps = false;
    /** Works For: which senders' etherwarps are predicted (see {@link ZpewSource}). All on by default - before the list
     *  existed every etherwarp use was predicted, whoever sent it. Saved as {"worksFor": {"MANUAL": true, ...}}. */
    private final java.util.EnumMap<ZpewSource, Boolean> worksFor = new java.util.EnumMap<>(ZpewSource.class);

    {
        for (ZpewSource s : ZpewSource.values()) {
            worksFor.put(s, true);
        }
    }

    private ZeroPingEtherwarpConfig() {
    }

    public static ZeroPingEtherwarpConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        ZeroPingEtherwarpConfig cfg = new ZeroPingEtherwarpConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
                cfg.setTimeoutMs(ConfigJson.getInt(obj, "timeoutMs", cfg.timeoutMs));
                cfg.chain = ConfigJson.getBool(obj, "chain", cfg.chain);
                cfg.overlayFromPrediction = ConfigJson.getBool(obj, "overlayFromPrediction", cfg.overlayFromPrediction);
                cfg.setWarpsPerTick(ConfigJson.getInt(obj, "warpsPerTick", cfg.warpsPerTick));
                cfg.aimedWarps = ConfigJson.getBool(obj, "aimedWarps", cfg.aimedWarps);
                if (obj.has("worksFor") && obj.get("worksFor").isJsonObject()) {
                    JsonObject wf = obj.getAsJsonObject("worksFor");
                    for (ZpewSource s : ZpewSource.values()) {
                        cfg.worksFor.put(s, ConfigJson.getBool(wf, s.name(), true));
                    }
                }
            } catch (Exception e) {
                cfg = new ZeroPingEtherwarpConfig();
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("timeoutMs", timeoutMs);
            obj.addProperty("chain", chain);
            obj.addProperty("overlayFromPrediction", overlayFromPrediction);
            obj.addProperty("warpsPerTick", warpsPerTick);
            obj.addProperty("aimedWarps", aimedWarps);
            JsonObject wf = new JsonObject();
            for (ZpewSource s : ZpewSource.values()) {
                wf.addProperty(s.name(), worksFor.get(s));
            }
            obj.add("worksFor", wf);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = Math.max(MIN_TIMEOUT_MS, Math.min(MAX_TIMEOUT_MS, timeoutMs));
    }

    public boolean isChain() {
        return chain;
    }

    public void setChain(boolean chain) {
        this.chain = chain;
    }

    public boolean isOverlayFromPrediction() {
        return overlayFromPrediction;
    }

    public void setOverlayFromPrediction(boolean overlayFromPrediction) {
        this.overlayFromPrediction = overlayFromPrediction;
    }

    /** The saved Warps Per Tick (1..{@link #MAX_WARPS_PER_TICK}), whatever the build (the tab and the testkit read this). */
    public int getWarpsPerTick() {
        return warpsPerTick;
    }

    public void setWarpsPerTick(int warpsPerTick) {
        this.warpsPerTick = Math.max(1, Math.min(MAX_WARPS_PER_TICK, warpsPerTick));
    }

    /** The saved Aimed Warps switch, whatever the build. */
    public boolean isAimedWarps() {
        return aimedWarps;
    }

    public void setAimedWarps(boolean aimedWarps) {
        this.aimedWarps = aimedWarps;
    }

    /** Aimed Warps as it applies: never in a legit build. */
    public boolean effectiveAimedWarps() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && aimedWarps;
    }

    /** Warps Per Tick as it applies: always 1 in a legit build (nothing extra is ever sent there). */
    public int effectiveWarpsPerTick() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED ? warpsPerTick : 1;
    }

    /** Whether ZPEW predicts this sender's etherwarps: its Works For switch, and a cheat source only in a cheat build. */
    public boolean worksFor(ZpewSource source) {
        if (source.cheatOnly() && !com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return false;
        }
        return worksFor.get(source);
    }

    /** The saved switch, whatever the build (the tab and the testkit read this). */
    public boolean getWorksForSetting(ZpewSource source) {
        return worksFor.get(source);
    }

    public void setWorksFor(ZpewSource source, boolean on) {
        worksFor.put(source, on);
    }

    /** By name, for the testkit's reflection. */
    public boolean getWorksForByName(String source) {
        return getWorksForSetting(ZpewSource.valueOf(source));
    }

    public void setWorksForByName(String source, boolean on) {
        setWorksFor(ZpewSource.valueOf(source), on);
    }
}
