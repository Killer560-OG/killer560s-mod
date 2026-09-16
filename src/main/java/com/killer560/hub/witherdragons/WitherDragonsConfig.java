package com.killer560.hub.witherdragons;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

        public TimerStyle next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    public enum TitleMode {
        PRIORITY("Your Priority"), EVERY("Every Dragon");
        public final String label;

        TitleMode(String label) {
            this.label = label;
        }

        public TitleMode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    public enum NameStyle {
        COLOUR("Colour (Red)"), TYPE("Type (Power)");
        public final String label;

        NameStyle(String label) {
            this.label = label;
        }

        public NameStyle next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /** Odin "Purple Solo Debuff": the class that solo-debuffs purple; the other one helps Berserk/Mage. */
    public enum SoloDebuff {
        TANK("Tank"), HEALER("Healer");
        public final String label;

        SoloDebuff(String label) {
            this.label = label;
        }

        public SoloDebuff next() {
            return values()[(ordinal() + 1) % values().length];
        }
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

        public ClassOverride next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-witherdragons.json");

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
    private float normalPower = 0f;
    private float easyPower = 0f;
    private SoloDebuff soloDebuff = SoloDebuff.TANK;
    private boolean soloDebuffOnAll = false;
    private boolean paulBuff = false;
    private ClassOverride classOverride = ClassOverride.AUTO;

    // ---- King Relics (Odin KingRelics.kt + NoammAddons M7Relics.kt) ----
    private boolean relicsEnabled = false;
    private boolean relicSpawnTimer = true;
    private int relicSpawnTicks = 38;
    private boolean relicHighlight = true;
    private boolean relicTracer = false;
    private boolean relicPlaceTime = true;
    private boolean relicSummary = true;

    private WitherDragonsConfig() {
    }

    public static WitherDragonsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        WitherDragonsConfig cfg = new WitherDragonsConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
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
                cfg.normalPower = clampPower(ConfigJson.getFloat(o, "normalPower", 0f));
                cfg.easyPower = clampPower(ConfigJson.getFloat(o, "easyPower", 0f));
                cfg.soloDebuff = ConfigJson.getEnum(o, "soloDebuff", SoloDebuff.class, SoloDebuff.TANK);
                cfg.soloDebuffOnAll = ConfigJson.getBool(o, "soloDebuffOnAll", false);
                cfg.paulBuff = ConfigJson.getBool(o, "paulBuff", false);
                cfg.classOverride = ConfigJson.getEnum(o, "classOverride", ClassOverride.class, ClassOverride.AUTO);
                cfg.relicsEnabled = ConfigJson.getBool(o, "relicsEnabled", false);
                cfg.relicSpawnTimer = ConfigJson.getBool(o, "relicSpawnTimer", true);
                cfg.relicSpawnTicks = Math.max(0, Math.min(100, ConfigJson.getInt(o, "relicSpawnTicks", 38)));
                cfg.relicHighlight = ConfigJson.getBool(o, "relicHighlight", true);
                cfg.relicTracer = ConfigJson.getBool(o, "relicTracer", false);
                cfg.relicPlaceTime = ConfigJson.getBool(o, "relicPlaceTime", true);
                cfg.relicSummary = ConfigJson.getBool(o, "relicSummary", true);
            } catch (Exception ignored) {
                // unreadable file - defaults (ConfigJson already covers per-key malformed values)
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
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
            o.addProperty("normalPower", normalPower);
            o.addProperty("easyPower", easyPower);
            o.addProperty("soloDebuff", soloDebuff.name());
            o.addProperty("soloDebuffOnAll", soloDebuffOnAll);
            o.addProperty("paulBuff", paulBuff);
            o.addProperty("classOverride", classOverride.name());
            o.addProperty("relicsEnabled", relicsEnabled);
            o.addProperty("relicSpawnTimer", relicSpawnTimer);
            o.addProperty("relicSpawnTicks", relicSpawnTicks);
            o.addProperty("relicHighlight", relicHighlight);
            o.addProperty("relicTracer", relicTracer);
            o.addProperty("relicPlaceTime", relicPlaceTime);
            o.addProperty("relicSummary", relicSummary);
            Files.writeString(CONFIG_PATH, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static float clampPower(float v) {
        return Math.max(0f, Math.min(32f, Math.round(v * 2f) / 2f));
    }

    // ---- masters (Skyblock Only gate) ----

    public boolean isEnabled() {
        return enabled && SkyblockGate.allows();
    }

    public void setEnabled(boolean v) {
        enabled = v;
    }

    public boolean isRelicsEnabled() {
        return relicsEnabled && SkyblockGate.allows();
    }

    public void setRelicsEnabled(boolean v) {
        relicsEnabled = v;
    }

    // ---- dragons ----

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
    public float getNormalPower() { return normalPower; }
    public void setNormalPower(float v) { normalPower = clampPower(v); }
    public float getEasyPower() { return easyPower; }
    public void setEasyPower(float v) { easyPower = clampPower(v); }
    public SoloDebuff getSoloDebuff() { return soloDebuff; }
    public void setSoloDebuff(SoloDebuff v) { soloDebuff = v == null ? SoloDebuff.TANK : v; }
    public boolean isSoloDebuffOnAll() { return soloDebuffOnAll; }
    public void setSoloDebuffOnAll(boolean v) { soloDebuffOnAll = v; }
    public boolean isPaulBuff() { return paulBuff; }
    public void setPaulBuff(boolean v) { paulBuff = v; }
    public ClassOverride getClassOverride() { return classOverride; }
    public void setClassOverride(ClassOverride v) { classOverride = v == null ? ClassOverride.AUTO : v; }

    // ---- relics ----

    public boolean isRelicSpawnTimer() { return relicSpawnTimer; }
    public void setRelicSpawnTimer(boolean v) { relicSpawnTimer = v; }
    public int getRelicSpawnTicks() { return relicSpawnTicks; }
    public void setRelicSpawnTicks(int v) { relicSpawnTicks = Math.max(0, Math.min(100, v)); }
    public boolean isRelicHighlight() { return relicHighlight; }
    public void setRelicHighlight(boolean v) { relicHighlight = v; }
    public boolean isRelicTracer() { return relicTracer; }
    public void setRelicTracer(boolean v) { relicTracer = v; }
    public boolean isRelicPlaceTime() { return relicPlaceTime; }
    public void setRelicPlaceTime(boolean v) { relicPlaceTime = v; }
    public boolean isRelicSummary() { return relicSummary; }
    public void setRelicSummary(boolean v) { relicSummary = v; }
}
