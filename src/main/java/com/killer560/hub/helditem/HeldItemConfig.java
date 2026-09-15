package com.killer560.hub.helditem;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted "Held Item Transform" settings - resize/reposition/reorient the first-person held item, plus
 *  no-swing / no-equip / no-hand-sway toggles and a swing speed multiplier. Ported from NoammAddons'
 *  "Animations" (itself Odin's / SkyHanni's "Item Animation" idea). Purely visual, both builds, ships OFF.
 *  <p>
 *  The mixins in {@code helditem.mixin} read {@link #active} first - one static boolean read is the entire
 *  disabled-path cost - and only touch {@link #getInstance()} once it's true. */
public final class HeldItemConfig {

    public static final float MIN_SCALE = 0.1f;
    public static final float MAX_SCALE = 2.0f;
    public static final float MIN_OFFSET = -1.0f;
    public static final float MAX_OFFSET = 1.0f;
    public static final float MIN_ROTATION = -180f;
    public static final float MAX_ROTATION = 180f;
    public static final float MIN_SWING_SPEED = 0.25f;
    public static final float MAX_SWING_SPEED = 4.0f;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-helditem.json");

    /** Mirror of {@code instance.enabled} - the hot-path flag the render mixins check. */
    public static boolean active;

    private static HeldItemConfig instance;

    // Load on first class touch (the first frame's mixin read of `active`), so the feature comes back on
    // after a restart without needing a register call in the client entrypoint.
    static {
        load();
    }

    /** One hand's transform. X offset and Y/Z rotations are mirrored for whichever hand renders on the
     *  left side (same convention vanilla uses), so a positive X always means "outward" on both hands. */
    public static final class HandTransform {
        public float scale = 1.0f;
        public float x = 0f;
        public float y = 0f;
        public float z = 0f;
        public float rotX = 0f;
        public float rotY = 0f;
        public float rotZ = 0f;

        public boolean isIdentity() {
            return scale == 1.0f && x == 0f && y == 0f && z == 0f && rotX == 0f && rotY == 0f && rotZ == 0f;
        }

        public void reset() {
            scale = 1.0f;
            x = y = z = 0f;
            rotX = rotY = rotZ = 0f;
        }

        private JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("scale", scale);
            obj.addProperty("x", x);
            obj.addProperty("y", y);
            obj.addProperty("z", z);
            obj.addProperty("rotX", rotX);
            obj.addProperty("rotY", rotY);
            obj.addProperty("rotZ", rotZ);
            return obj;
        }

        private void fromJson(JsonObject obj) {
            scale = clamp(getFloat(obj, "scale", 1.0f), MIN_SCALE, MAX_SCALE);
            x = clamp(getFloat(obj, "x", 0f), MIN_OFFSET, MAX_OFFSET);
            y = clamp(getFloat(obj, "y", 0f), MIN_OFFSET, MAX_OFFSET);
            z = clamp(getFloat(obj, "z", 0f), MIN_OFFSET, MAX_OFFSET);
            rotX = clamp(getFloat(obj, "rotX", 0f), MIN_ROTATION, MAX_ROTATION);
            rotY = clamp(getFloat(obj, "rotY", 0f), MIN_ROTATION, MAX_ROTATION);
            rotZ = clamp(getFloat(obj, "rotZ", 0f), MIN_ROTATION, MAX_ROTATION);
        }
    }

    private boolean enabled = false;
    /** When off, the off hand reuses the main hand's values (mirrored); when on, it has its own section. */
    private boolean separateOffHand = false;
    private boolean noSwing = false;
    private boolean noEquip = false;
    private boolean noHandSway = false;
    private float swingSpeed = 1.0f;
    private final HandTransform mainHand = new HandTransform();
    private final HandTransform offHand = new HandTransform();

    private HeldItemConfig() {
    }

    public static HeldItemConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        HeldItemConfig cfg = new HeldItemConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
                cfg.separateOffHand = ConfigJson.getBool(obj, "separateOffHand", cfg.separateOffHand);
                cfg.noSwing = ConfigJson.getBool(obj, "noSwing", cfg.noSwing);
                cfg.noEquip = ConfigJson.getBool(obj, "noEquip", cfg.noEquip);
                cfg.noHandSway = ConfigJson.getBool(obj, "noHandSway", cfg.noHandSway);
                cfg.swingSpeed = clamp(getFloat(obj, "swingSpeed", 1.0f), MIN_SWING_SPEED, MAX_SWING_SPEED);
                JsonObject main = ConfigJson.getObject(obj, "mainHand");
                if (main != null) {
                    cfg.mainHand.fromJson(main);
                }
                JsonObject off = ConfigJson.getObject(obj, "offHand");
                if (off != null) {
                    cfg.offHand.fromJson(off);
                }
            } catch (Exception e) {
                cfg = new HeldItemConfig();
            }
        }
        instance = cfg;
        active = cfg.enabled;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("separateOffHand", separateOffHand);
            obj.addProperty("noSwing", noSwing);
            obj.addProperty("noEquip", noEquip);
            obj.addProperty("noHandSway", noHandSway);
            obj.addProperty("swingSpeed", swingSpeed);
            obj.add("mainHand", mainHand.toJson());
            obj.add("offHand", offHand.toJson());
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** Resets every value (both hands, toggles, swing speed) to vanilla - leaves the master toggle alone. */
    public void resetValues() {
        separateOffHand = false;
        noSwing = false;
        noEquip = false;
        noHandSway = false;
        swingSpeed = 1.0f;
        mainHand.reset();
        offHand.reset();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        active = enabled;
    }

    public boolean isSeparateOffHand() {
        return separateOffHand;
    }

    public void setSeparateOffHand(boolean separateOffHand) {
        this.separateOffHand = separateOffHand;
    }

    public boolean isNoSwing() {
        return noSwing;
    }

    public void setNoSwing(boolean noSwing) {
        this.noSwing = noSwing;
    }

    public boolean isNoEquip() {
        return noEquip;
    }

    public void setNoEquip(boolean noEquip) {
        this.noEquip = noEquip;
    }

    public boolean isNoHandSway() {
        return noHandSway;
    }

    public void setNoHandSway(boolean noHandSway) {
        this.noHandSway = noHandSway;
    }

    public float getSwingSpeed() {
        return swingSpeed;
    }

    public void setSwingSpeed(float swingSpeed) {
        this.swingSpeed = clamp(swingSpeed, MIN_SWING_SPEED, MAX_SWING_SPEED);
    }

    public HandTransform getMainHand() {
        return mainHand;
    }

    public HandTransform getOffHand() {
        return offHand;
    }

    /** The transform that actually applies to a hand ({@code mainHand} for the off hand too unless
     *  {@link #isSeparateOffHand()}). */
    public HandTransform forHand(boolean isMainHand) {
        return isMainHand || !separateOffHand ? mainHand : offHand;
    }

    private static float getFloat(JsonObject obj, String key, float def) {
        return ConfigJson.getFloat(obj, key, def);
    }

    static float clamp(float v, float min, float max) {
        if (Float.isNaN(v)) {
            return min;
        }
        return v < min ? min : (v > max ? max : v);
    }
}
