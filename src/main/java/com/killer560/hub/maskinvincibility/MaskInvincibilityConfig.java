package com.killer560.hub.maskinvincibility;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Mask Invincibility Timer settings - see {@link MaskInvincibilityFeature}. Ships disabled
 *  by default. */
public final class MaskInvincibilityConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-maskinvincibility.json");

    private static MaskInvincibilityConfig instance;

    /** Bounds for {@link #getSwapStepDelayTicks()} - the pause between two steps of one automated swap, in whole
     *  client ticks (killer560, 2026-10-16: "change the mask ms timer to be in ticks"). The old millisecond range was
     *  150-1000, i.e. 3-20 ticks; 0 was never allowed, so the floor stays 3. */
    public static final int MIN_STEP_DELAY_TICKS = 3;
    public static final int MAX_STEP_DELAY_TICKS = 20;
    public static final int DEFAULT_STEP_DELAY_TICKS = 7;

    private boolean enabled = false;
    private boolean announceToParty = false;
    private boolean showSpirit = true;
    private boolean showBonzo = true;
    private boolean showPhoenix = true;
    private boolean showItemIcons = false;
    private boolean hideMaskNames = false;
    private boolean onlyInDungeons = false;
    private boolean bossOnly = false;
    private boolean autoSwapEnabled = false;
    private AutoMaskMode autoMaskMode = AutoMaskMode.FULL_AUTO;
    private SwapOrder swapOrder = SwapOrder.MASK_FIRST;
    private int swapKey = com.killer560.hub.util.KeyUtil.NONE;
    private boolean autoMaskDungeonsOnly = true;
    private boolean autoMaskBossOnly = false;
    private int swapStepDelayTicks = DEFAULT_STEP_DELAY_TICKS;
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

    /** killer560, 2026-10-16: "for auto mask have a swap keybind and a full auto mode". {@link #KEYBIND} swaps only
     *  when {@link #getSwapKey()} is pressed; {@link #FULL_AUTO} swaps the moment the worn mask (or Phoenix) procs,
     *  which is what Auto Swap always did. */
    public enum AutoMaskMode {
        KEYBIND("Keybind"),
        FULL_AUTO("Full Auto");

        private final String label;

        AutoMaskMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** killer560, 2026-10-16: "an option of swap mask first or swap phoenix first". What an Auto Mask swap tries
     *  next: {@link #MASK_FIRST} the other mask (Spirit before Bonzo when none is worn), then Phoenix;
     *  {@link #PHOENIX_FIRST} Phoenix, then the masks. */
    public enum SwapOrder {
        MASK_FIRST("Mask First"),
        PHOENIX_FIRST("Phoenix First");

        private final String label;

        SwapOrder(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
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
            cfg.announceToParty = ConfigJson.getBool(obj, "announceToParty", false);
            cfg.showSpirit = ConfigJson.getBool(obj, "showSpirit", true);
            cfg.showBonzo = ConfigJson.getBool(obj, "showBonzo", true);
            cfg.showPhoenix = ConfigJson.getBool(obj, "showPhoenix", true);
            cfg.showItemIcons = ConfigJson.getBool(obj, "showItemIcons", false);
            cfg.hideMaskNames = ConfigJson.getBool(obj, "hideMaskNames", false);
            cfg.onlyInDungeons = ConfigJson.getBool(obj, "onlyInDungeons", false);
            cfg.bossOnly = ConfigJson.getBool(obj, "bossOnly", false);
            // Ticks since 2026-10-16. A file from before that has only the millisecond key: ms / 50, rounded, so he
            // keeps about the same pause (350 ms -> 7 ticks). The ms key is not written again.
            if (obj.has("swapStepDelayTicks")) {
                cfg.swapStepDelayTicks = clampDelay(ConfigJson.getInt(obj, "swapStepDelayTicks", DEFAULT_STEP_DELAY_TICKS));
            } else if (obj.has("swapStepDelayMs")) {
                cfg.swapStepDelayTicks = clampDelay((int) Math.round(ConfigJson.getInt(obj, "swapStepDelayMs", 350) / 50.0));
            }
            // A file from before Mode existed only had the one Auto Swap switch, which always swapped on a proc:
            // that is Full Auto, so a missing key reads as Full Auto and the switch itself keeps its saved value.
            cfg.autoMaskMode = ConfigJson.getEnum(obj, "autoMaskMode", AutoMaskMode.class, AutoMaskMode.FULL_AUTO);
            cfg.swapOrder = ConfigJson.getEnum(obj, "autoMaskSwapOrder", SwapOrder.class, SwapOrder.MASK_FIRST);
            cfg.swapKey = com.killer560.hub.util.KeyUtil.sanitizeBind(
                    ConfigJson.getInt(obj, "autoMaskSwapKey", com.killer560.hub.util.KeyUtil.NONE));
            cfg.autoMaskDungeonsOnly = ConfigJson.getBool(obj, "autoMaskDungeonsOnly", true);
            cfg.autoMaskBossOnly = ConfigJson.getBool(obj, "autoMaskBossOnly", false);
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
            obj.addProperty("announceToParty", announceToParty);
            obj.addProperty("showSpirit", showSpirit);
            obj.addProperty("showBonzo", showBonzo);
            obj.addProperty("showPhoenix", showPhoenix);
            obj.addProperty("showItemIcons", showItemIcons);
            obj.addProperty("hideMaskNames", hideMaskNames);
            obj.addProperty("onlyInDungeons", onlyInDungeons);
            obj.addProperty("bossOnly", bossOnly);
            obj.addProperty("autoSwapEnabled", autoSwapEnabled);
            obj.addProperty("autoMaskMode", getAutoMaskMode().name());
            obj.addProperty("autoMaskSwapOrder", getSwapOrder().name());
            obj.addProperty("autoMaskSwapKey", swapKey);
            obj.addProperty("autoMaskDungeonsOnly", autoMaskDungeonsOnly);
            obj.addProperty("autoMaskBossOnly", autoMaskBossOnly);
            obj.addProperty("swapStepDelayTicks", swapStepDelayTicks);
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

    /** killer560, 2026-09-21: "For announce in chat make it send to party chat not client side." Kept as its
     *  own toggle next to the client-side one (same shape as {@code BlessingTracker}'s pair) and default OFF,
     *  so nothing starts typing in his party until he turns it on. */
    public boolean isAnnounceToParty() {
        return announceToParty;
    }

    public void setAnnounceToParty(boolean announceToParty) {
        this.announceToParty = announceToParty;
    }

    /** killer560 9.1: "allow them to select which invulnerabilities they actually own" - not just a HUD
     *  display toggle any more, since a proc timer for a mask you don't have is dead weight either way.
     *  {@link MaskInvincibilityFeature#trySwapMask()} also skips Auto Swap-ing to a type this is off for. */
    public boolean isShowSpirit() {
        return showSpirit;
    }

    public void setShowSpirit(boolean showSpirit) {
        this.showSpirit = showSpirit;
    }

    /** @see #isShowSpirit() */
    public boolean isShowBonzo() {
        return showBonzo;
    }

    public void setShowBonzo(boolean showBonzo) {
        this.showBonzo = showBonzo;
    }

    /** @see #isShowSpirit() */
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

    /** killer560 9.1: "add an option to only show the invulnerabilites in dungeons". */
    public boolean isOnlyInDungeons() {
        return onlyInDungeons;
    }

    public void setOnlyInDungeons(boolean onlyInDungeons) {
        this.onlyInDungeons = onlyInDungeons;
    }

    /** killer560 9.1: "and one for boss only" - same shape as {@code SecretsConfig#isBossOnly()}. Only
     *  meaningful together with {@link #isOnlyInDungeons()} (a boss fight is still a dungeon), but kept as
     *  its own independent flag rather than nested under it, the same way Secrets' own Boss Only stands
     *  alone next to its dungeon gating. */
    public boolean isBossOnly() {
        return bossOnly;
    }

    public void setBossOnly(boolean bossOnly) {
        this.bossOnly = bossOnly;
    }

    /** Pause between two steps of one automated swap (command -> menu -> click -> close), in whole client ticks. */
    public int getSwapStepDelayTicks() {
        return swapStepDelayTicks;
    }

    public void setSwapStepDelayTicks(int swapStepDelayTicks) {
        this.swapStepDelayTicks = clampDelay(swapStepDelayTicks);
    }

    /** Keybind or Full Auto - see {@link AutoMaskMode}. */
    public AutoMaskMode getAutoMaskMode() {
        return autoMaskMode == null ? AutoMaskMode.FULL_AUTO : autoMaskMode;
    }

    public void setAutoMaskMode(AutoMaskMode autoMaskMode) {
        this.autoMaskMode = autoMaskMode == null ? AutoMaskMode.FULL_AUTO : autoMaskMode;
    }

    /** Mask First or Phoenix First - see {@link SwapOrder}. */
    public SwapOrder getSwapOrder() {
        return swapOrder == null ? SwapOrder.MASK_FIRST : swapOrder;
    }

    public void setSwapOrder(SwapOrder swapOrder) {
        this.swapOrder = swapOrder == null ? SwapOrder.MASK_FIRST : swapOrder;
    }

    /** The Keybind mode's key or mouse button ({@link com.killer560.hub.util.KeyUtil} code), unbound by default. */
    public int getSwapKey() {
        return swapKey;
    }

    public void setSwapKey(int swapKey) {
        this.swapKey = com.killer560.hub.util.KeyUtil.sanitizeBind(swapKey);
    }

    /** Auto Mask never acts outside the Catacombs (default on). Separate from the HUD's {@link #isOnlyInDungeons()}. */
    public boolean isAutoMaskDungeonsOnly() {
        return autoMaskDungeonsOnly;
    }

    public void setAutoMaskDungeonsOnly(boolean autoMaskDungeonsOnly) {
        this.autoMaskDungeonsOnly = autoMaskDungeonsOnly;
    }

    /** Auto Mask acts only in the floor's boss fight, never in the clear (default off); implies Dungeons Only.
     *  Separate from the HUD's {@link #isBossOnly()}. */
    public boolean isAutoMaskBossOnly() {
        return autoMaskBossOnly;
    }

    public void setAutoMaskBossOnly(boolean autoMaskBossOnly) {
        this.autoMaskBossOnly = autoMaskBossOnly;
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

    private static int clampDelay(int ticks) {
        return Math.max(MIN_STEP_DELAY_TICKS, Math.min(MAX_STEP_DELAY_TICKS, ticks));
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
