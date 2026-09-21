package com.killer560.hub.social;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted Party Time Tracker ("Best Friends" / {@code /bestfriends}) SETTINGS - the toggle plus the menu's
 * own sort/filter prefs. Ships disabled by default (2026-09-21 rule: any new feature starts off, lives in
 * the New tab until confirmed working), same load/save shape as {@code teammates.TeammatesConfig}.
 * <p>
 * The actual accumulated per-player time/run data is NOT in here - it lives in {@link BestFriendsStore},
 * its own file under {@code config/killer560smod-social/}, for the same reason {@code runsummary.RunHistoryStore}
 * keeps run history out of a plain {@code killer560smod-*.json}: {@code profiles.ProfileManager} snapshots,
 * applies and overwrites every {@code killer560smod-*.json} setting file, and real accumulated data (that
 * killer560 explicitly wants kept "forever") must never be silently replaced by switching profiles.
 */
public final class BestFriendsConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-bestfriends.json");

    public enum SortMode {
        TIME("Time Together"), RUNS("Dungeon Runs"), NAME("Name");

        public final String label;

        SortMode(String label) {
            this.label = label;
        }

        public SortMode next() {
            SortMode[] all = values();
            return all[(ordinal() + 1) % all.length];
        }
    }

    private static BestFriendsConfig instance;

    private boolean enabled = false;
    private SortMode sortMode = SortMode.TIME;
    /** Menu filter: true = only show players with at least one dungeon run together. */
    private boolean dungeonOnlyFilter = false;

    private BestFriendsConfig() {
    }

    public static BestFriendsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new BestFriendsConfig();
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            BestFriendsConfig cfg = new BestFriendsConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.sortMode = ConfigJson.getEnum(obj, "sortMode", SortMode.class, SortMode.TIME);
            cfg.dungeonOnlyFilter = ConfigJson.getBool(obj, "dungeonOnlyFilter", false);
            instance = cfg;
        } catch (Exception e) {
            instance = new BestFriendsConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("sortMode", sortMode.name());
            obj.addProperty("dungeonOnlyFilter", dungeonOnlyFilter);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** Gated on Skyblock like every other feature, and this is also the master switch for whether
     *  {@link BestFriendsTracker} accrues anything at all - see that class's doc comment for why. */
    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    /** Ungated stored value - for the tab's own toggle label and for gating the tracker (which must keep
     *  working even outside Skyblock's own gate the instant killer560 turns the setting on in the hub). */
    public boolean getEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public SortMode getSortMode() {
        return sortMode;
    }

    public void setSortMode(SortMode sortMode) {
        this.sortMode = sortMode;
    }

    public boolean isDungeonOnlyFilter() {
        return dungeonOnlyFilter;
    }

    public void setDungeonOnlyFilter(boolean dungeonOnlyFilter) {
        this.dungeonOnlyFilter = dungeonOnlyFilter;
    }
}
