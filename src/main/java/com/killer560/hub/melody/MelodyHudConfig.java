package com.killer560.hub.melody;

import com.killer560.hub.util.ModPaths;
import com.google.gson.JsonObject;
import com.killer560.hub.partydata.PartyDataConfig;
import com.killer560.hub.util.ConfigJson;

import java.nio.file.Path;

/**
 * Persisted Team Melody settings - see {@link MelodyTrackerFeature}. Two independent switches:
 * {@link #isHudEnabled()} (the HUD element itself) and {@link #isShareProgress()} (whether OUR read-only
 * tracker's own progress gets published at all, to the relay and/or the reference).
 * <p>
 * <b>Defaults.</b> The HUD ships OFF like every other new feature in this mod (killer560's "New tab until
 * confirmed" rule) - a player has to opt in even though the underlying read-only tracker itself always runs
 * (it is harmless, never clicks, and other features may want its data). "Share My Progress" instead follows
 * {@link PartyDataConfig}'s own precedent (2026-09-21, "Share Dungeon Data With Party"): sharing dungeon
 * facts we already worked out is only useful if the party-facing half is already on, so this defaults to
 * whatever {@link PartyDataConfig#isShareEnabled()} was the first time this config file is created - computed
 * once, never re-derived later (flipping the other setting off after that is the player's own later choice).
 */
public final class MelodyHudConfig {

    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-teammelody.json");

    private static volatile MelodyHudConfig instance;
    /** Testkit: the thread that last read the file. */
    public static volatile String loadThreadForTest;

    private boolean hudEnabled = false;
    private boolean shareProgress;

    private MelodyHudConfig() {
    }

    /**
     * The settings. The file is read once, off the render thread (started at client start by {@link #loadInBackground()});
     * until that read lands, the render thread gets plain defaults and no disk (HUD and tick code call this every tick).
     * Any other thread loads it itself.
     */
    public static MelodyHudConfig getInstance() {
        MelodyHudConfig c = instance;
        if (c != null) {
            return c;
        }
        if (net.minecraft.client.Minecraft.getInstance().isSameThread()) {
            return new MelodyHudConfig();
        }
        return ensureLoaded();
    }

    /** Reads the file now when nothing has yet (only a caller that must not see defaults, off the frame path). */
    public static synchronized MelodyHudConfig ensureLoaded() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void loadInBackground() {
        Thread t = new Thread(MelodyHudConfig::ensureLoaded, "killer560smod-melody-config");
        t.setDaemon(true);
        t.start();
    }

    public static void load() {
        loadThreadForTest = Thread.currentThread().getName();
        // Sharing is OFF by default (killer560, 2026-11-04: Team Melody off), independent of any other setting.
        MelodyHudConfig cfg = new MelodyHudConfig();
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        if (obj != null) {
            cfg.hudEnabled = ConfigJson.getBool(obj, "hudEnabled", false);
            cfg.shareProgress = ConfigJson.getBool(obj, "shareProgress", false);
        }
        instance = cfg;
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("hudEnabled", hudEnabled);
        obj.addProperty("shareProgress", shareProgress);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    public boolean isHudEnabled() { return hudEnabled && com.killer560.hub.util.SkyblockGate.allows(); }

    public boolean isHudEnabledRaw() { return hudEnabled; }

    public void setHudEnabled(boolean hudEnabled) { this.hudEnabled = hudEnabled; }

    /** Publish OUR own read-only tracker's progress (relay + the reference, when its own toggle/connection allow it).
     *  Never affects what we RECEIVE from teammates - that's always shown once known. */
    public boolean isShareProgress() { return shareProgress; }

    public void setShareProgress(boolean shareProgress) { this.shareProgress = shareProgress; }
}
