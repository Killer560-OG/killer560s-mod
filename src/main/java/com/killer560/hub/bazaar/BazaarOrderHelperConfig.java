package com.killer560.hub.bazaar;

import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.file.Path;

/**
 * Bazaar Order Helper settings ({@link BazaarOrderHelper}): the switch (ON, as SkyHanni ships its Order Helper), Highlight
 * Outbid, Highlight Filled, and the two colors (ARGB, the alpha is how strong the slot fill is; the outline is drawn
 * opaque).
 */
public final class BazaarOrderHelperConfig {

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-bazaarorderhelper.json");

    /** Outbid / undercut: yellow (killer560, 2026-11-03: "yellow if they are undercut and red if they expired"). */
    public static final int DEFAULT_OUTBID_COLOR = 0x80FFFF55;
    /** The red outbid default before 2026-11-03; a saved file still holding it moves to the new yellow. */
    static final int OLD_OUTBID_COLOR = 0x80FF5555;
    /** Expired: red. */
    public static final int DEFAULT_EXPIRED_COLOR = 0x80FF5555;
    /** Filled or ready to claim: green, as SkyHanni and the Bazaar screen. */
    public static final int DEFAULT_FILLED_COLOR = 0x8055FF55;

    private static BazaarOrderHelperConfig instance;

    private boolean enabled = false;
    private boolean highlightOutbid = true;
    private boolean highlightFilled = true;
    private int outbidColor = DEFAULT_OUTBID_COLOR;
    private int filledColor = DEFAULT_FILLED_COLOR;
    private boolean highlightExpired = true;
    private boolean highlightAuctions = true;
    private int expiredColor = DEFAULT_EXPIRED_COLOR;

    private BazaarOrderHelperConfig() {
    }

    public static synchronized BazaarOrderHelperConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static synchronized void load() {
        BazaarOrderHelperConfig cfg = new BazaarOrderHelperConfig();
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        if (obj != null) {
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.highlightOutbid = ConfigJson.getBool(obj, "highlightOutbid", true);
            cfg.highlightFilled = ConfigJson.getBool(obj, "highlightFilled", true);
            cfg.outbidColor = ConfigJson.getInt(obj, "outbidColor", DEFAULT_OUTBID_COLOR);
            if (cfg.outbidColor == OLD_OUTBID_COLOR && !obj.has("expiredColor")) {
                cfg.outbidColor = DEFAULT_OUTBID_COLOR; // the old red default moves to yellow (2026-11-03)
            }
            cfg.filledColor = ConfigJson.getInt(obj, "filledColor", DEFAULT_FILLED_COLOR);
            cfg.highlightExpired = ConfigJson.getBool(obj, "highlightExpired", true);
            cfg.expiredColor = ConfigJson.getInt(obj, "expiredColor", DEFAULT_EXPIRED_COLOR);
            cfg.highlightAuctions = ConfigJson.getBool(obj, "highlightAuctions", true);
        }
        instance = cfg;
    }

    public synchronized void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        obj.addProperty("highlightOutbid", highlightOutbid);
        obj.addProperty("highlightFilled", highlightFilled);
        obj.addProperty("outbidColor", outbidColor);
        obj.addProperty("filledColor", filledColor);
        obj.addProperty("highlightExpired", highlightExpired);
        obj.addProperty("expiredColor", expiredColor);
        obj.addProperty("highlightAuctions", highlightAuctions);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    /** On and inside Skyblock (the Skyblock Only gate). */
    public synchronized boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public synchronized boolean isEnabledRaw() {
        return enabled;
    }

    public synchronized void setEnabled(boolean v) {
        enabled = v;
    }

    public synchronized boolean isHighlightOutbid() {
        return highlightOutbid;
    }

    public synchronized void setHighlightOutbid(boolean v) {
        highlightOutbid = v;
    }

    public synchronized boolean isHighlightFilled() {
        return highlightFilled;
    }

    public synchronized void setHighlightFilled(boolean v) {
        highlightFilled = v;
    }

    public synchronized int getOutbidColor() {
        return outbidColor;
    }

    public synchronized void setOutbidColor(int argb) {
        outbidColor = argb;
    }

    public synchronized int getFilledColor() {
        return filledColor;
    }

    public synchronized void setFilledColor(int argb) {
        filledColor = argb;
    }

    public synchronized boolean isHighlightExpired() {
        return highlightExpired;
    }

    public synchronized void setHighlightExpired(boolean v) {
        highlightExpired = v;
    }

    public synchronized boolean isHighlightAuctions() {
        return highlightAuctions;
    }

    public synchronized void setHighlightAuctions(boolean v) {
        highlightAuctions = v;
    }

    public synchronized int getExpiredColor() {
        return expiredColor;
    }

    public synchronized void setExpiredColor(int argb) {
        expiredColor = argb;
    }
}
