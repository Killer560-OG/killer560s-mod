package com.killer560.hub.storagesearch;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Persisted Storage Item Search settings - see {@link StorageSearchFeature}. Ships disabled. Every
 *  field here is saved/loaded so it survives a restart. */
public final class StorageSearchConfig {

    /** Result ordering - killer560 (2026-09-21): "allow you then to customize the viewer if you have multiple items
     *  to sort by in chests or in storage". */
    public enum SortMode {
        DEFAULT("Storage order"),
        NAME("Name A-Z"),
        COUNT("Stack size"),
        RECENT("Freshest first"),
        DISTANCE("Nearest chest first");

        private final String label;

        SortMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public SortMode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /** Which kinds of place are shown in the result list at all. */
    public enum SourceFilter {
        ALL("All"),
        STORAGE("Storage"),
        CHESTS("Chests"),
        INVENTORY("Inventory"),
        EQUIPMENT("Wardrobe/Pets");

        private final String label;

        SourceFilter(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public SourceFilter next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    public static final int MIN_CHEST_RADIUS = 2;
    public static final int MAX_CHEST_RADIUS = 24;
    public static final int MIN_ESP_SECONDS = 5;
    public static final int MAX_ESP_SECONDS = 120;
    /** More than this many open-binds is just a config file to get lost in. */
    public static final int MAX_BINDS = 5;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-storagesearch.json");

    private static StorageSearchConfig instance;

    private boolean enabled = false;
    /** Open-binds, each a key/mouse button plus optional modifiers - killer560 (2026-09-21) asked for several,
     *  "in the sense of ctrl + f". Ctrl+F is the shipped default; the feature itself is still off by default, so
     *  nothing happens until he turns it on. */
    private final List<StorageSearchBind> binds = new ArrayList<>();
    private boolean searchLore = false;
    private boolean includeInventory = true;
    private boolean openOnClick = true;
    /** Island chests (see {@link IslandChestCache}) - off by default: it adds a per-opened-chest cache file and an
     *  on-demand chunk scan, neither of which should start happening behind his back. */
    private boolean searchChests = false;
    private int chestRadius = 8;
    private boolean chestEsp = true;
    private boolean espThroughWalls = true;
    private int espSeconds = 30;
    /** Wardrobe / equipment / pets menus (see {@link StorageSearchExtraCache}) - off by default, same reasoning. */
    private boolean searchExtras = true;
    /** "if i search for an item and it is in one backpack then only show that backpack on the menu" - on, because
     *  it only ever happens as a direct result of him clicking a search result, and the grid's Back button undoes
     *  it in one click. */
    private boolean focusSingleStorage = true;
    private SortMode sortMode = SortMode.DEFAULT;
    private SourceFilter sourceFilter = SourceFilter.ALL;

    private StorageSearchConfig() {
        binds.add(new StorageSearchBind(StorageSearchBind.MOD_CTRL, org.lwjgl.glfw.GLFW.GLFW_KEY_F));
    }

    public static StorageSearchConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        StorageSearchConfig cfg = new StorageSearchConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                if (obj.has("enabled")) {
                    cfg.enabled = obj.get("enabled").getAsBoolean();
                }
                if (obj.has("binds")) {
                    cfg.binds.clear();
                    JsonArray array = obj.getAsJsonArray("binds");
                    for (int i = 0; i < array.size() && cfg.binds.size() < MAX_BINDS; i++) {
                        StorageSearchBind bind = StorageSearchBind.parse(array.get(i).getAsString());
                        if (bind != null) {
                            cfg.binds.add(bind);
                        }
                    }
                } else if (obj.has("keyCode")) {
                    // Pre-2026-09-21 configs stored one bare key. Carry it over instead of silently replacing his
                    // bind with the new Ctrl+F default.
                    int code = com.killer560.hub.util.KeyUtil.sanitizeBind(obj.get("keyCode").getAsInt());
                    cfg.binds.clear();
                    if (code != com.killer560.hub.util.KeyUtil.NONE) {
                        cfg.binds.add(new StorageSearchBind(0, code));
                    }
                }
                if (obj.has("searchLore")) {
                    cfg.searchLore = obj.get("searchLore").getAsBoolean();
                }
                if (obj.has("includeInventory")) {
                    cfg.includeInventory = obj.get("includeInventory").getAsBoolean();
                }
                if (obj.has("openOnClick")) {
                    cfg.openOnClick = obj.get("openOnClick").getAsBoolean();
                }
                if (obj.has("searchChests")) {
                    cfg.searchChests = obj.get("searchChests").getAsBoolean();
                }
                if (obj.has("chestRadius")) {
                    cfg.setChestRadius(obj.get("chestRadius").getAsInt());
                }
                if (obj.has("chestEsp")) {
                    cfg.chestEsp = obj.get("chestEsp").getAsBoolean();
                }
                if (obj.has("espThroughWalls")) {
                    cfg.espThroughWalls = obj.get("espThroughWalls").getAsBoolean();
                }
                if (obj.has("espSeconds")) {
                    cfg.setEspSeconds(obj.get("espSeconds").getAsInt());
                }
                // Wardrobe / pets default ON now (killer560, 2026-09-21: "it isn't working with things like wardrobe or
                // pets") - the source was off by default. Files saved before switch it on once (extrasOnV1).
                if (obj.has("searchExtras") && obj.has("extrasOnV1")) {
                    cfg.searchExtras = obj.get("searchExtras").getAsBoolean();
                }
                if (obj.has("focusSingleStorage")) {
                    cfg.focusSingleStorage = obj.get("focusSingleStorage").getAsBoolean();
                }
                if (obj.has("sortMode")) {
                    cfg.sortMode = parseEnum(obj.get("sortMode").getAsString(), SortMode.values(), SortMode.DEFAULT);
                }
                if (obj.has("sourceFilter")) {
                    cfg.sourceFilter = parseEnum(obj.get("sourceFilter").getAsString(), SourceFilter.values(), SourceFilter.ALL);
                }
            } catch (Exception ignored) {
                cfg = new StorageSearchConfig();
            }
        }
        instance = cfg;
    }

