package com.killer560.hub.secrets;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
            cfg.masterEnabled = obj.has("masterEnabled") && obj.get("masterEnabled").getAsBoolean();
            cfg.leversEnabled = obj.has("leversEnabled") && obj.get("leversEnabled").getAsBoolean();
            cfg.buttonsEnabled = obj.has("buttonsEnabled") && obj.get("buttonsEnabled").getAsBoolean();
            cfg.buttonsFullBox = obj.has("buttonsFullBox") && obj.get("buttonsFullBox").getAsBoolean();
            cfg.chestsEnabled = obj.has("chestsEnabled") && obj.get("chestsEnabled").getAsBoolean();
            cfg.essenceEnabled = obj.has("essenceEnabled") && obj.get("essenceEnabled").getAsBoolean();
            cfg.dungeonsOnly = obj.has("dungeonsOnly") && obj.get("dungeonsOnly").getAsBoolean();
            cfg.bossOnly = obj.has("bossOnly") && obj.get("bossOnly").getAsBoolean();
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
            obj.addProperty("chestsEnabled", chestsEnabled);
            obj.addProperty("essenceEnabled", essenceEnabled);
            obj.addProperty("dungeonsOnly", dungeonsOnly);
            obj.addProperty("bossOnly", bossOnly);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isMasterEnabled() {
        return masterEnabled;
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
