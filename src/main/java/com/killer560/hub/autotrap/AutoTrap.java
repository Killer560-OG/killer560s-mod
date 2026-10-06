package com.killer560.hub.autotrap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.autoroutes.AutoRoutesFeature;
import com.killer560.hub.autoroutes.Route;
import com.killer560.hub.autoroutes.RouteExecutor;
import com.killer560.hub.autoroutes.RouteNode;
import com.killer560.hub.autoroutes.RouteStore;
import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.livemap.autoclear.AutoClearUtils;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Auto Trap - CHEAT BUILD ONLY, off by default (killer560, 2026-10-06: "make an auto trap tab. I will make an auto route
 * for both traps that you will put in it. Then it will use auto trap to do trap rooms").
 *
 * <p>Each trap room ({@link #TRAPS}, the room database's two TRAP rooms) has two route slots, {@link Mode#FULL} ("Full
 * Trap": the whole trap) and {@link Mode#CLEARED} ("Just Cleared": only what gets the room cleared), and a selected
 * mode. When on, Auto Trap hands the trap route to Auto Routes for a trap room he has no Auto Routes route of his own for
 * ({@link RouteStore#setOverlay}), so Auto Routes plays it like his own:
 * <ul>
 *   <li>its START node is the entry - "I will add a start node of some sort, whether it is walk or pearl ... The pathfinder
 *       will be able to etherwarp to that node to start the room": the Interactive Map's one etherwarp, from outside, onto
 *       the start node, is the only warp into the room;</li>
 *   <li>no etherwarp inside - a route with an ETHERWARP or PATH node is refused, captured or bundled (no ability works in
 *       a trap room, docs/SIM.md; the Interactive Map also never paths or hops from inside one);</li>
 *   <li>his route takes him out ("I will have mine pathfind out of the room once it finishes"), and THE MOMENT he is no
 *       longer in the trap room every bit of trap programming ends ({@link #tick}): a trap route still playing is stopped,
 *       the run's mode choice is forgotten, and Auto Routes' latches are cleared so whatever plans next starts clean.</li>
 * </ul>
 *
 * <p>Where the routes come from, first wins: his captures ({@code config/killer560/dungeons/autotrap/killer560smod-autotrap.json};
 * the tab's Capture copies his Auto Routes route for that room into the selected mode's slot), then the BUNDLED defaults in
 * the jar at {@value #BUNDLED} ({@code src/main/resources/assets/killer560smod/autotrap/routes.json}, shaped
 * {@code {"routes": {"Old Trap": {"FULL": <route>, "CLEARED": <route>}}}} with each route in the routes file's own
 * format) - none yet; his recordings go there once made.
 */
public final class AutoTrap {

    private static final Logger LOGGER = ModLog.get("killer560smod-autotrap");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path FILE = ModPaths.config("killer560smod-autotrap.json");
    public static final String BUNDLED = "/assets/killer560smod/autotrap/routes.json";

    /** The room database's TRAP rooms. */
    public static final List<String> TRAPS = List.of("New Trap", "Old Trap");

    public enum Mode {
        FULL("Full Trap"), CLEARED("Just Cleared");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** A trap route and where it came from. */
    public record Entry(Route route, long savedMs, String source) {
    }

    private static boolean enabled = false;
    /** Room -> mode -> his capture. */
    private static final Map<String, Map<Mode, Entry>> captured = new LinkedHashMap<>();
    private static final Map<String, Map<Mode, Entry>> bundled = new LinkedHashMap<>();
    private static final Map<String, Mode> selected = new HashMap<>();
    /** Dungeon Autopilot's choice for this run (Solo picks per score), cleared when he leaves the room. */
    private static final Map<String, Mode> runChoice = new HashMap<>();
    private static boolean loaded;
    /** The trap room he stood in last tick, or null. */
    private static String inTrap;

    private AutoTrap() {
    }

    public static void register() {
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return;
        }
        load();
        RouteStore.setOverlay(name -> isEnabled() ? routeFor(name) : null);
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("AutoTrap.tick", AutoTrap::tick));
    }

    // ------------------------------------------------------------------------------------------- state

    public static boolean isEnabled() {
        ensureLoaded();
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && enabled
                && com.killer560.hub.util.SkyblockGate.allows();
    }

    public static boolean isEnabledRaw() {
        ensureLoaded();
        return enabled;
    }

    public static void setEnabled(boolean v) {
        ensureLoaded();
        enabled = v;
        save();
    }

    public static Mode selectedMode(String room) {
        ensureLoaded();
        return selected.getOrDefault(room, Mode.FULL);
    }

    public static void setSelectedMode(String room, Mode mode) {
        ensureLoaded();
        selected.put(room, mode == null ? Mode.FULL : mode);
        save();
    }

    /** Dungeon Autopilot's mode for this trap this time (null = his selected one). Forgotten when he leaves the room. */
    public static void chooseForRun(String room, Mode mode) {
        if (mode == null) {
            runChoice.remove(room);
        } else {
            runChoice.put(room, mode);
        }
    }

    /** The mode that plays now: the run's choice, else the selected one. */
    public static Mode modeNow(String room) {
        Mode m = runChoice.get(room);
        return m != null ? m : selectedMode(room);
    }

    public static Entry entry(String room, Mode mode) {
        ensureLoaded();
        if (room == null || mode == null) {
            return null;
        }
        Map<Mode, Entry> c = captured.get(room);
        Entry e = c == null ? null : c.get(mode);
        if (e == null) {
            Map<Mode, Entry> b = bundled.get(room);
            e = b == null ? null : b.get(mode);
        }
        return e;
    }

    /** The trap route that plays for this room now (its current mode), or null. */
    public static Route routeFor(String room) {
        Entry e = entry(room, modeNow(room));
        return e == null ? null : e.route();
    }

    public static boolean hasRoute(String room, Mode mode) {
        return entry(room, mode) != null;
    }

    /** Auto Trap is on and has a route for this trap room in its current mode: Dungeon Autopilot may take it. */
    public static boolean usable(String room) {
        return isEnabled() && routeFor(room) != null;
    }

    // ------------------------------------------------------------------------------------------- leaving the room

    /**
     * Every tick: the moment he is no longer in the trap room he was in, all trap programming ends - a trap route of
     * Auto Trap's still playing is stopped, the run's mode choice is dropped, and Auto Routes' "just finished" / "you
     * stopped it" latches are cleared, so the next thing (Dungeon Autopilot's next decision) plans from where he is.
     */
    private static void tick(Minecraft client) {
        if (client.player == null || client.level == null) {
            inTrap = null;
            return;
        }
        DungeonLayout layout = DungeonLayout.current();
        int room = layout.roomAtWorld(client.player.getX(), client.player.getZ());
        String now = room >= 0 && AutoClearUtils.isTrap(layout, room) ? layout.name(room) : null;
        if (inTrap != null && !inTrap.equals(now)) {
            String left = inTrap;
            // His own trap route (Auto Routes) or Auto Trap's - either way the trap room's programming ends here.
            boolean ours = left.equals(RouteExecutor.runningRoom());
            if (ours) {
                RouteExecutor.stop("left the trap room");
            }
            runChoice.remove(left);
            AutoRoutesFeature.cancelForInteractiveMap("Auto Trap");
            LOGGER.info("[AutoTrap] left {} - trap mode over{}", left, ours ? " (its route stopped)" : "");
            if (ours && isEnabled()) {
                ModChat.send("Auto Trap", ModChat.dim("Left " + left + " - trap done"));
            }
        }
        inTrap = now;
    }

    /** The trap room he stands in now, by the map, or null. */
    public static String inTrapRoom() {
        return inTrap;
    }

    // ------------------------------------------------------------------------------------------- capture / clear

    /** Copies his Auto Routes route for {@code room} into the {@code mode} slot and saves it. @return what happened. */
    public static String capture(String room, Mode mode) {
        ensureLoaded();
        Route own = RouteStore.getInstance().ownRoute(room);
        if (own == null || own.isEmpty()) {
            return "No Auto Routes route recorded for " + room;
        }
        Route copy = RouteStore.routeFromJson(room, RouteStore.routeToJson(own));
        if (copy == null) {
            return "Could not copy the route for " + room;
        }
        String bad = warpNode(copy);
        if (bad != null) {
            return "Not captured: " + bad + " - no etherwarp works inside a trap room";
        }
        if (copy.startNode() == null) {
            return "Not captured: no start node - it is where the one warp into the room lands";
        }
        captured.computeIfAbsent(room, k -> new LinkedHashMap<>()).put(mode,
                new Entry(copy, System.currentTimeMillis(), "captured"));
        save();
        LOGGER.info("[AutoTrap] captured {} {} ({} nodes)", room, mode, copy.nodes().size());
        return "Captured " + room + " " + mode.label() + " (" + copy.nodes().size() + " nodes)";
    }

    public static String clear(String room, Mode mode) {
        ensureLoaded();
        Map<Mode, Entry> c = captured.get(room);
        boolean had = c != null && c.remove(mode) != null;
        save();
        return had ? "Cleared " + room + " " + mode.label() : "Nothing captured for " + room + " " + mode.label();
    }

    /** The first node that would warp inside the room (ETHERWARP, PATH), described, or null. */
    static String warpNode(Route route) {
        List<RouteNode> nodes = route.nodes();
        for (int i = 0; i < nodes.size(); i++) {
            RouteNode.Type t = nodes.get(i).type();
            if (t == RouteNode.Type.ETHERWARP || t == RouteNode.Type.PATH) {
                return "node #" + (i + 1) + " is " + t;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------- files

    private static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    public static synchronized void load() {
        loaded = true;
        captured.clear();
        bundled.clear();
        selected.clear();
        enabled = false;
        try {
            if (Files.exists(FILE)) {
                JsonObject root = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject();
                enabled = ConfigJson.getBool(root, "enabled", false);
                JsonObject modes = ConfigJson.getObject(root, "modes");
                if (modes != null) {
                    for (String room : modes.keySet()) {
                        selected.put(room, parseMode(ConfigJson.getString(modes, room, "FULL")));
                    }
                }
                readInto(captured, ConfigJson.getObject(root, "routes"), true);
            }
        } catch (Exception e) {
            LOGGER.warn("[AutoTrap] could not read {}: {}", FILE, e.toString());
        }
        try (InputStream in = AutoTrap.class.getResourceAsStream(BUNDLED)) {
            if (in != null) {
                JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                readInto(bundled, ConfigJson.getObject(root, "routes"), false);
            }
        } catch (Exception e) {
            LOGGER.warn("[AutoTrap] could not read the bundled routes: {}", e.toString());
        }
    }

    /** {@code {room: {MODE: <entry or route>}}}; a captured entry is {@code {"savedMs": n, "route": {...}}}. */
    private static void readInto(Map<String, Map<Mode, Entry>> into, JsonObject routes, boolean entries) {
        if (routes == null) {
            return;
        }
        for (String room : routes.keySet()) {
            JsonObject byMode = ConfigJson.getObject(routes, room);
            if (byMode == null) {
                continue;
            }
            for (String m : byMode.keySet()) {
                JsonObject o = ConfigJson.getObject(byMode, m);
                JsonObject r = o == null ? null : entries ? ConfigJson.getObject(o, "route") : o;
                Route route = r == null ? null : RouteStore.routeFromJson(room, r);
                String bad = route == null ? "unreadable" : warpNode(route);
                if (bad != null) {
                    LOGGER.warn("[AutoTrap] {} route for {} {} refused: {}", entries ? "saved" : "bundled", room, m, bad);
                    continue;
                }
                long saved = entries && o.has("savedMs") ? o.get("savedMs").getAsLong() : 0L;
                into.computeIfAbsent(room, k -> new LinkedHashMap<>()).put(parseMode(m),
                        new Entry(route, saved, entries ? "captured" : "bundled"));
            }
        }
    }

    private static Mode parseMode(String s) {
        try {
            return Mode.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return Mode.FULL;
        }
    }

    private static synchronized void save() {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("enabled", enabled);
            JsonObject modes = new JsonObject();
            selected.forEach((room, m) -> modes.addProperty(room, m.name()));
            root.add("modes", modes);
            JsonObject routes = new JsonObject();
            for (Map.Entry<String, Map<Mode, Entry>> e : captured.entrySet()) {
                JsonObject byMode = new JsonObject();
                for (Map.Entry<Mode, Entry> me : e.getValue().entrySet()) {
                    JsonObject o = new JsonObject();
                    o.addProperty("savedMs", me.getValue().savedMs());
                    o.add("route", RouteStore.routeToJson(me.getValue().route()));
                    byMode.add(me.getKey().name(), o);
                }
                routes.add(e.getKey(), byMode);
            }
            root.add("routes", routes);
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[AutoTrap] could not save {}: {}", FILE, e.toString());
        }
    }

    /** Where his captures are saved. */
    public static Path file() {
        return FILE;
    }
}
