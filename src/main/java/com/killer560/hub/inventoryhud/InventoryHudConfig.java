package com.killer560.hub.inventoryhud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Inventory HUD settings - see {@link InventoryHudFeature}. Every field here survives a restart,
 *  including the runtime toggle-key state. Position/scale live in {@code HudConfig} under the element id. */
public final class InventoryHudConfig {

    public enum Background {
        NONE("None"), PANEL("Panel"), SLOTS("Slots");

        public final String label;

        Background(String label) {
            this.label = label;
        }

        public Background next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    public enum Visibility {
        ALWAYS("Always"), HOLD_KEY("Hold Key"), TOGGLE_KEY("Toggle Key");

        public final String label;

        Visibility(String label) {
            this.label = label;
        }

        public Visibility next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-inventoryhud.json");

    private static InventoryHudConfig instance;

    private boolean enabled = false;
    private boolean vertical = false;
    private boolean miniMode = false;
    private Background background = Background.SLOTS;
    private int backgroundOpacity = 80;
    private boolean showCounts = true;
    private boolean showDurability = true;
    private boolean pickupAnimation = true;
    private boolean hideWhenEmpty = false;
    private boolean hideInScreens = true;
    private Visibility visibility = Visibility.ALWAYS;
    private int keyCode = -1;
    private boolean toggledVisible = true;

    private InventoryHudConfig() {
    }

    public static InventoryHudConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        InventoryHudConfig cfg = new InventoryHudConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                cfg.enabled = bool(obj, "enabled", cfg.enabled);
                cfg.vertical = bool(obj, "vertical", cfg.vertical);
                cfg.miniMode = bool(obj, "miniMode", cfg.miniMode);
                cfg.background = enumValue(obj, "background", Background.class, cfg.background);
                cfg.backgroundOpacity = obj.has("backgroundOpacity")
                        ? clampOpacity(obj.get("backgroundOpacity").getAsInt()) : cfg.backgroundOpacity;
                cfg.showCounts = bool(obj, "showCounts", cfg.showCounts);
                cfg.showDurability = bool(obj, "showDurability", cfg.showDurability);
                cfg.pickupAnimation = bool(obj, "pickupAnimation", cfg.pickupAnimation);
                cfg.hideWhenEmpty = bool(obj, "hideWhenEmpty", cfg.hideWhenEmpty);
                cfg.hideInScreens = bool(obj, "hideInScreens", cfg.hideInScreens);
                cfg.visibility = enumValue(obj, "visibility", Visibility.class, cfg.visibility);
                cfg.keyCode = obj.has("keyCode") ? obj.get("keyCode").getAsInt() : cfg.keyCode;
                cfg.toggledVisible = bool(obj, "toggledVisible", cfg.toggledVisible);
            } catch (Exception e) {
                cfg = new InventoryHudConfig();
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("vertical", vertical);
            obj.addProperty("miniMode", miniMode);
            obj.addProperty("background", background.name());
            obj.addProperty("backgroundOpacity", backgroundOpacity);
            obj.addProperty("showCounts", showCounts);
            obj.addProperty("showDurability", showDurability);
            obj.addProperty("pickupAnimation", pickupAnimation);
            obj.addProperty("hideWhenEmpty", hideWhenEmpty);
            obj.addProperty("hideInScreens", hideInScreens);
            obj.addProperty("visibility", visibility.name());
            obj.addProperty("keyCode", keyCode);
            obj.addProperty("toggledVisible", toggledVisible);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static boolean bool(JsonObject obj, String key, boolean def) {
        return obj.has(key) ? obj.get(key).getAsBoolean() : def;
    }

    private static <E extends Enum<E>> E enumValue(JsonObject obj, String key, Class<E> type, E def) {
        if (!obj.has(key)) {
            return def;
        }
        try {
            return Enum.valueOf(type, obj.get(key).getAsString());
        } catch (IllegalArgumentException e) {
            return def;
        }
    }

    private static int clampOpacity(int value) {
        return Math.max(0, Math.min(100, value));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isVertical() {
        return vertical;
    }

    public void setVertical(boolean vertical) {
        this.vertical = vertical;
    }

    public boolean isMiniMode() {
        return miniMode;
    }

    public void setMiniMode(boolean miniMode) {
        this.miniMode = miniMode;
    }

    public Background getBackground() {
        return background;
    }

    public void setBackground(Background background) {
        this.background = background;
    }

    public int getBackgroundOpacity() {
        return backgroundOpacity;
    }

    public void setBackgroundOpacity(int backgroundOpacity) {
        this.backgroundOpacity = clampOpacity(backgroundOpacity);
    }

    public boolean isShowCounts() {
        return showCounts;
    }

    public void setShowCounts(boolean showCounts) {
        this.showCounts = showCounts;
    }

    public boolean isShowDurability() {
        return showDurability;
    }

    public void setShowDurability(boolean showDurability) {
        this.showDurability = showDurability;
    }

    public boolean isPickupAnimation() {
        return pickupAnimation;
    }

    public void setPickupAnimation(boolean pickupAnimation) {
        this.pickupAnimation = pickupAnimation;
    }

    public boolean isHideWhenEmpty() {
        return hideWhenEmpty;
    }

    public void setHideWhenEmpty(boolean hideWhenEmpty) {
        this.hideWhenEmpty = hideWhenEmpty;
    }

    public boolean isHideInScreens() {
        return hideInScreens;
    }

    public void setHideInScreens(boolean hideInScreens) {
        this.hideInScreens = hideInScreens;
    }

    public Visibility getVisibility() {
        return visibility;
    }

    public void setVisibility(Visibility visibility) {
        this.visibility = visibility;
    }

    public int getKeyCode() {
        return keyCode;
    }

    public void setKeyCode(int keyCode) {
        this.keyCode = keyCode;
    }

    public boolean isToggledVisible() {
        return toggledVisible;
    }

    public void setToggledVisible(boolean toggledVisible) {
        this.toggledVisible = toggledVisible;
    }
}
