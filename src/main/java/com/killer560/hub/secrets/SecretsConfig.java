package com.killer560.hub.secrets;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Secrets settings - see {@link SecretsFeature}. Each block type has its own independent
 *  toggle per killer560's explicit request (2026-09-09) - no single master switch. Ships disabled by
 *  default, same as every other new feature added going forward per killer560's standing instruction. */
public final class SecretsConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-secrets.json");

    private static SecretsConfig instance;

    // Per killer560's explicit request (2026-09-09): a single master switch on top of every per-block
    // toggle below - when off, nothing expands regardless of the individual toggles' own state.
    private boolean masterEnabled = false;
    private boolean leversEnabled = false;
    private boolean buttonsEnabled = false;
    // false = "Flat" (quoi's real "Expanded" mode - a wider but still thin, face-hugging shape, ported
    // 1:1 from quoi's own hardcoded per-face shapes). true = "Full Box" (the entire 1x1x1 cube).
    private boolean buttonsFullBox = false;
    /** Custom button hitbox (killer560, 2026-09-21): each axis 0 = vanilla button size, 100 = a full block along it. */
    public enum Shape { FLAT, FULL, CUSTOM }
    /** Buttons: Flat / Full / Custom. Levers, Chests, Essence: Full / Custom (they never had a Flat option). */
    private Shape buttonShape = Shape.FLAT;
    private Shape leverShape = Shape.FULL;
    private Shape chestShape = Shape.FULL;
    private Shape essenceShape = Shape.FULL;
    private int buttonWidthPct = 0, buttonHeightPct = 0, buttonLengthPct = 0;
    private int leverWidthPct = 0, leverHeightPct = 0, leverLengthPct = 0;
    private int chestWidthPct = 0, chestHeightPct = 0, chestLengthPct = 0;
    private int essenceWidthPct = 0, essenceHeightPct = 0, essenceLengthPct = 0;
    private boolean chestsEnabled = false;
    // Wither Essence, per killer560's own naming (2026-09-09) - a skull-family block (SkullBlock or
    // WallSkullBlock depending on placement) in real dungeon rooms.
    private boolean essenceEnabled = false;
    // Per killer560's explicit request (2026-09-09): restricts every block type above to only expand
    // while actually inside a real Catacombs/Master Mode run, not just anywhere on hypixel.net/p3sim.net.
    private boolean dungeonsOnly = false;
    // Per killer560's explicit request (2026-09-09): Levers and Buttons specifically (not Chests/
    // Essence) can be restricted further to ONLY the F7/M7 boss phase - see DungeonState's own doc for
    // why (avoids interfering with precision puzzle levers elsewhere in the dungeon, the same real edge
    // case NoammAddons' own per-floor lever blacklist exists for).
    private boolean bossOnly = false;

    private SecretsConfig() {
    }

    public static SecretsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new SecretsConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            SecretsConfig cfg = new SecretsConfig();
            cfg.masterEnabled = ConfigJson.getBool(obj, "masterEnabled", false);
            cfg.leversEnabled = ConfigJson.getBool(obj, "leversEnabled", false);
            cfg.buttonsEnabled = ConfigJson.getBool(obj, "buttonsEnabled", false);
            cfg.buttonsFullBox = ConfigJson.getBool(obj, "buttonsFullBox", false);
            // Older files had buttonsFullBox (+ briefly buttonsCustomSize) instead of one shape value.
            Shape legacyButton = ConfigJson.getBool(obj, "buttonsCustomSize", false) ? Shape.CUSTOM
                    : cfg.buttonsFullBox ? Shape.FULL : Shape.FLAT;
            cfg.buttonShape = shape(obj, "buttonShape", legacyButton);
            cfg.leverShape = shape(obj, "leverShape", Shape.FULL);
            cfg.chestShape = shape(obj, "chestShape", Shape.FULL);
            cfg.essenceShape = shape(obj, "essenceShape", Shape.FULL);
            cfg.buttonWidthPct = clampPct(ConfigJson.getInt(obj, "buttonWidthPct", 0));
            cfg.buttonHeightPct = clampPct(ConfigJson.getInt(obj, "buttonHeightPct", 0));
            cfg.buttonLengthPct = clampPct(ConfigJson.getInt(obj, "buttonLengthPct", 0));
            cfg.leverWidthPct = clampPct(ConfigJson.getInt(obj, "leverWidthPct", 0));
            cfg.leverHeightPct = clampPct(ConfigJson.getInt(obj, "leverHeightPct", 0));
            cfg.leverLengthPct = clampPct(ConfigJson.getInt(obj, "leverLengthPct", 0));
            cfg.chestWidthPct = clampPct(ConfigJson.getInt(obj, "chestWidthPct", 0));
            cfg.chestHeightPct = clampPct(ConfigJson.getInt(obj, "chestHeightPct", 0));
            cfg.chestLengthPct = clampPct(ConfigJson.getInt(obj, "chestLengthPct", 0));
            cfg.essenceWidthPct = clampPct(ConfigJson.getInt(obj, "essenceWidthPct", 0));
            cfg.essenceHeightPct = clampPct(ConfigJson.getInt(obj, "essenceHeightPct", 0));
            cfg.essenceLengthPct = clampPct(ConfigJson.getInt(obj, "essenceLengthPct", 0));
            cfg.chestsEnabled = ConfigJson.getBool(obj, "chestsEnabled", false);
            cfg.essenceEnabled = ConfigJson.getBool(obj, "essenceEnabled", false);
            cfg.dungeonsOnly = ConfigJson.getBool(obj, "dungeonsOnly", false);
            cfg.bossOnly = ConfigJson.getBool(obj, "bossOnly", false);
            instance = cfg;
        } catch (Exception e) {
            instance = new SecretsConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("masterEnabled", masterEnabled);
            obj.addProperty("leversEnabled", leversEnabled);
            obj.addProperty("buttonsEnabled", buttonsEnabled);
            obj.addProperty("buttonsFullBox", buttonsFullBox);
            obj.addProperty("buttonShape", buttonShape.name());
            obj.addProperty("leverShape", leverShape.name());
            obj.addProperty("chestShape", chestShape.name());
            obj.addProperty("essenceShape", essenceShape.name());
            obj.addProperty("buttonWidthPct", buttonWidthPct);
            obj.addProperty("buttonHeightPct", buttonHeightPct);
            obj.addProperty("buttonLengthPct", buttonLengthPct);
            obj.addProperty("leverWidthPct", leverWidthPct);
            obj.addProperty("leverHeightPct", leverHeightPct);
            obj.addProperty("leverLengthPct", leverLengthPct);
            obj.addProperty("chestWidthPct", chestWidthPct);
            obj.addProperty("chestHeightPct", chestHeightPct);
            obj.addProperty("chestLengthPct", chestLengthPct);
            obj.addProperty("essenceWidthPct", essenceWidthPct);
            obj.addProperty("essenceHeightPct", essenceHeightPct);
            obj.addProperty("essenceLengthPct", essenceLengthPct);
            obj.addProperty("chestsEnabled", chestsEnabled);
            obj.addProperty("essenceEnabled", essenceEnabled);
            obj.addProperty("dungeonsOnly", dungeonsOnly);
            obj.addProperty("bossOnly", bossOnly);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** Gated on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} (2026-09-10) - per
     *  killer560's explicit "cheat variant should have hitbox's auto etable and auto terms" request,
     *  matching the exact same pattern {@link com.killer560.hub.experiments.ExperimentsConfig
     *  #isAutonomousMode} and {@link com.killer560.hub.terminals.TerminalSolverConfig
     *  #isAutoTerminalsEnabled} already use: gated HERE, the single real source every Full Block call
     *  site already checks through (see {@link com.killer560.hub.secrets.SecretsFeature}), so the legit
     *  jar can never expand hitboxes even from a copied config.json - the raw field is never what gets
     *  read. */
    public boolean isMasterEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && masterEnabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public void setMasterEnabled(boolean masterEnabled) {
        this.masterEnabled = masterEnabled;
    }

    public boolean isLeversEnabled() {
        return leversEnabled;
    }

    public void setLeversEnabled(boolean leversEnabled) {
        this.leversEnabled = leversEnabled;
    }

    public boolean isButtonsEnabled() {
        return buttonsEnabled;
    }

    public void setButtonsEnabled(boolean buttonsEnabled) {
        this.buttonsEnabled = buttonsEnabled;
    }

    private static int clampPct(int v) {
        return Math.max(0, Math.min(100, v));
    }

    private static Shape shape(JsonObject obj, String key, Shape def) {
        try {
            return obj.has(key) ? Shape.valueOf(obj.get(key).getAsString()) : def;
        } catch (RuntimeException e) {
            return def;
        }
    }

    public Shape getButtonShape() { return buttonShape; }
    public void setButtonShape(Shape v) { buttonShape = v; buttonsFullBox = v == Shape.FULL; }
    public Shape getLeverShape() { return leverShape; }
    public void setLeverShape(Shape v) { leverShape = v == Shape.FLAT ? Shape.FULL : v; }
    public Shape getChestShape() { return chestShape; }
    public void setChestShape(Shape v) { chestShape = v == Shape.FLAT ? Shape.FULL : v; }
    public Shape getEssenceShape() { return essenceShape; }
    public void setEssenceShape(Shape v) { essenceShape = v == Shape.FLAT ? Shape.FULL : v; }

    public int getButtonWidthPct() { return buttonWidthPct; }
    public void setButtonWidthPct(int v) { buttonWidthPct = clampPct(v); }
    public int getButtonHeightPct() { return buttonHeightPct; }
    public void setButtonHeightPct(int v) { buttonHeightPct = clampPct(v); }
    public int getButtonLengthPct() { return buttonLengthPct; }
    public void setButtonLengthPct(int v) { buttonLengthPct = clampPct(v); }
    public int getLeverWidthPct() { return leverWidthPct; }
    public void setLeverWidthPct(int v) { leverWidthPct = clampPct(v); }
    public int getLeverHeightPct() { return leverHeightPct; }
    public void setLeverHeightPct(int v) { leverHeightPct = clampPct(v); }
    public int getLeverLengthPct() { return leverLengthPct; }
    public void setLeverLengthPct(int v) { leverLengthPct = clampPct(v); }
    public int getChestWidthPct() { return chestWidthPct; }
    public void setChestWidthPct(int v) { chestWidthPct = clampPct(v); }
    public int getChestHeightPct() { return chestHeightPct; }
    public void setChestHeightPct(int v) { chestHeightPct = clampPct(v); }
    public int getChestLengthPct() { return chestLengthPct; }
    public void setChestLengthPct(int v) { chestLengthPct = clampPct(v); }
    public int getEssenceWidthPct() { return essenceWidthPct; }
    public void setEssenceWidthPct(int v) { essenceWidthPct = clampPct(v); }
    public int getEssenceHeightPct() { return essenceHeightPct; }
    public void setEssenceHeightPct(int v) { essenceHeightPct = clampPct(v); }
    public int getEssenceLengthPct() { return essenceLengthPct; }
    public void setEssenceLengthPct(int v) { essenceLengthPct = clampPct(v); }

    public boolean isButtonsFullBox() {
        return buttonsFullBox;
    }

    public void setButtonsFullBox(boolean buttonsFullBox) {
        this.buttonsFullBox = buttonsFullBox;
    }

    public boolean isChestsEnabled() {
        return chestsEnabled;
    }

    public void setChestsEnabled(boolean chestsEnabled) {
        this.chestsEnabled = chestsEnabled;
    }

    public boolean isEssenceEnabled() {
        return essenceEnabled;
    }

    public void setEssenceEnabled(boolean essenceEnabled) {
        this.essenceEnabled = essenceEnabled;
    }

    public boolean isDungeonsOnly() {
        return dungeonsOnly;
    }

    public void setDungeonsOnly(boolean dungeonsOnly) {
        this.dungeonsOnly = dungeonsOnly;
    }

    public boolean isBossOnly() {
        return bossOnly;
    }

    public void setBossOnly(boolean bossOnly) {
        this.bossOnly = bossOnly;
    }
}
