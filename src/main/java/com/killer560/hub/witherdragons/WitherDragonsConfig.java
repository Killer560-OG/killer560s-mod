package com.killer560.hub.witherdragons;

import com.killer560.hub.util.ModPaths;
import com.google.gson.JsonObject;
import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.SkyblockGate;

import java.nio.file.Path;

/**
 * Persisted M7 Phase 5 settings - Wither Dragons ({@link WitherDragonsFeature}) and King Relics
 * ({@link KingRelicsFeature}). Both masters ship OFF. Sub-option defaults follow Odin's own module defaults
 * (WitherDragons.kt / KingRelics.kt). Every GUI change calls {@link #save()}.
 */
public final class WitherDragonsConfig {

    public enum TimerStyle {
        MILLISECONDS("Milliseconds"), SECONDS("Seconds"), TICKS("Ticks");
        public final String label;

        TimerStyle(String label) {
            this.label = label;
        }

        public TimerStyle next() { return values()[(ordinal() + 1) % values().length]; }

        public TimerStyle previous() { return values()[(values().length + ordinal() - 1) % values().length]; }
    }

    public enum TitleMode {
        PRIORITY("Your Priority"), EVERY("Every Dragon");
        public final String label;

        TitleMode(String label) {
            this.label = label;
        }

        public TitleMode next() { return values()[(ordinal() + 1) % values().length]; }

        public TitleMode previous() { return values()[(values().length + ordinal() - 1) % values().length]; }
    }

    public enum NameStyle {
        COLOUR("Color (Red)"), TYPE("Type (Power)");
        public final String label;

        NameStyle(String label) {
            this.label = label;
        }

        public NameStyle next() { return values()[(ordinal() + 1) % values().length]; }

        public NameStyle previous() { return values()[(values().length + ordinal() - 1) % values().length]; }
    }

    /** NoammAddons "Solo Priority": the class that takes the LATER of two spawning dragons. */
    public enum SoloDebuff {
        TANK("Tank"), HEALER("Healer");
        public final String label;

        SoloDebuff(String label) {
            this.label = label;
        }

        public SoloDebuff next() { return values()[(ordinal() + 1) % values().length]; }

        public SoloDebuff previous() { return values()[(values().length + ordinal() - 1) % values().length]; }
    }

    public enum ClassOverride {
        AUTO("Auto (Tab List)", null),
        ARCHER("Archer", DungeonClass.ARCHER),
        BERSERKER("Berserker", DungeonClass.BERSERKER),
        MAGE("Mage", DungeonClass.MAGE),
        HEALER("Healer", DungeonClass.HEALER),
        TANK("Tank", DungeonClass.TANK);
        public final String label;
        public final DungeonClass clazz;

        ClassOverride(String label, DungeonClass clazz) {
            this.label = label;
            this.clazz = clazz;
        }

        public ClassOverride next() { return values()[(ordinal() + 1) % values().length]; }

        public ClassOverride previous() { return values()[(values().length + ordinal() - 1) % values().length]; }
    }

    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-witherdragons.json");

    private static WitherDragonsConfig instance;

    // ---- Wither Dragons (Odin WitherDragons.kt defaults) ----
    private boolean enabled = false;
    private boolean dragonTimer = true;
    private TimerStyle timerStyle = TimerStyle.MILLISECONDS;
    private boolean timerSymbol = true;
    private boolean dragonBoxes = true;
    private boolean dragonTracer = true;
    private boolean dragonHealth = true;
    private boolean dragonTitle = true;
    private TitleMode titleMode = TitleMode.PRIORITY;
    private NameStyle nameStyle = NameStyle.COLOUR;
    private boolean titleSound = true;
    private boolean sendSpawned = true;
    private boolean sendTime = true;
    private boolean sendSpray = true;
    private boolean sendArrows = true;
    private boolean sendConfirmation = true;
    private boolean dragonPriority = true;
    private SoloDebuff soloDebuff = SoloDebuff.TANK;
    private boolean firstDragonOnly = true;
    private ClassOverride classOverride = ClassOverride.AUTO;

    // ---- King Relics (Odin KingRelics.kt + NoammAddons M7Relics.kt) ----
    private boolean relicsEnabled = false;
    private boolean relicSpawnTimer = true;
    private int relicSpawnTicks = 38;
    private boolean relicHighlight = true;
    private boolean relicTracer = false;
    private boolean relicPlaceTime = true;
    private boolean relicSummary = true;
    /** Block Wrong Relic (NoammAddons M7Relics.kt "Block Wrong Relic"): a click on another relic's cauldron while
     *  carrying a relic is dropped before it is sent. Off by default. */
    private boolean blockWrongRelic = false;

    private WitherDragonsConfig() {
    }

