package com.killer560.hub.zpew;

import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.file.Path;
import java.util.EnumMap;

/**
 * Persisted Etherwarp settings - Zero Ping Etherwarp ({@link ZeroPingEtherwarp}) and Multi Warps. Off by default.
 *
 * <p>Since zpewunify-1111 (killer560, 2026-11-11: "remove the zero ping stuff from auto routes ... have it all come from
 * zpew") this is also where Auto Routes' old Zero Ping Chain and Charged Chain live: Zero Ping Etherwarp working for Auto
 * Routes is the zero-ping chain, Multi Warps working for Auto Routes (or the Interactive Map) is the Charged Chain.
 * Both lists have the same four sources ({@link ZpewSource.Group}); Multi Warps never needs Zero Ping Etherwarp's switch.
 */
public final class ZeroPingEtherwarpConfig {

    public static final int MIN_TIMEOUT_MS = 250;
    public static final int MAX_TIMEOUT_MS = 3000;
    /** Warps Per Tick: 1 = off, up to this many etherwarps sent in one client tick per use (20 tested clean, 1027/1111). */
    public static final int MAX_WARPS_PER_TICK = 20;

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-zpew.json");

    private static ZeroPingEtherwarpConfig instance;

    private boolean enabled = false;
    /** How long a predicted landing waits for the server's teleport before the camera goes back. */
    private int timeoutMs = 1000;
    /** A warp sent while another is still pending is predicted from that one's landing (chained warps). */
    private boolean chain = true;
    /** The Etherwarp Overlay highlights from the predicted landing while a warp is pending. */
    private boolean overlayFromPrediction = true;
    /** Warps Per Tick: each of his own etherwarps becomes this many in the same tick, at the same look. 1 = off. */
    private int warpsPerTick = 1;
    /** Aimed Warps: several clicks of his inside one tick, each at its own look, go out in that tick (Blink Charge bank). */
    private boolean aimedWarps = false;
    /** Multi Warps (killer560 2026-11-01 "Make a toggle for the multi warps"): the switch over everything below it. */
    private boolean multiWarps = false;
    /** Dungeons Only (killer560, 2026-11-01): ZPEW predicts only inside a dungeon (the sim counts). Off by default. */
    private boolean dungeonsOnly = false;
    /** Works For: which sources' etherwarps Zero Ping Etherwarp predicts. All on by default. */
    private final EnumMap<ZpewSource.Group, Boolean> worksFor = all(true);
    /** Multi Warps For: which sources Multi Warps acts for (Manual: Warps Per Tick and Aimed Warps; Auto Routes and
     *  Interactive Map: a chain of warps sent in one tick from Blink Charge's bank). All on by default. */
    private final EnumMap<ZpewSource.Group, Boolean> multiWarpsFor = all(true);

    private ZeroPingEtherwarpConfig() {
    }

    private static EnumMap<ZpewSource.Group, Boolean> all(boolean on) {
        EnumMap<ZpewSource.Group, Boolean> m = new EnumMap<>(ZpewSource.Group.class);
        for (ZpewSource.Group g : ZpewSource.Group.values()) {
            m.put(g, on);
        }
        return m;
    }

    public static ZeroPingEtherwarpConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        ZeroPingEtherwarpConfig cfg = new ZeroPingEtherwarpConfig();
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        if (obj != null) {
            cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
            cfg.setTimeoutMs(ConfigJson.getInt(obj, "timeoutMs", cfg.timeoutMs));
            cfg.chain = ConfigJson.getBool(obj, "chain", cfg.chain);
            cfg.overlayFromPrediction = ConfigJson.getBool(obj, "overlayFromPrediction", cfg.overlayFromPrediction);
            cfg.setWarpsPerTick(ConfigJson.getInt(obj, "warpsPerTick", cfg.warpsPerTick));
            cfg.aimedWarps = ConfigJson.getBool(obj, "aimedWarps", cfg.aimedWarps);
            cfg.multiWarps = ConfigJson.getBool(obj, "multiWarps", cfg.warpsPerTick > 1 || cfg.aimedWarps);
            cfg.dungeonsOnly = ConfigJson.getBool(obj, "dungeonsOnly", cfg.dungeonsOnly);
            readGroups(ConfigJson.getObject(obj, "worksFor"), cfg.worksFor);
            JsonObject mw = ConfigJson.getObject(obj, "multiWarpsFor");
            if (mw != null) {
                readGroups(mw, cfg.multiWarpsFor);
            } else {
                // A file from before the list (zpewunify-1111): Multi Warps acted for his own warps only (Auto Routes' Charged
                // Chain was its own switch, carried over by migrateFromRoutes), whatever Works For said.
                cfg.multiWarpsFor.put(ZpewSource.Group.AUTO_ROUTES, false);
                cfg.multiWarpsFor.put(ZpewSource.Group.INTERACTIVE_MAP, false);
            }
        }
        instance = cfg;
    }

    /**
     * Reads a list saved by group name. A file from before zpewunify-1111 holds one switch per sender (LEFT_CLICK,
     * AUTO_SECRET, AP3 ...): a group is ON when any of its senders found in the file was ON, and stays ON with none found.
     */
    private static void readGroups(JsonObject wf, EnumMap<ZpewSource.Group, Boolean> into) {
        if (wf == null) return;
        EnumMap<ZpewSource.Group, Boolean> seen = new EnumMap<>(ZpewSource.Group.class);
        for (ZpewSource s : ZpewSource.values()) {
            if (s.group() != null && wf.has(s.name())) {
                seen.merge(s.group(), ConfigJson.getBool(wf, s.name(), true), Boolean::logicalOr);
            }
        }
        for (ZpewSource.Group g : ZpewSource.Group.values()) {
            if (wf.has(g.name())) {
                seen.merge(g, ConfigJson.getBool(wf, g.name(), true), Boolean::logicalOr);
            }
            into.put(g, seen.getOrDefault(g, true));
        }
    }

    /**
     * Auto Routes' Zero Ping Chain and Charged Chain switches, read from an old Auto Routes file (zpewunify-1111), carried
     * over so what he had on keeps working: Zero Ping Chain becomes Zero Ping Etherwarp for Auto Routes (switching ZPEW on
     * for Auto Routes alone when it was off), Charged Chain becomes Multi Warps for Auto Routes and the Interactive Map
     * (switching Multi Warps on for those alone when it was off). Saves.
     */
    public void migrateFromRoutes(boolean zeroPingChain, boolean chargedChain) {
        if (zeroPingChain || chargedChain) {
            if (!enabled) {
                enabled = true;
                worksFor.replaceAll((g, on) -> false);
            }
            worksFor.put(ZpewSource.Group.AUTO_ROUTES, true);
        } else {
            worksFor.put(ZpewSource.Group.AUTO_ROUTES, false);
        }
        if (chargedChain) {
            if (!multiWarps) {
                multiWarps = true;
                multiWarpsFor.replaceAll((g, on) -> false);
            }
            multiWarpsFor.put(ZpewSource.Group.AUTO_ROUTES, true);
            multiWarpsFor.put(ZpewSource.Group.INTERACTIVE_MAP, true);
        }
        save();
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        obj.addProperty("timeoutMs", timeoutMs);
        obj.addProperty("chain", chain);
        obj.addProperty("overlayFromPrediction", overlayFromPrediction);
        obj.addProperty("warpsPerTick", warpsPerTick);
        obj.addProperty("aimedWarps", aimedWarps);
        obj.addProperty("multiWarps", multiWarps);
        obj.addProperty("dungeonsOnly", dungeonsOnly);
        obj.add("worksFor", write(worksFor));
        obj.add("multiWarpsFor", write(multiWarpsFor));
        ConfigJson.write(CONFIG_PATH, obj);
    }

    private static JsonObject write(EnumMap<ZpewSource.Group, Boolean> m) {
        JsonObject o = new JsonObject();
        m.forEach((g, on) -> o.addProperty(g.name(), on));
        return o;
    }

    public boolean isEnabled() { return enabled; }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getTimeoutMs() { return timeoutMs; }

    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = Math.max(MIN_TIMEOUT_MS, Math.min(MAX_TIMEOUT_MS, timeoutMs)); }

    public boolean isChain() { return chain; }

    public void setChain(boolean chain) { this.chain = chain; }

    public boolean isOverlayFromPrediction() { return overlayFromPrediction; }

    public void setOverlayFromPrediction(boolean overlayFromPrediction) { this.overlayFromPrediction = overlayFromPrediction; }

    /** The saved Warps Per Tick (1..{@link #MAX_WARPS_PER_TICK}), whatever the build (the tab and the testkit read this). */
    public int getWarpsPerTick() { return warpsPerTick; }

    public void setWarpsPerTick(int warpsPerTick) { this.warpsPerTick = Math.max(1, Math.min(MAX_WARPS_PER_TICK, warpsPerTick)); }

    /** The saved Aimed Warps switch, whatever the build. */
    public boolean isAimedWarps() { return aimedWarps; }

    public void setAimedWarps(boolean aimedWarps) { this.aimedWarps = aimedWarps; }

    /** The saved Multi Warps switch, whatever the build. */
    public boolean isMultiWarps() { return multiWarps; }

    public void setMultiWarps(boolean multiWarps) { this.multiWarps = multiWarps; }

    public boolean isDungeonsOnly() { return dungeonsOnly; }

    public void setDungeonsOnly(boolean dungeonsOnly) { this.dungeonsOnly = dungeonsOnly; }

    /** ZPEW's switch, and Dungeons Only: whether it predicts where the player is now. */
    public boolean isActiveHere() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && enabled && (!dungeonsOnly || com.killer560.hub.secrets.DungeonState.isInDungeon());
    }

    /** Aimed Warps as it applies: never in a legit build, never with Multi Warps off or not working for Manual. */
    public boolean effectiveAimedWarps() {
        return multiWarpsFor(ZpewSource.MANUAL) && aimedWarps;
    }

    /** Warps Per Tick as it applies: 1 in a legit build or with Multi Warps off (its source is always Manual). */
    public int effectiveWarpsPerTick() {
        return multiWarpsFor(ZpewSource.MANUAL) ? warpsPerTick : 1;
    }

    /** Whether ZPEW predicts this sender's etherwarps: cheat build, its group's Works For switch (none for AP3). */
    public boolean worksFor(ZpewSource source) {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && source.group() != null && worksFor.get(source.group());
    }

    /** Whether Multi Warps acts for this sender: cheat build, the Multi Warps switch, its group's switch (none for AP3). Never
     *  depends on Zero Ping Etherwarp's own switch. */
    public boolean multiWarpsFor(ZpewSource source) {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && multiWarps && source.group() != null
                && multiWarpsFor.get(source.group());
    }

    /** The saved switches, whatever the build (the tab and the testkit read these). */
    public boolean getWorksForSetting(ZpewSource.Group g) { return worksFor.get(g); }

    public void setWorksFor(ZpewSource.Group g, boolean on) { worksFor.put(g, on); }

    public boolean getMultiWarpsForSetting(ZpewSource.Group g) { return multiWarpsFor.get(g); }

    public void setMultiWarpsFor(ZpewSource.Group g, boolean on) { multiWarpsFor.put(g, on); }

    /** By name (a group, or an old per-sender name meaning its group), for the testkit's reflection. AP3 has no switch:
     *  it reads false and setting it does nothing. */
    public boolean getWorksForByName(String name) { ZpewSource.Group g = group(name); return g != null && worksFor.get(g); }

    public void setWorksForByName(String name, boolean on) { ZpewSource.Group g = group(name); if (g != null) worksFor.put(g, on); }

    public boolean getMultiWarpsForByName(String name) { ZpewSource.Group g = group(name); return g != null && multiWarpsFor.get(g); }

    public void setMultiWarpsForByName(String name, boolean on) { ZpewSource.Group g = group(name); if (g != null) multiWarpsFor.put(g, on); }

    private static ZpewSource.Group group(String name) {
        for (ZpewSource.Group g : ZpewSource.Group.values()) {
            if (g.name().equals(name)) return g;
        }
        return ZpewSource.valueOf(name).group();
    }
}
