package com.killer560.hub.interop;

import com.killer560.hub.secrets.DungeonState;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The one place every party-wide dungeon fact lands, whoever noticed it.
 * <p>
 * Three feeds write here and each stamps its {@link InteropSource} on what it writes:
 * <ul>
 * <li>{@link InteropSource#SELF} - this client worked it out on its own. Written by
 * {@link SelfDerivation} (and by any existing feature that already knows something; see
 * {@code ScoreCalculatorFeature}). This is the only feed that works for a player whose party mates run no
 * mods at all, so it is always preferred.</li>
 * <li>{@link InteropSource#CHAT} - {@link InteropChatParser} recognised an announcement another dungeon mod
 * made in party chat. Works for OUR user with only OUR mod installed, as long as a party mate runs one of
 * those mods.</li>
 * <li>{@link InteropSource#BRIDGE} - {@link LocalModBridge} read another mod's own state in this JVM.
 * Only ever does anything on a machine that already has that mod installed.</li>
 * <li>{@link InteropSource#RELAY} - reserved for our own relay ({@code com.killer560.hub.relay}). Nothing
 * here calls it; the relay client pushes in through {@link #offerFlag}/{@link #offerCounter}/
 * {@link #offerRoomSecrets} with {@code InteropSource.RELAY} and reads back through the getters. See the
 * "relay seam" note in the Interop staging notes.</li>
 * </ul>
 * Conflict rule: a fact is only overwritten by a source of equal or higher {@link InteropSource#rank()}, or
 * by anything at all once the held value has gone {@link #STALE_MS stale}. Flags are monotonic for a run - a
 * mimic that died cannot un-die - so only {@code true} is ever accepted, and the recorded source upgrades if a
 * better source later confirms the same thing.
 * <p>
 * Everything resets when {@link DungeonState#isInDungeon()} flips, driven from {@link InteropFeature}.
 */
public final class PartyInteropState {

    /** After this long an untouched counter may be replaced by any source, however weak. */
    private static final long STALE_MS = 30_000L;
    private static final int MAX_ROOMS = 64;

    /** One-way facts about the run. Once true they stay true until the run resets. */
    public enum Flag {
        MIMIC_KILLED("Mimic killed"),
        PRINCE_KILLED("Prince killed"),
        BAT_KILLED("Bat killed"),
        BLOOD_OPENED("Blood opened"),
        BLOOD_DONE("Blood done");

        private final String label;

        Flag(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Run totals that only ever climb, plus the three P3 section counters that restart each section (see
     *  {@link #offerCounter(Counter, int, int, InteropSource, String)}). {@code -1} means "nobody has told us yet". */
    public enum Counter {
        SECRETS_FOUND("Secrets"),
        CRYPTS("Crypts"),
        DEATHS("Deaths"),
        TERMINALS_DONE("Terminals"),
        DEVICES_DONE("Devices"),
        LEVERS_DONE("Levers");

        private final String label;

        Counter(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** A value plus who told us and when - so the GUI can show provenance and the merge can compare trust. */
    public record Fact<T>(T value, InteropSource source, String reporter, long atMs) {
    }

    /** What we believe about one room's secrets. {@code total < 0} means the room's max is unknown. */
    public record RoomSecrets(int found, int total) {
    }

    private static final Object LOCK = new Object();
    private static final Map<Flag, Fact<Boolean>> FLAGS = new EnumMap<>(Flag.class);
    /** Flags a party mate's own mod has already said out loud in party chat this run. */
    private static final java.util.EnumSet<Flag> ANNOUNCED_IN_PARTY = java.util.EnumSet.noneOf(Flag.class);
    private static final Map<Counter, Fact<Integer>> COUNTERS = new EnumMap<>(Counter.class);
    /** The current P3 section's "(n/m)" as last read from a server line; -1 until one is seen. Guarded by LOCK. */
    private static int p3SectionDone = -1;
    private static int p3SectionTotal = -1;
    private static final Map<String, Fact<RoomSecrets>> ROOMS = new LinkedHashMap<>();
    private static final Map<String, Fact<Long>> DRAGONS = new LinkedHashMap<>();

    private PartyInteropState() {
    }

    // ------------------------------------------------------------------ writes

    /** @return true if this actually changed what we believe. Only {@code true} values are ever accepted. */
    public static boolean offerFlag(Flag flag, InteropSource source, String reporter) {
        if (flag == null || source == null) {
            return false;
        }
        synchronized (LOCK) {
            if (source == InteropSource.CHAT) {
                // Recorded even when the fact is rejected as lower-trust: "did a party mate already say this
                // out loud" is a separate question from "do we believe it", and it is what stops us repeating
                // an announcement the party has already seen.
                ANNOUNCED_IN_PARTY.add(flag);
            }
            Fact<Boolean> held = FLAGS.get(flag);
            if (held != null && held.source().rank() >= source.rank()) {
                return false; // already known, and from a source at least as good
            }
            boolean isNew = held == null;
            FLAGS.put(flag, new Fact<>(Boolean.TRUE, source, reporter, System.currentTimeMillis()));
            return isNew;
        }
    }

    /** Run totals. Accepted when the source is at least as trusted as the one we hold and the number has not
     *  gone backwards, or when what we hold has gone stale. */
    public static boolean offerCounter(Counter counter, int value, InteropSource source, String reporter) {
        return offerCounter(counter, value, -1, source, reporter);
    }

    /**
     * As {@link #offerCounter(Counter, int, InteropSource, String)}, with the "(n/m)" denominator when the source
     * knows it ({@code -1} otherwise). Terminals, devices and levers are NOT run totals: Hypixel's
     * "{@code Bob activated a terminal! (n/m)}" counts one P3 section, shared by all three kinds, and restarts at
     * the next section (7/7, then 1/8). A new section is recognised when the denominator changes, or when the
     * held section was complete and a lower number arrives; all three section counters are then dropped so the
     * new section's values are accepted instead of being refused as "going backwards" for {@link #STALE_MS}.
     */
    public static boolean offerCounter(Counter counter, int value, int total, InteropSource source, String reporter) {
        if (counter == null || source == null || value < 0) {
            return false;
        }
        synchronized (LOCK) {
            if (isSectionCounter(counter) && total > 0) {
                boolean newSection = p3SectionTotal > 0 && (total != p3SectionTotal
                        || (p3SectionDone >= p3SectionTotal && value < p3SectionDone));
                if (newSection) {
                    COUNTERS.remove(Counter.TERMINALS_DONE);
                    COUNTERS.remove(Counter.DEVICES_DONE);
                    COUNTERS.remove(Counter.LEVERS_DONE);
                    p3SectionDone = value;
                } else {
                    p3SectionDone = Math.max(p3SectionDone, value);
                }
                p3SectionTotal = total;
            }
            Fact<Integer> held = COUNTERS.get(counter);
            long now = System.currentTimeMillis();
            if (held != null) {
                boolean stale = now - held.atMs() > STALE_MS;
                if (!stale && source.rank() < held.source().rank()) {
                    return false;
                }
                if (!stale && value < held.value()) {
                    return false; // counters only climb within a run
                }
                if (value == held.value() && source.rank() <= held.source().rank()) {
                    COUNTERS.put(counter, new Fact<>(value, held.source(), held.reporter(), now));
                    return false;
                }
            }
            COUNTERS.put(counter, new Fact<>(value, source, reporter, now));
            return true;
        }
    }

    /** Per-room secret progress. {@code total} may be {@code -1} when only a "found" number is known. */
    public static boolean offerRoomSecrets(String roomName, int found, int total, InteropSource source, String reporter) {
        String key = normaliseRoom(roomName);
        if (key.isEmpty() || source == null || found < 0) {
            return false;
        }
        synchronized (LOCK) {
            Fact<RoomSecrets> held = ROOMS.get(key);
            long now = System.currentTimeMillis();
            if (held != null) {
                boolean stale = now - held.atMs() > STALE_MS;
                if (!stale && source.rank() < held.source().rank()) {
                    return false;
                }
                if (!stale && found < held.value().found()) {
                    return false;
                }
            }
            int keptTotal = total >= 0 ? total : (held == null ? -1 : held.value().total());
            boolean changed = held == null || found != held.value().found() || keptTotal != held.value().total();
            // The local bridge re-offers every room it knows about every other second: an unchanged room only
            // refreshes its timestamp (and keeps the better source), it is not a new fact.
            InteropSource keptSource = !changed && held.source().rank() > source.rank() ? held.source() : source;
            String keptReporter = keptSource == source ? reporter : held.reporter();
            ROOMS.put(key, new Fact<>(new RoomSecrets(found, keptTotal), keptSource, keptReporter, now));
            while (ROOMS.size() > MAX_ROOMS) {
                ROOMS.remove(ROOMS.keySet().iterator().next());
            }
            return changed;
        }
    }

    /** An M7 dragon spawn call. {@code colour} is the plain lower-case dragon name (red/orange/green/blue/purple). */
    public static boolean offerDragonSpawn(String colour, InteropSource source, String reporter) {
        String key = colour == null ? "" : colour.trim().toLowerCase(Locale.US);
        if (key.isEmpty() || source == null) {
            return false;
        }
        synchronized (LOCK) {
            Fact<Long> held = DRAGONS.get(key);
            if (held != null && held.source().rank() >= source.rank()
                    && System.currentTimeMillis() - held.atMs() < STALE_MS) {
                return false;
            }
            DRAGONS.put(key, new Fact<>(System.currentTimeMillis(), source, reporter, System.currentTimeMillis()));
            return true;
        }
    }

    // ------------------------------------------------------------------ reads

    public static boolean flag(Flag flag) {
        synchronized (LOCK) {
            return FLAGS.containsKey(flag);
        }
    }

    public static Fact<Boolean> flagFact(Flag flag) {
        synchronized (LOCK) {
            return FLAGS.get(flag);
        }
    }

    /** @return whether another player's mod already announced this in party chat this run. */
    public static boolean announcedInParty(Flag flag) {
        synchronized (LOCK) {
            return ANNOUNCED_IN_PARTY.contains(flag);
        }
    }

    /** @return the known value, or {@code -1} when nothing has reported it this run. */
    public static int counter(Counter counter) {
        synchronized (LOCK) {
            Fact<Integer> held = COUNTERS.get(counter);
            return held == null ? -1 : held.value();
        }
    }

    public static Fact<Integer> counterFact(Counter counter) {
        synchronized (LOCK) {
            return COUNTERS.get(counter);
        }
    }

    public static Fact<RoomSecrets> roomSecrets(String roomName) {
        synchronized (LOCK) {
            return ROOMS.get(normaliseRoom(roomName));
        }
    }

    /** Snapshot of every room we have secret numbers for, newest insertion last. */
    public static Map<String, Fact<RoomSecrets>> roomSnapshot() {
        synchronized (LOCK) {
            return new LinkedHashMap<>(ROOMS);
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** New dungeon run (or left the dungeon) - everything here is per-run. */
    public static void reset() {
        synchronized (LOCK) {
            FLAGS.clear();
            ANNOUNCED_IN_PARTY.clear();
            COUNTERS.clear();
            p3SectionDone = -1;
            p3SectionTotal = -1;
            ROOMS.clear();
            DRAGONS.clear();
        }
    }

    // ------------------------------------------------------------------ internals

    private static boolean isSectionCounter(Counter counter) {
        return counter == Counter.TERMINALS_DONE || counter == Counter.DEVICES_DONE || counter == Counter.LEVERS_DONE;
    }

    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    /** Room names arrive from several places with different capitalisation and stray spaces. The map asks for
     *  this every frame per room, so a name that is already clean (the usual case) is returned as it is. */
    private static String normaliseRoom(String roomName) {
        if (roomName == null) {
            return "";
        }
        boolean clean = true;
        int len = roomName.length();
        for (int i = 0; i < len && clean; i++) {
            char c = roomName.charAt(i);
            if (Character.isWhitespace(c)) {
                clean = c == ' ' && i > 0 && i < len - 1 && roomName.charAt(i - 1) != ' ';
            }
        }
        return clean ? roomName : WHITESPACE_RUN.matcher(roomName.trim()).replaceAll(" ");
    }
}
