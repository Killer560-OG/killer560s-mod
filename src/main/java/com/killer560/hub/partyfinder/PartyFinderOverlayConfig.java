package com.killer560.hub.partyfinder;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Persisted Party Finder Overlay settings ({@link PartyFinderOverlay}). The option set mirrors Devonian's
 * {@code PartyFinderHighlight} / {@code PartyFinderCount} / {@code PartyFinderOverview} features; colors that
 * Devonian hard-codes are exposed here so the overlay can be customized. The overlay itself defaults OFF; the
 * three parts default ON so switching the overlay on shows everything.
 */
public final class PartyFinderOverlayConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-partyfinderoverlay.json");

    /** Devonian: {@code Color.GREEN} / {@code Color.RED} / {@code -1}. */
    public static final int DEFAULT_JOINABLE_COLOR = 0xFF00FF00;
    public static final int DEFAULT_BLOCKED_COLOR = 0xFFFF0000;
    public static final int DEFAULT_COUNT_COLOR = 0xFFFFFFFF;

    /** Devonian "Overview PB": Both = S+ when it exists, otherwise S - and here, failing both, the floor's fastest
     *  clear at any score (gray, labeled "Any"), so a player with runs but no S is not shown as "NO PB". */
    public enum PbMode {
        BOTH("Both"), S("S"), S_PLUS("S+");

        public final String label;

        PbMode(String label) {
            this.label = label;
        }
    }

    /** Devonian "Overview Compact", plus (2026-10-15) five more built-in styles. Every style but None and Custom is a
     *  template in the same placeholder language Custom uses ({@link PartyFinderOverlay#PLACEHOLDERS}), which is also
     *  what "Load Built-in" copies into the Custom editor to start from. The enum names are what is saved. */
    public enum CompactMode {
        NONE("None", null),
        STYLE1("Style 1", "&8[$RoleColor$RoleSingle&8] $NameColor$Name &8[&e$RoleLevel &7| &6$Cata&8] "
                + "&8[&3$SecretsShort &7| &b$SecretShortAvg&8] &8[$PB&8]"),
        STYLE2("Style 2", "&8[$RoleColor$RoleSingle &e$RoleLevel&8] $NameColor$Name &8[&6$Cata &7| &3$SecretsShort "
                + "&7| &b$SecretShortAvg&8] $PB"),
        COMPACT("Compact", "$RoleColor$RoleSingle $NameColor$Name &7$RoleLevel &8| &6$Cata &8| $PB"),
        MINIMAL("Minimal", "$NameColor$Name &8(&6$Cata&8) $PB"),
        DETAILED("Detailed", "&8[$RoleColor$Role &e$RoleLevel&8] $NameColor$Name &8- &6Cata $Cata &8- &3$Secrets "
                + "&7secrets &8(&b$SecretAvg&7 avg&8) &8- $PB"),
        STAT_ROW("Stat Row", "$NameColor$Name &8>> &e$RoleLevel &7/ &6$Cata &7/ &3$SecretsShort &7/ "
                + "&b$SecretShortAvg &7/ $PB"),
        CLASS_TAG("Class Tag", "$RoleColor$RoleShort &8| $NameColor$Name &8| &6$Cata &8| &3$SecretsShort &8| $PB"),
        CUSTOM("Custom", null);

        public final String label;
        /** The style as a placeholder template; null for None (Hypixel's own line plus a suffix) and Custom. */
        public final String template;

        CompactMode(String label, String template) {
            this.label = label;
            this.template = template;
        }
    }

    /** Most named custom styles kept, the longest name, and the longest template. */
    public static final int MAX_SAVED_STYLES = 32;
    public static final int MAX_STYLE_NAME = 24;
    public static final int MAX_STYLE_LENGTH = 512;

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
    /** Named custom styles (name -&gt; template), in the order they were first saved; {@link #activeStyle} is the one
     *  last saved or loaded (the Custom editor's "Saved Style" button), "" when the text is not from one. */
    private final Map<String, String> savedStyles = new LinkedHashMap<>();
    private String activeStyle = "";
    private boolean rankNameColors = false;

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
                if (obj.has("savedStyles") && obj.get("savedStyles").isJsonArray()) {
                    for (JsonElement el : obj.getAsJsonArray("savedStyles")) {
                        if (el.isJsonObject()) {
                            cfg.putSaved(ConfigJson.getString(el.getAsJsonObject(), "name", ""),
                                    ConfigJson.getString(el.getAsJsonObject(), "style", ""));
                        }
                    }
                }
                String active = ConfigJson.getString(obj, "activeStyle", "");
                cfg.activeStyle = cfg.findSavedName(active) != null ? cfg.findSavedName(active) : "";
                cfg.rankNameColors = ConfigJson.getBool(obj, "rankNameColors", false);
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
            JsonArray saved = new JsonArray();
            savedStyles.forEach((name, text) -> {
                JsonObject entry = new JsonObject();
                entry.addProperty("name", name);
                entry.addProperty("style", text);
                saved.add(entry);
            });
            obj.add("savedStyles", saved);
            obj.addProperty("activeStyle", activeStyle);
            obj.addProperty("rankNameColors", rankNameColors);
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
        String t = customStyle == null ? "" : customStyle;
        this.customStyle = t.length() > MAX_STYLE_LENGTH ? t.substring(0, MAX_STYLE_LENGTH) : t;
    }

    // ---- named custom styles (2026-10-15: "a better way for them to make and save a custom style") ----

    /** A style name: control characters and edges stripped, at most {@link #MAX_STYLE_NAME} long; null when nothing is left. */
    public static String cleanStyleName(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.replaceAll("\\p{Cntrl}", "").strip();
        if (t.length() > MAX_STYLE_NAME) {
            t = t.substring(0, MAX_STYLE_NAME).strip();
        }
        return t.isEmpty() ? null : t;
    }

    private String findSavedName(String name) {
        if (name == null) {
            return null;
        }
        for (String n : savedStyles.keySet()) {
            if (n.equalsIgnoreCase(name.strip())) {
                return n;
            }
        }
        return null;
    }

    private void putSaved(String rawName, String text) {
        String name = cleanStyleName(rawName);
        if (name == null || text == null || savedStyles.size() >= MAX_SAVED_STYLES && findSavedName(name) == null) {
            return;
        }
        String existing = findSavedName(name);
        savedStyles.put(existing != null ? existing : name,
                text.length() > MAX_STYLE_LENGTH ? text.substring(0, MAX_STYLE_LENGTH) : text);
    }

    /** The saved style names, in the order they were first saved. */
    public List<String> savedStyleNames() {
        return new ArrayList<>(savedStyles.keySet());
    }

    /** The template saved under {@code name} (case-insensitive), or null. */
    public String savedStyle(String name) {
        String n = findSavedName(name);
        return n == null ? null : savedStyles.get(n);
    }

    /** The saved style the Custom editor is on, or "" when its text is not from one. */
    public String getActiveStyle() {
        return activeStyle;
    }

    /** Saves {@code text} under {@code name} (replacing a style of that name, whatever its case), makes it the active
     *  one and the current custom text. @return false for a bad name or a full list. */
    public boolean saveStyle(String name, String text) {
        String clean = cleanStyleName(name);
        if (clean == null || text == null || text.isBlank()
                || savedStyles.size() >= MAX_SAVED_STYLES && findSavedName(clean) == null) {
            return false;
        }
        putSaved(clean, text);
        activeStyle = findSavedName(clean);
        setCustomStyle(text);
        return true;
    }

    /** Makes the style saved as {@code name} the current custom text and selects Custom. @return false when none. */
    public boolean loadStyle(String name) {
        String n = findSavedName(name);
        if (n == null) {
            return false;
        }
        activeStyle = n;
        setCustomStyle(savedStyles.get(n));
        compactMode = CompactMode.CUSTOM;
        return true;
    }

    /** Forgets the style saved as {@code name}; the current text stays. @return false when there was none. */
    public boolean deleteStyle(String name) {
        String n = findSavedName(name);
        if (n == null) {
            return false;
        }
        savedStyles.remove(n);
        if (n.equals(activeStyle)) {
            activeStyle = "";
        }
        return true;
    }

    /** Leaves the active saved style without touching the text (the editor's "no saved style" step). */
    public void clearActiveStyle() {
        activeStyle = "";
    }

    /** Edits made to the text no longer match the saved style it came from: the editor marks it. */
    public boolean isActiveStyleModified() {
        return !activeStyle.isEmpty() && !customStyle.equals(savedStyles.get(activeStyle));
    }

    /** Copies a built-in style's template into the custom text (the editor's "Load Built-in"), selecting Custom. */
    public void startFromBuiltIn(CompactMode mode) {
        if (mode == null || mode.template == null) {
            return;
        }
        activeStyle = "";
        setCustomStyle(mode.template);
        compactMode = CompactMode.CUSTOM;
    }

    public boolean isRankNameColors() {
        return rankNameColors;
    }

    public void setRankNameColors(boolean rankNameColors) {
        this.rankNameColors = rankNameColors;
    }
}
