package com.killer560.hub.roomsim;

import com.killer560.hub.autoroutes.Route;
import com.killer560.hub.autoroutes.RouteStore;
import com.killer560.hub.roomdatabase.RoomDatabase;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModPaths;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Which sim rooms already have an Auto Route, for the room picker's Routes filter and the pause menu's
 * "Next room with no routes".
 *
 * <p>killer560 (2026-10-05): "I would like a way to sort maps based off of if they have secret routes in them or
 * not, and while in a solo map an option that says something like go to a new room with 0 routes in it [...]
 * that way someone configging has a really easy way of swapping maps without going out and scrolling through the
 * long list trying to find the one they are looking for. You do not need to have puzzles, blood, green, or fairy,
 * or any 0 secret rooms in this."
 *
 * <p>Two questions, each answered from the one place the rest of the mod answers it:
 * <ul>
 *   <li><b>Is the room worth routing?</b> The room database's own entry ({@link RoomDatabase#lookupByName}, the
 *       same source as {@link SimFloorGen#typeOf}): not PUZZLE, BLOOD, ENTRANCE or FAIRY, and {@code secrets > 0}.
 *       On his library that leaves 113 of 135 - every CHAMPION room is 0 secrets, so the miniboss rooms go too.
 *       A room the database does not know is not eligible: its type and secrets are unknown.</li>
 *   <li><b>Does it have a route?</b> {@link RouteStore#forRoom}, the store Auto Routes plays from, keyed by the
 *       room database name - which is also the library's name for it (all 135 match). Routes are stored
 *       room-relative and turned by the live rotation when played ({@code RouteCoords}), so one entry covers the
 *       room at every rotation; there is no per-rotation key to check. "Has a route" means at least one NODE: a
 *       recorded path with no nodes plays nothing.</li>
 * </ul>
 */
public final class SimRoomRoutes {

    /** The picker's Routes filter. */
    public enum Filter {
        ALL("Routes: All"), NONE("No routes"), HAS("Has routes");

        public final String label;

        Filter(String label) {
            this.label = label;
        }

        public Filter next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /** Remembered across restarts, like every other setting. One word in a text file, like the sim's speed. */
    private static final java.nio.file.Path FILE = ModPaths.config("killer560smod-sim-roompicker.txt");

    private static Filter filter;

    private SimRoomRoutes() {
    }

    // ------------------------------------------------------------------------------------------- setting

    public static synchronized Filter getFilter() {
        if (filter == null) {
            load();
        }
        return filter;
    }

    public static synchronized void setFilter(Filter value) {
        filter = value == null ? Filter.ALL : value;
        save();
    }

    public static synchronized void load() {
        filter = Filter.ALL;
        try {
            if (java.nio.file.Files.exists(FILE)) {
                String raw = java.nio.file.Files.readString(FILE, java.nio.charset.StandardCharsets.UTF_8).trim();
                for (Filter f : Filter.values()) {
                    if (f.name().equalsIgnoreCase(raw)) {
                        filter = f;
                    }
                }
            }
        } catch (Exception ignored) {
            // Unreadable means unknown, and showing every room is a safe unknown.
        }
    }

    public static synchronized void save() {
        try {
            java.nio.file.Files.createDirectories(FILE.getParent());
            java.nio.file.Files.writeString(FILE, (filter == null ? Filter.ALL : filter).name(),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // Losing the preference is not worth failing the click.
        }
    }

    // ------------------------------------------------------------------------------------------- questions

    /**
     * Whether a room belongs in the routes view at all: a non-puzzle, non-Blood, non-Entrance, non-Fairy room with
     * at least one secret, by the room database. False while the database is still loading.
     */
    public static boolean isEligible(String roomName) {
        RoomEntry entry = RoomDatabase.lookupByName(roomName);
        if (entry == null || entry.secrets <= 0) {
            return false;
        }
        String type = entry.type == null ? "" : entry.type.toUpperCase(Locale.ROOT);
        return switch (type) {
            case "PUZZLE", "BLOOD", "ENTRANCE", "FAIRY" -> false;
            default -> true;
        };
    }

    /** Auto Routes nodes saved for this room, 0 when it has none. */
    public static int routeNodes(String roomName) {
        Route route = RouteStore.getInstance().forRoom(roomName);
        return route == null ? 0 : route.nodes().size();
    }

    public static boolean matches(String roomName, Filter f) {
        return switch (f) {
            case ALL -> true;
            case NONE -> isEligible(roomName) && routeNodes(roomName) == 0;
            case HAS -> isEligible(roomName) && routeNodes(roomName) > 0;
        };
    }

    /**
     * The room loaded on its own right now, or null when what is loaded is a generated floor or nothing yet.
     */
    public static String currentSoloRoom() {
        if (SimState.isGeneratedFloor()) {
            return null;
        }
        List<SimRoomIndex.Placed> placed = SimRoomIndex.placed();
        return placed.size() == 1 ? placed.get(0).name() : null;
    }

    /**
     * Every eligible room without a route, in the picker's order (the library's, alphabetical), starting with the
     * first one AFTER {@code current} and wrapping round, {@code current} itself left out. Pressing "next" takes the
     * head, so pressing it again from there walks the list.
     */
    public static List<String> roomsWithoutRoutesAfter(String current) {
        List<String> names = RoomLibrary.names();
        int start = 0;
        if (current != null) {
            for (int i = 0; i < names.size(); i++) {
                if (names.get(i).equalsIgnoreCase(current)) {
                    start = i + 1;
                    break;
                }
            }
        }
        List<String> out = new ArrayList<>();
        for (int k = 0; k < names.size(); k++) {
            String name = names.get((start + k) % names.size());
            if (current != null && name.equalsIgnoreCase(current)) {
                continue;
            }
            if (matches(name, Filter.NONE)) {
                out.add(name);
            }
        }
        return out;
    }

    /**
     * "Next room with no routes": loads the next eligible room that has no Auto Routes nodes, after the one
     * standing. Says why in chat when it cannot.
     *
     * @return the room it started loading, or null when it loaded nothing
     */
    public static String loadNextWithoutRoutes(Minecraft client) {
        if (!SimState.canAct(client)) {
            ModChat.send("Sim", ModChat.text("Next room with no routes only works inside the dungeon sim."));
            return null;
        }
        if (!RoomDatabase.isReady()) {
            RoomDatabase.ensureLoading();
            ModChat.send("Sim", ModChat.text("The room database is still loading - try again in a moment."));
            return null;
        }
        String current = currentSoloRoom();
        List<String> left = roomsWithoutRoutesAfter(current);
        if (left.isEmpty()) {
            ModChat.send("Sim", ModChat.text(current == null
                    ? "Every eligible room already has Auto Routes."
                    : "No other eligible room is without Auto Routes - every one but this has a route."));
            return null;
        }
        String next = left.get(0);
        ModChat.send("Sim", ModChat.text("Next room with no routes: "), ModChat.value(next),
                ModChat.dim("  (" + left.size() + " without routes, excluding puzzles, Blood, Entrance, Fairy "
                        + "and 0-secret rooms)"));
        SimBuilder.buildSingleRoom(client, next);
        return next;
    }
}
