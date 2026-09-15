package com.killer560.hub.i4sensors;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Persisted "Sharp Shooter (i4)" settings - the i4 solver ({@link I4SolverFeature}), the always-on i4 sensors
 *  ({@link I4SensorsFeature}) and Auto i4 ({@link AutoI4Feature}, {@link I4AutoMask}). Every setting loads and
 *  saves. Automation defaults OFF; only harmless sub-options (mode, weapon, order, rotation time) have
 *  non-off defaults. The old "enabled" key (Verbose Sensor Logging, removed 2026-09-14) is still read and
 *  written back unchanged so an existing config round-trips, but nothing uses it any more. */
public final class I4SensorsConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-i4sensors.json");

    // killer560 (2026-09-14): "Add a slider to adjust how fast it rotates" - 0-400ms, used in Rotate mode even
    // with Predictions on (Noamm forces 170ms there; that override is gone).
    public static final int MAX_ROTATION_TIME_MS = 400;

    public enum Weapon {
        TERMINATOR("Terminator", "TERMINATOR", "Terminator"),
        MACHINE_GUN_SHORTBOW("Machine Gun Shortbow", "MACHINE_GUN_BOW", "Machine Gun Shortbow");

        public final String label;
        /** Real Skyblock ids from Hypixel's own items resource (hypixelskyblock.minecraft.wiki infobox agrees). */
        public final String skyblockId;
        public final String nameFallback;

        Weapon(String label, String skyblockId, String nameFallback) {
            this.label = label;
            this.skyblockId = skyblockId;
            this.nameFallback = nameFallback;
        }
    }

    public enum DeathItem {
        BONZO("Bonzo"), SPIRIT("Spirit"), PHOENIX("Phoenix");

        public final String label;

        DeathItem(String label) {
            this.label = label;
        }
    }

    /** All 6 orderings, in the order the tab's Order button cycles through them. */
    public static final List<List<DeathItem>> ORDERS = List.of(
            List.of(DeathItem.BONZO, DeathItem.SPIRIT, DeathItem.PHOENIX),
            List.of(DeathItem.BONZO, DeathItem.PHOENIX, DeathItem.SPIRIT),
            List.of(DeathItem.SPIRIT, DeathItem.BONZO, DeathItem.PHOENIX),
            List.of(DeathItem.SPIRIT, DeathItem.PHOENIX, DeathItem.BONZO),
            List.of(DeathItem.PHOENIX, DeathItem.BONZO, DeathItem.SPIRIT),
            List.of(DeathItem.PHOENIX, DeathItem.SPIRIT, DeathItem.BONZO));

    private static I4SensorsConfig instance;

    private boolean legacyVerboseEnabled = false;
    private boolean solverEnabled = false;
    // Aim marker color (2026-09-14, killer560: the aim squares were "almost impossible to see") - user-pickable;
    // bright cyan contrasts with the purple glass and grey wall. Hit targets stay orange.
    // Neon yellow (2026-09-14, killer560: "super bright color and really noticeable") - yellow is purple's
    // complement, so it pops hardest against the i4 wall's purple glass. Cyan was the previous default.
    public static final int DEFAULT_SOLVER_COLOR = 0xFFFFFF00;
    private static final int PREVIOUS_DEFAULT_SOLVER_COLOR = 0xFF00FFFF;
    private int solverColor = DEFAULT_SOLVER_COLOR;
    private boolean autoI4Enabled = false;
    // Same Rotate / No Rotate split as Simon Says (killer560's own request, 2026-09-14): Rotate turns the
    // real camera to each target before shooting; No Rotate aims server-side only for the shot.
    private boolean autoI4Rotate = true;
    // Defaults ported from NoammAddons' AutoI4.kt ("Rotation Time" 170ms, "Predictions" on).
    private int autoI4RotationTimeMs = 170;
    private boolean autoI4Predictions = true;
    private Weapon autoI4Weapon = Weapon.TERMINATOR;
    private boolean autoSwapToBow = false;
    private boolean autoMask = false;
    private int maskOrderIndex = 0;
    // killer560 (2026-09-14): "add a shot accuracy percentage. This is the chance that it aims a little too low or
    // high missing the shot." 100 = every shot aimed exactly.
    private int shotAccuracyPercent = 100;
    // CPS range (2026-09-14, killer560: "a cps range between 1-15 that has both ends of a slider. Whenever i4 starts
    // itll pick a number from that and go +-1 or 2 each way... somewhat evenly balanced"). Default 4-6: the
    // Terminator's own cooldown swallowed shots closer than ~180ms in a real p3sim log.
    public static final int MIN_CPS = 1;
    public static final int MAX_CPS = 15;
    private int cpsMin = 4;
    private int cpsMax = 6;

    private I4SensorsConfig() {
    }

    public static I4SensorsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new I4SensorsConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            I4SensorsConfig cfg = new I4SensorsConfig();
            cfg.legacyVerboseEnabled = obj.has("enabled") && obj.get("enabled").getAsBoolean();
            cfg.solverEnabled = obj.has("solverEnabled") && obj.get("solverEnabled").getAsBoolean();
            cfg.solverColor = obj.has("solverColor") ? obj.get("solverColor").getAsInt() : DEFAULT_SOLVER_COLOR;
            if (cfg.solverColor == PREVIOUS_DEFAULT_SOLVER_COLOR) {
                cfg.solverColor = DEFAULT_SOLVER_COLOR; // saved only because it was the old default, not a real pick
            }
            cfg.autoI4Enabled = obj.has("autoI4Enabled") && obj.get("autoI4Enabled").getAsBoolean();
            cfg.autoI4Rotate = !obj.has("autoI4Rotate") || obj.get("autoI4Rotate").getAsBoolean();
            cfg.setAutoI4RotationTimeMs(obj.has("autoI4RotationTimeMs") ? obj.get("autoI4RotationTimeMs").getAsInt() : 170);
            cfg.autoI4Predictions = !obj.has("autoI4Predictions") || obj.get("autoI4Predictions").getAsBoolean();
            if (obj.has("autoI4Weapon")) {
                try {
                    cfg.autoI4Weapon = Weapon.valueOf(obj.get("autoI4Weapon").getAsString());
                } catch (IllegalArgumentException ignored) {
                    cfg.autoI4Weapon = Weapon.TERMINATOR;
                }
            }
            cfg.autoSwapToBow = obj.has("autoSwapToBow") && obj.get("autoSwapToBow").getAsBoolean();
            cfg.autoMask = obj.has("autoMask") && obj.get("autoMask").getAsBoolean();
            cfg.setMaskOrderIndex(obj.has("maskOrderIndex") ? obj.get("maskOrderIndex").getAsInt() : 0);
            cfg.setShotAccuracyPercent(obj.has("shotAccuracyPercent") ? obj.get("shotAccuracyPercent").getAsInt() : 100);
            cfg.setCpsRange(obj.has("cpsMin") ? obj.get("cpsMin").getAsInt() : 4, obj.has("cpsMax") ? obj.get("cpsMax").getAsInt() : 6);
            instance = cfg;
        } catch (Exception e) {
            instance = new I4SensorsConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", legacyVerboseEnabled);
            obj.addProperty("solverEnabled", solverEnabled);
            obj.addProperty("solverColor", solverColor);
            obj.addProperty("autoI4Enabled", autoI4Enabled);
            obj.addProperty("autoI4Rotate", autoI4Rotate);
            obj.addProperty("autoI4RotationTimeMs", autoI4RotationTimeMs);
            obj.addProperty("autoI4Predictions", autoI4Predictions);
            obj.addProperty("autoI4Weapon", autoI4Weapon.name());
            obj.addProperty("autoSwapToBow", autoSwapToBow);
            obj.addProperty("autoMask", autoMask);
            obj.addProperty("maskOrderIndex", maskOrderIndex);
            obj.addProperty("shotAccuracyPercent", shotAccuracyPercent);
            obj.addProperty("cpsMin", cpsMin);
            obj.addProperty("cpsMax", cpsMax);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** Visual only - available on both builds. */
    public int getCpsMin() {
        return cpsMin;
    }

    public int getCpsMax() {
        return cpsMax;
    }

    public void setCpsRange(int min, int max) {
        int lo = Math.max(MIN_CPS, Math.min(MAX_CPS, Math.min(min, max)));
        int hi = Math.max(MIN_CPS, Math.min(MAX_CPS, Math.max(min, max)));
        this.cpsMin = lo;
        this.cpsMax = hi;
    }

    public int getShotAccuracyPercent() {
        return shotAccuracyPercent;
    }

    public void setShotAccuracyPercent(int percent) {
        this.shotAccuracyPercent = Math.max(0, Math.min(100, percent));
    }

    public boolean isSolverEnabled() {
        return solverEnabled;
    }

    public int getSolverColor() {
        return solverColor;
    }

    public void setSolverColor(int solverColor) {
        this.solverColor = solverColor;
    }

    public void setSolverEnabled(boolean solverEnabled) {
        this.solverEnabled = solverEnabled;
    }

    /** Gated on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} - automatic aiming/shooting,
     *  a real macro, same pattern as Auto Solve/Auto Terminals. */
    public boolean isAutoI4Enabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && autoI4Enabled;
    }

    public void setAutoI4Enabled(boolean autoI4Enabled) {
        this.autoI4Enabled = autoI4Enabled;
    }

    public boolean isAutoI4Rotate() {
        return autoI4Rotate;
    }

    public void setAutoI4Rotate(boolean autoI4Rotate) {
        this.autoI4Rotate = autoI4Rotate;
    }

    public int getAutoI4RotationTimeMs() {
        return autoI4RotationTimeMs;
    }

    public void setAutoI4RotationTimeMs(int ms) {
        this.autoI4RotationTimeMs = Math.max(0, Math.min(MAX_ROTATION_TIME_MS, ms));
    }

    /** Terminator only - a Machine Gun Shortbow fires one arrow, so there is nothing to pre-fire with. */
    public boolean isAutoI4Predictions() {
        return autoI4Predictions && autoI4Weapon == Weapon.TERMINATOR;
    }

    /** The raw toggle, for the tab. */
    public boolean getAutoI4PredictionsSetting() {
        return autoI4Predictions;
    }

    public void setAutoI4Predictions(boolean autoI4Predictions) {
        this.autoI4Predictions = autoI4Predictions;
    }

    public Weapon getAutoI4Weapon() {
        return autoI4Weapon;
    }

    public void setAutoI4Weapon(Weapon weapon) {
        this.autoI4Weapon = weapon == null ? Weapon.TERMINATOR : weapon;
    }

    /** Gated on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} - automatic hotbar swapping. */
    public boolean isAutoSwapToBow() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && autoSwapToBow;
    }

    public void setAutoSwapToBow(boolean autoSwapToBow) {
        this.autoSwapToBow = autoSwapToBow;
    }

    /** Gated on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} - automatic menu clicking. */
    public boolean isAutoMask() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && autoMask;
    }

    public void setAutoMask(boolean autoMask) {
        this.autoMask = autoMask;
    }

    public int getMaskOrderIndex() {
        return maskOrderIndex;
    }

    public void setMaskOrderIndex(int index) {
        this.maskOrderIndex = Math.floorMod(index, ORDERS.size());
    }

    public List<DeathItem> getMaskOrder() {
        return ORDERS.get(maskOrderIndex);
    }

    public static String orderLabel(List<DeathItem> order) {
        StringBuilder sb = new StringBuilder();
        for (DeathItem item : order) {
            sb.append(sb.length() == 0 ? "" : " > ").append(item.label);
        }
        return sb.toString();
    }
}
