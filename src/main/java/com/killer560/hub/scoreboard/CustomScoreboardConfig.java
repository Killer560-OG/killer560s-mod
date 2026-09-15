package com.killer560.hub.scoreboard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Persisted Custom Scoreboard settings - see {@link CustomScoreboardFeature}. Every field survives a restart,
 * including the order and on/off state of each line, event and chunked stat. Position/scale live in {@code HudConfig}
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
    }

    /** One row of an ordered, toggleable list. */
    public static final class Row<E extends Enum<E>> {
        public final E id;
        public boolean enabled;

        Row(E id, boolean enabled) {
            this.id = id;
            this.enabled = enabled;
        }
    }

    public static final int DEFAULT_BACKGROUND_COLOR = 0xAA1A1108;
    public static final int DEFAULT_BORDER_COLOR = 0xFFCC6600;
    public static final int DEFAULT_BORDER_BOTTOM_COLOR = 0xFFFFA040;
    public static final String DEFAULT_TITLE = "&&6&&lSKYBLOCK";
    public static final String DEFAULT_FOOTER = "&&ewww.hypixel.net";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-customscoreboard.json");
    /** Folder for the background image ({@code background.png}) and {@link ScoreboardExtraData}'s cache. */
    public static final Path DATA_DIR = FabricLoader.getInstance().getConfigDir().resolve("killer560smod-customscoreboard");

    private static CustomScoreboardConfig instance;

    private boolean enabled = false;
    private boolean hideVanillaScoreboard = true;
    private boolean useCustomLines = true;
    private Align textAlignment = Align.LEFT;
    private Align titleAlignment = Align.CENTER;
    private Align footerAlignment = Align.LEFT;
    private boolean textShadow = true;
    private int lineSpacing = 1;
    private HorizontalSnap horizontalSnap = HorizontalSnap.RIGHT;
    private VerticalSnap verticalSnap = VerticalSnap.CENTER;
    private NumberFormat numberFormat = NumberFormat.LONG;
    private NumberDisplayFormat numberDisplayFormat = NumberDisplayFormat.TEXT_COLOR_NUMBER;
    private boolean useCustomTitle = true;
    private String customTitle = DEFAULT_TITLE;
    private String customFooter = DEFAULT_FOOTER;
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
    private boolean unknownLinesWarning = true;
    private boolean lineActions = true;
    private boolean showNumberDifference = false;
    private boolean dateInLobbyCode = true;
    private DateFormat dateFormat = DateFormat.US_SLASH_MMDDYY;
    private boolean time24h = false;
    // line options
    private boolean showMayorPerks = true;
    private boolean showMayorTime = true;
    private boolean showMinister = true;
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
    private int padding = 5;
    private boolean roundedCorners = true;
    private int cornerRadius = 6;
    private boolean borderEnabled = true;
    private int borderColor = DEFAULT_BORDER_COLOR;
    private int borderThickness = 1;
    private boolean borderGradient = false;
    private int borderColorBottom = DEFAULT_BORDER_BOTTOM_COLOR;
    private boolean chromaBorder = false;
    private int chromaSpeed = 5;
    private boolean imageBackground = false;
    private int imageOpacity = 100;
    private final List<Row<ScoreboardEntry>> entries = new ArrayList<>();
    private final List<Row<ScoreboardEvent>> events = new ArrayList<>();
    private final List<Row<ChunkedStat>> chunkedStats = new ArrayList<>();

    private CustomScoreboardConfig() {
        resetEntries();
        resetEvents();
        resetChunkedStats();
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
                cfg.unknownLinesWarning = ConfigJson.getBool(obj, "unknownLinesWarning", cfg.unknownLinesWarning);
                cfg.lineActions = ConfigJson.getBool(obj, "lineActions", cfg.lineActions);
                cfg.showNumberDifference = ConfigJson.getBool(obj, "showNumberDifference", cfg.showNumberDifference);
                cfg.dateInLobbyCode = ConfigJson.getBool(obj, "dateInLobbyCode", cfg.dateInLobbyCode);
                cfg.dateFormat = ConfigJson.getEnum(obj, "dateFormat", DateFormat.class, cfg.dateFormat);
                cfg.time24h = ConfigJson.getBool(obj, "time24h", cfg.time24h);
                cfg.showMayorPerks = ConfigJson.getBool(obj, "showMayorPerks", cfg.showMayorPerks);
                cfg.showMayorTime = ConfigJson.getBool(obj, "showMayorTime", cfg.showMayorTime);
                cfg.showMinister = ConfigJson.getBool(obj, "showMinister", cfg.showMinister);
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
                loadRows(ConfigJson.getArray(obj, "entries"), cfg.entries, ScoreboardEntry.class);
                loadRows(ConfigJson.getArray(obj, "events"), cfg.events, ScoreboardEvent.class);
                loadRows(ConfigJson.getArray(obj, "chunkedStats"), cfg.chunkedStats, ChunkedStat.class);
            }
        }
        instance = cfg;
    }

    /**
     * Saved order first (unknown ids dropped). An id missing from the file (e.g. a line added in a newer build) is
     * inserted right after the nearest id that precedes it in the default order, keeping its default on/off state,
     * so new lines land where they belong instead of after the footer.
     */
    private static <E extends Enum<E>> void loadRows(JsonArray array, List<Row<E>> rows, Class<E> type) {
        if (array == null) {
            return;
        }
        List<Row<E>> loaded = new ArrayList<>();
        Set<E> seen = EnumSet.noneOf(type);
        for (JsonElement el : array) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            E id = ConfigJson.getEnum(o, "id", type, null);
            if (id == null || !seen.add(id)) {
                continue;
            }
            loaded.add(new Row<>(id, ConfigJson.getBool(o, "enabled", true)));
        }
        for (int d = 0; d < rows.size(); d++) {
            Row<E> def = rows.get(d);
            if (seen.contains(def.id)) {
                continue;
            }
            int insertAt = loaded.size();
            for (int p = d - 1; p >= 0; p--) {
                int idx = indexOfId(loaded, rows.get(p).id);
                if (idx >= 0) {
                    insertAt = idx + 1;
                    break;
                }
            }
            loaded.add(insertAt, def);
            seen.add(def.id);
        }
        rows.clear();
        rows.addAll(loaded);
    }

    private static <E extends Enum<E>> int indexOfId(List<Row<E>> rows, E id) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).id == id) {
                return i;
            }
        }
        return -1;
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
            obj.addProperty("horizontalSnap", horizontalSnap.name());
            obj.addProperty("verticalSnap", verticalSnap.name());
            obj.addProperty("numberFormat", numberFormat.name());
            obj.addProperty("numberDisplayFormat", numberDisplayFormat.name());
            obj.addProperty("useCustomTitle", useCustomTitle);
            obj.addProperty("customTitle", customTitle);
            obj.addProperty("customFooter", customFooter);
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
            obj.addProperty("unknownLinesWarning", unknownLinesWarning);
            obj.addProperty("lineActions", lineActions);
            obj.addProperty("showNumberDifference", showNumberDifference);
            obj.addProperty("dateInLobbyCode", dateInLobbyCode);
            obj.addProperty("dateFormat", dateFormat.name());
            obj.addProperty("time24h", time24h);
            obj.addProperty("showMayorPerks", showMayorPerks);
            obj.addProperty("showMayorTime", showMayorTime);
            obj.addProperty("showMinister", showMinister);
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
            obj.add("entries", saveRows(entries));
            obj.add("events", saveRows(events));
            obj.add("chunkedStats", saveRows(chunkedStats));
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static <E extends Enum<E>> JsonArray saveRows(List<Row<E>> rows) {
        JsonArray array = new JsonArray();
        for (Row<E> row : rows) {
            JsonObject o = new JsonObject();
            o.addProperty("id", row.id.name());
            o.addProperty("enabled", row.enabled);
            array.add(o);
        }
        return array;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public void resetEntries() {
        entries.clear();
        for (ScoreboardEntry e : ScoreboardEntry.values()) {
            entries.add(new Row<>(e, e.enabledByDefault));
        }
    }

    public void resetEvents() {
        events.clear();
        for (ScoreboardEvent e : ScoreboardEvent.values()) {
            events.add(new Row<>(e, e.enabledByDefault));
        }
    }

    public void resetChunkedStats() {
        chunkedStats.clear();
        for (ChunkedStat s : ChunkedStat.values()) {
            chunkedStats.add(new Row<>(s, true));
        }
    }

    /** Moves row {@code index} by {@code delta} (-1 up, +1 down); no-op at the ends. */
    public static <E extends Enum<E>> void move(List<Row<E>> rows, int index, int delta) {
        int target = index + delta;
        if (index < 0 || index >= rows.size() || target < 0 || target >= rows.size()) {
            return;
        }
        Row<E> row = rows.remove(index);
        rows.add(target, row);
    }

    /** True if {@code entry}'s row is switched on. */
    public boolean isEntryEnabled(ScoreboardEntry entry) {
        for (Row<ScoreboardEntry> row : entries) {
            if (row.id == entry) {
                return row.enabled;
            }
        }
        return false;
    }

    public List<Row<ScoreboardEntry>> entries() {
        return entries;
    }

    public List<Row<ScoreboardEvent>> events() {
        return events;
    }

    public List<Row<ChunkedStat>> chunkedStats() {
        return chunkedStats;
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

    public HorizontalSnap getHorizontalSnap() {
        return horizontalSnap;
    }

    public void setHorizontalSnap(HorizontalSnap v) {
        horizontalSnap = v;
    }

    public VerticalSnap getVerticalSnap() {
        return verticalSnap;
    }

    public void setVerticalSnap(VerticalSnap v) {
        verticalSnap = v;
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
}
