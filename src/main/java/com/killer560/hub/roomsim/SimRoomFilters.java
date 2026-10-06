package com.killer560.hub.roomsim;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.killer560.hub.roomdatabase.RoomDatabase;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;

import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * The map designer's room filters: which rooms its list shows, and which rooms Generate and Fill may use.
 *
 * <p>killer560 (2026-10-06): "in the generate a map thing have a filter section where i can filter based on things
 * like puzzles, room size, secrets in a room, etc."
 *
 * <p>One rule set, read in two places, so the list he looks at and the floor he gets cannot disagree:
 * <ul>
 *   <li>{@link #listMatches} - the room list (and so Fill, which draws from the list as shown);</li>
 *   <li>{@link #generatorAllows} - Generate. The same test, except that the four rooms every floor must have -
 *       Entrance, Blood, Fairy and the trap - are never filtered out of it, so a filter can narrow a floor but
 *       never make it invalid. A room he pinned on the grid is always kept as well (that is {@link SimFloorGen}'s
 *       job, since it knows the pins).</li>
 * </ul>
 *
 * <p>Puzzle rooms are chosen by the type toggle and the per-puzzle toggles ONLY. Size, secrets, crypts and Auto
 * Routes do not apply to them: every puzzle is a 1x1 with no secrets and no route, so applying "at least 3 secrets"
 * to them would silently zero the Puzzles slider.
 *
 * <p>Saved as JSON in the sim's config folder on every change, so they survive closing the screen and a restart.
 * Hidden things are stored rather than shown ones, so a type or puzzle captured later appears by default.
 */
public final class SimRoomFilters {

    private static final Logger LOGGER = ModLog.get("killer560smod-roomsim");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = ModPaths.config("killer560smod-sim-designer-filters.json");

    /** Every type the room database uses, in the order the filter shows them. */
    public static final List<String> TYPES = List.of(
            "NORMAL", "PUZZLE", "TRAP", "CHAMPION", "RARE", "FAIRY", "ENTRANCE", "BLOOD");

    /** The database's shape strings ({@link RoomDatabase#shapeTileCount}'s list). */
    public static final List<String> SHAPES = List.of("1x1", "1x2", "1x3", "1x4", "2x2", "L");

    /** The rooms a generated floor must have; Generate ignores the filters for these. */
    public static final Set<String> REQUIRED = Set.of("ENTRANCE", "BLOOD", "FAIRY", "TRAP");

    /** What is saved. "max -1" means no upper bound. */
    private static final class Data {
        Set<String> hiddenTypes = new TreeSet<>();
        Set<String> hiddenPuzzles = new TreeSet<>();
        Set<String> hiddenShapes = new TreeSet<>();
        int minSecrets = 0;
        int maxSecrets = -1;
        int minCrypts = 0;
        int maxCrypts = -1;
        String routes = SimRoomRoutes.Filter.ALL.name();
    }

    private static Data data;

    private SimRoomFilters() {
    }

    // ------------------------------------------------------------------------------------------- storage

    private static synchronized Data data() {
        if (data == null) {
            load();
        }
        return data;
    }

    /** Re-reads the file. Public so a test can prove the filters really came back from disk. */
    public static synchronized void load() {
        Data d = null;
        try {
            if (Files.exists(FILE)) {
                d = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), Data.class);
            }
        } catch (Exception e) {
            LOGGER.warn("Could not read the map designer's room filters, starting with none: {}", e.toString());
        }
        if (d == null) {
            d = new Data();
        }
        // A file written by hand, or by an older build, can leave any of these null.
        if (d.hiddenTypes == null) {
            d.hiddenTypes = new TreeSet<>();
        }
        if (d.hiddenPuzzles == null) {
            d.hiddenPuzzles = new TreeSet<>();
        }
        if (d.hiddenShapes == null) {
            d.hiddenShapes = new TreeSet<>();
        }
        if (d.routes == null) {
            d.routes = SimRoomRoutes.Filter.ALL.name();
        }
        data = d;
    }

    public static synchronized void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(data()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("Could not save the map designer's room filters: {}", e.toString());
        }
    }

    /** Back to showing every room. Saved at once. */
    public static synchronized void reset() {
        data = new Data();
        save();
    }

    // ------------------------------------------------------------------------------------------- settings

    public static synchronized boolean isTypeShown(String type) {
        return !data().hiddenTypes.contains(type.toUpperCase(Locale.ROOT));
    }

    public static synchronized void setTypeShown(String type, boolean shown) {
        toggle(data().hiddenTypes, type.toUpperCase(Locale.ROOT), shown);
    }

    public static synchronized boolean isPuzzleShown(String name) {
        return !data().hiddenPuzzles.contains(name.toLowerCase(Locale.ROOT));
    }

    public static synchronized void setPuzzleShown(String name, boolean shown) {
        toggle(data().hiddenPuzzles, name.toLowerCase(Locale.ROOT), shown);
    }

    public static synchronized boolean isShapeShown(String shape) {
        return !data().hiddenShapes.contains(shape);
    }

    public static synchronized void setShapeShown(String shape, boolean shown) {
        toggle(data().hiddenShapes, shape, shown);
    }

    public static synchronized int getMinSecrets() {
        return data().minSecrets;
    }

    /** @return the upper bound, or -1 for none */
    public static synchronized int getMaxSecrets() {
        return data().maxSecrets;
    }

    public static synchronized void setSecrets(int min, int max) {
        data().minSecrets = Math.max(0, min);
        data().maxSecrets = max < 0 ? -1 : Math.max(0, max);
        save();
    }

    public static synchronized int getMinCrypts() {
        return data().minCrypts;
    }

    /** @return the upper bound, or -1 for none */
    public static synchronized int getMaxCrypts() {
        return data().maxCrypts;
    }

    public static synchronized void setCrypts(int min, int max) {
        data().minCrypts = Math.max(0, min);
        data().maxCrypts = max < 0 ? -1 : Math.max(0, max);
        save();
    }

    public static synchronized SimRoomRoutes.Filter getRoutes() {
        for (SimRoomRoutes.Filter f : SimRoomRoutes.Filter.values()) {
            if (f.name().equalsIgnoreCase(data().routes)) {
                return f;
            }
        }
        return SimRoomRoutes.Filter.ALL;
    }

    public static synchronized void setRoutes(SimRoomRoutes.Filter f) {
        data().routes = (f == null ? SimRoomRoutes.Filter.ALL : f).name();
        save();
    }

    private static void toggle(Set<String> hidden, String key, boolean shown) {
        if (shown) {
            hidden.remove(key);
        } else {
            hidden.add(key);
        }
        save();
    }

    /** How many filters are narrowing anything - 0 means every room is shown. */
    public static synchronized int activeCount() {
        Data d = data();
        int n = d.hiddenTypes.size() + d.hiddenShapes.size() + (d.hiddenPuzzles.isEmpty() ? 0 : 1);
        n += d.minSecrets > 0 || d.maxSecrets >= 0 ? 1 : 0;
        n += d.minCrypts > 0 || d.maxCrypts >= 0 ? 1 : 0;
        n += getRoutes() != SimRoomRoutes.Filter.ALL ? 1 : 0;
        return n;
    }

    public static boolean isDefault() {
        return activeCount() == 0;
    }

    // ------------------------------------------------------------------------------------------- questions

    /** The filter's type for a room: the database's, with the two trap rooms read as TRAP like the generator does. */
    public static String typeKey(String name) {
        String type = SimFloorGen.typeOf(name).toUpperCase(Locale.ROOT);
        return SimFloorLayout.isTrap(name, type) ? "TRAP" : type;
    }

    /**
     * The room's shape as the database writes it, or one worked out from the capture's footprint when the database
     * does not know the room (an L cannot be told from a capture, which is its 2x2 bounding box).
     */
    public static String shapeKey(String name) {
        String shape = SimFloorGen.shapeOf(name);
        if (shape != null && SHAPES.contains(shape)) {
            return shape;
        }
        RoomLibrary.Room r = RoomLibrary.get(name);
        if (r == null) {
            return "1x1";
        }
        int a = Math.max(1, (r.sizeX - 1) / (RoomLibrary.TILE + 1));
        int b = Math.max(1, (r.sizeZ - 1) / (RoomLibrary.TILE + 1));
        if (a == 2 && b == 2) {
            return "2x2";
        }
        return "1x" + Math.min(4, Math.max(a, b));
    }

    public static int secretsOf(String name) {
        RoomEntry e = RoomDatabase.lookupByName(name);
        return e == null ? 0 : e.secrets;
    }

    public static int cryptsOf(String name) {
        RoomEntry e = RoomDatabase.lookupByName(name);
        return e == null ? 0 : e.crypts;
    }

    /** Whether the room list shows this room. */
    public static synchronized boolean listMatches(String name) {
        Data d = data();
        String type = typeKey(name);
        if (d.hiddenTypes.contains(type)) {
            return false;
        }
        if ("PUZZLE".equals(type)) {
            // Puzzles answer to their own toggles only - see the class doc.
            return !d.hiddenPuzzles.contains(name.toLowerCase(Locale.ROOT));
        }
        if (d.hiddenShapes.contains(shapeKey(name))) {
            return false;
        }
        int secrets = secretsOf(name);
        if (secrets < d.minSecrets || (d.maxSecrets >= 0 && secrets > d.maxSecrets)) {
            return false;
        }
        int crypts = cryptsOf(name);
        if (crypts < d.minCrypts || (d.maxCrypts >= 0 && crypts > d.maxCrypts)) {
            return false;
        }
        // SimRoomRoutes' own test, the one the sim's room picker uses.
        return SimRoomRoutes.matches(name, getRoutes());
    }

    /** Whether Generate may put this room on a floor: {@link #listMatches}, but never refusing a required room. */
    public static boolean generatorAllows(String name) {
        return REQUIRED.contains(typeKey(name)) || listMatches(name);
    }

    /** Usable rooms in the library, by name - the "M" of "N of M rooms". */
    public static List<String> usableNames() {
        List<String> out = new ArrayList<>();
        for (String name : RoomLibrary.names()) {
            RoomLibrary.Room r = RoomLibrary.get(name);
            if (r != null && r.usable()) {
                out.add(name);
            }
        }
        return out;
    }

    /** The puzzle rooms in the library, sorted - what the per-puzzle toggles list. */
    public static List<String> puzzleNames() {
        List<String> out = new ArrayList<>();
        for (String name : usableNames()) {
            if ("PUZZLE".equals(typeKey(name))) {
                out.add(name);
            }
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /**
     * How many puzzle rooms Generate can place: allowed by the filters AND a single tile, since the generator only
     * places 1x1 puzzles ({@code SimFloorLayout.shortfalls} counts the same way).
     */
    public static int allowedPuzzleCount() {
        int n = 0;
        for (String name : puzzleNames()) {
            RoomLibrary.Room r = RoomLibrary.get(name);
            boolean single = r != null && Math.max(1, (r.sizeX - 1) / (RoomLibrary.TILE + 1)) == 1
                    && Math.max(1, (r.sizeZ - 1) / (RoomLibrary.TILE + 1)) == 1;
            if (single && listMatches(name)) {
                n++;
            }
        }
        return n;
    }

    /** The largest secret and crypt counts in the library, {secrets, crypts}, for the sliders' ranges. */
    public static int[] maxima() {
        int s = 0;
        int c = 0;
        for (String name : usableNames()) {
            s = Math.max(s, secretsOf(name));
            c = Math.max(c, cryptsOf(name));
        }
        return new int[]{Math.max(1, s), Math.max(1, c)};
    }

    /** The types present in the library, in {@link #TYPES} order - a toggle for a type he has no room of is noise. */
    public static List<String> presentTypes() {
        Set<String> have = new TreeSet<>();
        for (String name : usableNames()) {
            have.add(typeKey(name));
        }
        List<String> out = new ArrayList<>();
        for (String t : TYPES) {
            if (have.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    /** "Champion", "Normal" - a type as a button says it. */
    public static String typeLabel(String type) {
        String t = type.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }
}
