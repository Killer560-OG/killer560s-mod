package com.killer560.hub.partyfinder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted Party Finder Overlay settings ({@link PartyFinderOverlay}). The option set mirrors Devonian's
 * {@code PartyFinderHighlight} / {@code PartyFinderCount} / {@code PartyFinderOverview} features; colours that
 * Devonian hard-codes are exposed here so the overlay can be customised. The overlay itself defaults OFF; the
 * three parts default ON so switching the overlay on shows everything.
 */
public final class PartyFinderOverlayConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-partyfinderoverlay.json");

    /** Devonian: {@code Color.GREEN} / {@code Color.RED} / {@code -1}. */
    public static final int DEFAULT_JOINABLE_COLOR = 0xFF00FF00;
    public static final int DEFAULT_BLOCKED_COLOR = 0xFFFF0000;
    public static final int DEFAULT_COUNT_COLOR = 0xFFFFFFFF;

    /** Devonian "Overview PB": Both = S+ when it exists, otherwise S. */
    public enum PbMode {
        BOTH("Both"), S("S"), S_PLUS("S+");

        public final String label;

        PbMode(String label) {
            this.label = label;
        }
    }

    /** Devonian "Overview Compact". */
    public enum CompactMode {
        NONE("None"), STYLE1("Style 1"), STYLE2("Style 2"), CUSTOM("Custom");

        public final String label;

        CompactMode(String label) {
            this.label = label;
        }
    }

    private static PartyFinderOverlayConfig instance;

    private boolean enabled = false;

    private boolean highlight = true;
    private boolean ignoreCataRequirement = false;
    private boolean ignoreRoleLevel = false;
    private boolean ignoreOwnRole = false;
    private int joinableColor = DEFAULT_JOINABLE_COLOR;
    private int blockedColor = DEFAULT_BLOCKED_COLOR;

    private boolean memberCount = true;
    private int countColor = DEFAULT_COUNT_COLOR;

    private boolean tooltip = true;
    private boolean showMissing = true;
    private PbMode pbMode = PbMode.BOTH;
    private CompactMode compactMode = CompactMode.NONE;
    private String customStyle = "";
    private boolean rankNameColors = false;
    /** Settings-tab "live preview of the selected style" (killer560 7.2) - a preview panel, not an in-game
     *  behaviour change, so unlike a new automation feature this defaults ON. */
    private boolean stylePreview = true;

    private PartyFinderOverlayConfig() {
    }

    public static PartyFinderOverlayConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        PartyFinderOverlayConfig cfg = new PartyFinderOverlayConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
                cfg.highlight = ConfigJson.getBool(obj, "highlight", true);
                cfg.ignoreCataRequirement = ConfigJson.getBool(obj, "ignoreCataRequirement", false);
                cfg.ignoreRoleLevel = ConfigJson.getBool(obj, "ignoreRoleLevel", false);
                cfg.ignoreOwnRole = ConfigJson.getBool(obj, "ignoreOwnRole", false);
                cfg.joinableColor = ConfigJson.getInt(obj, "joinableColor", DEFAULT_JOINABLE_COLOR);
                cfg.blockedColor = ConfigJson.getInt(obj, "blockedColor", DEFAULT_BLOCKED_COLOR);
                cfg.memberCount = ConfigJson.getBool(obj, "memberCount", true);
                cfg.countColor = ConfigJson.getInt(obj, "countColor", DEFAULT_COUNT_COLOR);
                cfg.tooltip = ConfigJson.getBool(obj, "tooltip", true);
                cfg.showMissing = ConfigJson.getBool(obj, "showMissing", true);
                cfg.pbMode = ConfigJson.getEnum(obj, "pbMode", PbMode.class, PbMode.BOTH);
                cfg.compactMode = ConfigJson.getEnum(obj, "compactMode", CompactMode.class, CompactMode.NONE);
                cfg.setCustomStyle(ConfigJson.getString(obj, "customStyle", ""));
                cfg.rankNameColors = ConfigJson.getBool(obj, "rankNameColors", false);
                cfg.stylePreview = ConfigJson.getBool(obj, "stylePreview", true);
            } catch (Exception ignored) {
                // unreadable file - defaults
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("highlight", highlight);
            obj.addProperty("ignoreCataRequirement", ignoreCataRequirement);
            obj.addProperty("ignoreRoleLevel", ignoreRoleLevel);
            obj.addProperty("ignoreOwnRole", ignoreOwnRole);
            obj.addProperty("joinableColor", joinableColor);
            obj.addProperty("blockedColor", blockedColor);
            obj.addProperty("memberCount", memberCount);
            obj.addProperty("countColor", countColor);
            obj.addProperty("tooltip", tooltip);
            obj.addProperty("showMissing", showMissing);
            obj.addProperty("pbMode", pbMode.name());
            obj.addProperty("compactMode", compactMode.name());
            obj.addProperty("customStyle", customStyle);
            obj.addProperty("rankNameColors", rankNameColors);
            obj.addProperty("stylePreview", stylePreview);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    /** Raw toggle state for the settings tab (ignores the Skyblock gate). */
    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isHighlight() {
        return highlight;
    }

    public void setHighlight(boolean highlight) {
        this.highlight = highlight;
    }

    public boolean isIgnoreCataRequirement() {
        return ignoreCataRequirement;
    }

    public void setIgnoreCataRequirement(boolean v) {
        this.ignoreCataRequirement = v;
    }

    public boolean isIgnoreRoleLevel() {
        return ignoreRoleLevel;
    }

    public void setIgnoreRoleLevel(boolean v) {
        this.ignoreRoleLevel = v;
    }

    public boolean isIgnoreOwnRole() {
        return ignoreOwnRole;
    }

    public void setIgnoreOwnRole(boolean v) {
        this.ignoreOwnRole = v;
    }

    public int getJoinableColor() {
        return joinableColor;
    }

    public void setJoinableColor(int argb) {
        this.joinableColor = argb;
    }

    public int getBlockedColor() {
        return blockedColor;
    }

    public void setBlockedColor(int argb) {
        this.blockedColor = argb;
    }

    public boolean isMemberCount() {
        return memberCount;
    }

    public void setMemberCount(boolean memberCount) {
        this.memberCount = memberCount;
    }

    public int getCountColor() {
        return countColor;
    }

    public void setCountColor(int argb) {
        this.countColor = argb;
    }

    public boolean isTooltip() {
        return tooltip;
    }

    public void setTooltip(boolean tooltip) {
        this.tooltip = tooltip;
    }

    public boolean isShowMissing() {
        return showMissing;
    }

    public void setShowMissing(boolean showMissing) {
        this.showMissing = showMissing;
    }

    public PbMode getPbMode() {
        return pbMode;
    }

    public void setPbMode(PbMode pbMode) {
        this.pbMode = pbMode == null ? PbMode.BOTH : pbMode;
    }

    public CompactMode getCompactMode() {
        return compactMode;
    }

    public void setCompactMode(CompactMode compactMode) {
        this.compactMode = compactMode == null ? CompactMode.NONE : compactMode;
    }

    public String getCustomStyle() {
        return customStyle;
    }

    public void setCustomStyle(String customStyle) {
        this.customStyle = customStyle == null ? "" : customStyle;
    }

    public boolean isRankNameColors() {
        return rankNameColors;
    }

    public void setRankNameColors(boolean rankNameColors) {
        this.rankNameColors = rankNameColors;
    }

    public boolean isStylePreview() {
        return stylePreview;
    }

    public void setStylePreview(boolean stylePreview) {
        this.stylePreview = stylePreview;
    }
}
