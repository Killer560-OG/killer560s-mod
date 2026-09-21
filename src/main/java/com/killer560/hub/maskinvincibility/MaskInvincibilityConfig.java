package com.killer560.hub.maskinvincibility;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Mask Invincibility Timer settings - see {@link MaskInvincibilityFeature}. Ships disabled
 *  by default. */
public final class MaskInvincibilityConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-maskinvincibility.json");

    private static MaskInvincibilityConfig instance;

    /** Bounds for {@link #getSwapStepDelayMs()} - the pause between two steps of one automated swap. */
    public static final int MIN_STEP_DELAY_MS = 150;
    public static final int MAX_STEP_DELAY_MS = 1000;

    private boolean enabled = false;
    private boolean announceInChat = true;
    private boolean announceToParty = false;
    private boolean showSpirit = true;
    private boolean showBonzo = true;
    private boolean showPhoenix = true;
    private boolean showItemIcons = false;
    private boolean hideMaskNames = false;
    private boolean autoSwapEnabled = false;
    private int swapStepDelayMs = 350;
    private String phoenixRodName = "Rod";
    private boolean rodReturnToPreviousSlot = true;
    private PhoenixRoute phoenixRoute = PhoenixRoute.ROD;

    /** How an automated swap puts Phoenix out. killer560's own route ("throw a rod to swap to phoenix", relying
     *  on a Hypixel Autopet rule) is {@link #ROD} and stays the default; {@link #PETS} is the {@code /pets} menu
     *  walk i4's old Auto Mask used before the two mods were unified onto {@link MaskSwapper} - restored here
     *  as a setting instead of a second implementation (2026-09-21 regression fix: i4 lost this route entirely,
     *  and {@link MaskSwapper#request} falls back to it on its own if the rod route can't find a rod). */
    public enum PhoenixRoute {
        ROD,
        PETS
    }

    private MaskInvincibilityConfig() {
    }

    public static MaskInvincibilityConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new MaskInvincibilityConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            MaskInvincibilityConfig cfg = new MaskInvincibilityConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.announceInChat = ConfigJson.getBool(obj, "announceInChat", true);
            cfg.announceToParty = ConfigJson.getBool(obj, "announceToParty", false);
            cfg.showSpirit = ConfigJson.getBool(obj, "showSpirit", true);
            cfg.showBonzo = ConfigJson.getBool(obj, "showBonzo", true);
            cfg.showPhoenix = ConfigJson.getBool(obj, "showPhoenix", true);
            cfg.showItemIcons = ConfigJson.getBool(obj, "showItemIcons", false);
            cfg.hideMaskNames = ConfigJson.getBool(obj, "hideMaskNames", false);
            cfg.swapStepDelayMs = clampDelay(ConfigJson.getInt(obj, "swapStepDelayMs", 350));
            cfg.phoenixRodName = ConfigJson.getString(obj, "phoenixRodName", "Rod");
            cfg.rodReturnToPreviousSlot = ConfigJson.getBool(obj, "rodReturnToPreviousSlot", true);
            cfg.phoenixRoute = ConfigJson.getEnum(obj, "phoenixRoute", PhoenixRoute.class, PhoenixRoute.ROD);
            // Real bug found and fixed (2026-09-14, pre-testing bug-review pass): this used to also gate
            // on CHEAT_FEATURES_ENABLED here, forcing the raw field itself to false in memory on a legit
            // build even if the saved file said true - isAutoSwapEnabled() below already applies that
            // same gate on every read, so gating here too just meant a legit-build session that saved ANY
            // other unrelated setting afterward would silently persist "false" back to disk, permanently
            // losing a cheat-build user's real setting the next time they switched builds.
            cfg.autoSwapEnabled = ConfigJson.getBool(obj, "autoSwapEnabled", false);
            instance = cfg;
        } catch (Exception e) {
            instance = new MaskInvincibilityConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("announceInChat", announceInChat);
            obj.addProperty("announceToParty", announceToParty);
            obj.addProperty("showSpirit", showSpirit);
            obj.addProperty("showBonzo", showBonzo);
            obj.addProperty("showPhoenix", showPhoenix);
            obj.addProperty("showItemIcons", showItemIcons);
            obj.addProperty("hideMaskNames", hideMaskNames);
            obj.addProperty("autoSwapEnabled", autoSwapEnabled);
            obj.addProperty("swapStepDelayMs", swapStepDelayMs);
            obj.addProperty("phoenixRodName", phoenixRodName);
            obj.addProperty("rodReturnToPreviousSlot", rodReturnToPreviousSlot);
            obj.addProperty("phoenixRoute", phoenixRoute.name());
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAnnounceInChat() {
        return announceInChat;
    }

    public void setAnnounceInChat(boolean announceInChat) {
        this.announceInChat = announceInChat;
    }

    /** killer560, 2026-09-21: "For announce in chat make it send to party chat not client side." Kept as its
     *  own toggle next to the client-side one (same shape as {@code BlessingTracker}'s pair) and default OFF,
     *  so nothing starts typing in his party until he turns it on. */
    public boolean isAnnounceToParty() {
        return announceToParty;
    }

    public void setAnnounceToParty(boolean announceToParty) {
        this.announceToParty = announceToParty;
    }

    public boolean isShowSpirit() {
        return showSpirit;
    }

    public void setShowSpirit(boolean showSpirit) {
        this.showSpirit = showSpirit;
    }

    public boolean isShowBonzo() {
        return showBonzo;
    }

    public void setShowBonzo(boolean showBonzo) {
        this.showBonzo = showBonzo;
    }

    public boolean isShowPhoenix() {
        return showPhoenix;
    }

    public void setShowPhoenix(boolean showPhoenix) {
        this.showPhoenix = showPhoenix;
    }

    /** Draws the real item's texture next to each timer line (the stack out of your own inventory when it is
     *  there, a generic head otherwise). */
    public boolean isShowItemIcons() {
        return showItemIcons;
    }

    public void setShowItemIcons(boolean showItemIcons) {
        this.showItemIcons = showItemIcons;
    }

    /** Only the icon and the time, no "Spirit Mask:" label. Needs {@link #isShowItemIcons()} on to mean anything. */
    public boolean isHideMaskNames() {
        return hideMaskNames;
    }

    public void setHideMaskNames(boolean hideMaskNames) {
        this.hideMaskNames = hideMaskNames;
    }

    /** Pause between two steps of one automated swap (command -> menu -> click -> close). */
    public int getSwapStepDelayMs() {
        return swapStepDelayMs;
    }

    public void setSwapStepDelayMs(int swapStepDelayMs) {
        this.swapStepDelayMs = clampDelay(swapStepDelayMs);
    }

    /** Hotbar item name (substring, case-insensitive) thrown to trigger the Autopet rule that summons Phoenix. */
    public String getPhoenixRodName() {
        return phoenixRodName == null || phoenixRodName.isBlank() ? "Rod" : phoenixRodName;
    }

    public void setPhoenixRodName(String phoenixRodName) {
        this.phoenixRodName = phoenixRodName == null ? "" : phoenixRodName;
    }

    /** Switch back to the slot you were holding once the rod has been thrown. */
    public boolean isRodReturnToPreviousSlot() {
        return rodReturnToPreviousSlot;
    }

    public void setRodReturnToPreviousSlot(boolean rodReturnToPreviousSlot) {
        this.rodReturnToPreviousSlot = rodReturnToPreviousSlot;
    }

    /** Which route {@link MaskSwapper} uses to put Phoenix out. See {@link PhoenixRoute}. */
    public PhoenixRoute getPhoenixRoute() {
        return phoenixRoute == null ? PhoenixRoute.ROD : phoenixRoute;
    }

    public void setPhoenixRoute(PhoenixRoute phoenixRoute) {
        this.phoenixRoute = phoenixRoute == null ? PhoenixRoute.ROD : phoenixRoute;
    }

    private static int clampDelay(int ms) {
        return Math.max(MIN_STEP_DELAY_MS, Math.min(MAX_STEP_DELAY_MS, ms));
    }

    /** Gated on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} - automatically swaps
     *  your worn mask, a real automation. */
    public boolean isAutoSwapEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && autoSwapEnabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public void setAutoSwapEnabled(boolean autoSwapEnabled) {
        this.autoSwapEnabled = autoSwapEnabled;
    }
}