    public static WitherDragonsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    /** Loads, then tells FeatureBits a gated setting may have changed (profile apply reloads every config). */
    public static void load() {
        load0();
        com.killer560.hub.util.FeatureBits.changed();
    }

    /** The stored switch alone (no Skyblock Only gate): a FeatureBits gate. */
    public boolean isEnabledRaw() { return enabled; }

    /** The stored switch alone (no Skyblock Only gate): a FeatureBits gate. */
    public boolean isRelicsEnabledRaw() { return relicsEnabled; }

    /** The stored switch alone (no Skyblock Only gate): a FeatureBits gate. */
    public boolean isRelicHighlightRaw() { return relicHighlight; }

    private static void load0() {
        WitherDragonsConfig cfg = new WitherDragonsConfig();
        JsonObject o = ConfigJson.read(CONFIG_PATH);
        if (o != null) {
            cfg.enabled = ConfigJson.getBool(o, "enabled", false);
            cfg.dragonTimer = ConfigJson.getBool(o, "dragonTimer", true);
            cfg.timerStyle = ConfigJson.getEnum(o, "timerStyle", TimerStyle.class, TimerStyle.MILLISECONDS);
            cfg.timerSymbol = ConfigJson.getBool(o, "timerSymbol", true);
            cfg.dragonBoxes = ConfigJson.getBool(o, "dragonBoxes", true);
            cfg.dragonTracer = ConfigJson.getBool(o, "dragonTracer", true);
            cfg.dragonHealth = ConfigJson.getBool(o, "dragonHealth", true);
            cfg.dragonTitle = ConfigJson.getBool(o, "dragonTitle", true);
            cfg.titleMode = ConfigJson.getEnum(o, "titleMode", TitleMode.class, TitleMode.PRIORITY);
            cfg.nameStyle = ConfigJson.getEnum(o, "nameStyle", NameStyle.class, NameStyle.COLOUR);
            cfg.titleSound = ConfigJson.getBool(o, "titleSound", true);
            cfg.sendSpawned = ConfigJson.getBool(o, "sendSpawned", true);
            cfg.sendTime = ConfigJson.getBool(o, "sendTime", true);
            cfg.sendSpray = ConfigJson.getBool(o, "sendSpray", true);
            cfg.sendArrows = ConfigJson.getBool(o, "sendArrows", true);
            cfg.sendConfirmation = ConfigJson.getBool(o, "sendConfirmation", true);
            cfg.dragonPriority = ConfigJson.getBool(o, "dragonPriority", true);
            cfg.soloDebuff = ConfigJson.getEnum(o, "soloDebuff", SoloDebuff.class, SoloDebuff.TANK);
            cfg.firstDragonOnly = ConfigJson.getBool(o, "firstDragonOnly", true);
            cfg.classOverride = ConfigJson.getEnum(o, "classOverride", ClassOverride.class, ClassOverride.AUTO);
            cfg.relicsEnabled = ConfigJson.getBool(o, "relicsEnabled", false);
            cfg.relicSpawnTimer = ConfigJson.getBool(o, "relicSpawnTimer", true);
            cfg.relicSpawnTicks = Math.max(0, Math.min(100, ConfigJson.getInt(o, "relicSpawnTicks", 38)));
            cfg.relicHighlight = ConfigJson.getBool(o, "relicHighlight", true);
            cfg.relicTracer = ConfigJson.getBool(o, "relicTracer", false);
            cfg.relicPlaceTime = ConfigJson.getBool(o, "relicPlaceTime", true);
            cfg.relicSummary = ConfigJson.getBool(o, "relicSummary", true);
            cfg.blockWrongRelic = ConfigJson.getBool(o, "blockWrongRelic", false);
        }
        instance = cfg;
    }

