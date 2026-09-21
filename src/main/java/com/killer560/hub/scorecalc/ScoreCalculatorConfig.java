package com.killer560.hub.scorecalc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted settings for {@link ScoreCalculatorFeature}. Everything that does something ships OFF; per-key
 *  {@link ConfigJson} readers so one bad value never resets the rest; the tab saves after every change.
 *  <p>
 *  Reorg 2026-09-21 (killer560's "score hud that has all the send messages and the score display"): the
 *  mimic/prince/bat KILL party-alert settings moved here from {@code DungeonInfoConfig} - the DETECTION
 *  itself already lived in {@link ScoreCalculatorFeature} independently (needed for the score formula), so
 *  {@code DungeonInfoFeature} keeping its own second copy just to fire a chat message was pure overlap; see
 *  {@link #migrateLegacyDungeonInfoSettings} for the one-time carry-over of anything killer560 already had
 *  configured there. */
public final class ScoreCalculatorConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-scorecalc.json");
    private static final Path LEGACY_DUNGEON_INFO_CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-dungeoninfo.json");

    public enum PaulMode {
        AUTO("Auto"), FORCE_ON("Force On"), FORCE_OFF("Force Off");

        public final String label;

        PaulMode(String label) {
            this.label = label;
        }

        public PaulMode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private static ScoreCalculatorConfig instance;

    private boolean enabled = false;
    // HUD content (only matters once the master toggle is on)
    private boolean showBreakdown = false;
    private boolean showSecretsNeeded = true;
    private boolean showCryptsDeaths = true;
    private boolean showMimicPrince = false;
    private boolean textShadow = true;
    // Formula options
    private PaulMode paulMode = PaulMode.AUTO;
    private boolean assumeSpiritPet = true;
    // 270 alerts
    private boolean title270 = false;
    private String title270Text = "270 Score!";
    private boolean party270 = false;
    private String party270Message = "270 Score!";
    // 300 alerts
    private boolean title300 = false;
    private String title300Text = "300 Score!";
    private boolean party300 = false;
    private String party300Message = "300 Score!";
    private boolean chatNote = false;
    private boolean alertSound = true;
    // Bonus-kill party alerts (moved from DungeonInfoConfig 2026-09-21 - see class doc). Same default
    // messages as before the move; detection stays in ScoreCalculatorFeature (self-kill only - a kill
    // another mod already announced in party chat is never re-announced, same as before the move).
    private boolean mimicAlertEnabled = false;
    private String mimicAlertMessage = "Mimic Killed!";
    private boolean princeAlertEnabled = false;
    private String princeAlertMessage = "Prince Killed!";
    private boolean batAlertEnabled = false;
    private String batAlertMessage = "Bat Killed!";
    /** Guards {@link #migrateLegacyDungeonInfoSettings} to exactly one carry-over, ever - once true (and
     *  saved), killer560 turning these back off/blank on purpose must stick, not get re-migrated. */
    private boolean legacyAlertsMigrated = false;

    private ScoreCalculatorConfig() {
    }

    public static ScoreCalculatorConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        ScoreCalculatorConfig cfg = new ScoreCalculatorConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
                cfg.showBreakdown = ConfigJson.getBool(obj, "showBreakdown", cfg.showBreakdown);
                cfg.showSecretsNeeded = ConfigJson.getBool(obj, "showSecretsNeeded", cfg.showSecretsNeeded);
                cfg.showCryptsDeaths = ConfigJson.getBool(obj, "showCryptsDeaths", cfg.showCryptsDeaths);
                cfg.showMimicPrince = ConfigJson.getBool(obj, "showMimicPrince", cfg.showMimicPrince);
                cfg.textShadow = ConfigJson.getBool(obj, "textShadow", cfg.textShadow);
                cfg.paulMode = ConfigJson.getEnum(obj, "paulMode", PaulMode.class, cfg.paulMode);
                cfg.assumeSpiritPet = ConfigJson.getBool(obj, "assumeSpiritPet", cfg.assumeSpiritPet);
                cfg.title270 = ConfigJson.getBool(obj, "title270", cfg.title270);
                cfg.title270Text = ConfigJson.getString(obj, "title270Text", cfg.title270Text);
                cfg.party270 = ConfigJson.getBool(obj, "party270", cfg.party270);
                cfg.party270Message = ConfigJson.getString(obj, "party270Message", cfg.party270Message);
                cfg.title300 = ConfigJson.getBool(obj, "title300", cfg.title300);
                cfg.title300Text = ConfigJson.getString(obj, "title300Text", cfg.title300Text);
                cfg.party300 = ConfigJson.getBool(obj, "party300", cfg.party300);
                cfg.party300Message = ConfigJson.getString(obj, "party300Message", cfg.party300Message);
                cfg.chatNote = ConfigJson.getBool(obj, "chatNote", cfg.chatNote);
                cfg.alertSound = ConfigJson.getBool(obj, "alertSound", cfg.alertSound);
                cfg.mimicAlertEnabled = ConfigJson.getBool(obj, "mimicAlertEnabled", cfg.mimicAlertEnabled);
                cfg.mimicAlertMessage = ConfigJson.getString(obj, "mimicAlertMessage", cfg.mimicAlertMessage);
                cfg.princeAlertEnabled = ConfigJson.getBool(obj, "princeAlertEnabled", cfg.princeAlertEnabled);
                cfg.princeAlertMessage = ConfigJson.getString(obj, "princeAlertMessage", cfg.princeAlertMessage);
                cfg.batAlertEnabled = ConfigJson.getBool(obj, "batAlertEnabled", cfg.batAlertEnabled);
                cfg.batAlertMessage = ConfigJson.getString(obj, "batAlertMessage", cfg.batAlertMessage);
                cfg.legacyAlertsMigrated = ConfigJson.getBool(obj, "legacyAlertsMigrated", cfg.legacyAlertsMigrated);
            } catch (Exception ignored) {
                // Unparseable file: keep defaults for this session (per-key readers cover single bad values).
            }
        }
        if (!cfg.legacyAlertsMigrated) {
            migrateLegacyDungeonInfoSettings(cfg);
            instance = cfg;
            // Persist the migration result (and the guard flag) immediately - otherwise, if killer560 never
            // touches a Score Calculator setting before his next restart, legacyAlertsMigrated never reaches
            // disk and this harmless-but-pointless re-read happens on every launch.
            cfg.save();
            return;
        }
        instance = cfg;
    }

    /** One-time carry-over (2026-09-21 reorg) of whatever killer560 already had set for the mimic/prince/bat
     *  kill alerts and the manual 270/300 messages in the old {@code killer560smod-dungeoninfo.json} - read
     *  only, never written back to that file. Runs at most once per install: {@link #legacyAlertsMigrated}
     *  is set and saved here so a later intentional "turn it back off" is never overwritten again. Missing
     *  or unparseable legacy file just means there was nothing to carry over - defaults stand. */
    private static void migrateLegacyDungeonInfoSettings(ScoreCalculatorConfig cfg) {
        cfg.legacyAlertsMigrated = true;
        if (!Files.exists(LEGACY_DUNGEON_INFO_CONFIG_PATH)) {
            return;
        }
        try {
            JsonObject legacy = JsonParser.parseString(Files.readString(LEGACY_DUNGEON_INFO_CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            cfg.mimicAlertEnabled = ConfigJson.getBool(legacy, "mimicMessageEnabled", cfg.mimicAlertEnabled);
            cfg.mimicAlertMessage = ConfigJson.getString(legacy, "mimicMessage", cfg.mimicAlertMessage);
            cfg.princeAlertEnabled = ConfigJson.getBool(legacy, "princeMessageEnabled", cfg.princeAlertEnabled);
            cfg.princeAlertMessage = ConfigJson.getString(legacy, "princeMessage", cfg.princeAlertMessage);
            cfg.batAlertEnabled = ConfigJson.getBool(legacy, "batMessageEnabled", cfg.batAlertEnabled);
            cfg.batAlertMessage = ConfigJson.getString(legacy, "batMessage", cfg.batAlertMessage);
            // The old manual 270/300 "Send Now" text only carries over into the (now shared) party message
            // if killer560 actually customised it away from its own old default - otherwise the party
            // message's own text (customised or not) wins, same idea as DungeonInfoConfig's own
            // migrateOldDefault used to apply to the mimic/prince/bat text.
            // These alerts used to fire from Dungeon Info on their own; here they only fire while the Score
            // Calculator is on. If any was switched on before the move, switch the Score Calculator on too so
            // the move does not silently mute an alert he had set up.
            if (!cfg.enabled && (cfg.mimicAlertEnabled || cfg.princeAlertEnabled || cfg.batAlertEnabled)) {
                cfg.enabled = true;
            }
            String legacy270 = ConfigJson.getString(legacy, "score270Message", null);
            if (legacy270 != null && !"270 score - carrying/leaving is fine from here!".equals(legacy270)) {
                cfg.party270Message = legacy270;
            }
            String legacy300 = ConfigJson.getString(legacy, "score300Message", null);
            if (legacy300 != null && !"300 score!".equals(legacy300)) {
                cfg.party300Message = legacy300;
            }
        } catch (Exception ignored) {
            // Unparseable/missing legacy file: nothing to carry over, defaults stand.
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("showBreakdown", showBreakdown);
            obj.addProperty("showSecretsNeeded", showSecretsNeeded);
            obj.addProperty("showCryptsDeaths", showCryptsDeaths);
            obj.addProperty("showMimicPrince", showMimicPrince);
            obj.addProperty("textShadow", textShadow);
            obj.addProperty("paulMode", paulMode.name());
            obj.addProperty("assumeSpiritPet", assumeSpiritPet);
            obj.addProperty("title270", title270);
            obj.addProperty("title270Text", title270Text);
            obj.addProperty("party270", party270);
            obj.addProperty("party270Message", party270Message);
            obj.addProperty("title300", title300);
            obj.addProperty("title300Text", title300Text);
            obj.addProperty("party300", party300);
            obj.addProperty("party300Message", party300Message);
            obj.addProperty("chatNote", chatNote);
            obj.addProperty("alertSound", alertSound);
            obj.addProperty("mimicAlertEnabled", mimicAlertEnabled);
            obj.addProperty("mimicAlertMessage", mimicAlertMessage);
            obj.addProperty("princeAlertEnabled", princeAlertEnabled);
            obj.addProperty("princeAlertMessage", princeAlertMessage);
            obj.addProperty("batAlertEnabled", batAlertEnabled);
            obj.addProperty("batAlertMessage", batAlertMessage);
            obj.addProperty("legacyAlertsMigrated", legacyAlertsMigrated);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    // ---- master ----

    public boolean isEnabled() {
        return enabled && SkyblockGate.allows();
    }

    public void setEnabled(boolean v) {
        enabled = v;
    }

    // ---- HUD ----

    public boolean isShowBreakdown() {
        return showBreakdown;
    }

    public void setShowBreakdown(boolean v) {
        showBreakdown = v;
    }

    public boolean isShowSecretsNeeded() {
        return showSecretsNeeded;
    }

    public void setShowSecretsNeeded(boolean v) {
        showSecretsNeeded = v;
    }

    public boolean isShowCryptsDeaths() {
        return showCryptsDeaths;
    }

    public void setShowCryptsDeaths(boolean v) {
        showCryptsDeaths = v;
    }

    public boolean isShowMimicPrince() {
        return showMimicPrince;
    }

    public void setShowMimicPrince(boolean v) {
        showMimicPrince = v;
    }

    public boolean isTextShadow() {
        return textShadow;
    }

    public void setTextShadow(boolean v) {
        textShadow = v;
    }

    // ---- formula ----

    public PaulMode getPaulMode() {
        return paulMode;
    }

    public void setPaulMode(PaulMode v) {
        paulMode = v == null ? PaulMode.AUTO : v;
    }

    public boolean isAssumeSpiritPet() {
        return assumeSpiritPet;
    }

    public void setAssumeSpiritPet(boolean v) {
        assumeSpiritPet = v;
    }

    // ---- alerts (action toggles are gated like the master) ----

    public boolean isTitle270() {
        return title270 && SkyblockGate.allows();
    }

    public void setTitle270(boolean v) {
        title270 = v;
    }

    public String getTitle270Text() {
        return title270Text;
    }

    public void setTitle270Text(String v) {
        title270Text = v == null ? "" : v;
    }

    public boolean isParty270() {
        return party270 && SkyblockGate.allows();
    }

    public void setParty270(boolean v) {
        party270 = v;
    }

    public String getParty270Message() {
        return party270Message;
    }

    public void setParty270Message(String v) {
        party270Message = v == null ? "" : v;
    }

    public boolean isTitle300() {
        return title300 && SkyblockGate.allows();
    }

    public void setTitle300(boolean v) {
        title300 = v;
    }

    public String getTitle300Text() {
        return title300Text;
    }

    public void setTitle300Text(String v) {
        title300Text = v == null ? "" : v;
    }

    public boolean isParty300() {
        return party300 && SkyblockGate.allows();
    }

    public void setParty300(boolean v) {
        party300 = v;
    }

    public String getParty300Message() {
        return party300Message;
    }

    public void setParty300Message(String v) {
        party300Message = v == null ? "" : v;
    }

    public boolean isChatNote() {
        return chatNote && SkyblockGate.allows();
    }

    public void setChatNote(boolean v) {
        chatNote = v;
    }

    public boolean isAlertSound() {
        return alertSound;
    }

    public void setAlertSound(boolean v) {
        alertSound = v;
    }

    // ---- bonus-kill party alerts (moved from DungeonInfoConfig 2026-09-21) ----

    public boolean isMimicAlertEnabled() {
        return mimicAlertEnabled && SkyblockGate.allows();
    }

    public void setMimicAlertEnabled(boolean v) {
        mimicAlertEnabled = v;
    }

    public String getMimicAlertMessage() {
        return mimicAlertMessage;
    }

    public void setMimicAlertMessage(String v) {
        mimicAlertMessage = v == null ? "" : v;
    }

    public boolean isPrinceAlertEnabled() {
        return princeAlertEnabled && SkyblockGate.allows();
    }

    public void setPrinceAlertEnabled(boolean v) {
        princeAlertEnabled = v;
    }

    public String getPrinceAlertMessage() {
        return princeAlertMessage;
    }

    public void setPrinceAlertMessage(String v) {
        princeAlertMessage = v == null ? "" : v;
    }

    public boolean isBatAlertEnabled() {
        return batAlertEnabled && SkyblockGate.allows();
    }

    public void setBatAlertEnabled(boolean v) {
        batAlertEnabled = v;
    }

    public String getBatAlertMessage() {
        return batAlertMessage;
    }

    public void setBatAlertMessage(String v) {
        batAlertMessage = v == null ? "" : v;
    }
}
