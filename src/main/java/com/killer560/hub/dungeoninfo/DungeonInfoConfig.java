package com.killer560.hub.dungeoninfo;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted settings for the Secrets HUD - see {@link DungeonInfoFeature}. Everything ships
 *  off by default, per killer560's standing instruction for new features.
 *  <p>
 *  Reorg 2026-09-21 (killer560's "secret hud / score hud / time hud" three-way split): the mimic/prince/bat
 *  KILL alerts and the manual 270/300 "Send Now" messages that used to live here moved to
 *  {@code ScoreCalculatorConfig} - killer560's own wording ("a score hud that has all the send messages and
 *  the score display") puts every bonus-score-related chat message under Score, not here.
 *  <p>
 *  Removed 2026-09-27 (killer560: "remove the time hud those are things that should be in the splits
 *  section") - the run/no-lag timer and its "show current split" mirror are gone; that belongs to
 *  {@code SplitTimersFeature}'s own HUD now. This class only backs the Secrets HUD. */
public final class DungeonInfoConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-dungeoninfo.json");

    private static DungeonInfoConfig instance;

    // ---- Secrets HUD ----
    private boolean secretsHudEnabled = false;
    /** New (2026-09-21, killer560: "the hud for how many secrets I have gotten in a room") - secrets found
     *  since the player entered the room currently standing in, alongside the existing run total. Off by
     *  default like every new HUD line here. See {@link DungeonInfoFeature#updateRoomSecrets()}. */
    private boolean showPerRoomSecrets = false;

    private DungeonInfoConfig() {
    }

    public static DungeonInfoConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new DungeonInfoConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            DungeonInfoConfig cfg = new DungeonInfoConfig();
            cfg.secretsHudEnabled = ConfigJson.getBool(obj, "secretsHudEnabled", cfg.secretsHudEnabled);
            cfg.showPerRoomSecrets = ConfigJson.getBool(obj, "showPerRoomSecrets", cfg.showPerRoomSecrets);
            instance = cfg;
        } catch (Exception e) {
            instance = new DungeonInfoConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("secretsHudEnabled", secretsHudEnabled);
            obj.addProperty("showPerRoomSecrets", showPerRoomSecrets);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isSecretsHudEnabled() {
        return secretsHudEnabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public void setSecretsHudEnabled(boolean secretsHudEnabled) {
        this.secretsHudEnabled = secretsHudEnabled;
    }

    public boolean isShowPerRoomSecrets() {
        return showPerRoomSecrets;
    }

    public void setShowPerRoomSecrets(boolean v) {
        this.showPerRoomSecrets = v;
    }
}
