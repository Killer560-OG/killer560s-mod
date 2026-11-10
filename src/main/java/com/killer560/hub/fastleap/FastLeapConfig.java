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
    /** Leap Timeout (killer560, 2026-11-01: "let me customize the timeout slider for fastleap"): how long a leap waits
     *  for the menu, then for the target's head, before it gives up and hands every input back. */
    public static final int MIN_TIMEOUT_MS = 500;
    public static final int MAX_TIMEOUT_MS = 5000;
    public static final int TIMEOUT_STEP_MS = 250;
    public static final int DEFAULT_TIMEOUT_MS = 2000;

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

    /** What one leap does (killer560, 2026-11-02/03: one row per leap, Off / Fast / Auto). OFF: the leap is unused (its
     *  trigger does nothing and a left click there does nothing). FAST: his left click with a leap item in that spot leaps
     *  to the target. AUTO: the spot's trigger leaps by itself (his left click there does not). Until 2026-11-02 Fast and
     *  Auto were two independent switches; "both on" reads as Auto (see {@link #load}). */
    public enum LeapMode {
        OFF("Off"), FAST("Fast"), AUTO("Auto");

        public final String label;

        LeapMode(String label) {
            this.label = label;
        }
    }

    private static final class GroupSettings {
        LeapMode mode = LeapMode.OFF;
    }

    /** Return To: the hotbar slot an Auto leap swaps to after leaping, when it had to swap to the leap first.
     *  {@link #RETURN_PREVIOUS} is the slot held before the swap; {@link #RETURN_STAY} stays on the leap (no swap back,
     *  killer560 2026-10-13); 0-8 a fixed hotbar slot. */
    public static final int RETURN_PREVIOUS = -1;
    public static final int RETURN_STAY = -2;

    private static final class TargetSettings {
        /** Target Mode of this leap alone (killer560, 2026-11-09: "target mode should be inside of each leap option"). */
        TargetMode mode = TargetMode.PER_LEAP;
        String name = "";
        DungeonClass clazz;
        String keywords;
        /** Which of the two the Target button picked: the class (true) or the name. */
        boolean byClass = true;
        /** Per-class targets (killer560, 2026-10-27: "change who it leaps to based on what class you are playing"): the
         *  class YOU play this run -> who this leap goes to instead. A class missing from the map uses the Target above. */
        final Map<DungeonClass, ClassPick> byPlayerClass = new EnumMap<>(DungeonClass.class);
    }

    /** One per-class target: a class (the teammate who has it) or a party member's name - exactly one is set. */
    public record ClassPick(DungeonClass clazz, String name) {
        public boolean byClass() {
            return clazz != null;
        }
    }

    private static FastLeapConfig instance;

    private boolean enabled = false;
    private int clickDelayMs = 250;
    private int timeoutMs = DEFAULT_TIMEOUT_MS;
    private boolean blockInputs = false;
    private boolean fastMode = false;
    private int returnToSlot = RETURN_PREVIOUS;
    private boolean disableAfterBloodOpen = false;
    private boolean onlyWhenGateBlown = false;
    /** Keep Rotation On Leap (leaprot-1104): a leap's teleport leaves his look as it was (fastleap/LeapRotation). */
    private boolean leapTakesTargetLook = false;
    /** Targets (killer560, 2026-11-02: "you should have a toggle to use universal or to use set class"): false uses the
     *  Universal Targets page for everyone; true uses the Set Class Targets page's pick for the class you play, falling
     *  back to the Universal target for a class with none set. */
    private boolean useClassTargets = false;
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
                // The one card-wide Target Mode of older files is every leap's own mode now, until a leap saves its own.
                for (TargetSettings ts : cfg.targets.values()) {
                    ts.mode = parseMode(oldMode);
                }
                cfg.clickDelayMs = clampDelay(intOr(obj, "clickDelayMs", 250));
                cfg.timeoutMs = clampTimeout(intOr(obj, "timeoutMs", DEFAULT_TIMEOUT_MS));
                cfg.blockInputs = bool(obj, "blockInputs", false);
                cfg.fastMode = bool(obj, "fastMode", false);
                // "swapBack" (before 2026-10-13) is not carried over: an Auto leap always returns now (killer560:
                // "swap to your leap, open it, then leap to the person, then swap back to the item you want").
                cfg.returnToSlot = clampReturn(intOr(obj, "returnToSlot", RETURN_PREVIOUS));
                cfg.disableAfterBloodOpen = bool(obj, "disableAfterBloodOpen", false);
                cfg.onlyWhenGateBlown = bool(obj, "onlyWhenGateBlown", false);
                cfg.leapTakesTargetLook = bool(obj, "leapTakesTargetLook", false);
                if (obj.has("groups") && obj.get("groups").isJsonObject()) {
                    JsonObject g = obj.getAsJsonObject("groups");
                    for (LeapGroup group : LeapGroup.values()) {
                        if (g.has(group.name()) && g.get(group.name()).isJsonObject()) {
                            JsonObject o = g.getAsJsonObject(group.name());
                            GroupSettings s = cfg.groups.get(group);
                            boolean fast;
                            boolean auto;
                            if (o.has("fast")) {
                                // 2026-10-13 on: the fast and auto keys (two switches until 11-02; one mode since,
                                // saved the same way)
                                fast = bool(o, "fast", false);
                                auto = bool(o, "auto", false);
                            } else {
                                // Before 2026-10-13: one switch plus a Mode, Fast or Auto.
                                boolean on = bool(o, "enabled", false);
                                fast = on && !bool(o, "auto", false);
                                auto = on && bool(o, "auto", false);
                            }
                            // Auto (with or without Fast) -> Auto, Fast only -> Fast, both off -> Off
                            s.mode = auto ? LeapMode.AUTO : fast ? LeapMode.FAST : LeapMode.OFF;
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
                            if (o.has("mode")) {
                                s.mode = parseMode(str(o, "mode", ""));
                            }
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
                            if (o.has("classTargets") && o.get("classTargets").isJsonObject()) {
                                JsonObject ct = o.getAsJsonObject("classTargets");
                                for (DungeonClass mine : DungeonClass.values()) {
                                    if (!ct.has(mine.name()) || !ct.get(mine.name()).isJsonObject()) {
                                        continue;
                                    }
                                    JsonObject p = ct.getAsJsonObject(mine.name());
                                    DungeonClass pc = DungeonClass.byName(str(p, "class", ""));
                                    String pn = str(p, "name", "").trim();
                                    if (pc != null) {
                                        s.byPlayerClass.put(mine, new ClassPick(pc, null));
                                    } else if (!pn.isEmpty()) {
                                        s.byPlayerClass.put(mine, new ClassPick(null, pn));
                                    }
                                }
                            }
                        }
                    }
                }
                // Before 2026-11-02 a per-class target applied whenever one was set; a file without the key keeps that
                // (Set Class when any is set - with none set the two read the same).
                cfg.dropNames();
                cfg.useClassTargets = obj.has("useClassTargets") ? bool(obj, "useClassTargets", false)
                        : cfg.classTargetCount() > 0;
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
            obj.addProperty("clickDelayMs", clickDelayMs);
            obj.addProperty("timeoutMs", timeoutMs);
            obj.addProperty("blockInputs", blockInputs);
            obj.addProperty("fastMode", fastMode);
            obj.addProperty("returnToSlot", returnToSlot);
            obj.addProperty("disableAfterBloodOpen", disableAfterBloodOpen);
            obj.addProperty("onlyWhenGateBlown", onlyWhenGateBlown);
            obj.addProperty("leapTakesTargetLook", leapTakesTargetLook);
            obj.addProperty("useClassTargets", useClassTargets);
            JsonObject g = new JsonObject();
            for (LeapGroup group : LeapGroup.values()) {
                GroupSettings s = groups.get(group);
                JsonObject o = new JsonObject();
                // both keys kept so an older jar reading this file sees the same mode
                o.addProperty("fast", s.mode == LeapMode.FAST);
                o.addProperty("auto", s.mode == LeapMode.AUTO);
                g.add(group.name(), o);
            }
            obj.add("groups", g);
            JsonObject t = new JsonObject();
            for (LeapTarget target : LeapTarget.values()) {
                TargetSettings s = targets.get(target);
                JsonObject o = new JsonObject();
                o.addProperty("name", s.name);
                o.addProperty("mode", s.mode.name());
                o.addProperty("class", s.clazz == null ? "" : s.clazz.name());
                o.addProperty("keywords", s.keywords);
                o.addProperty("byClass", s.byClass);
                JsonObject ct = new JsonObject();
                for (Map.Entry<DungeonClass, ClassPick> e : s.byPlayerClass.entrySet()) {
                    JsonObject p = new JsonObject();
                    if (e.getValue().byClass()) {
                        p.addProperty("class", e.getValue().clazz().name());
                    } else {
                        p.addProperty("name", e.getValue().name());
                    }
                    ct.add(e.getKey().name(), p);
                }
                o.add("classTargets", ct);
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

    private static int clampTimeout(int ms) {
        int stepped = Math.round(ms / (float) TIMEOUT_STEP_MS) * TIMEOUT_STEP_MS;
        return Math.max(MIN_TIMEOUT_MS, Math.min(MAX_TIMEOUT_MS, stepped));
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int ms) {
        timeoutMs = clampTimeout(ms);
    }

    /** {@link #getTimeoutMs()} in client ticks, at least one. */
    public int getTimeoutTicks() {
        return Math.max(1, (timeoutMs + 49) / 50);
    }

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

    public TargetMode getTargetMode(LeapTarget target) {
        return targets.get(target).mode;
    }

    public void setTargetMode(LeapTarget target, TargetMode mode) {
        targets.get(target).mode = mode == null ? TargetMode.PER_LEAP : mode;
    }

    /** Every leap's Target Mode at once (the testkit's shortcut; the card sets one leap at a time). */
    public void setTargetMode(TargetMode mode) {
        for (LeapTarget t : LeapTarget.values()) {
            setTargetMode(t, mode);
        }
    }

    /** The first leap's Target Mode (the testkit reads the all-leaps shortcut back through it). */
    public TargetMode getTargetMode() {
        return getTargetMode(LeapTarget.values()[0]);
    }

    /** No leap goes by a player's name any more (killer560, 2026-11-09: "just class"): a saved name becomes that player's
     *  class when the party knows it, else the leap is emptied; logged once for the whole file. */
    private void dropNames() {
        int changed = 0;
        for (Map.Entry<LeapTarget, TargetSettings> e : targets.entrySet()) {
            TargetSettings s = e.getValue();
            if (!s.byClass && !s.name.isBlank()) {
                DungeonClass c = com.killer560.hub.leapmenu.PartyTracker.classOf(s.name);
                if (c != null) {
                    s.clazz = c; // else the class saved beside the name (its backup) stays
                }
                changed++;
            }
            s.byClass = true;
            s.name = "";
            for (Map.Entry<DungeonClass, ClassPick> p : new java.util.ArrayList<>(s.byPlayerClass.entrySet())) {
                if (!p.getValue().byClass()) {
                    DungeonClass c = com.killer560.hub.leapmenu.PartyTracker.classOf(p.getValue().name());
                    if (c != null) {
                        s.byPlayerClass.put(p.getKey(), new ClassPick(c, null));
                    } else {
                        s.byPlayerClass.remove(p.getKey());
                    }
                    changed++;
                }
            }
        }
        if (changed > 0) {
            FastLeapFeature.LOGGER.info("[FastLeap] {} leap target(s) saved by player name were turned into classes (or emptied)", changed);
        }
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

    public boolean isLeapTakesTargetLook() {
        return BuildVariant.CHEAT_FEATURES_ENABLED && leapTakesTargetLook;
    }

    public void setLeapTakesTargetLook(boolean v) {
        leapTakesTargetLook = v;
    }

    /** The leap's row: Off, Fast or Auto. */
    public LeapMode getLeapMode(LeapGroup group) {
        return groups.get(group).mode;
    }

    public void setLeapMode(LeapGroup group, LeapMode mode) {
        groups.get(group).mode = mode == null ? LeapMode.OFF : mode;
    }

    /** Fast: his left click with a leap item leaps to this section's target. */
    public boolean isLeapFast(LeapGroup group) {
        return BuildVariant.CHEAT_FEATURES_ENABLED && groups.get(group).mode == LeapMode.FAST;
    }

    /** true makes the leap Fast; false turns a Fast leap Off (an Auto one stays Auto). */
    public void setLeapFast(LeapGroup group, boolean v) {
        GroupSettings s = groups.get(group);
        if (v) {
            s.mode = LeapMode.FAST;
        } else if (s.mode == LeapMode.FAST) {
            s.mode = LeapMode.OFF;
        }
    }

    /** Auto: the leap fires by itself when its trigger happens (and his left click does not). */
    public boolean isLeapAuto(LeapGroup group) {
        return BuildVariant.CHEAT_FEATURES_ENABLED && groups.get(group).mode == LeapMode.AUTO;
    }

    /** true makes the leap Auto; false turns an Auto leap Off (a Fast one stays Fast). */
    public void setLeapAuto(LeapGroup group, boolean v) {
        GroupSettings s = groups.get(group);
        if (v) {
            s.mode = LeapMode.AUTO;
        } else if (s.mode == LeapMode.AUTO) {
            s.mode = LeapMode.OFF;
        }
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

    // ---- per-class targets ----------------------------------------------------------------------------------------

    /** Targets: true = Set Class (the Set Class page's pick for your class, Universal for a class with none), false =
     *  Universal for everyone. */
    public boolean isUseClassTargets() {
        return useClassTargets;
    }

    public void setUseClassTargets(boolean v) {
        useClassTargets = v;
    }

    /** Who this leap goes to while YOU play {@code mine}, or null when that class has no target of its own (the leap's
     *  Target applies). */
    public ClassPick getClassTarget(LeapTarget target, DungeonClass mine) {
        return mine == null ? null : targets.get(target).byPlayerClass.get(mine);
    }

    /** While you play {@code mine}, this leap goes to the teammate with class {@code clazz} (null clears it). */
    public void setClassTargetClass(LeapTarget target, DungeonClass mine, DungeonClass clazz) {
        if (mine == null) {
            return;
        }
        if (clazz == null) {
            targets.get(target).byPlayerClass.remove(mine);
        } else {
            targets.get(target).byPlayerClass.put(mine, new ClassPick(clazz, null));
        }
    }

    /** While you play {@code mine}, this leap goes to the party member {@code name} (blank clears it). */
    public void setClassTargetName(LeapTarget target, DungeonClass mine, String name) {
        if (mine == null) {
            return;
        }
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) {
            targets.get(target).byPlayerClass.remove(mine);
        } else {
            targets.get(target).byPlayerClass.put(mine, new ClassPick(null, n));
        }
    }

    /** Clears this leap's target for {@code mine}: the leap's Target applies again. */
    public void clearClassTarget(LeapTarget target, DungeonClass mine) {
        if (mine != null) {
            targets.get(target).byPlayerClass.remove(mine);
        }
    }

    /** How many per-class targets are set across every leap (the Class Targets button shows it). */
    public int classTargetCount() {
        int n = 0;
        for (TargetSettings s : targets.values()) {
            n += s.byPlayerClass.size();
        }
        return n;
    }
}
