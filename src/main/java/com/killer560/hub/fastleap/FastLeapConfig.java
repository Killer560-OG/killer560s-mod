package com.killer560.hub.fastleap;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.BuildVariant;
import com.killer560.hub.dungeonclass.DungeonClass;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/**
 * Persisted Fast Leap settings (QUOI {@code AutoLeap.kt} port) - see {@link FastLeapFeature}. Real automation
 * (uses the Spirit Leap item and clicks the leap menu), so every getter is gated on
 * {@link BuildVariant#CHEAT_FEATURES_ENABLED}. Every switch defaults OFF. Saved to
 * {@code config/killer560/dungeons/runs/killer560smod-fastleap.json}.
 */
public final class FastLeapConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-fastleap.json");

    public static final int MIN_CLICK_DELAY_MS = 100;
    public static final int MAX_CLICK_DELAY_MS = 500;
    public static final int CLICK_DELAY_STEP_MS = 50;

    /** How a leap picks who it goes to. PER_LEAP: each leap's own Target button (a class or a name - QUOI's Name and
     *  Class modes, chosen per leap since 2026-10-13). POSMSG: whoever last announced that position in party chat,
     *  with the leap's Target as the backup. Files from before 2026-10-13 say NAME or CLASS, read as PER_LEAP. */
    public enum TargetMode {
        PER_LEAP("Per Leap"), POSMSG("Posmsg");

        public final String label;

        TargetMode(String label) {
            this.label = label;
        }
    }

    /** One QUOI leap ("Crystal leap", "P3 leap", ...), with its own Fast switch (his left click) and Auto switch (its
     *  trigger), each on or off by itself since 2026-10-13 (killer560: "for fast leap it should be a toggle for each
     *  specific section"). The constant
     *  names are the file's keys, so P1 keeps its name though the leap is "Crystal" since 2026-10-13 (killer560:
     *  "Rename p1 leap to crystal leap") - an old file's P1 settings carry straight over. The temporary Test leap
     *  went the same day ("Remove test leap"); an old file's TEST entry is ignored. */
    public enum LeapGroup {
        DOOR("Door Opener"),
        P1("Crystal"),
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
        /** Fast: his left click with a leap item leaps to this section's target. */
        boolean fast = false;
        /** Auto: the section's trigger leaps by itself. */
        boolean auto = false;
    }

    /** Return To: the hotbar slot an Auto leap swaps to after leaping, when it had to swap to the leap first.
     *  {@link #RETURN_PREVIOUS} is the slot held before the swap; {@link #RETURN_STAY} stays on the leap (no swap back,
     *  killer560 2026-10-13); 0-8 a fixed hotbar slot. */
    public static final int RETURN_PREVIOUS = -1;
    public static final int RETURN_STAY = -2;

    private static final class TargetSettings {
        String name = "";
        DungeonClass clazz;
        String keywords;
        /** Which of the two the Target button picked: the class (true) or the name. */
        boolean byClass = true;
    }

    private static FastLeapConfig instance;

    private boolean enabled = false;
    private TargetMode targetMode = TargetMode.PER_LEAP;
    private int clickDelayMs = 250;
    private boolean blockInputs = false;
    private boolean fastMode = false;
    private int returnToSlot = RETURN_PREVIOUS;
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
                String oldMode = str(obj, "targetMode", "NAME");
                cfg.targetMode = parseMode(oldMode);
                cfg.clickDelayMs = clampDelay(intOr(obj, "clickDelayMs", 250));
                cfg.blockInputs = bool(obj, "blockInputs", false);
                cfg.fastMode = bool(obj, "fastMode", false);
                // "swapBack" (before 2026-10-13) is not carried over: an Auto leap always returns now (killer560:
                // "swap to your leap, open it, then leap to the person, then swap back to the item you want").
                cfg.returnToSlot = clampReturn(intOr(obj, "returnToSlot", RETURN_PREVIOUS));
                cfg.disableAfterBloodOpen = bool(obj, "disableAfterBloodOpen", false);
                cfg.onlyWhenGateBlown = bool(obj, "onlyWhenGateBlown", false);
                if (obj.has("groups") && obj.get("groups").isJsonObject()) {
                    JsonObject g = obj.getAsJsonObject("groups");
                    for (LeapGroup group : LeapGroup.values()) {
                        if (g.has(group.name()) && g.get(group.name()).isJsonObject()) {
                            JsonObject o = g.getAsJsonObject(group.name());
                            GroupSettings s = cfg.groups.get(group);
                            if (o.has("fast")) {
                                s.fast = bool(o, "fast", false);
                                s.auto = bool(o, "auto", false);
                            } else {
                                // Before 2026-10-13 (v2): one switch plus a Mode, Fast or Auto. Decided from the OLD
                                // keys: Mode Fast -> Fast on / Auto off, Mode Auto -> Auto on / Fast off, and a leap
                                // whose switch was off stays off (both).
                                boolean on = bool(o, "enabled", false);
                                boolean auto = bool(o, "auto", false);
                                s.fast = on && !auto;
                                s.auto = on && auto;
                            }
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
                            // Before 2026-10-13 one global mode said whether every leap went by name or by class
                            // (Posmsg's backup tried the name first). Decided from the OLD key, never from fields.
                            s.byClass = o.has("byClass") ? bool(o, "byClass", true)
                                    : "CLASS".equalsIgnoreCase(oldMode)
                                    || ("POSMSG".equalsIgnoreCase(oldMode) && s.name.isBlank());
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
            obj.addProperty("returnToSlot", returnToSlot);
            obj.addProperty("disableAfterBloodOpen", disableAfterBloodOpen);
            obj.addProperty("onlyWhenGateBlown", onlyWhenGateBlown);
            JsonObject g = new JsonObject();
            for (LeapGroup group : LeapGroup.values()) {
                GroupSettings s = groups.get(group);
                JsonObject o = new JsonObject();
                o.addProperty("fast", s.fast);
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
                o.addProperty("byClass", s.byClass);
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

    /** A number that is not one (hand-edited file) falls back alone instead of discarding every other setting. */
    private static int intOr(JsonObject o, String key, int fallback) {
        try {
            return o.has(key) ? o.get(key).getAsInt() : fallback;
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
        return "POSMSG".equalsIgnoreCase(s) ? TargetMode.POSMSG : TargetMode.PER_LEAP;
    }

    private static int clampReturn(int slot) {
        return slot == RETURN_STAY ? RETURN_STAY : slot < 0 || slot > 8 ? RETURN_PREVIOUS : slot;
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
        targetMode = mode == null ? TargetMode.PER_LEAP : mode;
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

    /** Return To: {@link #RETURN_PREVIOUS}, {@link #RETURN_STAY} or a hotbar slot 0-8. */
    public int getReturnToSlot() {
        return returnToSlot;
    }

    public void setReturnToSlot(int slot) {
        returnToSlot = clampReturn(slot);
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

    /** Fast: his left click with a leap item leaps to this section's target. Independent of Auto. */
    public boolean isLeapFast(LeapGroup group) {
        return BuildVariant.CHEAT_FEATURES_ENABLED && groups.get(group).fast;
    }

    public void setLeapFast(LeapGroup group, boolean v) {
        groups.get(group).fast = v;
    }

    /** Auto: the leap fires by itself when its trigger happens. Independent of Fast. */
    public boolean isLeapAuto(LeapGroup group) {
        return BuildVariant.CHEAT_FEATURES_ENABLED && groups.get(group).auto;
    }

    public void setLeapAuto(LeapGroup group, boolean v) {
        groups.get(group).auto = v;
    }

    /** The leap's Target button: a class (true) or a name. */
    public boolean isTargetByClass(LeapTarget target) {
        return targets.get(target).byClass;
    }

    /** Points the target at a class (the name stays, as Posmsg's second backup). */
    public void setTargetPickClass(LeapTarget target, DungeonClass clazz) {
        TargetSettings s = targets.get(target);
        s.clazz = clazz;
        s.byClass = true;
    }

    /** Points the target at a player by name. */
    public void setTargetPickName(LeapTarget target, String name) {
        TargetSettings s = targets.get(target);
        s.name = name == null ? "" : name.trim();
        s.byClass = false;
    }

    public String getTargetName(LeapTarget target) {
        return targets.get(target).name;
    }

    /** @return the configured class, or null for QUOI's "Unknown" (not set). */
    public DungeonClass getTargetClass(LeapTarget target) {
        return targets.get(target).clazz;
    }

    public String getTargetKeywords(LeapTarget target) {
        return targets.get(target).keywords;
    }

    public void setTargetKeywords(LeapTarget target, String keywords) {
        targets.get(target).keywords = keywords == null ? "" : keywords;
    }
}
