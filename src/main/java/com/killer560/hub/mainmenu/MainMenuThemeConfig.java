package com.killer560.hub.mainmenu;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted "Themed Main Menu" toggle (2026-09-15). Default ON - killer560 explicitly asked for the
 *  black+orange title screen redesign; switching it off restores the vanilla panorama, grey buttons and
 *  white/yellow text. Not gated by SkyblockGate: the title screen is never "in Skyblock". */
public final class MainMenuThemeConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-mainmenu.json");

    private static volatile MainMenuThemeConfig instance;

    private boolean enabled = true;
    private boolean particles = true;
    /** Extends the theme past the title screen (multiplayer, options, world select, other mods' menus). */
    private boolean otherMenus = true;

    // ---- the redesigned main menu (killer560, 2026-10-15: "Redo the main menu overlay to fit the new mod themes ...
    // have toggle options for it to match, customize it, or have a different main menu than mod menu") ----

    /** How the main menu picks its look. */
    public enum Style {
        /** The mod menu's own theme and that theme's background. */
        MATCH("Match Menu Theme"),
        /** A theme of its own (any preset or the menu's Custom colors), with that theme's background. */
        SEPARATE("Separate Theme"),
        /** A theme of its own plus every look setting: background, particles, speed, splash text, button panel. */
        CUSTOM("Custom");

        public final String label;

        Style(String label) {
            this.label = label;
        }
    }

    /** The animated background. AUTO is the theme's own (Amber embers, Ocean waterfall, Dark starfield...). */
    public enum Scene {
        AUTO("Theme Default"),
        EMBERS("Embers"),
        WATERFALL("Waterfall"),
        STARFIELD("Starfield"),
        AURORA("Aurora"),
        SOFT_LIGHT("Soft Light"),
        FIREFLIES("Fireflies"),
        PLAIN("Plain");

        public final String label;

        Scene(String label) {
            this.label = label;
        }
    }

    public enum Density {
        LOW("Low", 0.5f),
        NORMAL("Normal", 1f),
        HIGH("High", 1.6f);

        public final String label;
        public final float factor;

        Density(String label, float factor) {
            this.label = label;
            this.factor = factor;
        }
    }

    public static final int[] SPEEDS = {25, 50, 75, 100, 150, 200};

    private Style style = Style.MATCH;
    /** The main menu's own theme for Separate and Custom: a MenuTheme name (CUSTOM = the menu's Custom colors). */
    private com.killer560.hub.gui.MenuTheme theme = com.killer560.hub.gui.MenuTheme.OCEAN;
    private Scene scene = Scene.AUTO;
    private Density density = Density.NORMAL;
    /** Animation speed in percent. */
    private int speed = 100;
    private boolean splash = true;
    /** The frosted panel behind the button column. */
    private boolean panel = true;
    /** Bumped on every change, so the drawing side can cache what it resolves. */
    private static volatile int version;

    private MainMenuThemeConfig() {
    }

    public static MainMenuThemeConfig getInstance() {
        MainMenuThemeConfig cfg = instance;
        if (cfg == null) {
            load();
            cfg = instance;
        }
        return cfg;
    }

    public static void load() {
        MainMenuThemeConfig cfg = new MainMenuThemeConfig();
        try {
            if (Files.exists(CONFIG_PATH)) {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
                cfg.particles = ConfigJson.getBool(obj, "particles", cfg.particles);
                cfg.otherMenus = ConfigJson.getBool(obj, "otherMenus", cfg.otherMenus);
                cfg.style = ConfigJson.getEnum(obj, "style", Style.class, cfg.style);
                if (obj.has("theme")) {
                    cfg.theme = com.killer560.hub.gui.MenuTheme.parse(ConfigJson.getString(obj, "theme", "OCEAN"));
                }
                cfg.scene = ConfigJson.getEnum(obj, "scene", Scene.class, cfg.scene);
                cfg.density = ConfigJson.getEnum(obj, "density", Density.class, cfg.density);
                cfg.speed = clampSpeed(ConfigJson.getInt(obj, "speed", cfg.speed));
                cfg.splash = ConfigJson.getBool(obj, "splash", cfg.splash);
                cfg.panel = ConfigJson.getBool(obj, "panel", cfg.panel);
            }
        } catch (Exception ignored) {
            // Unparseable file: keep defaults for this session (per-key reads above handle single bad keys).
        }
        instance = cfg;
        version++;
    }

    public static int version() {
        return version;
    }

    private static int clampSpeed(int s) {
        int best = 100;
        for (int v : SPEEDS) {
            if (Math.abs(v - s) < Math.abs(best - s)) {
                best = v;
            }
        }
        return best;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("particles", particles);
            obj.addProperty("otherMenus", otherMenus);
            obj.addProperty("style", style.name());
            obj.addProperty("theme", theme.name());
            obj.addProperty("scene", scene.name());
            obj.addProperty("density", density.name());
            obj.addProperty("speed", speed);
            obj.addProperty("splash", splash);
            obj.addProperty("panel", panel);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        version++;
    }

    /** Drifting ember particles on the themed background (no UI toggle yet; hand-editable). */
    public boolean isParticles() {
        return particles;
    }

    public void setParticles(boolean particles) {
        this.particles = particles;
        version++;
    }

    public boolean isOtherMenus() {
        return otherMenus;
    }

    public void setOtherMenus(boolean otherMenus) {
        this.otherMenus = otherMenus;
    }

    public Style getStyle() {
        return style;
    }

    public void setStyle(Style style) {
        this.style = style == null ? Style.MATCH : style;
        version++;
    }

    public com.killer560.hub.gui.MenuTheme getTheme() {
        return theme;
    }

    public void setTheme(com.killer560.hub.gui.MenuTheme theme) {
        this.theme = theme == null ? com.killer560.hub.gui.MenuTheme.OCEAN : theme;
        version++;
    }

    public Scene getScene() {
        return scene;
    }

    public void setScene(Scene scene) {
        this.scene = scene == null ? Scene.AUTO : scene;
        version++;
    }

    public Density getDensity() {
        return density;
    }

    public void setDensity(Density density) {
        this.density = density == null ? Density.NORMAL : density;
        version++;
    }

    public int getSpeed() {
        return speed;
    }

    public void setSpeed(int speed) {
        this.speed = clampSpeed(speed);
        version++;
    }

    public boolean isSplash() {
        return splash;
    }

    public void setSplash(boolean splash) {
        this.splash = splash;
        version++;
    }

    public boolean isPanel() {
        return panel;
    }

    public void setPanel(boolean panel) {
        this.panel = panel;
        version++;
    }
}
