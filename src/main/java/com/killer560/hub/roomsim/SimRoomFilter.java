package com.killer560.hub.roomsim;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.killer560.hub.roomdatabase.RoomDatabase;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.util.ModLog;

import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * One set of sim room filters: the rows of the Filters panel ({@link SimRoomFilterScreen}) and the one test that
 * decides whether a room passes them.
 *
 * <p>killer560 (2026-10-07), with a mockup: "for the room filters it should look kind of like this [...] the single
 * room should have the same filter option." There are three of these - the Map Designer's, the Load a Room picker's
 * and All Rooms' (held in {@link SimRoomFilters}) - each saved in its own file, but all three are this class, edited
 * by the same panel and tested by the same {@link #matches}, so the places cannot drift apart. What differs is
 * written down once, as the {@link Use}:
 * <ul>
 *   <li>{@link Use#DESIGNER}: puzzle rooms answer to Kind and the Puzzles row only (every puzzle is a 1x1 with no
 *       secrets and no route, so "4 or more secrets" would otherwise silently zero the Puzzles slider).</li>
 *   <li>{@link Use#PICKER}: every row applies to every room.</li>
 *   <li>{@link Use#CYCLE}: as the picker, plus All Rooms' standing exclusion, kept as the default: with no Kind
 *       chosen, puzzles, Blood, Entrance and Fairy are left out, and with no Kind and Secrets on Any, 0-secret rooms
 *       are too - exactly {@link SimRoomRoutes#isEligible}. Choosing a Kind or a Secrets bucket replaces that part of
 *       the default, so the filters can bring those rooms back.</li>
 * </ul>
 *
 * <p>Multi-select rows (Size, Kind, Puzzles) list what is CHOSEN, and an empty row means any. Every field comes from
 * the room database: Kind is its type (RARE read as Normal, and Old/New Trap as Trap the way the generator reads them),
 * Rare room is its RARE type, Champion is its CHAMPION type (the four miniboss rooms) and Fairy its FAIRY type.
 */
public final class SimRoomFilter {

    private static final Logger LOGGER = ModLog.get("killer560smod-roomsim");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Which list this filter narrows; see the class doc for how each one reads the rows. */
    public enum Use { DESIGNER, PICKER, CYCLE }

    /** The Size row, in the database's own shape strings. */
    public static final List<String> SIZES = List.of("1x1", "1x2", "1x3", "1x4", "2x2", "L");

    /** The Kind row, as {@link #kindKey} names them. */
    public static final List<String> KINDS = List.of(
            "NORMAL", "PUZZLE", "TRAP", "CHAMPION", "BLOOD", "ENTRANCE", "FAIRY");

    /** Kinds All Rooms leaves out while no Kind is chosen. */
    private static final Set<String> CYCLE_DEFAULT_OUT = Set.of("PUZZLE", "BLOOD", "ENTRANCE", "FAIRY");

    /** The Rare room row. */
    public enum Rare {
        ANY("Any"), YES("Yes"), NO("No");

        public final String label;

        Rare(String label) {
            this.label = label;
        }
    }

    /** The Secrets and Crypts rows: a count bucket. */
    public enum Count {
        ANY("Any"), NONE("None"), FEW("1 to 3"), MANY("4 or more");

        public final String label;

        Count(String label) {
            this.label = label;
        }

        public boolean test(int n) {
            return switch (this) {
                case ANY -> true;
                case NONE -> n <= 0;
                case FEW -> n >= 1 && n <= 3;
                case MANY -> n >= 4;
            };
        }
    }

    /** What is saved. */
    private static final class Data {
        Set<String> sizes = new TreeSet<>();
        Set<String> kinds = new TreeSet<>();
        /** Lower-case puzzle room names. */
        Set<String> puzzles = new TreeSet<>();
        String rare = Rare.ANY.name();
        String secrets = Count.ANY.name();
        String crypts = Count.ANY.name();
        String routes = SimRoomRoutes.Filter.ALL.name();
    }

    private final Use use;
    private final Path file;
    private Data data;

    public SimRoomFilter(Use use, Path file) {
        this.use = use;
        this.file = file;
    }

    public Use use() {
        return use;
    }

    // ------------------------------------------------------------------------------------------- storage

    private synchronized Data data() {
        if (data == null) {
            load();
        }
        return data;
    }

    /** Re-reads the file. Public so a test can prove the filters really came back from disk. */
    public synchronized void load() {
        Data d = null;
        try {
            if (Files.exists(file)) {
                d = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
            }
        } catch (Exception e) {
            LOGGER.warn("Could not read the sim room filters in {}, starting with none: {}", file.getFileName(),
                    e.toString());
        }
        if (d == null) {
            d = new Data();
        }
        // A file written by hand, or by the designer's first filter build (hiddenTypes etc., 2026-10-06), leaves
        // these null; that older file's rules are not carried over and read as no filter.
        d.sizes = d.sizes == null ? new TreeSet<>() : new TreeSet<>(d.sizes);
        d.kinds = d.kinds == null ? new TreeSet<>() : new TreeSet<>(d.kinds);
        d.puzzles = d.puzzles == null ? new TreeSet<>() : new TreeSet<>(d.puzzles);
        d.sizes.retainAll(SIZES);
        d.kinds.retainAll(KINDS);
        data = d;
    }

    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(data()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("Could not save the sim room filters to {}: {}", file.getFileName(), e.toString());
        }
    }

    /** "Clear all": back to no filter. Saved at once. */
    public synchronized void clear() {
        data = new Data();
        save();
    }

    // ------------------------------------------------------------------------------------------- rows

    public synchronized boolean hasSize(String size) {
        return data().sizes.contains(size);
    }

    public synchronized void toggleSize(String size) {
        toggle(data().sizes, size);
    }

    public synchronized boolean hasKind(String kind) {
        return data().kinds.contains(kind.toUpperCase(Locale.ROOT));
    }

    public synchronized void toggleKind(String kind) {
        toggle(data().kinds, kind.toUpperCase(Locale.ROOT));
    }

    public synchronized boolean hasPuzzle(String name) {
        return data().puzzles.contains(name.toLowerCase(Locale.ROOT));
    }

    public synchronized void togglePuzzle(String name) {
        toggle(data().puzzles, name.toLowerCase(Locale.ROOT));
    }

    /** Whether the Puzzles row can change anything: puzzles are in play under the current Kind row. */
    public synchronized boolean puzzlesInPlay() {
        Set<String> kinds = data().kinds;
        return kinds.contains("PUZZLE") || (kinds.isEmpty() && use != Use.CYCLE);
    }

    public synchronized Rare rare() {
        return parse(Rare.class, data().rare, Rare.ANY);
    }

    public synchronized void setRare(Rare r) {
        data().rare = (r == null ? Rare.ANY : r).name();
        save();
    }

    public synchronized Count secrets() {
        return parse(Count.class, data().secrets, Count.ANY);
    }

    public synchronized void setSecrets(Count c) {
        data().secrets = (c == null ? Count.ANY : c).name();
        save();
    }

    public synchronized Count crypts() {
        return parse(Count.class, data().crypts, Count.ANY);
    }

    public synchronized void setCrypts(Count c) {
        data().crypts = (c == null ? Count.ANY : c).name();
        save();
    }

    public synchronized SimRoomRoutes.Filter routes() {
        return parse(SimRoomRoutes.Filter.class, data().routes, SimRoomRoutes.Filter.ALL);
    }

    public synchronized void setRoutes(SimRoomRoutes.Filter f) {
        data().routes = (f == null ? SimRoomRoutes.Filter.ALL : f).name();
        save();
    }

    /** How many rows are narrowing anything - 0 means no filter. */
    public synchronized int activeCount() {
        Data d = data();
        int n = 0;
        n += d.sizes.isEmpty() ? 0 : 1;
        n += d.kinds.isEmpty() ? 0 : 1;
        n += d.puzzles.isEmpty() ? 0 : 1;
        n += rare() != Rare.ANY ? 1 : 0;
        n += secrets() != Count.ANY ? 1 : 0;
        n += crypts() != Count.ANY ? 1 : 0;
        n += routes() != SimRoomRoutes.Filter.ALL ? 1 : 0;
        return n;
    }

    public boolean isDefault() {
        return activeCount() == 0;
    }

    private void toggle(Set<String> set, String key) {
        if (!set.remove(key)) {
            set.add(key);
        }
        save();
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String raw, E fallback) {
        if (raw != null) {
            for (E e : type.getEnumConstants()) {
                if (e.name().equalsIgnoreCase(raw)) {
                    return e;
                }
            }
        }
        return fallback;
    }

    // ------------------------------------------------------------------------------------------- the test

    /** Whether a room passes these filters, read for this filter's {@link Use}. */
    public synchronized boolean matches(String name) {
        Data d = data();
        if (use == Use.CYCLE && RoomDatabase.lookupByName(name) == null) {
            return false;   // as SimRoomRoutes.isEligible: an unknown room's type and secrets are unknown
        }
        String kind = kindKey(name);
        boolean puzzle = "PUZZLE".equals(kind);
        if (!d.kinds.isEmpty()) {
            if (!d.kinds.contains(kind)) {
                return false;
            }
        } else if (use == Use.CYCLE && CYCLE_DEFAULT_OUT.contains(kind)) {
            return false;
        }
        if (puzzle && !d.puzzles.isEmpty() && !d.puzzles.contains(name.toLowerCase(Locale.ROOT))) {
            return false;
        }
        if (puzzle && use == Use.DESIGNER) {
            return true;
        }
        if (!d.sizes.isEmpty() && !d.sizes.contains(shapeKey(name))) {
            return false;
        }
        Rare r = rare();
        if (r != Rare.ANY && isRare(name) != (r == Rare.YES)) {
            return false;
        }
        int secrets = secretsOf(name);
        Count s = secrets();
        if (s == Count.ANY) {
            if (use == Use.CYCLE && d.kinds.isEmpty() && secrets <= 0) {
                return false;
            }
        } else if (!s.test(secrets)) {
            return false;
        }
        if (!crypts().test(cryptsOf(name))) {
            return false;
        }
        return SimRoomRoutes.matches(name, routes());
    }

    // ------------------------------------------------------------------------------------------- room facts

    /** The database's type upper case, with the two trap rooms read as TRAP like the generator does. */
    public static String typeKey(String name) {
        String type = SimFloorGen.typeOf(name).toUpperCase(Locale.ROOT);
        return SimFloorLayout.isTrap(name, type) ? "TRAP" : type;
    }

    /** The Kind row's name for a room: {@link #typeKey}, with a RARE room read as the normal room it is. */
    public static String kindKey(String name) {
        String type = typeKey(name);
        return "RARE".equals(type) ? "NORMAL" : type;
    }

    /** The Rare room row: the database's RARE type. */
    public static boolean isRare(String name) {
        return "RARE".equals(typeKey(name));
    }

    /**
     * The room's shape as the database writes it, or one worked out from the capture's footprint when the database
     * does not know the room (an L cannot be told from a capture, which is its 2x2 bounding box).
     */
    public static String shapeKey(String name) {
        String shape = SimFloorGen.shapeOf(name);
        if (shape != null && SIZES.contains(shape)) {
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

    /** "Champion", "Normal" - a kind as a chip says it. */
    public static String kindLabel(String kind) {
        String t = kind.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }
}
