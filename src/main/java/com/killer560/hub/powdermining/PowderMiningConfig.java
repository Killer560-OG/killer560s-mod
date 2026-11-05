package com.killer560.hub.powdermining;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.BuildVariant;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;
import com.killer560.hub.util.SkyblockGate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Settings for the Crystal Hollows powder grinding features (killer560, 2026-11-03: "implement everything you think i need
 * from all those other mods to gemstone powder grind ... no macros"): Treasure Chest Highlight, Treasure Chest Alert,
 * Lockpick Helper, Powder Tracker, 2x Powder Alert and Chest Loot Compact. One file,
 * {@code killer560smod-powdermining.json} (folder skyblock/miningqol).
 *
 * <p>The {@code is...} getters a feature reads are the gated ones: OFF outside Skyblock, and the cheat-only switches are
 * false in a legit jar whatever a copied file says. The {@code ...Raw} getters are the switches as saved (the menu).
 */
public final class PowderMiningConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-powdermining.json");

    public static final int DEFAULT_CHEST_COLOR = 0xFFFFAA00;
    public static final int DEFAULT_LOCKPICK_COLOR = 0xFF55FF55;

    private static PowderMiningConfig instance;

    private boolean treasureChest = false;
    private int treasureChestColor = DEFAULT_CHEST_COLOR;
    private boolean treasureChestTimer = true;
    private boolean treasureChestFade = true;
    private boolean treasureChestEsp = false;
    private boolean treasureChestHideOpened = false;
    private boolean treasureChestTracer = false;
    private TracerMode treasureChestTracerMode = TracerMode.CHAIN;
    private boolean treasureChestCountdown = true;

    private boolean chestAlert = false;
    private boolean chestAlertSound = true;
    private boolean chestAlertTitle = false;
    private boolean breakSound = false;
    private boolean breakSoundMute = true;
    private com.killer560.hub.dungeonalerts.SecretSound.SoundChoice breakSoundChoice =
            com.killer560.hub.dungeonalerts.SecretSound.SoundChoice.PLING;
    private int breakSoundVolumeTenths = 10;
    private int breakSoundPitchTenths = 10;

    private boolean lockpickHelper = false;
    private int lockpickColor = DEFAULT_LOCKPICK_COLOR;

    private boolean powderTracker = false;
    private boolean powderTrackerMithril = true;
    private boolean powderTrackerItems = true;

    private boolean doublePowder = false;
    private boolean doublePowderSound = true;

    private boolean lootCompact = false;
    private boolean hideTreasureUncovered = true;
    /** Chest Loot Compact leaves a rare reward's own line in chat (staging-mining-3's Powder Chest Chat Filter). */
    private boolean keepRareRewards = true;
    private LootShow lootShow = LootShow.ALL;

    /** What Chest Loot Compact shows (killer560, 2026-11-03): left click forward, right click back, wrapping. */
    public enum LootShow {
        ALL("All"), POWDER_ONLY("Powder Only"), POWDER_RARE("Powder + Rare"), HIDE_ALL("Hide All");

        private final String label;

        LootShow(String label) {
            this.label = label;
        }

        public String label() { return label; }

        static LootShow byName(String n) {
            for (LootShow v : values()) {
                if (v.name().equals(n)) return v;
            }
            return ALL;
        }
    }

    public LootShow getLootShow() { return lootShow; }

    public void stepLootShow(int dir) {
        LootShow[] all = LootShow.values();
        lootShow = all[Math.floorMod(lootShow.ordinal() + dir, all.length)];
    }

    private PowderMiningConfig() {
    }

    public static PowderMiningConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        PowderMiningConfig cfg = new PowderMiningConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                cfg.treasureChest = ConfigJson.getBool(o, "treasureChest", cfg.treasureChest);
                cfg.treasureChestColor = intOr(o, "treasureChestColor", cfg.treasureChestColor);
                cfg.treasureChestTimer = ConfigJson.getBool(o, "treasureChestTimer", cfg.treasureChestTimer);
                cfg.treasureChestFade = ConfigJson.getBool(o, "treasureChestFade", cfg.treasureChestFade);
                cfg.treasureChestEsp = ConfigJson.getBool(o, "treasureChestEsp", cfg.treasureChestEsp);
                cfg.treasureChestHideOpened = ConfigJson.getBool(o, "treasureChestHideOpened", cfg.treasureChestHideOpened);
                cfg.treasureChestTracer = ConfigJson.getBool(o, "treasureChestTracer", cfg.treasureChestTracer);
                // treasureChestTracerColor (before chestfx-1104) is ignored: each line takes its chest's box color.
                cfg.treasureChestTracerMode = TracerMode.byName(o.has("treasureChestTracerMode")
                        && o.get("treasureChestTracerMode").isJsonPrimitive() ? o.get("treasureChestTracerMode").getAsString() : null);
                cfg.treasureChestCountdown = ConfigJson.getBool(o, "treasureChestCountdown", cfg.treasureChestCountdown);
                cfg.chestAlert = ConfigJson.getBool(o, "chestAlert", cfg.chestAlert);
                cfg.chestAlertSound = ConfigJson.getBool(o, "chestAlertSound", cfg.chestAlertSound);
                cfg.chestAlertTitle = ConfigJson.getBool(o, "chestAlertTitle", cfg.chestAlertTitle);
                cfg.breakSound = ConfigJson.getBool(o, "breakSound", cfg.breakSound);
                cfg.breakSoundMute = ConfigJson.getBool(o, "breakSoundMute", cfg.breakSoundMute);
                cfg.breakSoundChoice = com.killer560.hub.dungeonalerts.SecretSound.SoundChoice.byName(
                        ConfigJson.getString(o, "breakSoundChoice", cfg.breakSoundChoice.name()));
                cfg.breakSoundVolumeTenths = Math.max(1, Math.min(20,
                        ConfigJson.getInt(o, "breakSoundVolumeTenths", cfg.breakSoundVolumeTenths)));
                cfg.breakSoundPitchTenths = Math.max(5, Math.min(20,
                        ConfigJson.getInt(o, "breakSoundPitchTenths", cfg.breakSoundPitchTenths)));
                cfg.lockpickHelper = ConfigJson.getBool(o, "lockpickHelper", cfg.lockpickHelper);
                cfg.lockpickColor = intOr(o, "lockpickColor", cfg.lockpickColor);
                cfg.powderTracker = ConfigJson.getBool(o, "powderTracker", cfg.powderTracker);
                cfg.powderTrackerMithril = ConfigJson.getBool(o, "powderTrackerMithril", cfg.powderTrackerMithril);
                cfg.powderTrackerItems = ConfigJson.getBool(o, "powderTrackerItems", cfg.powderTrackerItems);
                cfg.doublePowder = ConfigJson.getBool(o, "doublePowder", cfg.doublePowder);
                cfg.doublePowderSound = ConfigJson.getBool(o, "doublePowderSound", cfg.doublePowderSound);
                cfg.lootCompact = ConfigJson.getBool(o, "lootCompact", cfg.lootCompact);
                cfg.hideTreasureUncovered = ConfigJson.getBool(o, "hideTreasureUncovered", cfg.hideTreasureUncovered);
                cfg.keepRareRewards = ConfigJson.getBool(o, "keepRareRewards", cfg.keepRareRewards);
                cfg.lootShow = LootShow.byName(o.has("lootShow") && o.get("lootShow").isJsonPrimitive()
                        ? o.get("lootShow").getAsString() : null);
            } catch (Exception ignored) {
                // a broken file keeps the defaults
            }
        }
        instance = cfg;
    }

    private static int intOr(JsonObject o, String key, int def) {
        try {
            return o.has(key) ? o.get(key).getAsInt() : def;
        } catch (RuntimeException e) {
            return def;
        }
    }

    public void save() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("treasureChest", treasureChest);
            o.addProperty("treasureChestColor", treasureChestColor);
            o.addProperty("treasureChestTimer", treasureChestTimer);
            o.addProperty("treasureChestFade", treasureChestFade);
            o.addProperty("treasureChestEsp", treasureChestEsp);
            o.addProperty("treasureChestHideOpened", treasureChestHideOpened);
            o.addProperty("treasureChestTracer", treasureChestTracer);
            o.addProperty("treasureChestTracerMode", treasureChestTracerMode.name());
            o.addProperty("treasureChestCountdown", treasureChestCountdown);
            o.addProperty("chestAlert", chestAlert);
            o.addProperty("chestAlertSound", chestAlertSound);
            o.addProperty("chestAlertTitle", chestAlertTitle);
            o.addProperty("breakSound", breakSound);
            o.addProperty("breakSoundMute", breakSoundMute);
            o.addProperty("breakSoundChoice", breakSoundChoice.name());
            o.addProperty("breakSoundVolumeTenths", breakSoundVolumeTenths);
            o.addProperty("breakSoundPitchTenths", breakSoundPitchTenths);
            o.addProperty("lockpickHelper", lockpickHelper);
            o.addProperty("lockpickColor", lockpickColor);
            o.addProperty("powderTracker", powderTracker);
            o.addProperty("powderTrackerMithril", powderTrackerMithril);
            o.addProperty("powderTrackerItems", powderTrackerItems);
            o.addProperty("doublePowder", doublePowder);
            o.addProperty("doublePowderSound", doublePowderSound);
            o.addProperty("lootCompact", lootCompact);
            o.addProperty("hideTreasureUncovered", hideTreasureUncovered);
            o.addProperty("keepRareRewards", keepRareRewards);
            o.addProperty("lootShow", lootShow.name());
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // a failed save keeps the in-memory values
        }
    }

    private static boolean sb() { return SkyblockGate.allows(); }

    public boolean isTreasureChest() { return treasureChest && sb(); }

    public boolean isTreasureChestRaw() { return treasureChest; }

    public void setTreasureChest(boolean v) { treasureChest = v; }

    public int getTreasureChestColor() { return treasureChestColor; }

    public void setTreasureChestColor(int v) { treasureChestColor = v; }

    public boolean isTreasureChestTimer() { return treasureChestTimer; }

    public void setTreasureChestTimer(boolean v) { treasureChestTimer = v; }

    public boolean isTreasureChestFade() { return treasureChestFade; }

    public void setTreasureChestFade(boolean v) { treasureChestFade = v; }

    /** Through walls: cheat build only. */
    public boolean isTreasureChestEsp() { return BuildVariant.CHEAT_FEATURES_ENABLED && treasureChestEsp; }

    public void setTreasureChestEsp(boolean v) { treasureChestEsp = v; }

    /** Stop drawing a claimed chest once he has clicked it (client only; the block is never changed). */
    public boolean isTreasureChestHideOpened() {
        return treasureChestHideOpened;
    }

    public void setTreasureChestHideOpened(boolean v) {
        treasureChestHideOpened = v;
    }

    public boolean isTreasureChestTracer() {
        return treasureChestTracer;
    }

    public void setTreasureChestTracer(boolean v) {
        treasureChestTracer = v;
    }

    /**
     * How the treasure chest tracer runs (killer560, 2026-11-04): Chain goes crosshair -> oldest chest -> next oldest ...;
     * From Crosshair draws one line from the crosshair to each chest.
     */
    public enum TracerMode {
        CHAIN("Chain"), FROM_CROSSHAIR("From Crosshair");

        private final String label;

        TracerMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        static TracerMode byName(String n) {
            for (TracerMode v : values()) {
                if (v.name().equals(n)) {
                    return v;
                }
            }
            return CHAIN;
        }
    }

    public TracerMode getTreasureChestTracerMode() {
        return treasureChestTracerMode;
    }

    public void stepTreasureChestTracerMode(int dir) {
        TracerMode[] all = TracerMode.values();
        treasureChestTracerMode = all[Math.floorMod(treasureChestTracerMode.ordinal() + dir, all.length)];
    }

    public void setTreasureChestTracerMode(TracerMode v) {
        treasureChestTracerMode = v == null ? TracerMode.CHAIN : v;
    }

    /** The seconds left above each chest, green to red. */
    public boolean isTreasureChestCountdown() {
        return treasureChestCountdown;
    }

    public void setTreasureChestCountdown(boolean v) {
        treasureChestCountdown = v;
    }

    // ---- Treasure Chest Alert
    public boolean isChestAlert() {
        return chestAlert && sb();
    }

    public boolean isChestAlertRaw() { return chestAlert; }

    public void setChestAlert(boolean v) { chestAlert = v; }

    // ---- Powder Break Sounds (killer560, 2026-11-03): the local player's own block breaks while powder mining
    public boolean isBreakSound() {
        return breakSound && sb();
    }

    public boolean isBreakSoundRaw() {
        return breakSound;
    }

    public void setBreakSound(boolean v) {
        breakSound = v;
    }

    /** True: Mute; false: Custom (the selector has no Normal value, the switch is Normal when OFF). */
    public boolean isBreakSoundMute() {
        return breakSoundMute;
    }

    public void setBreakSoundMute(boolean v) {
        breakSoundMute = v;
    }

    public com.killer560.hub.dungeonalerts.SecretSound.SoundChoice getBreakSoundChoice() {
        return breakSoundChoice;
    }

    public void setBreakSoundChoice(com.killer560.hub.dungeonalerts.SecretSound.SoundChoice v) {
        breakSoundChoice = v == null ? com.killer560.hub.dungeonalerts.SecretSound.SoundChoice.PLING : v;
    }

    public float getBreakSoundVolume() {
        return breakSoundVolumeTenths / 10.0f;
    }

    public int getBreakSoundVolumeTenths() {
        return breakSoundVolumeTenths;
    }

    public void stepBreakSoundVolume(int dir) {
        breakSoundVolumeTenths = com.killer560.hub.gui.SettingsButtonWidget.step(breakSoundVolumeTenths - 1, dir, 20) + 1;
    }

    public float getBreakSoundPitch() {
        return breakSoundPitchTenths / 10.0f;
    }

    public int getBreakSoundPitchTenths() {
        return breakSoundPitchTenths;
    }

    public void stepBreakSoundPitch(int dir) {
        breakSoundPitchTenths = com.killer560.hub.gui.SettingsButtonWidget.step(breakSoundPitchTenths - 5, dir, 16) + 5;
    }

    public boolean isChestAlertSound() {
        return chestAlertSound;
    }

    public void setChestAlertSound(boolean v) { chestAlertSound = v; }

    public boolean isChestAlertTitle() { return chestAlertTitle; }

    public void setChestAlertTitle(boolean v) { chestAlertTitle = v; }

    // ---- Lockpick Helper (legit, both builds; killer560, 2026-11-03)
    public boolean isLockpickHelper() { return lockpickHelper && sb(); }

    public boolean isLockpickHelperRaw() { return lockpickHelper; }

    public void setLockpickHelper(boolean v) { lockpickHelper = v; }

    public int getLockpickColor() { return lockpickColor; }

    public void setLockpickColor(int v) { lockpickColor = v; }

    public boolean isPowderTracker() { return powderTracker && sb(); }

    public boolean isPowderTrackerRaw() { return powderTracker; }

    public void setPowderTracker(boolean v) { powderTracker = v; }

    public boolean isPowderTrackerMithril() { return powderTrackerMithril; }

    public void setPowderTrackerMithril(boolean v) { powderTrackerMithril = v; }

    public boolean isPowderTrackerItems() { return powderTrackerItems; }

    public void setPowderTrackerItems(boolean v) { powderTrackerItems = v; }

    // ---- 2x Powder Alert
    public boolean isDoublePowder() { return doublePowder && sb(); }

    public boolean isDoublePowderRaw() { return doublePowder; }

    public void setDoublePowder(boolean v) { doublePowder = v; }

    public boolean isDoublePowderSound() { return doublePowderSound; }

    public void setDoublePowderSound(boolean v) { doublePowderSound = v; }

    /** Hide "You uncovered a treasure chest!" from the chat window (display time, after every reader saw it). */
    public boolean isHideTreasureUncovered() { return hideTreasureUncovered && sb(); }

    public boolean isHideTreasureUncoveredRaw() { return hideTreasureUncovered; }

    public void setHideTreasureUncovered(boolean v) { hideTreasureUncovered = v; }

    public boolean isKeepRareRewards() {
        return keepRareRewards;
    }

    public void setKeepRareRewards(boolean v) {
        keepRareRewards = v;
    }

    public boolean isLootCompact() {
        return lootCompact && sb();
    }

    public boolean isLootCompactRaw() { return lootCompact; }

    public void setLootCompact(boolean v) { lootCompact = v; }
}