    private static <T extends Enum<T>> T parseEnum(String name, T[] values, T fallback) {
        for (T value : values) {
            if (value.name().equals(name)) {
                return value;
            }
        }
        return fallback;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            JsonArray array = new JsonArray();
            for (StorageSearchBind bind : binds) {
                array.add(bind.serialize());
            }
            obj.add("binds", array);
            obj.addProperty("searchLore", searchLore);
            obj.addProperty("includeInventory", includeInventory);
            obj.addProperty("openOnClick", openOnClick);
            obj.addProperty("searchChests", searchChests);
            obj.addProperty("chestRadius", chestRadius);
            obj.addProperty("chestEsp", chestEsp);
            obj.addProperty("espThroughWalls", espThroughWalls);
            obj.addProperty("espSeconds", espSeconds);
            obj.addProperty("searchExtras", searchExtras);
            obj.addProperty("extrasOnV1", true);
            obj.addProperty("focusSingleStorage", focusSingleStorage);
            obj.addProperty("sortMode", sortMode.name());
            obj.addProperty("sourceFilter", sourceFilter.name());
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

    /** Live list - callers mutate it through {@link #addBind}/{@link #setBind}/{@link #removeBind} and then save. */
    public List<StorageSearchBind> getBinds() {
        return binds;
    }

    public void addBind(StorageSearchBind bind) {
        if (bind != null && binds.size() < MAX_BINDS) {
            binds.add(bind);
        }
    }

    public void setBind(int index, StorageSearchBind bind) {
        if (index >= 0 && index < binds.size() && bind != null) {
            binds.set(index, bind);
        }
    }

    public void removeBind(int index) {
        if (index >= 0 && index < binds.size()) {
            binds.remove(index);
        }
    }

    public boolean isSearchLore() {
        return searchLore;
    }

    public void setSearchLore(boolean searchLore) {
        this.searchLore = searchLore;
    }

    public boolean isIncludeInventory() {
        return includeInventory;
    }

    public void setIncludeInventory(boolean includeInventory) {
        this.includeInventory = includeInventory;
    }

    public boolean isOpenOnClick() {
        return openOnClick;
    }

    public void setOpenOnClick(boolean openOnClick) {
        this.openOnClick = openOnClick;
    }

    public boolean isSearchChests() {
        return searchChests;
    }

    public void setSearchChests(boolean searchChests) {
        this.searchChests = searchChests;
    }

    public int getChestRadius() {
        return chestRadius;
    }

    public void setChestRadius(int chestRadius) {
        this.chestRadius = Math.max(MIN_CHEST_RADIUS, Math.min(MAX_CHEST_RADIUS, chestRadius));
    }

    public boolean isChestEsp() {
        return chestEsp;
    }

    public void setChestEsp(boolean chestEsp) {
        this.chestEsp = chestEsp;
    }

    public boolean isEspThroughWalls() {
        return espThroughWalls;
    }

    public void setEspThroughWalls(boolean espThroughWalls) {
        this.espThroughWalls = espThroughWalls;
    }

    public int getEspSeconds() {
        return espSeconds;
    }

    public void setEspSeconds(int espSeconds) {
        this.espSeconds = Math.max(MIN_ESP_SECONDS, Math.min(MAX_ESP_SECONDS, espSeconds));
    }

    public boolean isSearchExtras() {
        return searchExtras;
    }

    public void setSearchExtras(boolean searchExtras) {
        this.searchExtras = searchExtras;
    }

    public boolean isFocusSingleStorage() {
        return focusSingleStorage;
    }

    public void setFocusSingleStorage(boolean focusSingleStorage) {
        this.focusSingleStorage = focusSingleStorage;
    }

    public SortMode getSortMode() {
        return sortMode;
    }

    public void setSortMode(SortMode sortMode) {
        this.sortMode = sortMode;
    }

    public SourceFilter getSourceFilter() {
        return sourceFilter;
    }

    public void setSourceFilter(SourceFilter sourceFilter) {
        this.sourceFilter = sourceFilter;
    }
}
