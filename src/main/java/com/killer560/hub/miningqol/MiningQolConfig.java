package com.killer560.hub.miningqol;

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
 * Settings for the pre-2.0 mining features (killer560's to-do Doc, 2026-11-01: "the only mining features I want you to add
 * before 2.0"): Commission HUD, Pickobulus Estimate, Ability Ready Alert, Carpet Highlighter, Mithril As Stone, Titanium
 * Highlight and Raffle Ticket Highlight. One file, {@code killer560smod-miningqol.json}; every feature ships OFF.
 *
 * <p>The {@code is...} getters a feature reads are the gated ones: OFF outside Skyblock (Skyblock Only), and the two
 * through-walls switches are false in a legit jar whatever a copied file says.
 */
public final class MiningQolConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-miningqol.json");

    public static final int DEFAULT_PICKOBULUS_COLOR = 0xFF55FFFF;
    public static final int DEFAULT_CARPET_COLOR = 0x80FF3030;
    public static final int DEFAULT_TITANIUM_COLOR = 0xFFF0F0F0;
    public static final int DEFAULT_RAFFLE_COLOR = 0xFFFFAA00;
    public static final int DEFAULT_COMMISSION_MOB_COLOR = 0xFFFF5555;

    /** When the Titanium Highlight shows titanium. */
    public enum TitaniumMode {
        ALWAYS("Always"),
        COMMISSION("Commission Area");

        private final String label;

        TitaniumMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * How Commission Mobs draws a mob (killer560, 2026-11-03: "let me choose outline, fill, fill+outline or glow"). Drawn
     * with the shared paths: {@code WorldRenderUtils.renderOutlineBoxes}/{@code renderFilledBoxes} for the boxes, and the
     * vanilla glow outline through the cheatutils WitherGlow mixins Dungeon ESP's Glow style uses.
     */
    public enum CommissionMobStyle {
        OUTLINE("Outline", true, false, false),
        FILL("Fill", false, true, false),
        FILL_OUTLINE("Fill + Outline", true, true, false),
        GLOW("Glow", false, false, true);

        private final String label;
        public final boolean outline;
        public final boolean fill;
        public final boolean glow;

        CommissionMobStyle(String label, boolean outline, boolean fill, boolean glow) {
            this.label = label;
            this.outline = outline;
            this.fill = fill;
            this.glow = glow;
        }

        public String label() {
            return label;
        }
    }

    private static MiningQolConfig instance;

    // Commission HUD
    private boolean commissionHud = false;
    private boolean commissionColorProgress = true;
    private boolean commissionInterpolate = true;
    private boolean commissionWaypoints = true;
    private boolean commissionEmissaries = true;
    /** Commission Mobs (killer560, 2026-11-03: "it should highlight commission mobs as an option as well"). */
    private boolean commissionMobs = false;
    private boolean commissionMobsEsp = false;
    private int commissionMobColor = DEFAULT_COMMISSION_MOB_COLOR;
    private CommissionMobStyle commissionMobStyle = CommissionMobStyle.OUTLINE;
    /** Completed Commissions in the Commissions menu (killer560, 2026-11-03: "have it highlight completed commissions"). */
    private boolean commissionMenuHighlight = true;
    // Pickobulus Estimate
    private boolean pickobulus = false;
    private int pickobulusColor = DEFAULT_PICKOBULUS_COLOR;
    // Ability Ready Alert
    private boolean abilityAlert = false;
    private boolean abilityTimer = true;
    private boolean abilitySound = true;
    // Carpet Highlighter
    private boolean carpet = false;
    private int carpetColor = DEFAULT_CARPET_COLOR;
    // Mithril As Stone. (A "universalAccess" switch lived here until mining-1103: a commission that counts everywhere has
    // no area in its name - "Mithril Miner" - and MiningState.wants already applies it everywhere, so the key is ignored.)
    private boolean mithrilStone = false;
    // Titanium Highlight
    private boolean titanium = false;
    private TitaniumMode titaniumMode = TitaniumMode.COMMISSION;
    private int titaniumColor = DEFAULT_TITANIUM_COLOR;
    private boolean titaniumEsp = false;
    // Raffle Ticket Highlight
    private boolean raffle = false;
    private int raffleColor = DEFAULT_RAFFLE_COLOR;
    private boolean raffleEsp = false;

    private MiningQolConfig() {
    }

    public static MiningQolConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        MiningQolConfig cfg = new MiningQolConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                cfg.commissionHud = ConfigJson.getBool(o, "commissionHud", cfg.commissionHud);
                cfg.commissionColorProgress = ConfigJson.getBool(o, "commissionColorProgress", cfg.commissionColorProgress);
                cfg.commissionInterpolate = ConfigJson.getBool(o, "commissionInterpolate", cfg.commissionInterpolate);
                cfg.commissionWaypoints = ConfigJson.getBool(o, "commissionWaypoints", cfg.commissionWaypoints);
                cfg.commissionEmissaries = ConfigJson.getBool(o, "commissionEmissaries", cfg.commissionEmissaries);
                cfg.commissionMobs = ConfigJson.getBool(o, "commissionMobs", cfg.commissionMobs);
                cfg.commissionMobsEsp = ConfigJson.getBool(o, "commissionMobsEsp", cfg.commissionMobsEsp);
                cfg.commissionMobColor = intOr(o, "commissionMobColor", cfg.commissionMobColor);
                cfg.commissionMobStyle = ConfigJson.getEnum(o, "commissionMobStyle", CommissionMobStyle.class,
                        CommissionMobStyle.OUTLINE);
                cfg.commissionMenuHighlight = ConfigJson.getBool(o, "commissionMenuHighlight", cfg.commissionMenuHighlight);
                cfg.pickobulus = ConfigJson.getBool(o, "pickobulus", cfg.pickobulus);
                cfg.pickobulusColor = intOr(o, "pickobulusColor", cfg.pickobulusColor);
                cfg.abilityAlert = ConfigJson.getBool(o, "abilityAlert", cfg.abilityAlert);
                cfg.abilityTimer = ConfigJson.getBool(o, "abilityTimer", cfg.abilityTimer);
                cfg.abilitySound = ConfigJson.getBool(o, "abilitySound", cfg.abilitySound);
                cfg.carpet = ConfigJson.getBool(o, "carpet", cfg.carpet);
                cfg.carpetColor = intOr(o, "carpetColor", cfg.carpetColor);
                cfg.mithrilStone = ConfigJson.getBool(o, "mithrilStone", cfg.mithrilStone);
                cfg.titanium = ConfigJson.getBool(o, "titanium", cfg.titanium);
                try {
                    if (o.has("titaniumMode")) {
                        cfg.titaniumMode = TitaniumMode.valueOf(o.get("titaniumMode").getAsString());
                    }
                } catch (RuntimeException ignored) {
                    // unknown name: keep the default
                }
                cfg.titaniumColor = intOr(o, "titaniumColor", cfg.titaniumColor);
                cfg.titaniumEsp = ConfigJson.getBool(o, "titaniumEsp", cfg.titaniumEsp);
                cfg.raffle = ConfigJson.getBool(o, "raffle", cfg.raffle);
                cfg.raffleColor = intOr(o, "raffleColor", cfg.raffleColor);
                cfg.raffleEsp = ConfigJson.getBool(o, "raffleEsp", cfg.raffleEsp);
            } catch (Exception e) {
                cfg = new MiningQolConfig();
            }
        }
        instance = cfg;
        MithrilStone.settingsChanged();
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
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("commissionHud", commissionHud);
            o.addProperty("commissionColorProgress", commissionColorProgress);
            o.addProperty("commissionInterpolate", commissionInterpolate);
            o.addProperty("commissionWaypoints", commissionWaypoints);
            o.addProperty("commissionEmissaries", commissionEmissaries);
            o.addProperty("commissionMobs", commissionMobs);
            o.addProperty("commissionMobsEsp", commissionMobsEsp);
            o.addProperty("commissionMobColor", commissionMobColor);
            o.addProperty("commissionMobStyle", commissionMobStyle.name());
            o.addProperty("commissionMenuHighlight", commissionMenuHighlight);
            o.addProperty("pickobulus", pickobulus);
            o.addProperty("pickobulusColor", pickobulusColor);
            o.addProperty("abilityAlert", abilityAlert);
            o.addProperty("abilityTimer", abilityTimer);
            o.addProperty("abilitySound", abilitySound);
            o.addProperty("carpet", carpet);
            o.addProperty("carpetColor", carpetColor);
            o.addProperty("mithrilStone", mithrilStone);
            o.addProperty("titanium", titanium);
            o.addProperty("titaniumMode", titaniumMode.name());
            o.addProperty("titaniumColor", titaniumColor);
            o.addProperty("titaniumEsp", titaniumEsp);
            o.addProperty("raffle", raffle);
            o.addProperty("raffleColor", raffleColor);
            o.addProperty("raffleEsp", raffleEsp);
            Files.writeString(CONFIG_PATH, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // a failed save keeps the in-memory values
        }
        MithrilStone.settingsChanged();
    }

    private static boolean sb() {
        return SkyblockGate.allows();
    }

    // ---- Commission HUD
    public boolean isCommissionHud() {
        return commissionHud && sb();
    }

    public boolean isCommissionHudRaw() {
        return commissionHud;
    }

    public void setCommissionHud(boolean v) {
        commissionHud = v;
    }

    public boolean isCommissionColorProgress() {
        return commissionColorProgress;
    }

    public void setCommissionColorProgress(boolean v) {
        commissionColorProgress = v;
    }

    public boolean isCommissionInterpolate() {
        return commissionInterpolate;
    }

    public void setCommissionInterpolate(boolean v) {
        commissionInterpolate = v;
    }

    public boolean isCommissionWaypoints() {
        return commissionWaypoints;
    }

    public void setCommissionWaypoints(boolean v) {
        commissionWaypoints = v;
    }

    public boolean isCommissionEmissaries() {
        return commissionEmissaries;
    }

    public void setCommissionEmissaries(boolean v) {
        commissionEmissaries = v;
    }

    /** Commission Mobs as set (the feature also needs the Commission HUD switch, see {@link CommissionMobs}). */
    public boolean isCommissionMobs() {
        return commissionMobs;
    }

    public void setCommissionMobs(boolean v) {
        commissionMobs = v;
    }

    /** Commission Mobs through walls: cheat build only. */
    public boolean isCommissionMobsEsp() {
        return BuildVariant.CHEAT_FEATURES_ENABLED && commissionMobsEsp;
    }

    public void setCommissionMobsEsp(boolean v) {
        commissionMobsEsp = v;
    }

    public int getCommissionMobColor() {
        return commissionMobColor;
    }

    public void setCommissionMobColor(int v) {
        commissionMobColor = v;
    }

    public boolean isCommissionMenuHighlight() {
        return commissionMenuHighlight;
    }

    public void setCommissionMenuHighlight(boolean v) {
        commissionMenuHighlight = v;
    }

    // ---- Pickobulus Estimate
    public boolean isPickobulus() {
        return pickobulus && sb();
    }

    public boolean isPickobulusRaw() {
        return pickobulus;
    }

    public void setPickobulus(boolean v) {
        pickobulus = v;
    }

    public int getPickobulusColor() {
        return pickobulusColor;
    }

    public void setPickobulusColor(int v) {
        pickobulusColor = v;
    }

    // ---- Ability Ready Alert
    public boolean isAbilityAlert() {
        return abilityAlert && sb();
    }

    public boolean isAbilityAlertRaw() {
        return abilityAlert;
    }

    public void setAbilityAlert(boolean v) {
        abilityAlert = v;
    }

    public boolean isAbilityTimer() {
        return abilityTimer;
    }

    public void setAbilityTimer(boolean v) {
        abilityTimer = v;
    }

    public boolean isAbilitySound() {
        return abilitySound;
    }

    public void setAbilitySound(boolean v) {
        abilitySound = v;
    }

    // ---- Carpet Highlighter
    public boolean isCarpet() {
        return carpet && sb();
    }

    public boolean isCarpetRaw() {
        return carpet;
    }

    public void setCarpet(boolean v) {
        carpet = v;
    }

    public int getCarpetColor() {
        return carpetColor;
    }

    public void setCarpetColor(int v) {
        carpetColor = v;
    }

    // ---- Mithril As Stone
    public boolean isMithrilStone() {
        return mithrilStone && sb();
    }

    public boolean isMithrilStoneRaw() {
        return mithrilStone;
    }

    public void setMithrilStone(boolean v) {
        mithrilStone = v;
    }

    // ---- Titanium Highlight
    public boolean isTitanium() {
        return titanium && sb();
    }

    public boolean isTitaniumRaw() {
        return titanium;
    }

    public void setTitanium(boolean v) {
        titanium = v;
    }

    public CommissionMobStyle getCommissionMobStyle() {
        return commissionMobStyle;
    }

    public void setCommissionMobStyle(CommissionMobStyle v) {
        commissionMobStyle = v == null ? CommissionMobStyle.OUTLINE : v;
    }

    /** Left click forward, right click back, wrapping. */
    public void stepCommissionMobStyle(int dir) {
        CommissionMobStyle[] all = CommissionMobStyle.values();
        commissionMobStyle = all[Math.floorMod(commissionMobStyle.ordinal() + dir, all.length)];
    }

    public TitaniumMode getTitaniumMode() {
        return titaniumMode;
    }

    public void setTitaniumMode(TitaniumMode v) {
        titaniumMode = v == null ? TitaniumMode.COMMISSION : v;
    }

    public int getTitaniumColor() {
        return titaniumColor;
    }

    public void setTitaniumColor(int v) {
        titaniumColor = v;
    }

    /** Through walls: cheat build only. */
    public boolean isTitaniumEsp() {
        return BuildVariant.CHEAT_FEATURES_ENABLED && titaniumEsp;
    }

    public void setTitaniumEsp(boolean v) {
        titaniumEsp = v;
    }

    // ---- Raffle Ticket Highlight
    public boolean isRaffle() {
        return raffle && sb();
    }

    public boolean isRaffleRaw() {
        return raffle;
    }

    public void setRaffle(boolean v) {
        raffle = v;
    }

    public int getRaffleColor() {
        return raffleColor;
    }

    public void setRaffleColor(int v) {
        raffleColor = v;
    }

    /** Through walls: cheat build only. */
    public boolean isRaffleEsp() {
        return BuildVariant.CHEAT_FEATURES_ENABLED && raffleEsp;
    }

    public void setRaffleEsp(boolean v) {
        raffleEsp = v;
    }
}
