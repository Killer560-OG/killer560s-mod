package com.killer560.hub.autoroutes;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.KeyUtil;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Persisted Auto Routes settings ({@code killer560smod-autoroutes-settings.json}). Cheat build only; ships
 * disabled. Kept SEPARATE from the routes file ({@link RouteStore}) so sharing your routes never hands over your
 * keybinds and colours, and someone else's routes file never overwrites your settings.
 * <p>
 * Same split as {@code leveraura/LeverAuraConfig}: every getter that can make the feature act is gated on
 * {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} and {@link com.killer560.hub.util.SkyblockGate},
 * the {@code ...Raw()} getters are for tab labels. Per-key {@link ConfigJson} readers, defaults on any bad key, and
 * a file whose parse actually failed is never overwritten by the load itself (see {@code posmsg/PosmsgConfig}).
 */
public final class AutoRoutesConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-autoroutes-settings.json");

    /** Node marker styles (QUOI: Box / Filled box / Cylinder). */
    public enum RenderStyle {
        BOX, FILLED, CYLINDER;

        public String label() {
            return switch (this) {
                case BOX -> "Box";
                case FILLED -> "Filled box";
                case CYLINDER -> "Cylinder";
            };
        }
    }

    /** Stable keybind ids, one per {@code /ar} command - all default to unbound ({@link KeyUtil#NONE}). Unknown
     *  ids passed to {@link #getKeybind}/{@link #setKeybind} are accepted too (stored under their own name). */
    public static final List<String> KEYBIND_IDS = List.of(
            "startRecord", "stopRecord", "addEtherwarp", "addBreaker", "addUse", "addWalk", "addBoom", "addAwait",
            "addStart", "editDb", "clear", "list", "deleteLast", "reload");

    public static final float MIN_THICKNESS = 1f;
    public static final float MAX_THICKNESS = 8f;
    public static final float MIN_HEIGHT = 0.1f;
    public static final float MAX_HEIGHT = 1f;
    public static final int MIN_INTERACT_DELAY = 0;
    public static final int MAX_INTERACT_DELAY = 6;
    public static final double MIN_DRIFT = 0.5;
    public static final double MAX_DRIFT = 6.0;
    public static final int MIN_REACH_TIMEOUT = 10;
    public static final int MAX_REACH_TIMEOUT = 400;

    private static AutoRoutesConfig instance;

    private boolean enabled = false;
    /** killer560: "legit or obvious - in legit mode it rotates for every etherwarp." */
    private boolean legitMode = true;
    /** "an option in the main settings so it will not start a route halfway through" - forced on in legit mode. */
    private boolean startFromStartNodeOnly = true;
    private boolean uniformColor = false;
    private int uniformColorArgb = 0xFF00FFFF;
    private int activeColorArgb = 0xFFFFFFFF;
    private final Map<RouteNode.Type, Integer> nodeColors = new EnumMap<>(RouteNode.Type.class);
    private RenderStyle renderStyle = RenderStyle.BOX;
    private float thickness = 4f;
    private float height = 0.1f;
    /** Ticks between a rotation settling and the click that follows it (QUOI "Interact delay"). */
    private int interactDelayTicks = 2;
    /** Further than this from the recorded path and playback gives up (lag defence, see the spec). */
    private double maxDriftDistance = 2.5;
    /** No progress along the path for this many ticks and playback gives up. */
    private int reachTimeoutTicks = 60;
    /** COMMAND nodes send chat/commands from the player's own account. Default OFF because the routes file
     *  is meant to be shared - see RouteExecutor's COMMAND case (2026-09-16 review). */
    private boolean allowCommandNodes = false;
    private boolean chatFeedback = true;
    private final Map<String, Integer> keybinds = new LinkedHashMap<>();

    private AutoRoutesConfig() {
        for (RouteNode.Type t : RouteNode.Type.values()) {
            nodeColors.put(t, defaultNodeColor(t));
        }
        for (String id : KEYBIND_IDS) {
            keybinds.put(id, KeyUtil.NONE);
        }
    }

    /** QUOI's per-action colours, plus killer560's "superboom nodes red, bat nodes bat-coloured" (await = the
     *  bat-secret wait, so it gets a bat brown). */
    public static int defaultNodeColor(RouteNode.Type type) {
        return switch (type) {
            case START -> 0xFF55FF55;
            case WALK -> 0xFFFFFFFF;
            case ETHERWARP -> 0xFF00FFFF;
            case USE_ITEM -> 0xFFA0522D;
            case DUNGEON_BREAKER -> 0xFFFFA500;
            case BOOM -> 0xFFFF0000;
            case AWAIT -> 0xFF6B4E2E;
            case ROTATE -> 0xFFFFFF00;
            case UNSNEAK -> 0xFFFF00FF;
            case COMMAND -> 0xFFC0C0C0;
        };
    }

    public static AutoRoutesConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        AutoRoutesConfig cfg = new AutoRoutesConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(o, "enabled", cfg.enabled);
                cfg.legitMode = ConfigJson.getBool(o, "legitMode", cfg.legitMode);
                cfg.startFromStartNodeOnly = ConfigJson.getBool(o, "startFromStartNodeOnly", cfg.startFromStartNodeOnly);
                cfg.uniformColor = ConfigJson.getBool(o, "uniformColor", cfg.uniformColor);
                cfg.uniformColorArgb = ConfigJson.getInt(o, "uniformColorArgb", cfg.uniformColorArgb);
                cfg.activeColorArgb = ConfigJson.getInt(o, "activeColorArgb", cfg.activeColorArgb);
                JsonObject colors = ConfigJson.getObject(o, "nodeColors");
                if (colors != null) {
                    for (RouteNode.Type t : RouteNode.Type.values()) {
                        cfg.nodeColors.put(t, ConfigJson.getInt(colors, t.name(), cfg.nodeColors.get(t)));
                    }
                }
                cfg.renderStyle = ConfigJson.getEnum(o, "renderStyle", RenderStyle.class, cfg.renderStyle);
                cfg.setThickness(ConfigJson.getFloat(o, "thickness", cfg.thickness));
                cfg.setHeight(ConfigJson.getFloat(o, "height", cfg.height));
                cfg.setInteractDelayTicks(ConfigJson.getInt(o, "interactDelayTicks", cfg.interactDelayTicks));
                cfg.setMaxDriftDistance(ConfigJson.getDouble(o, "maxDriftDistance", cfg.maxDriftDistance));
                cfg.setReachTimeoutTicks(ConfigJson.getInt(o, "reachTimeoutTicks", cfg.reachTimeoutTicks));
                cfg.allowCommandNodes = ConfigJson.getBool(o, "allowCommandNodes", cfg.allowCommandNodes);
                cfg.chatFeedback = ConfigJson.getBool(o, "chatFeedback", cfg.chatFeedback);
                JsonObject keys = ConfigJson.getObject(o, "keybinds");
                if (keys != null) {
                    for (String id : keys.keySet()) {
                        cfg.keybinds.put(id, KeyUtil.sanitize(ConfigJson.getInt(keys, id, KeyUtil.NONE)));
                    }
                }
            } catch (Exception e) {
                // unreadable file (not just a bad key) - keep defaults; the next explicit save() rewrites it
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("enabled", enabled);
            o.addProperty("legitMode", legitMode);
            o.addProperty("startFromStartNodeOnly", startFromStartNodeOnly);
            o.addProperty("uniformColor", uniformColor);
            o.addProperty("allowCommandNodes", allowCommandNodes);
            o.addProperty("uniformColorArgb", uniformColorArgb);
            o.addProperty("activeColorArgb", activeColorArgb);
            JsonObject colors = new JsonObject();
            for (RouteNode.Type t : RouteNode.Type.values()) {
                colors.addProperty(t.name(), nodeColors.get(t));
            }
            o.add("nodeColors", colors);
            o.addProperty("renderStyle", renderStyle.name());
            o.addProperty("thickness", thickness);
            o.addProperty("height", height);
            o.addProperty("interactDelayTicks", interactDelayTicks);
            o.addProperty("maxDriftDistance", maxDriftDistance);
            o.addProperty("reachTimeoutTicks", reachTimeoutTicks);
            o.addProperty("chatFeedback", chatFeedback);
            JsonObject keys = new JsonObject();
            for (Map.Entry<String, Integer> e : keybinds.entrySet()) {
                keys.addProperty(e.getKey(), e.getValue());
            }
            o.add("keybinds", keys);
            Files.writeString(CONFIG_PATH, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------------------------------- master

    /** The legit jar can never report true here, even from a copied cheat-build config file. */
    public boolean isEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && enabled
                && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean isEnabledRaw() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }

    public boolean isLegitMode() { return legitMode; }
    public void setLegitMode(boolean v) { legitMode = v; }

    /** Forced on in legit mode - "in legit mode it can only start from the start node". */
    public boolean isStartFromStartNodeOnly() { return legitMode || startFromStartNodeOnly; }
    public boolean isStartFromStartNodeOnlyRaw() { return startFromStartNodeOnly; }
    public void setStartFromStartNodeOnly(boolean v) { startFromStartNodeOnly = v; }

    // ------------------------------------------------------------------------------------------- colours

    public boolean isUniformColor() { return uniformColor; }
    public void setUniformColor(boolean v) { uniformColor = v; }

    public int getUniformColorArgb() { return uniformColorArgb; }
    public void setUniformColorArgb(int argb) { uniformColorArgb = argb; }

    public int getActiveColorArgb() { return activeColorArgb; }
    public void setActiveColorArgb(int argb) { activeColorArgb = argb; }

    public int getNodeColorArgb(RouteNode.Type type) {
        Integer c = type == null ? null : nodeColors.get(type);
        return c == null ? 0xFFFFFFFF : c;
    }

    public void setNodeColorArgb(RouteNode.Type type, int argb) {
        if (type != null) {
            nodeColors.put(type, argb);
        }
    }

    /** The colour a node should render with: its own override, else the uniform colour, else its type's. */
    public int colorFor(RouteNode node) {
        if (node != null && node.colour != null) {
            return node.colour;
        }
        if (uniformColor) {
            return uniformColorArgb;
        }
        return getNodeColorArgb(node == null ? null : node.type);
    }

    // ------------------------------------------------------------------------------------------- rendering

    public RenderStyle getRenderStyle() { return renderStyle; }
    public void setRenderStyle(RenderStyle v) { renderStyle = v == null ? RenderStyle.BOX : v; }

    public float getThickness() { return thickness; }
    public void setThickness(float v) {
        if (!Float.isFinite(v)) {
            return;
        }
        thickness = Math.max(MIN_THICKNESS, Math.min(MAX_THICKNESS, Math.round(v * 2f) / 2f));
    }

    public float getHeight() { return height; }
    public void setHeight(float v) {
        if (!Float.isFinite(v)) {
            return;
        }
        height = Math.max(MIN_HEIGHT, Math.min(MAX_HEIGHT, Math.round(v * 10f) / 10f));
    }

    // ------------------------------------------------------------------------------------------- executor

    public int getInteractDelayTicks() { return interactDelayTicks; }
    public void setInteractDelayTicks(int v) {
        interactDelayTicks = Math.max(MIN_INTERACT_DELAY, Math.min(MAX_INTERACT_DELAY, v));
    }

    public double getMaxDriftDistance() { return maxDriftDistance; }
    public void setMaxDriftDistance(double v) {
        if (!Double.isFinite(v)) {
            return;
        }
        maxDriftDistance = Math.max(MIN_DRIFT, Math.min(MAX_DRIFT, Math.round(v * 10.0) / 10.0));
    }

    public int getReachTimeoutTicks() { return reachTimeoutTicks; }
    public void setReachTimeoutTicks(int v) {
        reachTimeoutTicks = Math.max(MIN_REACH_TIMEOUT, Math.min(MAX_REACH_TIMEOUT, v));
    }

    public boolean isChatFeedback() { return chatFeedback; }
    public void setChatFeedback(boolean v) { chatFeedback = v; }

    // ------------------------------------------------------------------------------------------- keybinds

    /** GLFW key code for a command's keybind ({@link KeyUtil#NONE} when unbound). Ids are matched
     *  case-insensitively so {@code "addEtherwarp"} and {@code "addetherwarp"} are the same bind. */
    public int getKeybind(String id) {
        if (id == null) {
            return KeyUtil.NONE;
        }
        Integer code = keybinds.get(canonical(id));
        return code == null ? KeyUtil.NONE : code;
    }

    public void setKeybind(String id, int code) {
        if (id != null) {
            keybinds.put(canonical(id), KeyUtil.sanitize(code));
        }
    }

    private static String canonical(String id) {
        String t = id.trim();
        for (String known : KEYBIND_IDS) {
            if (known.equalsIgnoreCase(t)) {
                return known;
            }
        }
        return t.toLowerCase(Locale.ROOT);
    }

    public boolean isAllowCommandNodes() {
        return allowCommandNodes;
    }

    public void setAllowCommandNodes(boolean allowCommandNodes) {
        this.allowCommandNodes = allowCommandNodes;
    }
}
