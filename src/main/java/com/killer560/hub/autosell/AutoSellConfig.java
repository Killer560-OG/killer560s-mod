package com.killer560.hub.autosell;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Persisted Auto Sell settings. Ships disabled by default, same as every other new feature in this mod - it
 * automates real clicks that can irreversibly get rid of items, so on top of the usual
 * {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} gate (see {@link #isEnabled()}) it also needs its
 * own separate opt-in per item: nothing is ever clicked unless its identity is on {@link #getSellIdentities()},
 * and the never-sell list in {@link #getNeverSellIdentities()} wins over that if an id somehow ends up on both
 * (see {@code AutoSellFeature#isSellable}).
 */
public final class AutoSellConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-autosell.json");

    /** killer560, "add an auto sell feature": no QUOI source to port a real screen title from (see
     *  {@code AutoSellFeature}'s class doc) - this is deliberately loose (matches anything with "sell" in the
     *  title) rather than a guessed exact string, and is the one thing worth tuning per-server if a screen
     *  Auto Sell should run in doesn't happen to say "sell" in its title. */
    public static final String DEFAULT_SCREEN_TITLE_PATTERN = "(?i).*sell.*";

    private static AutoSellConfig instance;

    private boolean enabled = false;
    private int minDelayMs = 150;
    private int maxDelayMs = 350;
    private String screenTitlePattern = DEFAULT_SCREEN_TITLE_PATTERN;
    private final Set<String> sellIdentities = new LinkedHashSet<>();
    private final Set<String> neverSellIdentities = new LinkedHashSet<>();

    private AutoSellConfig() {
    }

    public static AutoSellConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new AutoSellConfig();
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            AutoSellConfig cfg = new AutoSellConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
            cfg.minDelayMs = ConfigJson.getInt(obj, "minDelayMs", cfg.minDelayMs);
            cfg.maxDelayMs = ConfigJson.getInt(obj, "maxDelayMs", cfg.maxDelayMs);
            cfg.screenTitlePattern = validPatternOrDefault(ConfigJson.getString(obj, "screenTitlePattern", cfg.screenTitlePattern));
            readIdentities(obj, "sellIdentities", cfg.sellIdentities);
            readIdentities(obj, "neverSellIdentities", cfg.neverSellIdentities);
            instance = cfg;
        } catch (Exception e) {
            instance = new AutoSellConfig();
        }
    }

    private static void readIdentities(JsonObject obj, String key, Set<String> into) {
        JsonArray arr = ConfigJson.getArray(obj, key);
        if (arr == null) {
            return;
        }
        for (var el : arr) {
            try {
                String s = el.getAsString();
                if (s != null && !s.isBlank()) {
                    into.add(s.trim().toUpperCase(Locale.ROOT));
                }
            } catch (Exception ignored) {
            }
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("minDelayMs", minDelayMs);
            obj.addProperty("maxDelayMs", maxDelayMs);
            obj.addProperty("screenTitlePattern", screenTitlePattern);
            JsonArray sell = new JsonArray();
            sellIdentities.forEach(sell::add);
            obj.add("sellIdentities", sell);
            JsonArray never = new JsonArray();
            neverSellIdentities.forEach(never::add);
            obj.add("neverSellIdentities", never);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** Gated on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED}: it clicks items away for you,
     *  same category as Auto Croesus - and can destroy value, so this is checked on top of the per-item lists,
     *  never instead of them. */
    public boolean isEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMinDelayMs() {
        return minDelayMs;
    }

    public int getMaxDelayMs() {
        return maxDelayMs;
    }

    public void setMinDelayMs(int v) {
        this.minDelayMs = Math.max(0, v);
    }

    public void setMaxDelayMs(int v) {
        this.maxDelayMs = Math.max(0, v);
    }

    public String getScreenTitlePattern() {
        return screenTitlePattern;
    }

    /** @return the error, or null on success - refuses a regex that fails to compile so a typo can't quietly
     *  turn into "no screen ever matches" or a crash on the next check. */
    public String setScreenTitlePattern(String pattern) {
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            return "Bad pattern: " + e.getMessage();
        }
        this.screenTitlePattern = pattern;
        return null;
    }

    private static String validPatternOrDefault(String pattern) {
        try {
            Pattern.compile(pattern);
            return pattern;
        } catch (Exception e) {
            return DEFAULT_SCREEN_TITLE_PATTERN;
        }
    }

    /** Unmodifiable view - what Auto Sell WILL click, subject to {@link #getNeverSellIdentities()}. */
    public Set<String> getSellIdentities() {
        return java.util.Collections.unmodifiableSet(sellIdentities);
    }

    /** Unmodifiable view - what Auto Sell will NEVER click, even if it's also on the sell list. */
    public Set<String> getNeverSellIdentities() {
        return java.util.Collections.unmodifiableSet(neverSellIdentities);
    }

    public boolean addSellIdentity(String identity) {
        return identity != null && !identity.isBlank() && sellIdentities.add(identity.trim().toUpperCase(Locale.ROOT));
    }

    public boolean removeSellIdentity(String identity) {
        return identity != null && sellIdentities.remove(identity.trim().toUpperCase(Locale.ROOT));
    }

    public boolean addNeverSellIdentity(String identity) {
        return identity != null && !identity.isBlank() && neverSellIdentities.add(identity.trim().toUpperCase(Locale.ROOT));
    }

    public boolean removeNeverSellIdentity(String identity) {
        return identity != null && neverSellIdentities.remove(identity.trim().toUpperCase(Locale.ROOT));
    }
}