    public void save() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled);
        o.addProperty("dragonTimer", dragonTimer);
        o.addProperty("timerStyle", timerStyle.name());
        o.addProperty("timerSymbol", timerSymbol);
        o.addProperty("dragonBoxes", dragonBoxes);
        o.addProperty("dragonTracer", dragonTracer);
        o.addProperty("dragonHealth", dragonHealth);
        o.addProperty("dragonTitle", dragonTitle);
        o.addProperty("titleMode", titleMode.name());
        o.addProperty("nameStyle", nameStyle.name());
        o.addProperty("titleSound", titleSound);
        o.addProperty("sendSpawned", sendSpawned);
        o.addProperty("sendTime", sendTime);
        o.addProperty("sendSpray", sendSpray);
        o.addProperty("sendArrows", sendArrows);
        o.addProperty("sendConfirmation", sendConfirmation);
        o.addProperty("dragonPriority", dragonPriority);
        o.addProperty("soloDebuff", soloDebuff.name());
        o.addProperty("firstDragonOnly", firstDragonOnly);
        o.addProperty("classOverride", classOverride.name());
        o.addProperty("relicsEnabled", relicsEnabled);
        o.addProperty("relicSpawnTimer", relicSpawnTimer);
        o.addProperty("relicSpawnTicks", relicSpawnTicks);
        o.addProperty("relicHighlight", relicHighlight);
        o.addProperty("relicTracer", relicTracer);
        o.addProperty("relicPlaceTime", relicPlaceTime);
        o.addProperty("relicSummary", relicSummary);
        o.addProperty("blockWrongRelic", blockWrongRelic);
        ConfigJson.write(CONFIG_PATH, o);
    }


    // ---- masters (Skyblock Only gate) ----

    public boolean isEnabled() { return enabled && SkyblockGate.allows(); }

    public void setEnabled(boolean v) {
        enabled = v; com.killer560.hub.util.FeatureBits.changed();
    }

    public boolean isRelicsEnabled() { return relicsEnabled && SkyblockGate.allows(); }

    public void setRelicsEnabled(boolean v) {
        relicsEnabled = v; com.killer560.hub.util.FeatureBits.changed();
    }


    public boolean isDragonTimer() { return dragonTimer; }
    public void setDragonTimer(boolean v) { dragonTimer = v; }
    public TimerStyle getTimerStyle() { return timerStyle; }
    public void setTimerStyle(TimerStyle v) { timerStyle = v == null ? TimerStyle.MILLISECONDS : v; }
    public boolean isTimerSymbol() { return timerSymbol; }
    public void setTimerSymbol(boolean v) { timerSymbol = v; }
    public boolean isDragonBoxes() { return dragonBoxes; }
    public void setDragonBoxes(boolean v) { dragonBoxes = v; }
    public boolean isDragonTracer() { return dragonTracer; }
    public void setDragonTracer(boolean v) { dragonTracer = v; }
    public boolean isDragonHealth() { return dragonHealth; }
    public void setDragonHealth(boolean v) { dragonHealth = v; }
    public boolean isDragonTitle() { return dragonTitle; }
    public void setDragonTitle(boolean v) { dragonTitle = v; }
    public TitleMode getTitleMode() { return titleMode; }
    public void setTitleMode(TitleMode v) { titleMode = v == null ? TitleMode.PRIORITY : v; }
    public NameStyle getNameStyle() { return nameStyle; }
    public void setNameStyle(NameStyle v) { nameStyle = v == null ? NameStyle.COLOUR : v; }
    public boolean isTitleSound() { return titleSound; }
    public void setTitleSound(boolean v) { titleSound = v; }
    public boolean isSendSpawned() { return sendSpawned; }
    public void setSendSpawned(boolean v) { sendSpawned = v; }
    public boolean isSendTime() { return sendTime; }
    public void setSendTime(boolean v) { sendTime = v; }
    public boolean isSendSpray() { return sendSpray; }
    public void setSendSpray(boolean v) { sendSpray = v; }
    public boolean isSendArrows() { return sendArrows; }
    public void setSendArrows(boolean v) { sendArrows = v; }
    public boolean isSendConfirmation() { return sendConfirmation; }
    public void setSendConfirmation(boolean v) { sendConfirmation = v; }
    public boolean isDragonPriority() { return dragonPriority; }
    public void setDragonPriority(boolean v) { dragonPriority = v; }
    public SoloDebuff getSoloDebuff() { return soloDebuff; }
    public void setSoloDebuff(SoloDebuff v) { soloDebuff = v == null ? SoloDebuff.TANK : v; }
    public boolean isFirstDragonOnly() { return firstDragonOnly; }
    public void setFirstDragonOnly(boolean v) { firstDragonOnly = v; }
    public ClassOverride getClassOverride() { return classOverride; }
    public void setClassOverride(ClassOverride v) { classOverride = v == null ? ClassOverride.AUTO : v; }


    public boolean isRelicSpawnTimer() { return relicSpawnTimer; }
    public void setRelicSpawnTimer(boolean v) { relicSpawnTimer = v; }
    public int getRelicSpawnTicks() { return relicSpawnTicks; }
    public void setRelicSpawnTicks(int v) { relicSpawnTicks = Math.max(0, Math.min(100, v)); }
    public boolean isRelicHighlight() { return relicHighlight; }
    public void setRelicHighlight(boolean v) { relicHighlight = v; com.killer560.hub.util.FeatureBits.changed(); }
    public boolean isRelicTracer() { return relicTracer; }
    public void setRelicTracer(boolean v) { relicTracer = v; }
    public boolean isRelicPlaceTime() { return relicPlaceTime; }
    public void setRelicPlaceTime(boolean v) { relicPlaceTime = v; }
    public boolean isRelicSummary() { return relicSummary; }
    public void setRelicSummary(boolean v) { relicSummary = v; }
    public boolean isBlockWrongRelic() { return blockWrongRelic; }
    public void setBlockWrongRelic(boolean v) { blockWrongRelic = v; }
}
