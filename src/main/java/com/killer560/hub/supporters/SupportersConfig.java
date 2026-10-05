package com.killer560.hub.supporters;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted settings for the Cosmetics tab's "receive" and "own player model" halves - written to {@code
 * killer560smod-supporters.json} on every change so they survive a restart, same load/save shape as this
 * mod's other {@code XyzConfig} classes. Grew from just killer560's original item 8.5 ("mod-wide custom IGNs
 * for supporters") to also carry the Cosmetics tab's newer, purely-local additions:
 * <ul>
 *   <li>{@link #customCosmeticsEnabled} ("Toggle Global Cosmetics" in the tab) - whether to SHOW other
 *       supporters' shared cosmetics at all.</li>
 *   <li>{@link #shareIfSupporter} ("Share if Supporter") - whether to automatically PUSH your own Cosmetics
 *       tab name/scale to the relay once this account is confirmed linked - see {@code SupportersAutoShare}.</li>
 *   <li>{@link #modelWidth}/{@link #modelHeight}/{@link #modelThickness} - killer560's "allow me to change my
 *       players... width, height, and thickness" request. These have NO relay equivalent (the supporters
 *       contract only ever carries a single {@code scale} float) so they are always purely local - see
 *       {@code com.killer560.hub.supporters.mixin.CosmeticsModelShapeMixin} for exactly how, and why they
 *       never touch anything the server sees.</li>
 * </ul>
 * <b>{@link #customCosmeticsEnabled} ships ON by default</b> - killer560's own explicit spec for that one
 * setting (item 8.5: "a 'toggle custom cosmetics' setting on by default"), the deliberate exception to this
 * mod's usual "new features default OFF" rule. {@link #shareIfSupporter} extends that same "share
 * automatically" spirit to the OUTGOING half (killer560: "if the account is a supporter, its cosmetics should
 * be shared with others automatically"), so it ships ON too.
 * <p>
 * Who is a supporter and their shared name/scale are NOT settings - they come from the relay (see
 * {@code SUPPORTERS-CONTRACT.md}) and are cached separately in {@link SupportersCache}.
 */
public final class SupportersConfig {

    public static final float MIN_MODEL_DIMENSION = 0.5f;
    public static final float MAX_MODEL_DIMENSION = 2.0f;
    /** Player Size sliders (killer560, 2026-10-04: "make the minimum player size 0.05 and the max 2"). */
    public static final float MIN_PLAYER_SIZE = 0.05f;
    public static final float MAX_PLAYER_SIZE = 2.0f;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-supporters.json");

    private static SupportersConfig instance;
    private static volatile int version = 0;

    /** What of other supporters' shared cosmetics to SHOW (killer560, 2026-10-05: "make it so the toggle for
     *  cosmetics has an option for just toggling names or just toggling sizes"). Replaces the old on/off
     *  {@code customCosmeticsEnabled}, which still loads: true reads as ALL, false as OFF. */
    public enum GlobalCosmetics {
        ALL("All"), NAMES("Names only"), SIZES("Sizes only"), OFF("Off");

        public final String label;

        GlobalCosmetics(String label) {
            this.label = label;
        }

        public GlobalCosmetics next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private GlobalCosmetics globalCosmetics = GlobalCosmetics.ALL;
    private boolean shareIfSupporter = true;
    private float modelWidth = 1.0f;
    private float modelHeight = 1.0f;
    private float modelThickness = 1.0f;
    /** Your own player size - applied to your own model locally, and shared through the relay while Share if
     *  Supporter is on and the account is linked (one toggle covers both name and size). */
    private float ownScale = 1.0f;
    /** A local size multiplier for every OTHER real player's model; never shared. */
    private float othersScale = 1.0f;

    private SupportersConfig() {
    }

    public static SupportersConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static int version() {
        return version;
    }

    public static void load() {
        SupportersConfig cfg = new SupportersConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.globalCosmetics = ConfigJson.getBool(obj, "customCosmeticsEnabled", true)
                        ? GlobalCosmetics.ALL : GlobalCosmetics.OFF;
                if (obj.has("globalCosmetics")) {
                    try {
                        cfg.globalCosmetics = GlobalCosmetics.valueOf(obj.get("globalCosmetics").getAsString());
                    } catch (Exception ignored) {
                        // unknown value: keep what the old flag said
                    }
                }
                cfg.shareIfSupporter = ConfigJson.getBool(obj, "shareIfSupporter", true);
                cfg.modelWidth = clamp(ConfigJson.getFloat(obj, "modelWidth", 1.0f));
                cfg.modelHeight = clamp(ConfigJson.getFloat(obj, "modelHeight", 1.0f));
                cfg.modelThickness = clamp(ConfigJson.getFloat(obj, "modelThickness", 1.0f));
                cfg.ownScale = clampSize(ConfigJson.getFloat(obj, "ownScale", 1.0f));
                cfg.othersScale = clampSize(ConfigJson.getFloat(obj, "othersScale", 1.0f));
            } catch (Exception e) {
                cfg = new SupportersConfig();
            }
        }
        instance = cfg;
        version++;
    }

    public void save() {
        version++;
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("globalCosmetics", globalCosmetics.name());
            // Kept so an older jar reading this file still gets a sensible on/off.
            obj.addProperty("customCosmeticsEnabled", globalCosmetics != GlobalCosmetics.OFF);
            obj.addProperty("shareIfSupporter", shareIfSupporter);
            obj.addProperty("modelWidth", modelWidth);
            obj.addProperty("modelHeight", modelHeight);
            obj.addProperty("modelThickness", modelThickness);
            obj.addProperty("ownScale", ownScale);
            obj.addProperty("othersScale", othersScale);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** Anything of other supporters' cosmetics shown at all. */
    public boolean isCustomCosmeticsEnabled() {
        return globalCosmetics != GlobalCosmetics.OFF;
    }

    /** On/off for callers that only know the old toggle: true is ALL, false is OFF. */
    public void setCustomCosmeticsEnabled(boolean value) {
        setGlobalCosmetics(value ? GlobalCosmetics.ALL : GlobalCosmetics.OFF);
    }

    public GlobalCosmetics getGlobalCosmetics() {
        return globalCosmetics;
    }

    public void setGlobalCosmetics(GlobalCosmetics value) {
        this.globalCosmetics = value == null ? GlobalCosmetics.ALL : value;
        version++;
    }

    /** Other supporters' shared NAMES are shown (All or Names only). */
    public boolean isShowSupporterNames() {
        return globalCosmetics == GlobalCosmetics.ALL || globalCosmetics == GlobalCosmetics.NAMES;
    }

    /** Other supporters' shared SIZES are shown (All or Sizes only). */
    public boolean isShowSupporterSizes() {
        return globalCosmetics == GlobalCosmetics.ALL || globalCosmetics == GlobalCosmetics.SIZES;
    }

    public boolean isShareIfSupporter() {
        return shareIfSupporter;
    }

    public void setShareIfSupporter(boolean value) {
        this.shareIfSupporter = value;
        version++;
    }

    public float getModelWidth() {
        return modelWidth;
    }

    public void setModelWidth(float value) {
        this.modelWidth = clamp(value);
        version++;
    }

    public float getModelHeight() {
        return modelHeight;
    }

    public void setModelHeight(float value) {
        this.modelHeight = clamp(value);
        version++;
    }

    public float getModelThickness() {
        return modelThickness;
    }

    public void setModelThickness(float value) {
        this.modelThickness = clamp(value);
        version++;
    }

    public float getOwnScale() {
        return ownScale;
    }

    public void setOwnScale(float value) {
        this.ownScale = clampSize(value);
        version++;
    }

    public float getOthersScale() {
        return othersScale;
    }

    public void setOthersScale(float value) {
        this.othersScale = clampSize(value);
        version++;
    }

    private static float clampSize(float v) {
        if (Float.isNaN(v)) {
            return 1.0f;
        }
        return v < MIN_PLAYER_SIZE ? MIN_PLAYER_SIZE : (v > MAX_PLAYER_SIZE ? MAX_PLAYER_SIZE : v);
    }

    /** Cosmetics tab's bottom "Reset" button - puts width/height/thickness and both player sizes back to 1.0x. Leaves {@link
     *  #customCosmeticsEnabled}/{@link #shareIfSupporter} alone - those are feature toggles, not values. */
    public void resetModelShape() {
        modelWidth = modelHeight = modelThickness = 1.0f;
        ownScale = othersScale = 1.0f;
        version++;
    }

    private static float clamp(float v) {
        if (Float.isNaN(v)) {
            return 1.0f;
        }
        return v < MIN_MODEL_DIMENSION ? MIN_MODEL_DIMENSION : (v > MAX_MODEL_DIMENSION ? MAX_MODEL_DIMENSION : v);
    }
}
