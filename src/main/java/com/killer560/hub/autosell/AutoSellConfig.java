package com.killer560.hub.autosell;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.KeyUtil;

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
 * (see {@code AutoSellVisit#sellable}).
 */
public final class AutoSellConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-autosell.json");

    /** killer560, "add an auto sell feature": no QUOI source to port a real screen title from (see
     *  {@code AutoSellFeature}'s class doc) - this is deliberately loose (matches anything with "sell" in the
     *  title) rather than a guessed exact string, and is the one thing worth tuning per-server if a screen
     *  Auto Sell should run in doesn't happen to say "sell" in its title. */
    public static final String DEFAULT_SCREEN_TITLE_PATTERN = "(?i).*(sell|trade).*";

    private static AutoSellConfig instance;

    private boolean enabled = false;
    /** Random wait between two sell clicks, in client ticks: a uniform pick from {@link #minTicks} to {@link #maxTicks}
     *  (killer560, 2026-10-18: "a random ticks between selling from 0 to 10"). Both clamped to 0..{@link #MAX_TICKS}. */
    public static final int MAX_TICKS = 10;
    /** Never 0: a sell every tick is flagged by GrimAC (killer560, 2026-10-19). */
    public static final int MIN_TICKS = 1;
    private int minTicks = 1;
    private int maxTicks = 1;
    private String screenTitlePattern = DEFAULT_SCREEN_TITLE_PATTERN;
    private final Set<String> sellIdentities = new LinkedHashSet<>();
    private final Set<String> neverSellIdentities = new LinkedHashSet<>();
    /** Key that adds or removes the hovered inventory item on the sell list (killer560, 2026-10-18). {@code KeyUtil.NONE} = unset. */
    private int toggleKeyCode = KeyUtil.NONE;
    /** Sell in any Hypixel NPC shop, not only screens the title pattern matches (killer560, 2026-11-01). */
    private boolean anyNpcMenu = true;
    /** Bumped on every change to either list (not saved): lets Auto Sell run again on a screen it already finished
     *  on when an item is added there (killer560, 2026-10-20). */
    private static int listVersion = 0;

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
            // A file from before the tick setting has minDelayMs/maxDelayMs (150-350 ms); those are not carried over.
            cfg.setMinTicks(ConfigJson.getInt(obj, "minTicks", cfg.minTicks));
            cfg.setMaxTicks(ConfigJson.getInt(obj, "maxTicks", cfg.maxTicks));
            // killer560, 2026-10-19: the old default "(?i).*sell.*" is saved in files from before "trade" was added, and it
            // refused the /trades screen. A saved copy of the old default moves to the new one; any other pattern is kept.
            String savedPattern = ConfigJson.getString(obj, "screenTitlePattern", cfg.screenTitlePattern);
            if ("(?i).*sell.*".equals(savedPattern)) {
                savedPattern = DEFAULT_SCREEN_TITLE_PATTERN;
            }
            cfg.screenTitlePattern = validPatternOrDefault(savedPattern);
            readIdentities(obj, "sellIdentities", cfg.sellIdentities);
            readIdentities(obj, "neverSellIdentities", cfg.neverSellIdentities);
            cfg.toggleKeyCode = KeyUtil.sanitizeBind(ConfigJson.getInt(obj, "toggleKeyCode", cfg.toggleKeyCode));
            cfg.anyNpcMenu = ConfigJson.getBool(obj, "anyNpcMenu", true);
            instance = cfg;
            listVersion++;
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
            obj.addProperty("minTicks", minTicks);
            obj.addProperty("maxTicks", maxTicks);
            obj.addProperty("screenTitlePattern", screenTitlePattern);
            JsonArray sell = new JsonArray();
            sellIdentities.forEach(sell::add);
            obj.add("sellIdentities", sell);
            JsonArray never = new JsonArray();
            neverSellIdentities.forEach(never::add);
            obj.add("neverSellIdentities", never);
            obj.addProperty("toggleKeyCode", toggleKeyCode);
            obj.addProperty("anyNpcMenu", anyNpcMenu);
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

    public int getMinTicks() {
        return minTicks;
    }

    public int getMaxTicks() {
        return maxTicks;
    }

    /** Clamped to {@link #MIN_TICKS}..{@link #MAX_TICKS}; the max is pulled up to it if it would fall below. */
    public void setMinTicks(int v) {
        this.minTicks = Math.max(MIN_TICKS, Math.min(MAX_TICKS, v));
        if (maxTicks < minTicks) {
            maxTicks = minTicks;
        }
    }

    /** Clamped to {@link #MIN_TICKS}..{@link #MAX_TICKS}; the min is pulled down to it if it would rise above. */
    public void setMaxTicks(int v) {
        this.maxTicks = Math.max(MIN_TICKS, Math.min(MAX_TICKS, v));
        if (minTicks > maxTicks) {
            minTicks = maxTicks;
        }
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

    /**
     * Add Defaults (killer560, 2026-11-01): the dungeon junk his own Personal Deletor holds, as he listed it, each once.
     * Skyblock ids from Hypixel's items resource; "a set" is all four armor pieces, the Skeleton Master set without its
     * chestplate. The Healing VIII Splash Potion has no id of its own (Hypixel potions share POTION), so it goes by its
     * name - see {@code AutoSellFeature.identityOf}.
     */
    public static final java.util.List<String> DEFAULT_SELL_IDENTITIES = java.util.List.of(
            "SIGN",
            "LEVER",
            "EARTH_SHARD",
            "SILENT_DEATH",
            "ZOMBIE_LORD_HELMET",
            "ZOMBIE_LORD_CHESTPLATE",
            "ZOMBIE_LORD_LEGGINGS",
            "ZOMBIE_LORD_BOOTS",
            "SUPER_HEAVY_HELMET",
            "SUPER_HEAVY_CHESTPLATE",
            "SUPER_HEAVY_LEGGINGS",
            "SUPER_HEAVY_BOOTS",
            "ZOMBIE_KNIGHT_HELMET",
            "ZOMBIE_KNIGHT_CHESTPLATE",
            "ZOMBIE_KNIGHT_LEGGINGS",
            "ZOMBIE_KNIGHT_BOOTS",
            "ZOMBIE_SOLDIER_HELMET",
            "ZOMBIE_SOLDIER_CHESTPLATE",
            "ZOMBIE_SOLDIER_LEGGINGS",
            "ZOMBIE_SOLDIER_BOOTS",
            "BOUNCY_HELMET",
            "BOUNCY_CHESTPLATE",
            "BOUNCY_LEGGINGS",
            "BOUNCY_BOOTS",
            "SKELETOR_HELMET",
            "SKELETOR_CHESTPLATE",
            "SKELETOR_LEGGINGS",
            "SKELETOR_BOOTS",
            "SKELETON_GRUNT_HELMET",
            "SKELETON_GRUNT_CHESTPLATE",
            "SKELETON_GRUNT_LEGGINGS",
            "SKELETON_GRUNT_BOOTS",
            "HEAVY_HELMET",
            "HEAVY_CHESTPLATE",
            "HEAVY_LEGGINGS",
            "HEAVY_BOOTS",
            "ROTTEN_HELMET",
            "ROTTEN_CHESTPLATE",
            "ROTTEN_LEGGINGS",
            "ROTTEN_BOOTS",
            "SKELETON_LORD_HELMET",
            "SKELETON_LORD_CHESTPLATE",
            "SKELETON_LORD_LEGGINGS",
            "SKELETON_LORD_BOOTS",
            "ZOMBIE_COMMANDER_HELMET",
            "ZOMBIE_COMMANDER_CHESTPLATE",
            "ZOMBIE_COMMANDER_LEGGINGS",
            "ZOMBIE_COMMANDER_BOOTS",
            "SKELETON_SOLDIER_HELMET",
            "SKELETON_SOLDIER_CHESTPLATE",
            "SKELETON_SOLDIER_LEGGINGS",
            "SKELETON_SOLDIER_BOOTS",
            "SKELETON_MASTER_HELMET",
            "SKELETON_MASTER_LEGGINGS",
            "SKELETON_MASTER_BOOTS",
            "VITAMIN_DEATH",
            "MACHINE_GUN_BOW",
            "CRYPT_BOW",
            "PREMIUM_FLESH",
            "CANDYCOMB",
            "TRAINING_WEIGHTS",
            "DEFUSE_KIT",
            "TRIPWIRE_HOOK",
            "STONE_BUTTON",
            "HEALING_VIII_SPLASH_POTION"
    );

    /** Adds every {@link #DEFAULT_SELL_IDENTITIES} entry not on the sell list yet; returns how many were new. Never touches
     *  the never-sell list, which still wins. */
    public int addDefaults() {
        int added = 0;
        for (String id : DEFAULT_SELL_IDENTITIES) {
            if (addSellIdentity(id)) {
                added++;
            }
        }
        return added;
    }

    public boolean isAnyNpcMenu() {
        return anyNpcMenu;
    }

    public void setAnyNpcMenu(boolean v) {
        anyNpcMenu = v;
    }

    public boolean addSellIdentity(String identity) {
        return bump(identity != null && !identity.isBlank() && sellIdentities.add(identity.trim().toUpperCase(Locale.ROOT)));
    }

    public boolean removeSellIdentity(String identity) {
        return bump(identity != null && sellIdentities.remove(identity.trim().toUpperCase(Locale.ROOT)));
    }

    public boolean addNeverSellIdentity(String identity) {
        return bump(identity != null && !identity.isBlank() && neverSellIdentities.add(identity.trim().toUpperCase(Locale.ROOT)));
    }

    public boolean removeNeverSellIdentity(String identity) {
        return bump(identity != null && neverSellIdentities.remove(identity.trim().toUpperCase(Locale.ROOT)));
    }

    private boolean bump(boolean changed) {
        if (changed) {
            listVersion++;
        }
        return changed;
    }

    /** Changes whenever either list changes in this session. */
    public int getListVersion() {
        return listVersion;
    }

    public int getToggleKeyCode() {
        return toggleKeyCode;
    }

    public void setToggleKeyCode(int code) {
        this.toggleKeyCode = KeyUtil.sanitizeBind(code);
    }
}
