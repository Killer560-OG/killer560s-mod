package com.killer560.hub.scoreboard;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Persisted Custom Scoreboard settings - see {@link CustomScoreboardFeature}. Every field survives a restart,
 * including which lines, events and chunked stats are on the board and their order ({@link EntryOrder}). Position/scale live in {@code HudConfig}
 * under {@link CustomScoreboardFeature#ELEMENT_ID}. Defaults mirror SkyHanni's CustomScoreboardConfig/DisplayConfig/
 * BackgroundConfig/InformationFilteringConfig (plus a few SkyBlock Custom Scoreboard options), with the colours swapped
 * to this mod's orange theme. Cached game data (Maxwell, cookie buff, quiver, mayor) lives in
 * {@link ScoreboardExtraData}'s own file.
 */
public final class CustomScoreboardConfig {

    public enum Align {
        LEFT("Left"), CENTER("Center"), RIGHT("Right");

        public final String label;

        Align(String label) {
            this.label = label;
        }

        public Align next() {
            return values()[(ordinal() + 1) % values().length];
        }

        public Align previous() {
            return values()[(values().length + ordinal() - 1) % values().length];
        }
    }

    public enum HorizontalSnap {
        NONE("Free"), LEFT("Left"), CENTER("Center"), RIGHT("Right");

        public final String label;

        HorizontalSnap(String label) {
            this.label = label;
        }

        public HorizontalSnap next() {
            return values()[(ordinal() + 1) % values().length];
        }

        public HorizontalSnap previous() {
            return values()[(values().length + ordinal() - 1) % values().length];
        }
    }

    public enum VerticalSnap {
        NONE("Free"), TOP("Top"), CENTER("Center"), BOTTOM("Bottom");

        public final String label;

        VerticalSnap(String label) {
            this.label = label;
        }

        public VerticalSnap next() {
            return values()[(ordinal() + 1) % values().length];
        }

        public VerticalSnap previous() {
            return values()[(values().length + ordinal() - 1) % values().length];
        }
    }

    /** Which screen edge the board is anchored to (2026-10-15): the board grows away from it, so a right-anchored board
     *  that gets a longer line grows to the left instead of off the screen. */
    public enum Side {
        LEFT("Left"), RIGHT("Right");

        public final String label;

        Side(String label) {
            this.label = label;
        }
    }

    public enum NumberFormat {
        LONG("Long"), SHORT("Short");

        public final String label;

        NumberFormat(String label) {
            this.label = label;
        }

        public NumberFormat next() {
            return values()[(ordinal() + 1) % values().length];
        }

        public NumberFormat previous() {
            return values()[(values().length + ordinal() - 1) % values().length];
        }
    }

    /** SkyHanni's CustomScoreboardUtils.NumberDisplayFormat. */
    public enum NumberDisplayFormat {
        TEXT_COLOR_NUMBER("§fPurse: §6123"),
        COLOR_TEXT_NUMBER("§6Purse: 123"),
        COLOR_NUMBER_TEXT("§6123 Purse"),
        COLOR_NUMBER_RESET_TEXT("§6123 §fPurse");

        public final String label;

        NumberDisplayFormat(String label) {
            this.label = label;
        }

        public NumberDisplayFormat next() {
            return values()[(ordinal() + 1) % values().length];
        }

        public NumberDisplayFormat previous() {
            return values()[(values().length + ordinal() - 1) % values().length];
        }
    }

    /** What to show outside Skyblock / p3sim. The vanilla sidebar is never hidden there either way. */
    public enum OutsideSkyblockMode {
        VANILLA("Vanilla"), MINIMAL("Minimal Board");

        public final String label;

        OutsideSkyblockMode(String label) {
            this.label = label;
        }

        public OutsideSkyblockMode next() {
            return values()[(ordinal() + 1) % values().length];
        }

        public OutsideSkyblockMode previous() {
            return values()[(values().length + ordinal() - 1) % values().length];
        }
    }

    /** SkyHanni/SkyBlock Custom Scoreboard's DateFormat (used by the Lobby Code line). */
    public enum DateFormat {
        US_SLASH_MMDDYY("MM/dd/yy"),
        US_SLASH_MMDDYYYY("MM/dd/yyyy"),
        US_DASH_MMDDYYYY("MM-dd-yyyy"),
        UK_DDMMYYYY("dd/MM/yyyy"),
        UK_DASH_DDMMYYYY("dd-MM-yyyy"),
        ISO_YYYYMMDD("yyyy-MM-dd"),
        YEAR_MONTH_DAY("yyyy/MM/dd"),
        DAY_MONTH_YEAR("dd MMMM yyyy"),
        DAY_MONTH_YEAR_SHORT("dd MMM yyyy"),
        FULL_MONTH_DAY_YEAR("MMMM dd, yyyy"),
        SHORT_MONTH_DAY_YEAR("MMM dd, yyyy");

        public final String pattern;
        private final DateTimeFormatter formatter;

        DateFormat(String pattern) {
            this.pattern = pattern;
            this.formatter = DateTimeFormatter.ofPattern(pattern, Locale.US);
        }

        public String today() {
            return LocalDate.now().format(formatter);
        }

        public DateFormat next() {
            return values()[(ordinal() + 1) % values().length];
        }

        public DateFormat previous() {
            return values()[(values().length + ordinal() - 1) % values().length];
        }
    }

    /** SkyHanni's DisplayConfig.PowderDisplay. */
    public enum PowderDisplay {
        AVAILABLE("Available"), TOTAL("Total"), BOTH("Available/Total");

        public final String label;

        PowderDisplay(String label) {
            this.label = label;
        }

        public PowderDisplay next() {
            return values()[(ordinal() + 1) % values().length];
        }

        public PowderDisplay previous() {
            return values()[(values().length + ordinal() - 1) % values().length];
        }
    }

    public enum ArrowDisplay {
        NUMBER("Number"), PERCENTAGE("Percentage");

        public final String label;

        ArrowDisplay(String label) {
            this.label = label;
        }

        public ArrowDisplay next() {
            return values()[(ordinal() + 1) % values().length];
        }

        public ArrowDisplay previous() {
            return values()[(values().length + ordinal() - 1) % values().length];
        }
    }

    /** A plain translucent black like the vanilla sidebar's (2026-10-15, "make the custom scoreboard simpler"); the title
     *  row is drawn a little darker over it, as vanilla does. */
    public static final int DEFAULT_BACKGROUND_COLOR = 0x50000000;
    /** The look before 2026-10-15 (orange-tinted box, 5 px padding, rounded corners, orange border). A saved config that
     *  still holds one of these old defaults is moved to the new one once ({@link #LOOK_VERSION}); a value he changed is kept. */
    private static final int OLD_DEFAULT_BACKGROUND_COLOR = 0xAA1A1108;
    private static final int OLD_DEFAULT_PADDING = 5;
    private static final int LOOK_VERSION = 2;
    /** Gap between the board and the edge it is anchored to, in GUI pixels: the vanilla sidebar's 1. */
    public static final int DEFAULT_EDGE_GAP = 1;
    public static final int DEFAULT_BORDER_COLOR = 0xFFCC6600;
    public static final int DEFAULT_BORDER_BOTTOM_COLOR = 0xFFFFA040;
    public static final String DEFAULT_TITLE = "&&6&&lSKYBLOCK";
    public static final String DEFAULT_FOOTER = "&&ewww.hypixel.net";
    public static final String DEFAULT_ALPHA_FOOTER = "&&ealpha.hypixel.net";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-customscoreboard.json");
    /** Folder for the background image ({@code background.png}) and {@link ScoreboardExtraData}'s cache. */
    public static final Path DATA_DIR = ModPaths.config("killer560smod-customscoreboard");

    private static CustomScoreboardConfig instance;

    private boolean enabled = false;
    private boolean hideVanillaScoreboard = true;
    private boolean useCustomLines = true;
    private Align textAlignment = Align.LEFT;
    private Align titleAlignment = Align.CENTER;
    private Align footerAlignment = Align.LEFT;
    private boolean textShadow = true;
    private int lineSpacing = 1;
    /** Before 2026-10-15 the board was placed by SkyHanni's snap (Snap X / Snap Y / Screen Margin) or, once dragged,
     *  by an absolute HudConfig x. Read only to convert an old save into the anchor below; never written again. */
    private HorizontalSnap horizontalSnap = HorizontalSnap.RIGHT;
    private VerticalSnap verticalSnap = VerticalSnap.CENTER;
    /** Where the board sits (2026-10-15): against this edge, {@link #edgeGap} pixels in, with its middle
     *  {@link #centerOffsetY} pixels below the screen's middle - so it stays against the right edge, vertically
     *  centered like the vanilla sidebar, at every GUI scale and window size. Set by dragging it in Edit HUD. */
    private Side anchorSide = Side.RIGHT;
    private int edgeGap = DEFAULT_EDGE_GAP;
    private int centerOffsetY = 0;
    /** An old save placed somewhere other than right/center: converted from where it was drawn, on the first frame. */
    private boolean positionPending = false;
    /** The editor's Settings page Advanced section is open (remembered). */
    private boolean editorAdvancedOpen = false;
    private NumberFormat numberFormat = NumberFormat.LONG;
    private NumberDisplayFormat numberDisplayFormat = NumberDisplayFormat.TEXT_COLOR_NUMBER;
    private boolean useCustomTitle = true;
    private String customTitle = DEFAULT_TITLE;
    private String customFooter = DEFAULT_FOOTER;
    private String customAlphaFooter = DEFAULT_ALPHA_FOOTER;
    private boolean useCustomTitleOutsideSkyblock = false;
    private boolean hideEmptyLines = true;
    private boolean hideConsecutiveEmptyLines = true;
    private boolean hideEmptyLinesAtTopAndBottom = true;
    private boolean hideIrrelevantLines = true;
    private boolean showProfileName = false;
    private boolean showPartyEverywhere = false;
    private int maxPartyMembers = 4;
    private boolean showAllActiveEvents = true;
    // general (SkyHanni DisplayConfig / CustomScoreboardConfig + SkyBlock Custom Scoreboard Config)
    private boolean hideWhenTab = false;
    private boolean hideWhenChat = false;
    private OutsideSkyblockMode outsideSkyblockMode = OutsideSkyblockMode.VANILLA;
    private boolean cacheOnIslandSwitch = false;
    /** Chat note for an unknown sidebar line. Off by default since 2026-10-07 (killer560's dungeon screenshot:
     *  "[Custom Scoreboard] Unknown scoreboard line: Solo" in chat mid-run): it is a debugging aid, and every unknown
     *  line is written to the log once regardless. Saved under a NEW key, "unknownLinesChat", so the old key's saved
     *  true (written by every save before this, whether or not he ever touched it) does not keep it on. */
    private boolean unknownLinesWarning = false;
    private boolean lineActions = true;
    private boolean showNumberDifference = false;
    private boolean dateInLobbyCode = true;
    private DateFormat dateFormat = DateFormat.US_SLASH_MMDDYY;
    private boolean time24h = false;
    private boolean timeExactMinutes = false;
    private boolean separatorBetweenEvents = false;
    // line options
    private boolean showMayorPerks = true;
    private boolean showMayorTime = true;
    private boolean showMinister = true;
    private boolean showJerryMayor = true;
    private boolean showUnclaimedBits = false;
    private PowderDisplay powderDisplay = PowderDisplay.AVAILABLE;
    private boolean hidePurseInDungeons = false;
    private boolean showMagicalPower = true;
    private boolean compactTuning = false;
    private int tuningAmount = 2;
    private ArrowDisplay arrowDisplay = ArrowDisplay.NUMBER;
    private boolean colorArrowAmount = false;
    private boolean showMaxIslandPlayers = true;
    private boolean showPartyLeader = true;
    private int statsPerLine = 3;
    // background
    private boolean backgroundEnabled = true;
    private int backgroundColor = DEFAULT_BACKGROUND_COLOR;
    private int padding = 2;
    private boolean roundedCorners = false;
    private int cornerRadius = 6;
    private boolean borderEnabled = false;
    private int borderColor = DEFAULT_BORDER_COLOR;
    private int borderThickness = 1;
    private boolean borderGradient = false;
    private int borderColorBottom = DEFAULT_BORDER_BOTTOM_COLOR;
    private boolean chromaBorder = false;
    private int chromaSpeed = 5;
    private boolean imageBackground = false;
    private int imageOpacity = 100;
    private int margin = 0;
    private int minWidth = 0;
    private int minHeight = 0;
    private int borderSoftness = 0;
    private boolean backgroundBlur = false;
    private int blurStrength = 6;
    /** The board's lines, events and chunked stats: what is on it, in order, and the pool Add offers. */
    private final EntryOrder<ScoreboardEntry> entries = new EntryOrder<>(ScoreboardEntry.class, e -> e.enabledByDefault);
    private final EntryOrder<ScoreboardEvent> events = new EntryOrder<>(ScoreboardEvent.class, e -> e.enabledByDefault);
    private final EntryOrder<ChunkedStat> chunkedStats = new EntryOrder<>(ChunkedStat.class, s -> true);
    /** Which shape each list was read from on the last load ("current", "legacy" = migrated, "default"). */
    private String loadedFrom = "default";

    private CustomScoreboardConfig() {
    }

    public static CustomScoreboardConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        CustomScoreboardConfig cfg = new CustomScoreboardConfig();
        if (Files.exists(CONFIG_PATH)) {
            JsonObject obj = null;
            try {
                obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (Exception ignored) {
                // unreadable file: keep defaults
            }
            if (obj != null) {
                cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
                cfg.hideVanillaScoreboard = ConfigJson.getBool(obj, "hideVanillaScoreboard", cfg.hideVanillaScoreboard);
                cfg.useCustomLines = ConfigJson.getBool(obj, "useCustomLines", cfg.useCustomLines);
                cfg.textAlignment = ConfigJson.getEnum(obj, "textAlignment", Align.class, cfg.textAlignment);
                cfg.titleAlignment = ConfigJson.getEnum(obj, "titleAlignment", Align.class, cfg.titleAlignment);
                cfg.footerAlignment = ConfigJson.getEnum(obj, "footerAlignment", Align.class, cfg.footerAlignment);
                cfg.textShadow = ConfigJson.getBool(obj, "textShadow", cfg.textShadow);
                cfg.lineSpacing = clamp(ConfigJson.getInt(obj, "lineSpacing", cfg.lineSpacing), 0, 10);
                cfg.horizontalSnap = ConfigJson.getEnum(obj, "horizontalSnap", HorizontalSnap.class, cfg.horizontalSnap);
                cfg.verticalSnap = ConfigJson.getEnum(obj, "verticalSnap", VerticalSnap.class, cfg.verticalSnap);
                cfg.numberFormat = ConfigJson.getEnum(obj, "numberFormat", NumberFormat.class, cfg.numberFormat);
                cfg.numberDisplayFormat = ConfigJson.getEnum(obj, "numberDisplayFormat", NumberDisplayFormat.class, cfg.numberDisplayFormat);
                cfg.useCustomTitle = ConfigJson.getBool(obj, "useCustomTitle", cfg.useCustomTitle);
                cfg.customTitle = ConfigJson.getString(obj, "customTitle", cfg.customTitle);
                cfg.customFooter = ConfigJson.getString(obj, "customFooter", cfg.customFooter);
                cfg.customAlphaFooter = ConfigJson.getString(obj, "customAlphaFooter", cfg.customAlphaFooter);
                cfg.useCustomTitleOutsideSkyblock = ConfigJson.getBool(obj, "useCustomTitleOutsideSkyblock", cfg.useCustomTitleOutsideSkyblock);
                cfg.hideEmptyLines = ConfigJson.getBool(obj, "hideEmptyLines", cfg.hideEmptyLines);
                cfg.hideConsecutiveEmptyLines = ConfigJson.getBool(obj, "hideConsecutiveEmptyLines", cfg.hideConsecutiveEmptyLines);
                cfg.hideEmptyLinesAtTopAndBottom = ConfigJson.getBool(obj, "hideEmptyLinesAtTopAndBottom", cfg.hideEmptyLinesAtTopAndBottom);
                cfg.hideIrrelevantLines = ConfigJson.getBool(obj, "hideIrrelevantLines", cfg.hideIrrelevantLines);
                cfg.showProfileName = ConfigJson.getBool(obj, "showProfileName", cfg.showProfileName);
                cfg.showPartyEverywhere = ConfigJson.getBool(obj, "showPartyEverywhere", cfg.showPartyEverywhere);
                cfg.maxPartyMembers = clamp(ConfigJson.getInt(obj, "maxPartyMembers", cfg.maxPartyMembers), 1, 25);
                cfg.showAllActiveEvents = ConfigJson.getBool(obj, "showAllActiveEvents", cfg.showAllActiveEvents);
                cfg.hideWhenTab = ConfigJson.getBool(obj, "hideWhenTab", cfg.hideWhenTab);
                cfg.hideWhenChat = ConfigJson.getBool(obj, "hideWhenChat", cfg.hideWhenChat);
                cfg.outsideSkyblockMode = ConfigJson.getEnum(obj, "outsideSkyblockMode", OutsideSkyblockMode.class, cfg.outsideSkyblockMode);
                cfg.cacheOnIslandSwitch = ConfigJson.getBool(obj, "cacheOnIslandSwitch", cfg.cacheOnIslandSwitch);
                cfg.unknownLinesWarning = ConfigJson.getBool(obj, "unknownLinesChat", cfg.unknownLinesWarning);
                cfg.lineActions = ConfigJson.getBool(obj, "lineActions", cfg.lineActions);
                cfg.showNumberDifference = ConfigJson.getBool(obj, "showNumberDifference", cfg.showNumberDifference);
                cfg.dateInLobbyCode = ConfigJson.getBool(obj, "dateInLobbyCode", cfg.dateInLobbyCode);
                cfg.dateFormat = ConfigJson.getEnum(obj, "dateFormat", DateFormat.class, cfg.dateFormat);
                cfg.time24h = ConfigJson.getBool(obj, "time24h", cfg.time24h);
                cfg.timeExactMinutes = ConfigJson.getBool(obj, "timeExactMinutes", cfg.timeExactMinutes);
                cfg.separatorBetweenEvents = ConfigJson.getBool(obj, "separatorBetweenEvents", cfg.separatorBetweenEvents);
                cfg.showMayorPerks = ConfigJson.getBool(obj, "showMayorPerks", cfg.showMayorPerks);
                cfg.showMayorTime = ConfigJson.getBool(obj, "showMayorTime", cfg.showMayorTime);
                cfg.showMinister = ConfigJson.getBool(obj, "showMinister", cfg.showMinister);
                cfg.showJerryMayor = ConfigJson.getBool(obj, "showJerryMayor", cfg.showJerryMayor);
                cfg.showUnclaimedBits = ConfigJson.getBool(obj, "showUnclaimedBits", cfg.showUnclaimedBits);
                cfg.powderDisplay = ConfigJson.getEnum(obj, "powderDisplay", PowderDisplay.class, cfg.powderDisplay);
                cfg.hidePurseInDungeons = ConfigJson.getBool(obj, "hidePurseInDungeons", cfg.hidePurseInDungeons);
                cfg.showMagicalPower = ConfigJson.getBool(obj, "showMagicalPower", cfg.showMagicalPower);
                cfg.compactTuning = ConfigJson.getBool(obj, "compactTuning", cfg.compactTuning);
                cfg.tuningAmount = clamp(ConfigJson.getInt(obj, "tuningAmount", cfg.tuningAmount), 1, 8);
                cfg.arrowDisplay = ConfigJson.getEnum(obj, "arrowDisplay", ArrowDisplay.class, cfg.arrowDisplay);
                cfg.colorArrowAmount = ConfigJson.getBool(obj, "colorArrowAmount", cfg.colorArrowAmount);
                cfg.showMaxIslandPlayers = ConfigJson.getBool(obj, "showMaxIslandPlayers", cfg.showMaxIslandPlayers);
                cfg.showPartyLeader = ConfigJson.getBool(obj, "showPartyLeader", cfg.showPartyLeader);
                cfg.statsPerLine = clamp(ConfigJson.getInt(obj, "statsPerLine", cfg.statsPerLine), 1, 10);
                cfg.backgroundEnabled = ConfigJson.getBool(obj, "backgroundEnabled", cfg.backgroundEnabled);
                cfg.backgroundColor = ConfigJson.getInt(obj, "backgroundColor", cfg.backgroundColor);
                cfg.padding = clamp(ConfigJson.getInt(obj, "padding", cfg.padding), 0, 20);
                cfg.roundedCorners = ConfigJson.getBool(obj, "roundedCorners", cfg.roundedCorners);
                cfg.cornerRadius = clamp(ConfigJson.getInt(obj, "cornerRadius", cfg.cornerRadius), 1, 20);
                cfg.borderEnabled = ConfigJson.getBool(obj, "borderEnabled", cfg.borderEnabled);
                cfg.borderColor = ConfigJson.getInt(obj, "borderColor", cfg.borderColor);
                cfg.borderThickness = clamp(ConfigJson.getInt(obj, "borderThickness", cfg.borderThickness), 1, 5);
                cfg.borderGradient = ConfigJson.getBool(obj, "borderGradient", cfg.borderGradient);
                cfg.borderColorBottom = ConfigJson.getInt(obj, "borderColorBottom", cfg.borderColorBottom);
                cfg.chromaBorder = ConfigJson.getBool(obj, "chromaBorder", cfg.chromaBorder);
                cfg.chromaSpeed = clamp(ConfigJson.getInt(obj, "chromaSpeed", cfg.chromaSpeed), 1, 20);
                cfg.imageBackground = ConfigJson.getBool(obj, "imageBackground", cfg.imageBackground);
                cfg.imageOpacity = clamp(ConfigJson.getInt(obj, "imageOpacity", cfg.imageOpacity), 5, 100);
                cfg.margin = clamp(ConfigJson.getInt(obj, "margin", cfg.margin), 0, 50);
                cfg.minWidth = clamp(ConfigJson.getInt(obj, "minWidth", cfg.minWidth), 0, 400);
                cfg.minHeight = clamp(ConfigJson.getInt(obj, "minHeight", cfg.minHeight), 0, 400);
                cfg.borderSoftness = clamp(ConfigJson.getInt(obj, "borderSoftness", cfg.borderSoftness), 0, 10);
                cfg.backgroundBlur = ConfigJson.getBool(obj, "backgroundBlur", cfg.backgroundBlur);
                cfg.blurStrength = clamp(ConfigJson.getInt(obj, "blurStrength", cfg.blurStrength), 1, 20);
                cfg.editorAdvancedOpen = ConfigJson.getBool(obj, "editorAdvancedOpen", cfg.editorAdvancedOpen);
                if (ConfigJson.getInt(obj, "lookVersion", 1) < LOOK_VERSION) {
                    cfg.migrateLook(obj);
                }
                if (obj.has("anchorSide")) {
                    cfg.anchorSide = ConfigJson.getEnum(obj, "anchorSide", Side.class, cfg.anchorSide);
                    cfg.edgeGap = clamp(ConfigJson.getInt(obj, "edgeGap", cfg.edgeGap), 0, 4000);
                    cfg.centerOffsetY = clamp(ConfigJson.getInt(obj, "centerOffsetY", cfg.centerOffsetY), -4000, 4000);
                } else if (cfg.horizontalSnap == HorizontalSnap.RIGHT && cfg.verticalSnap == VerticalSnap.CENTER) {
                    // The old default (never dragged): the same place, now as an anchor.
                    cfg.anchorSide = Side.RIGHT;
                    cfg.edgeGap = cfg.margin;
                    cfg.centerOffsetY = 0;
                } else {
                    cfg.positionPending = true;
                }
                // New keys first; a save from before the add/trash editor (only "entries"/"events"/"chunkedStats",
                // each row with an on/off flag) migrates to the same lines in the same order - see EntryOrder.
                cfg.loadedFrom = cfg.entries.load(obj.get("lineOrder"), ConfigJson.getArray(obj, "entries"))
                        + "/" + cfg.events.load(obj.get("eventOrder"), ConfigJson.getArray(obj, "events"))
                        + "/" + cfg.chunkedStats.load(obj.get("statOrder"), ConfigJson.getArray(obj, "chunkedStats"));
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("hideVanillaScoreboard", hideVanillaScoreboard);
            obj.addProperty("useCustomLines", useCustomLines);
            obj.addProperty("textAlignment", textAlignment.name());
            obj.addProperty("titleAlignment", titleAlignment.name());
            obj.addProperty("footerAlignment", footerAlignment.name());
            obj.addProperty("textShadow", textShadow);
            obj.addProperty("lineSpacing", lineSpacing);
            obj.addProperty("anchorSide", anchorSide.name());
            obj.addProperty("edgeGap", edgeGap);
            obj.addProperty("centerOffsetY", centerOffsetY);
            obj.addProperty("lookVersion", LOOK_VERSION);
            obj.addProperty("editorAdvancedOpen", editorAdvancedOpen);
            obj.addProperty("numberFormat", numberFormat.name());
            obj.addProperty("numberDisplayFormat", numberDisplayFormat.name());
            obj.addProperty("useCustomTitle", useCustomTitle);
            obj.addProperty("customTitle", customTitle);
            obj.addProperty("customFooter", customFooter);
            obj.addProperty("customAlphaFooter", customAlphaFooter);
            obj.addProperty("useCustomTitleOutsideSkyblock", useCustomTitleOutsideSkyblock);
            obj.addProperty("hideEmptyLines", hideEmptyLines);
            obj.addProperty("hideConsecutiveEmptyLines", hideConsecutiveEmptyLines);
            obj.addProperty("hideEmptyLinesAtTopAndBottom", hideEmptyLinesAtTopAndBottom);
            obj.addProperty("hideIrrelevantLines", hideIrrelevantLines);
            obj.addProperty("showProfileName", showProfileName);
            obj.addProperty("showPartyEverywhere", showPartyEverywhere);
            obj.addProperty("maxPartyMembers", maxPartyMembers);
            obj.addProperty("showAllActiveEvents", showAllActiveEvents);
            obj.addProperty("hideWhenTab", hideWhenTab);
            obj.addProperty("hideWhenChat", hideWhenChat);
            obj.addProperty("outsideSkyblockMode", outsideSkyblockMode.name());
            obj.addProperty("cacheOnIslandSwitch", cacheOnIslandSwitch);
            obj.addProperty("unknownLinesChat", unknownLinesWarning);
            obj.addProperty("lineActions", lineActions);
            obj.addProperty("showNumberDifference", showNumberDifference);
            obj.addProperty("dateInLobbyCode", dateInLobbyCode);
            obj.addProperty("dateFormat", dateFormat.name());
            obj.addProperty("time24h", time24h);
            obj.addProperty("timeExactMinutes", timeExactMinutes);
            obj.addProperty("separatorBetweenEvents", separatorBetweenEvents);
            obj.addProperty("showMayorPerks", showMayorPerks);
            obj.addProperty("showMayorTime", showMayorTime);
            obj.addProperty("showMinister", showMinister);
            obj.addProperty("showJerryMayor", showJerryMayor);
            obj.addProperty("showUnclaimedBits", showUnclaimedBits);
            obj.addProperty("powderDisplay", powderDisplay.name());
            obj.addProperty("hidePurseInDungeons", hidePurseInDungeons);
            obj.addProperty("showMagicalPower", showMagicalPower);
            obj.addProperty("compactTuning", compactTuning);
            obj.addProperty("tuningAmount", tuningAmount);
            obj.addProperty("arrowDisplay", arrowDisplay.name());
            obj.addProperty("colorArrowAmount", colorArrowAmount);
            obj.addProperty("showMaxIslandPlayers", showMaxIslandPlayers);
            obj.addProperty("showPartyLeader", showPartyLeader);
            obj.addProperty("statsPerLine", statsPerLine);
            obj.addProperty("backgroundEnabled", backgroundEnabled);
            obj.addProperty("backgroundColor", backgroundColor);
            obj.addProperty("padding", padding);
            obj.addProperty("roundedCorners", roundedCorners);
            obj.addProperty("cornerRadius", cornerRadius);
            obj.addProperty("borderEnabled", borderEnabled);
            obj.addProperty("borderColor", borderColor);
            obj.addProperty("borderThickness", borderThickness);
            obj.addProperty("borderGradient", borderGradient);
            obj.addProperty("borderColorBottom", borderColorBottom);
            obj.addProperty("chromaBorder", chromaBorder);
            obj.addProperty("chromaSpeed", chromaSpeed);
            obj.addProperty("imageBackground", imageBackground);
            obj.addProperty("imageOpacity", imageOpacity);
            obj.addProperty("minWidth", minWidth);
            obj.addProperty("minHeight", minHeight);
            obj.addProperty("borderSoftness", borderSoftness);
            obj.addProperty("backgroundBlur", backgroundBlur);
            obj.addProperty("blurStrength", blurStrength);
            obj.add("lineOrder", entries.toJson());
            obj.add("eventOrder", events.toJson());
            obj.add("statOrder", chunkedStats.toJson());
            // The old toggle-list shape too, so an older jar (or a friend's, receiving shared settings) draws the same board.
            obj.add("entries", entries.toLegacyJson());
            obj.add("events", events.toLegacyJson());
            obj.add("chunkedStats", chunkedStats.toLegacyJson());
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** An old save's look fields that still hold the old default take the new one; anything he set himself is kept. */
    private void migrateLook(JsonObject obj) {
        if (ConfigJson.getInt(obj, "backgroundColor", OLD_DEFAULT_BACKGROUND_COLOR) == OLD_DEFAULT_BACKGROUND_COLOR) {
            backgroundColor = DEFAULT_BACKGROUND_COLOR;
        }
        if (ConfigJson.getInt(obj, "padding", OLD_DEFAULT_PADDING) == OLD_DEFAULT_PADDING) {
            padding = 2;
        }
        if (ConfigJson.getBool(obj, "roundedCorners", true)) {
            roundedCorners = false;
        }
        if (ConfigJson.getBool(obj, "borderEnabled", true) && !ConfigJson.getBool(obj, "chromaBorder", false)
                && !ConfigJson.getBool(obj, "borderGradient", false)
                && ConfigJson.getInt(obj, "borderColor", DEFAULT_BORDER_COLOR) == DEFAULT_BORDER_COLOR) {
            borderEnabled = false; // the plain orange border nobody chose; a chroma, gradient or recolored one stays
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public void resetEntries() {
        entries.reset();
    }

    public void resetEvents() {
        events.reset();
    }

    public void resetChunkedStats() {
        chunkedStats.reset();
    }

    /** True if {@code entry} is on the board. */
    public boolean isEntryEnabled(ScoreboardEntry entry) {
        return entries.contains(entry);
    }

    /** The board's lines, top to bottom, and the pool the editor's Add offers. */
    public EntryOrder<ScoreboardEntry> entries() {
        return entries;
    }

    public EntryOrder<ScoreboardEvent> events() {
        return events;
    }

    public EntryOrder<ChunkedStat> chunkedStats() {
        return chunkedStats;
    }

    public List<ScoreboardEntry> getLineOrder() {
        return entries.active();
    }

    public void setLineOrder(List<ScoreboardEntry> v) {
        entries.setActive(v);
    }

    public List<ScoreboardEvent> getEventOrder() {
        return events.active();
    }

    public void setEventOrder(List<ScoreboardEvent> v) {
        events.setActive(v);
    }

    public List<ChunkedStat> getStatOrder() {
        return chunkedStats.active();
    }

    public void setStatOrder(List<ChunkedStat> v) {
        chunkedStats.setActive(v);
    }

    /** "current", "legacy" (migrated from the toggle lists) or "default", per list: lines/events/stats. */
    public String getLoadedFrom() {
        return loadedFrom;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        enabled = v;
    }

    public boolean isHideVanillaScoreboard() {
        return hideVanillaScoreboard;
    }

    public void setHideVanillaScoreboard(boolean v) {
        hideVanillaScoreboard = v;
    }

    public boolean isUseCustomLines() {
        return useCustomLines;
    }

    public void setUseCustomLines(boolean v) {
        useCustomLines = v;
    }

    public Align getTextAlignment() {
        return textAlignment;
    }

    public void setTextAlignment(Align v) {
        textAlignment = v;
    }

    public Align getTitleAlignment() {
        return titleAlignment;
    }

    public void setTitleAlignment(Align v) {
        titleAlignment = v;
    }

    public Align getFooterAlignment() {
        return footerAlignment;
    }

    public void setFooterAlignment(Align v) {
        footerAlignment = v;
    }

    public boolean isTextShadow() {
        return textShadow;
    }

    public void setTextShadow(boolean v) {
        textShadow = v;
    }

    public int getLineSpacing() {
        return lineSpacing;
    }

    public void setLineSpacing(int v) {
        lineSpacing = clamp(v, 0, 10);
    }

    /** The pre-2026-10-15 Snap X, for converting an old save ({@link #isPositionPending}). */
    HorizontalSnap legacyHorizontalSnap() {
        return horizontalSnap;
    }

    /** The pre-2026-10-15 Snap Y, for converting an old save ({@link #isPositionPending}). */
    VerticalSnap legacyVerticalSnap() {
        return verticalSnap;
    }

    /** The pre-2026-10-15 Screen Margin, for converting an old save ({@link #isPositionPending}). */
    int legacyMargin() {
        return margin;
    }

    public Side getAnchorSide() {
        return anchorSide;
    }

    public int getEdgeGap() {
        return edgeGap;
    }

    public int getCenterOffsetY() {
        return centerOffsetY;
    }

    /** Sets where the board sits (see {@link #anchorSide}); the caller saves. */
    public void setAnchor(Side side, int gap, int offsetY) {
        anchorSide = side == null ? Side.RIGHT : side;
        edgeGap = clamp(gap, 0, 4000);
        centerOffsetY = clamp(offsetY, -4000, 4000);
        positionPending = false;
    }

    /** Back to the default spot: against the right edge, vertically centered. */
    public void resetAnchor() {
        setAnchor(Side.RIGHT, DEFAULT_EDGE_GAP, 0);
    }

    public boolean isPositionPending() {
        return positionPending;
    }

    public boolean isEditorAdvancedOpen() {
        return editorAdvancedOpen;
    }

    public void setEditorAdvancedOpen(boolean v) {
        editorAdvancedOpen = v;
    }

    public NumberFormat getNumberFormat() {
        return numberFormat;
    }

    public void setNumberFormat(NumberFormat v) {
        numberFormat = v;
    }

    public NumberDisplayFormat getNumberDisplayFormat() {
        return numberDisplayFormat;
    }

    public void setNumberDisplayFormat(NumberDisplayFormat v) {
        numberDisplayFormat = v;
    }

    public boolean isUseCustomTitle() {
        return useCustomTitle;
    }

    public void setUseCustomTitle(boolean v) {
        useCustomTitle = v;
    }

    public String getCustomTitle() {
        return customTitle;
    }

    public void setCustomTitle(String v) {
        customTitle = v == null ? "" : v;
    }

    public String getCustomFooter() {
        return customFooter;
    }

    public void setCustomFooter(String v) {
        customFooter = v == null ? "" : v;
    }

    public boolean isHideEmptyLines() {
        return hideEmptyLines;
    }

    public void setHideEmptyLines(boolean v) {
        hideEmptyLines = v;
    }

    public boolean isHideConsecutiveEmptyLines() {
        return hideConsecutiveEmptyLines;
    }

    public void setHideConsecutiveEmptyLines(boolean v) {
        hideConsecutiveEmptyLines = v;
    }

    public boolean isHideEmptyLinesAtTopAndBottom() {
        return hideEmptyLinesAtTopAndBottom;
    }

    public void setHideEmptyLinesAtTopAndBottom(boolean v) {
        hideEmptyLinesAtTopAndBottom = v;
    }

    public boolean isHideIrrelevantLines() {
        return hideIrrelevantLines;
    }

    public void setHideIrrelevantLines(boolean v) {
        hideIrrelevantLines = v;
    }

    public boolean isShowProfileName() {
        return showProfileName;
    }

    public void setShowProfileName(boolean v) {
        showProfileName = v;
    }

    public boolean isShowPartyEverywhere() {
        return showPartyEverywhere;
    }

    public void setShowPartyEverywhere(boolean v) {
        showPartyEverywhere = v;
    }

    public int getMaxPartyMembers() {
        return maxPartyMembers;
    }

    public void setMaxPartyMembers(int v) {
        maxPartyMembers = clamp(v, 1, 25);
    }

    public boolean isShowAllActiveEvents() {
        return showAllActiveEvents;
    }

    public void setShowAllActiveEvents(boolean v) {
        showAllActiveEvents = v;
    }

    public boolean isHideWhenTab() {
        return hideWhenTab;
    }

    public void setHideWhenTab(boolean v) {
        hideWhenTab = v;
    }

    public boolean isHideWhenChat() {
        return hideWhenChat;
    }

    public void setHideWhenChat(boolean v) {
        hideWhenChat = v;
    }

    public OutsideSkyblockMode getOutsideSkyblockMode() {
        return outsideSkyblockMode;
    }

    public void setOutsideSkyblockMode(OutsideSkyblockMode v) {
        outsideSkyblockMode = v;
    }

    public boolean isCacheOnIslandSwitch() {
        return cacheOnIslandSwitch;
    }

    public void setCacheOnIslandSwitch(boolean v) {
        cacheOnIslandSwitch = v;
    }

    public boolean isUnknownLinesWarning() {
        return unknownLinesWarning;
    }

    public void setUnknownLinesWarning(boolean v) {
        unknownLinesWarning = v;
    }

    public boolean isLineActions() {
        return lineActions;
    }

    public void setLineActions(boolean v) {
        lineActions = v;
    }

    public boolean isShowNumberDifference() {
        return showNumberDifference;
    }

    public void setShowNumberDifference(boolean v) {
        showNumberDifference = v;
    }

    public boolean isDateInLobbyCode() {
        return dateInLobbyCode;
    }

    public void setDateInLobbyCode(boolean v) {
        dateInLobbyCode = v;
    }

    public DateFormat getDateFormat() {
        return dateFormat;
    }

    public void setDateFormat(DateFormat v) {
        dateFormat = v;
    }

    public boolean isTime24h() {
        return time24h;
    }

    public void setTime24h(boolean v) {
        time24h = v;
    }

    public boolean isShowMayorPerks() {
        return showMayorPerks;
    }

    public void setShowMayorPerks(boolean v) {
        showMayorPerks = v;
    }

    public boolean isShowMayorTime() {
        return showMayorTime;
    }

    public void setShowMayorTime(boolean v) {
        showMayorTime = v;
    }

    public boolean isShowMinister() {
        return showMinister;
    }

    public void setShowMinister(boolean v) {
        showMinister = v;
    }

    public boolean isShowMagicalPower() {
        return showMagicalPower;
    }

    public void setShowMagicalPower(boolean v) {
        showMagicalPower = v;
    }

    public boolean isCompactTuning() {
        return compactTuning;
    }

    public void setCompactTuning(boolean v) {
        compactTuning = v;
    }

    public int getTuningAmount() {
        return tuningAmount;
    }

    public void setTuningAmount(int v) {
        tuningAmount = clamp(v, 1, 8);
    }

    public ArrowDisplay getArrowDisplay() {
        return arrowDisplay;
    }

    public void setArrowDisplay(ArrowDisplay v) {
        arrowDisplay = v;
    }

    public boolean isColorArrowAmount() {
        return colorArrowAmount;
    }

    public void setColorArrowAmount(boolean v) {
        colorArrowAmount = v;
    }

    public boolean isShowMaxIslandPlayers() {
        return showMaxIslandPlayers;
    }

    public void setShowMaxIslandPlayers(boolean v) {
        showMaxIslandPlayers = v;
    }

    public boolean isShowPartyLeader() {
        return showPartyLeader;
    }

    public void setShowPartyLeader(boolean v) {
        showPartyLeader = v;
    }

    public int getStatsPerLine() {
        return statsPerLine;
    }

    public void setStatsPerLine(int v) {
        statsPerLine = clamp(v, 1, 10);
    }

    public boolean isBackgroundEnabled() {
        return backgroundEnabled;
    }

    public void setBackgroundEnabled(boolean v) {
        backgroundEnabled = v;
    }

    public int getBackgroundColor() {
        return backgroundColor;
    }

    public void setBackgroundColor(int v) {
        backgroundColor = v;
    }

    public int getPadding() {
        return padding;
    }

    public void setPadding(int v) {
        padding = clamp(v, 0, 20);
    }

    public boolean isRoundedCorners() {
        return roundedCorners;
    }

    public void setRoundedCorners(boolean v) {
        roundedCorners = v;
    }

    public int getCornerRadius() {
        return cornerRadius;
    }

    public void setCornerRadius(int v) {
        cornerRadius = clamp(v, 1, 20);
    }

    public boolean isBorderEnabled() {
        return borderEnabled;
    }

    public void setBorderEnabled(boolean v) {
        borderEnabled = v;
    }

    public int getBorderColor() {
        return borderColor;
    }

    public void setBorderColor(int v) {
        borderColor = v;
    }

    public int getBorderThickness() {
        return borderThickness;
    }

    public void setBorderThickness(int v) {
        borderThickness = clamp(v, 1, 5);
    }

    public boolean isBorderGradient() {
        return borderGradient;
    }

    public void setBorderGradient(boolean v) {
        borderGradient = v;
    }

    public int getBorderColorBottom() {
        return borderColorBottom;
    }

    public void setBorderColorBottom(int v) {
        borderColorBottom = v;
    }

    public boolean isChromaBorder() {
        return chromaBorder;
    }

    public void setChromaBorder(boolean v) {
        chromaBorder = v;
    }

    public int getChromaSpeed() {
        return chromaSpeed;
    }

    public void setChromaSpeed(int v) {
        chromaSpeed = clamp(v, 1, 20);
    }

    public boolean isImageBackground() {
        return imageBackground;
    }

    public void setImageBackground(boolean v) {
        imageBackground = v;
    }

    public int getImageOpacity() {
        return imageOpacity;
    }

    public void setImageOpacity(int v) {
        imageOpacity = clamp(v, 5, 100);
    }

    public String getCustomAlphaFooter() {
        return customAlphaFooter;
    }

    public void setCustomAlphaFooter(String v) {
        customAlphaFooter = v == null ? "" : v;
    }

    public boolean isUseCustomTitleOutsideSkyblock() {
        return useCustomTitleOutsideSkyblock;
    }

    public void setUseCustomTitleOutsideSkyblock(boolean v) {
        useCustomTitleOutsideSkyblock = v;
    }

    public boolean isTimeExactMinutes() {
        return timeExactMinutes;
    }

    public void setTimeExactMinutes(boolean v) {
        timeExactMinutes = v;
    }

    public boolean isSeparatorBetweenEvents() {
        return separatorBetweenEvents;
    }

    public void setSeparatorBetweenEvents(boolean v) {
        separatorBetweenEvents = v;
    }

    public boolean isShowJerryMayor() {
        return showJerryMayor;
    }

    public void setShowJerryMayor(boolean v) {
        showJerryMayor = v;
    }

    public boolean isShowUnclaimedBits() {
        return showUnclaimedBits;
    }

    public void setShowUnclaimedBits(boolean v) {
        showUnclaimedBits = v;
    }

    public PowderDisplay getPowderDisplay() {
        return powderDisplay;
    }

    public void setPowderDisplay(PowderDisplay v) {
        powderDisplay = v;
    }

    public boolean isHidePurseInDungeons() {
        return hidePurseInDungeons;
    }

    public void setHidePurseInDungeons(boolean v) {
        hidePurseInDungeons = v;
    }

    public int getMinWidth() {
        return minWidth;
    }

    public void setMinWidth(int v) {
        minWidth = clamp(v, 0, 400);
    }

    public int getMinHeight() {
        return minHeight;
    }

    public void setMinHeight(int v) {
        minHeight = clamp(v, 0, 400);
    }

    public int getBorderSoftness() {
        return borderSoftness;
    }

    public void setBorderSoftness(int v) {
        borderSoftness = clamp(v, 0, 10);
    }

    public boolean isBackgroundBlur() {
        return backgroundBlur;
    }

    public void setBackgroundBlur(boolean v) {
        backgroundBlur = v;
    }

    public int getBlurStrength() {
        return blurStrength;
    }

    public void setBlurStrength(int v) {
        blurStrength = clamp(v, 1, 20);
    }
}
