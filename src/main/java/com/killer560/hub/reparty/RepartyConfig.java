package com.killer560.hub.reparty;

import com.google.gson.JsonObject;
import com.killer560.hub.util.BoolFields;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;
import com.killer560.hub.util.SkyblockGate;

import java.nio.file.Path;

/**
 * Reparty settings, {@code killer560smod-reparty.json}. The switch and Auto Rejoin ship OFF. The old Auto Reparty keys
 * ("auto", "delaySeconds") are no longer read or written (redesign reparty2-1109).
 */
public final class RepartyConfig {

    public static final double MIN_DELAY = 0.0, MAX_DELAY = 10.0, DEFAULT_DELAY = 1.0;
    public static final int MIN_WINDOW = 10, MAX_WINDOW = 120, DEFAULT_WINDOW = 60;

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-reparty.json");
    private static RepartyConfig instance;

    private boolean enabled = false;
    private boolean autoRejoin = false;
    private double inviteDelay = DEFAULT_DELAY;
    private int rejoinWindow = DEFAULT_WINDOW;

    private static final BoolFields<RepartyConfig> BOOLS = new BoolFields<RepartyConfig>()
            .add("enabled", false, c -> c.enabled, (c, v) -> c.enabled = v)
            .add("autoRejoin", false, c -> c.autoRejoin, (c, v) -> c.autoRejoin = v);

    private RepartyConfig() {
    }

    public static RepartyConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        RepartyConfig cfg = new RepartyConfig();
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        if (obj != null) {
            BOOLS.load(cfg, obj);
            cfg.setInviteDelay(obj.has("inviteDelay") ? obj.get("inviteDelay").getAsDouble() : DEFAULT_DELAY);
            cfg.setRejoinWindow(ConfigJson.getInt(obj, "rejoinWindow", DEFAULT_WINDOW));
        }
        instance = cfg;
    }

    public void save() {
        JsonObject obj = new JsonObject();
        BOOLS.save(this, obj);
        obj.addProperty("inviteDelay", inviteDelay);
        obj.addProperty("rejoinWindow", rejoinWindow);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    public boolean isEnabled() { return enabled && SkyblockGate.allows(); }

    public boolean getEnabledRaw() { return enabled; }

    public void setEnabled(boolean v) { enabled = v; }

    public boolean isAutoRejoin() { return autoRejoin; }

    public void setAutoRejoin(boolean v) { autoRejoin = v; }

    /** Seconds between disbanding and re-inviting. */
    public double getInviteDelay() { return inviteDelay; }

    /** Clamped to 0..10 and snapped to half seconds. */
    public void setInviteDelay(double v) {
        inviteDelay = Math.round(Math.max(MIN_DELAY, Math.min(MAX_DELAY, v)) * 2) / 2.0;
    }

    /** Seconds after a disband in which an invite from a former member is accepted. */
    public int getRejoinWindow() { return rejoinWindow; }

    public void setRejoinWindow(int v) { rejoinWindow = Math.max(MIN_WINDOW, Math.min(MAX_WINDOW, v)); }
}
