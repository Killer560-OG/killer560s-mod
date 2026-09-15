package com.killer560.hub.fastleap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.BuildVariant;
import com.killer560.hub.dungeonclass.DungeonClass;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/**
 * Persisted Fast Leap settings (QUOI {@code AutoLeap.kt} port) - see {@link FastLeapFeature}. Real automation
 * (uses the Spirit Leap item and clicks the leap menu), so every getter is gated on
 * {@link BuildVariant#CHEAT_FEATURES_ENABLED}. Every switch defaults OFF. Saved to
 * {@code config/killer560smod-fastleap.json}.
 */
public final class FastLeapConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-fastleap.json");

    public static final int MIN_CLICK_DELAY_MS = 100;
    public static final int MAX_CLICK_DELAY_MS = 500;
    public static final int CLICK_DELAY_STEP_MS = 50;

    /** QUOI "Leap mode" (Name/Class) plus Posmsg: target = whoever last announced that position in party chat. */
    public enum TargetMode {
        NAME("Name"), CLASS("Class"), POSMSG("Posmsg");

        public final String label;

        TargetMode(String label) {
            this.label = label;
        }
    }

    /** One QUOI leap switch ("P1 leap", "P3 leap", ...), each with its "Auto" child. */
    public enum LeapGroup {
        DOOR("Door Opener"),
        P1("P1"),
        PREDEV("Predev"),
        GREEN("Green Pad"),
        YELLOW("Yellow Pad"),
        PURPLE("Purple Pad"),
        PY_HEALER("PY Healer"),
        STORM_DEATH("Storm Death"),
        P3("P3"),
        MIDDLE("Middle"),
        P4("P4"),
        RELIC("Relic");

        public final String label;

        LeapGroup(String label) {
            this.label = label;
        }
    }

    /** One configurable target (QUOI's per-leap name/class). The P3 group has four (S1-S4); DOOR has none. */
    public enum LeapTarget {
        P1(LeapGroup.P1, "Target", null, "p2, storm"),
        PREDEV(LeapGroup.PREDEV, "Target", null, "ss, simon says, predev, pre dev"),
        GREEN(LeapGroup.GREEN, "Target", null, "green"),
        YELLOW(LeapGroup.YELLOW, "Target", null, "yellow"),
        PURPLE(LeapGroup.PURPLE, "Target", null, "purple"),
        PY_HEALER(LeapGroup.PY_HEALER, "Target", null, "py, healer py"),
        STORM_DEATH(LeapGroup.STORM_DEATH, "Target", null, "p3, s1, ss"),
        S1(LeapGroup.P3, "S1", DungeonClass.HEALER, "ee2, hee2, s2"),
        S2(LeapGroup.P3, "S2", DungeonClass.ARCHER, "ee3, hee3, s3"),
        S3(LeapGroup.P3, "S3", DungeonClass.MAGE, "core, ee4, s4"),
        S4(LeapGroup.P3, "S4", DungeonClass.MAGE, "tunnel, inside core, in core"),
        MIDDLE(LeapGroup.MIDDLE, "Target", null, "mid, middle, necron's platform, platform"),
        P4(LeapGroup.P4, "Target", null, "p5"),
        RELIC(LeapGroup.RELIC, "Target", null, "relic, p5");

        public final LeapGroup group;
        public final String label;
        public final DungeonClass defaultClass;
        public final String defaultKeywords;

        LeapTarget(LeapGroup group, String label, DungeonClass defaultClass, String defaultKeywords) {
            this.group = group;
            this.label = label;
            this.defaultClass = defaultClass;
            this.defaultKeywords = defaultKeywords;
        }
    }

    private static final class GroupSettings {
        boolean enabled = false;
        boolean auto = false;
    }

    private static final class TargetSettings {
        String name = "";
        DungeonClass clazz;
        String keywords;
    }

    private static FastLeapConfig instance;

    private boolean enabled = false;
    private TargetMode targetMode = TargetMode.NAME;
    private int clickDelayMs = 250;
    private boolean blockInputs = false;
    private boolean fastMode = false;
    private boolean swapBack = false;
    private boolean disableAfterBloodOpen = false;
    private boolean onlyWhenGateBlown = false;
    private final Map<LeapGroup, GroupSettings> groups = new EnumMap<>(LeapGroup.class);
    private final Map<LeapTarget, TargetSettings> targets = new EnumMap<>(LeapTarget.class);

    private FastLeapConfig() {
        for (LeapGroup g : LeapGroup.values()) {
            groups.put(g, new GroupSettings());
        }
        for (LeapTarget t : LeapTarget.values()) {
            TargetSettings s = new TargetSettings();
            s.clazz = t.defaultClass;
            s.keywords = t.defaultKeywords;
            targets.put(t, s);
        }
    }

    public static FastLeapConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        FastLeapConfig cfg = new FastLeapConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = bool(obj, "enabled", false);
                cfg.targetMode = parseMode(str(obj, "targetMode", "NAME"));
                cfg.clickDelayMs = clampDelay(obj.has("clickDelayMs") ? obj.get("clickDelayMs").getAsInt() : 250);
                cfg.blockInputs = bool(obj, "blockInputs", false);
                cfg.fastMode = bool(obj, "fastMode", false);
                cfg.swapBack = bool(obj, "swapBack", false);
                cfg.disableAfterBloodOpen = bool(obj, "disableAfterBloodOpen", false);
                cfg.onlyWhenGateBlown = bool(obj, "onlyWhenGateBlown", false);
                if (obj.has("groups") && obj.get("groups").isJsonObject()) {
                    JsonObject g = obj.getAsJsonObject("groups");
                    for (LeapGroup group : LeapGroup.values()) {
                        if (g.has(group.name()) && g.get(group.name()).isJsonObject()) {
                            JsonObject o = g.getAsJsonObject(group.name());
                            GroupSettings s = cfg.groups.get(group);
                            s.enabled = bool(o, "enabled", false);
                            s.auto = bool(o, "auto", false);
                        }
                    }
                }
                if (obj.has("targets") && obj.get("targets").isJsonObject()) {
                    JsonObject t = obj.getAsJsonObject("targets");
                    for (LeapTarget target : LeapTarget.values()) {
                        if (t.has(target.name()) && t.get(target.name()).isJsonObject()) {
                            JsonObject o = t.getAsJsonObject(target.name());
                            TargetSettings s = cfg.targets.get(target);
                            s.name = str(o, "name", "");
                            if (o.has("class")) {
                                JsonElement c = o.get("class");
                                s.clazz = c.isJsonNull() || c.getAsString().isEmpty() ? null : DungeonClass.byName(c.getAsString());
                            }
                            s.keywords = str(o, "keywords", target.defaultKeywords);
                        }
                    }
                }
            } catch (Exception e) {
                FastLeapFeature.LOGGER.warn("[FastLeap] Failed to read {} - using defaults", CONFIG_PATH, e);
                cfg = new FastLeapConfig();
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("targetMode", targetMode.name());
            obj.addProperty("clickDelayMs", clickDelayMs);
            obj.addProperty("blockInputs", blockInputs);
            obj.addProperty("fastMode", fastMode);
            obj.addProperty("swapBack", swapBack);
            obj.addProperty("disableAfterBloodOpen", disableAfterBloodOpen);
            obj.addProperty("onlyWhenGateBlown", onlyWhenGateBlown);
            JsonObject g = new JsonObject();
            for (LeapGroup group : LeapGroup.values()) {
                GroupSettings s = groups.get(group);
                JsonObject o = new JsonObject();
                o.addProperty("enabled", s.enabled);
                o.addProperty("auto", s.auto);
                g.add(group.name(), o);
            }
            obj.add("groups", g);
            JsonObject t = new JsonObject();
            for (LeapTarget target : LeapTarget.values()) {
                TargetSettings s = targets.get(target);
                JsonObject o = new JsonObject();
                o.addProperty("name", s.name);
                o.addProperty("class", s.clazz == null ? "" : s.clazz.name());
                o.addProperty("keywords", s.keywords);
                t.add(target.name(), o);
            }
            obj.add("targets", t);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception e) {
            FastLeapFeature.LOGGER.warn("[FastLeap] Failed to save {}", CONFIG_PATH, e);
        }
    }

    private static boolean bool(JsonObject o, String key, boolean fallback) {
        try {
            return o.has(key) ? o.get(key).getAsBoolean() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String str(JsonObject o, String key, String fallback) {
        try {
            return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static TargetMode parseMode(String s) {
        for (TargetMode m : TargetMode.values()) {
            if (m.name().equalsIgnoreCase(s)) {
                return m;
            }
        }
        return TargetMode.NAME;
    }

    private static int clampDelay(int ms) {
        int stepped = Math.round(ms / (float) CLICK_DELAY_STEP_MS) * CLICK_DELAY_STEP_MS;
        return Math.max(MIN_CLICK_DELAY_MS, Math.min(MAX_CLICK_DELAY_MS, stepped));
    }

    // ---- master / general ------------------------------------------------------------------------------------

    public boolean isEnabled() {
        return BuildVariant.CHEAT_FEATURES_ENABLED && enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    /** Raw value for the settings UI toggle. */
    public boolean isEnabledSetting() {
        return BuildVariant.CHEAT_FEATURES_ENABLED && enabled;
    }

    public void setEnabled(boolean v) {
        enabled = v;
    }

    public TargetMode getTargetMode() {
        return targetMode;
    }

    public void setTargetMode(TargetMode mode) {
        targetMode = mode == null ? TargetMode.NAME : mode;
    }

    public int getClickDelayMs() {
        return clickDelayMs;
    }

    public void setClickDelayMs(int ms) {
        clickDelayMs = clampDelay(ms);
    }

    public boolean isBlockInputs() {
        return BuildVariant.CHEAT_FEATURES_ENABLED && blockInputs;
    }

    public void setBlockInputs(boolean v) {
        blockInputs = v;
    }

    public boolean isFastMode() {
        return BuildVariant.CHEAT_FEATURES_ENABLED && fastMode;
    }

    public void setFastMode(boolean v) {
        fastMode = v;
    }

    public boolean isSwapBack() {
        return BuildVariant.CHEAT_FEATURES_ENABLED && swapBack;
    }

    public void setSwapBack(boolean v) {
        swapBack = v;
    }

    public boolean isDisableAfterBloodOpen() {
        return BuildVariant.CHEAT_FEATURES_ENABLED && disableAfterBloodOpen;
    }

    public void setDisableAfterBloodOpen(boolean v) {
        disableAfterBloodOpen = v;
    }

    public boolean isOnlyWhenGateBlown() {
        return BuildVariant.CHEAT_FEATURES_ENABLED && onlyWhenGateBlown;
    }

    public void setOnlyWhenGateBlown(boolean v) {
        onlyWhenGateBlown = v;
    }

    // ---- per leap ---------------------------------------------------------------------------------------------

    public boolean isLeapEnabled(LeapGroup group) {
        return BuildVariant.CHEAT_FEATURES_ENABLED && groups.get(group).enabled;
    }

    public void setLeapEnabled(LeapGroup group, boolean v) {
        groups.get(group).enabled = v;
    }

    /** QUOI's "Auto" children only act while their parent leap switch is also on. */
    public boolean isLeapAuto(LeapGroup group) {
        return BuildVariant.CHEAT_FEATURES_ENABLED && groups.get(group).auto;
    }

    public void setLeapAuto(LeapGroup group, boolean v) {
        groups.get(group).auto = v;
    }

    public String getTargetName(LeapTarget target) {
        return targets.get(target).name;
    }

    public void setTargetName(LeapTarget target, String name) {
        targets.get(target).name = name == null ? "" : name.trim();
    }

    /** @return the configured class, or null for QUOI's "Unknown" (not set). */
    public DungeonClass getTargetClass(LeapTarget target) {
        return targets.get(target).clazz;
    }

    public void setTargetClass(LeapTarget target, DungeonClass clazz) {
        targets.get(target).clazz = clazz;
    }

    public String getTargetKeywords(LeapTarget target) {
        return targets.get(target).keywords;
    }

    public void setTargetKeywords(LeapTarget target, String keywords) {
        targets.get(target).keywords = keywords == null ? "" : keywords;
    }
}
