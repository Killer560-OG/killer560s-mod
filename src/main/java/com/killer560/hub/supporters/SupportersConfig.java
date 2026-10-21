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

    /** Whether other supporters' shared NAMES and SIZES are shown - two separate switches (killer560,
     *  2026-10-05: "two different things I flip one to hide names one for sizes. Both hides everything").
     *  They replace the old single {@code customCosmeticsEnabled}, which still loads into both. */
    private boolean showSupporterNames = true;
    private boolean showSupporterSizes = true;
    private boolean shareIfSupporter = true;
    private float modelWidth = 1.0f;
    private float modelHeight = 1.0f;
    private float modelThickness = 1.0f;
    /** Your own player size - applied to your own model locally, and shared through the relay while Share if
     *  Supporter is on and the account is linked (one toggle covers both name and size). */
    private float ownScale = 1.0f;
    /** A local size multiplier for every OTHER real player's model; never shared. */
    private float othersScale = 1.0f;
    /** "My Name in F5" - your own nametag in both third-person views (never first person). Off by default. */
    private boolean ownNameInF5 = false;
    /** "Share Ribbon Trail" (killer560, 2026-10-13): your Trail goes out with your shared cosmetics. Only a linked supporter
     *  can share, and only while Share if Supporter is on. ON by default, like every sharing setting. */
    private boolean shareTrail = true;
    /** "Show Others' Trails": draw the trails other supporters share. ON by default. */
    private boolean showOthersTrails = true;
    /** "My Mob" (Play as Mob, killer560 2026-10-18): the mob your player is drawn as, a {@code MobSkins} id, or "" for
     *  your normal player. Shared while Share if Supporter is on and the account is a linked supporter. */
    private String ownMob = "";
    /** Play as Mob on/off (killer560, 2026-10-18): off, your normal player is drawn and shared, and your chosen mob is kept. */
    private boolean playAsMob = true;
    /** "Show Others' Mobs": draw other supporters as the mob they share (never inside a dungeon). ON by default. */
    private boolean showOthersMobs = true;

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
                boolean legacy = ConfigJson.getBool(obj, "customCosmeticsEnabled", true);
                cfg.showSupporterNames = ConfigJson.getBool(obj, "showSupporterNames", legacy);
                cfg.showSupporterSizes = ConfigJson.getBool(obj, "showSupporterSizes", legacy);
                cfg.shareIfSupporter = ConfigJson.getBool(obj, "shareIfSupporter", true);
                cfg.modelWidth = clamp(ConfigJson.getFloat(obj, "modelWidth", 1.0f));
                cfg.modelHeight = clamp(ConfigJson.getFloat(obj, "modelHeight", 1.0f));
                cfg.modelThickness = clamp(ConfigJson.getFloat(obj, "modelThickness", 1.0f));
                cfg.ownScale = clampSize(ConfigJson.getFloat(obj, "ownScale", 1.0f));
                cfg.othersScale = clampSize(ConfigJson.getFloat(obj, "othersScale", 1.0f));
                cfg.ownNameInF5 = ConfigJson.getBool(obj, "ownNameInF5", false);
                cfg.shareTrail = ConfigJson.getBool(obj, "shareTrail", true);
                cfg.showOthersTrails = ConfigJson.getBool(obj, "showOthersTrails", true);
                cfg.ownMob = cleanMob(ConfigJson.getString(obj, "ownMob", ""));
                cfg.playAsMob = ConfigJson.getBool(obj, "playAsMob", true);
                cfg.showOthersMobs = ConfigJson.getBool(obj, "showOthersMobs", true);
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
            obj.addProperty("showSupporterNames", showSupporterNames);
            obj.addProperty("showSupporterSizes", showSupporterSizes);
            // Kept so an older jar reading this file still gets a sensible on/off.
            obj.addProperty("customCosmeticsEnabled", showSupporterNames || showSupporterSizes);
            obj.addProperty("shareIfSupporter", shareIfSupporter);
            obj.addProperty("modelWidth", modelWidth);
            obj.addProperty("modelHeight", modelHeight);
            obj.addProperty("modelThickness", modelThickness);
            obj.addProperty("ownScale", ownScale);
            obj.addProperty("othersScale", othersScale);
            obj.addProperty("ownNameInF5", ownNameInF5);
            obj.addProperty("shareTrail", shareTrail);
            obj.addProperty("showOthersTrails", showOthersTrails);
            obj.addProperty("ownMob", ownMob);
            obj.addProperty("playAsMob", playAsMob);
            obj.addProperty("showOthersMobs", showOthersMobs);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** Both switches at once, for callers that only know the old single toggle. */
    public void setCustomCosmeticsEnabled(boolean value) {
        this.showSupporterNames = value;
        this.showSupporterSizes = value;
        version++;
    }

    public boolean isShowSupporterNames() {
        return showSupporterNames;
    }

    public void setShowSupporterNames(boolean value) {
        this.showSupporterNames = value;
        version++;
    }

    public boolean isShowSupporterSizes() {
        return showSupporterSizes;
    }

    public void setShowSupporterSizes(boolean value) {
        this.showSupporterSizes = value;
        version++;
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

    public boolean isOwnNameInF5() {
        return ownNameInF5;
    }

    public void setOwnNameInF5(boolean value) {
        this.ownNameInF5 = value;
        version++;
    }

    public boolean isShareTrail() {
        return shareTrail;
    }

    public void setShareTrail(boolean value) {
        this.shareTrail = value;
        version++;
    }

    public boolean isShowOthersTrails() {
        return showOthersTrails;
    }

    public void setShowOthersTrails(boolean value) {
        this.showOthersTrails = value;
        version++;
    }

    /** Your mob ({@code MobSkins} id), or "" for your normal player. */
    public String getOwnMob() {
        return ownMob;
    }

    /** Your mob as it is drawn and shared: "" while Play as Mob is off, otherwise {@link #getOwnMob()}. */
    public String getActiveOwnMob() {
        return playAsMob ? ownMob : "";
    }

    public boolean isPlayAsMob() {
        return playAsMob;
    }

    public void setPlayAsMob(boolean value) {
        this.playAsMob = value;
        version++;
    }

    /** Anything that is not an allowed mob id (null, "", a boss, a typo) means your normal player. */
    public void setOwnMob(String value) {
        this.ownMob = cleanMob(value);
        version++;
    }

    public boolean isShowOthersMobs() {
        return showOthersMobs;
    }

    public void setShowOthersMobs(boolean value) {
        this.showOthersMobs = value;
        version++;
    }

    private static String cleanMob(String id) {
        String ok = com.killer560.hub.mobskin.MobSkins.sanitize(id);
        return ok == null ? "" : ok;
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
