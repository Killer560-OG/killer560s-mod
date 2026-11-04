package com.killer560.hub.splittimers;

import com.killer560.hub.doorkeys.DungeonKeys;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Blood Rush Splits: the blood rush, room by room (Doc 2026-11-01, "the blood rush split stuff" of the reference mod adm;
 * its idea and its timeline, read not copied - adm is All Rights Reserved).
 *
 * <p>A rush room starts when the door into it opens ("&lt;name&gt; opened a WITHER door!", the first one at Mort's line) and
 * ends when the door out of it opens, so the rooms tile end to end until "The BLOOD DOOR has been opened!". Inside a room:
 * <pre>
 *   door down   the wither door into it, all the way open (its block on the map turns to air), seen when loaded
 *   last mob    the room's key appearing ("Wither Key" / "Blood Key" stand): where its last mob died, seen when loaded
 *   pickup      "&lt;name&gt; has obtained Wither Key!" (or Blood Key)
 *   opened      the door out of it opening
 * </pre>
 * Times are real time and, with Split Timers' Lagless Times, the lagless time in parentheses ({@link SplitLagClock}).
 * A room is named from the map: the room its key dropped in, else the far side of the door into it (the side further
 * from the Entrance on the map's room graph). The room that led into Fairy is drawn pink; nothing names who killed the
 * last mob (Minecraft has no kill attribution), so that moment is timed and never credited.
 *
 * <p>Display only: no pattern here gates an action, but each is still a full-line match of a server line.
 */
public final class BloodRushSplits {

    private static final Pattern MORT = Pattern.compile(
            "^\\[NPC\\] Mort: (?:Here, I found this map when I first entered the dungeon\\.|Right-click the Orb for spells, and Left-click \\(or Drop\\) to use your Ultimate!)$");
    /** With or without a rank in front (unranked players have none), or the nameless form. Group 1 is the name. */
    private static final Pattern KEY_PICKED = Pattern.compile(
            "^(?:\\[[A-Z+]{1,8}\\] )?([A-Za-z0-9_]{1,16}) has obtained (?:Wither|Blood) Key!?$|^(?:A )?(?:Wither|Blood) Key was picked up!?$");
    private static final Pattern WITHER_DOOR = Pattern.compile("^([A-Za-z0-9_]{1,16}) opened a WITHER door!?$");
    private static final Pattern BLOOD_DOOR = Pattern.compile("^The BLOOD DOOR has been opened!?$");

    /** Compact: the key time goes dark red when the pickup itself (key on the ground to key in hand) took longer. */
    static final long SLOW_PICKUP_MS = 350L;
    /** Compact: the door time shows only when key-in-hand to door-open took longer (quicker is just the click). */
    static final long SLOW_DOOR_MS = 250L;
    private static final String UNNAMED = "...";

    /** A moment on both clocks: real ms and the lag clock then. */
    record Stamp(long ms, long lagMs) {
    }

    /** One side of a door: the map room's name, its index in the layout, and whether it is Fairy. */
    record Side(String name, int id, boolean fairy) {
    }

    static final class Room {
        Stamp start;
        String startFrom;
        String name = UNNAMED;
        int mapId = -1;
        boolean toFairy;
        Stamp doorDown;
        Stamp keyDrop;
        double keySeenAt = -1;
        Stamp keyPicked;
        String keyBy = "";
        Stamp doorOpened;
        String doorBy = "";

        Room(Stamp start, String from) {
            this.start = start;
            this.startFrom = from;
        }
    }

    private static final List<Room> rooms = new ArrayList<>();
    private static Room current;
    private static boolean started;
    private static boolean done;
    private static Object lastLevel;
    /** Wither/blood door cells and whether each read locked last tick, for the open moment. */
    private static final Map<Integer, Boolean> doorLocked = new HashMap<>();
    private static final Set<Integer> keyStandsSeen = new HashSet<>();

    private BloodRushSplits() {
    }

    public static void register() {
        ChatObserver.subscribe(m -> {
            if (BloodRushSplitsConfig.getInstance().isActive()) {
                onLine(ChatObserver.strip(m), stamp());
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("BloodRushSplits", BloodRushSplits::tick));
    }

    private static Stamp stamp() { return new Stamp(System.currentTimeMillis(), SplitLagClock.accumulatedLagMs()); }

    static void reset() {
        rooms.clear();
        current = null;
        started = false;
        done = false;
        doorLocked.clear();
        keyStandsSeen.clear();
    }

    /** From Mort's line to the Blood door. */
    public static boolean isActive() { return started && !done; }


    static void onLine(String raw, Stamp at) {
        if (raw == null) return;
        String msg = raw.trim();
        if ("Starting in 1 second.".equals(msg)) {
            reset();
            return;
        }
        if (done) return;
        if (!started) {
            if (MORT.matcher(msg).matches()) {
                reset();
                started = true;
                current = new Room(at, "Mort's line");
            }
            return;
        }
        Room r = current;
        if (r == null || !DungeonKeys.mayBeKeyOrDoorLine(msg)) return;
        var key = KEY_PICKED.matcher(msg);
        if (key.matches()) {
            if (r.keyPicked == null) {
                r.keyPicked = at;
                r.keyBy = key.group(1) == null ? "" : key.group(1);
                if (r.keyDrop == null) {
                    // The key never seen on the ground (too far away): its drop can only be put at the pickup.
                    r.keyDrop = at;
                }
            }
            return;
        }
        var door = WITHER_DOOR.matcher(msg);
        if (door.matches()) {
            r.doorOpened = at;
            r.doorBy = door.group(1);
            rooms.add(r);
            current = new Room(at, "the wither door line");
            return;
        }
        if (BLOOD_DOOR.matcher(msg).matches()) {
            r.doorOpened = at;
            rooms.add(r);
            current = null;
            done = true;
            announce();
        }
    }

    /**
     * A wither door the rush is going through is all the way down. {@code earlier}/{@code later} are its two rooms, the
     * earlier the one nearer the Entrance. Only the room the last door line started takes it, once.
     */
    static void onDoorDown(Stamp at, Side earlier, Side later) {
        Room r = current;
        if (r == null || r.doorDown != null || rooms.isEmpty() || at.ms() < r.start.ms() - 250L) return;
        r.doorDown = at;
        Room previous = rooms.get(rooms.size() - 1);
        if (later != null && UNNAMED.equals(r.name)) {
            r.name = later.name();
            r.mapId = later.id();
        }
        if (earlier != null) {
            if (earlier.fairy() && previous.mapId != earlier.id()) {
                // The door out of Fairy, which the rush walked into through Fairy's own open door: the room before
                // this one is the one that led there.
                previous.toFairy = true;
            } else if (UNNAMED.equals(previous.name)) {
                previous.name = earlier.name();
                previous.mapId = earlier.id();
            }
        }
    }

    /** A key's stand appeared {@code distance} blocks away, in map room {@code room} (null when unknown). */
    static void onKeySpawned(Stamp at, double distance, Side room) {
        Room r = current;
        if (r == null || r.keyDrop != null) return;
        r.keyDrop = at;
        r.keySeenAt = distance;
        if (room != null && room.name() != null) {
            // Where the key dropped is the room being run: the most direct name there is.
            r.name = room.name();
            r.mapId = room.id();
        }
    }


    private static void tick(Minecraft client) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            reset();
        }
        if (!isActive() || !BloodRushSplitsConfig.getInstance().isActive() || client.player == null
                || !DungeonState.isInDungeon()) {
            return;
        }
        Stamp now = stamp();
        DungeonLayout layout = DungeonLayout.current();
        int[] dist = null;
        for (int idx = 0; idx < DungeonLayout.GRID * DungeonLayout.GRID; idx++) {
            int type = layout.doorType(idx);
            if (type != DungeonLayout.DOOR_WITHER && type != DungeonLayout.DOOR_BLOOD) continue;
            boolean locked = layout.isLocked(idx);
            Boolean before = doorLocked.put(idx, locked);
            if (before == null || !before || locked) continue;
            int[] sides = sides(layout, idx);
            if (sides == null) continue;
            if (dist == null) {
                dist = distanceFromEntrance(layout);
            }
            boolean aFirst = dist[sides[0]] <= dist[sides[1]];
            onDoorDown(now, side(layout, aFirst ? sides[0] : sides[1]), side(layout, aFirst ? sides[1] : sides[0]));
        }
        for (DungeonKeys.Dropped key : DungeonKeys.dropped(client)) {
            if (keyStandsSeen.add(key.id())) {
                int room = layout.roomAtWorld(key.x(), key.z());
                double d = Math.sqrt(client.player.distanceToSqr(key.x(), key.y(), key.z()));
                onKeySpawned(now, d, room >= 0 ? side(layout, room) : null);
            }
        }
    }

    /** The two rooms a door cell joins, or null. */
    private static int[] sides(DungeonLayout layout, int idx) {
        int g = DungeonLayout.GRID;
        int gx = idx % g;
        int gz = idx / g;
        int a;
        int b;
        if (gx % 2 == 1 && gz % 2 == 0) {
            a = layout.roomOfCell(idx - 1);
            b = layout.roomOfCell(idx + 1);
        } else if (gx % 2 == 0 && gz % 2 == 1) {
            a = layout.roomOfCell(idx - g);
            b = layout.roomOfCell(idx + g);
        } else {
            return null;
        }
        return a < 0 || b < 0 || a == b ? null : new int[]{a, b};
    }

    /** Each room's door count from the Entrance over the map's room graph (unreachable: a large number). */
    private static int[] distanceFromEntrance(DungeonLayout layout) {
        int n = layout.roomCount();
        int[] dist = new int[n];
        Arrays.fill(dist, Integer.MAX_VALUE / 2);
        int entrance = -1;
        for (int r = 0; r < n; r++) {
            RoomEntry e = layout.entry(r);
            if ((e != null && "ENTRANCE".equalsIgnoreCase(e.type)) || "Entrance".equalsIgnoreCase(layout.name(r))) {
                entrance = r;
                break;
            }
        }
        if (entrance < 0) return dist;
        List<List<Integer>> adj = new ArrayList<>();
        for (int r = 0; r < n; r++) {
            adj.add(new ArrayList<>());
        }
        for (int idx = 0; idx < DungeonLayout.GRID * DungeonLayout.GRID; idx++) {
            if (!layout.isDoor(idx)) continue;
            int[] s = sides(layout, idx);
            if (s != null && s[0] < n && s[1] < n) {
                adj.get(s[0]).add(s[1]);
                adj.get(s[1]).add(s[0]);
            }
        }
        dist[entrance] = 0;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(entrance);
        while (!queue.isEmpty()) {
            int r = queue.poll();
            for (int o : adj.get(r)) {
                if (dist[o] > dist[r] + 1) {
                    dist[o] = dist[r] + 1;
                    queue.add(o);
                }
            }
        }
        return dist;
    }

    private static Side side(DungeonLayout layout, int room) {
        RoomEntry e = layout.entry(room);
        return new Side(layout.name(room), room, e != null && "FAIRY".equalsIgnoreCase(e.type));
    }


    /** {real ms, lagless ms} from {@code a} to {@code b}, or null when either is missing. */
    private static long[] span(Stamp a, Stamp b) {
        if (a == null || b == null) return null;
        long t = Math.max(0L, b.ms() - a.ms());
        return new long[]{t, Math.max(0L, t - Math.max(0L, b.lagMs() - a.lagMs()))};
    }

    /** The room's own key time: the door into it down (else its start) to the key in hand. */
    private static long[] keySpan(Room r, Stamp live) {
        Stamp from = r.doorDown != null ? r.doorDown : r.start;
        return span(from, r.keyPicked != null ? r.keyPicked : live);
    }

    private static long[] pickupSpan(Room r, Stamp live) { return span(r.keyDrop, r.keyPicked != null ? r.keyPicked : live); }

    private static long[] doorSpan(Room r, Stamp live) { return span(r.keyPicked, r.doorOpened != null ? r.doorOpened : live); }

    private static long[] totalSpan(Room r, Stamp live) { return span(r.start, r.doorOpened != null ? r.doorOpened : live); }

    private static List<Room> all() {
        List<Room> out = new ArrayList<>(rooms);
        if (current != null) {
            out.add(current);
        }
        return out;
    }

    private static String time(long[] s, boolean lagless) {
        if (s == null) return "";
        String real = SplitTimersFeature.formatTime(s[0]);
        return lagless ? real + " §7(" + SplitTimersFeature.formatTime(s[1]) + ")" : real;
    }

    private static boolean lagless() { return SplitTimersConfig.getInstance().isLaglessTimes() && SplitLagClock.isTrustworthy(); }

    private static String name(Room r) { return (r.toFairy ? "§d" : "§5") + r.name; }

    /** The rows the HUD draws now ({@link BloodRushSplitsConfig#getStyle}). Empty before Mort's line. */
    static List<String> rows(Stamp now, BloodRushSplitsConfig.Style style) {
        List<String> out = new ArrayList<>();
        if (!started) return out;
        boolean lagless = lagless();
        out.add("§4Blood Rush" + (done ? "" : " §7(" + all().size() + ")"));
        for (Room r : all()) {
            Stamp live = r == current ? now : null;
            if (style == BloodRushSplitsConfig.Style.DETAILED) {
                out.add(name(r));
                long[] killFrom = span(r.doorDown != null ? r.doorDown : r.start, r.keyDrop != null ? r.keyDrop : live);
                addLabelled(out, "§8 Door Down", span(r.start, r.doorDown != null ? r.doorDown : null), lagless);
                addLabelled(out, "§7 Last Mob", killFrom, lagless);
                addLabelled(out, "§c Pickup", pickupSpan(r, live), lagless);
                addLabelled(out, "§4 Opened", doorSpan(r, live), lagless);
                addLabelled(out, "§6 Room Total", totalSpan(r, live), lagless);
            } else {
                out.add(compactRow(r, live, lagless));
            }
        }
        if (done && !rooms.isEmpty()) {
            long[] avgTotal = average(rooms, r -> totalSpan(r, null));
            long[] avgKey = average(rooms, r -> keySpan(r, null));
            out.add("§6Average§f: " + time(avgTotal, lagless) + (avgKey == null ? "" : " §8| §ckey " + time(avgKey, false)));
        }
        return out;
    }

    private static void addLabelled(List<String> out, String label, long[] s, boolean lagless) {
        if (s != null) {
            out.add(label + "§f: " + time(s, lagless));
        }
    }

    private static String compactRow(Room r, Stamp live, boolean lagless) {
        StringBuilder sb = new StringBuilder(name(r)).append("§f: ");
        long[] total = totalSpan(r, live);
        sb.append(total == null ? "" : time(total, lagless));
        long[] key = keySpan(r, live);
        long[] pickup = pickupSpan(r, live);
        if (key != null && (r.keyPicked != null || r.keyDrop == null)) {
            boolean slow = pickup != null && pickup[0] > SLOW_PICKUP_MS;
            sb.append(" §8| ").append(slow ? "§4" : "§c").append("key ").append(SplitTimersFeature.formatTime(key[0]));
        }
        long[] door = doorSpan(r, live);
        if (door != null && door[0] > SLOW_DOOR_MS) {
            sb.append(" §8| §7door ").append(SplitTimersFeature.formatTime(door[0]));
        }
        return sb.toString();
    }

    private static long[] average(List<Room> list, java.util.function.Function<Room, long[]> f) {
        long a = 0;
        long b = 0;
        int n = 0;
        for (Room r : list) {
            long[] s = f.apply(r);
            if (s != null) {
                a += s[0];
                b += s[1];
                n++;
            }
        }
        return n == 0 ? null : new long[]{a / n, b / n};
    }

    /** The rush as one Mod Chat line, when the Blood door opens and the option is on. */
    private static void announce() {
        Minecraft client = Minecraft.getInstance();
        if (!BloodRushSplitsConfig.getInstance().isAnnounceInChat() || client == null || client.player == null
                || rooms.isEmpty()) {
            return;
        }
        boolean lagless = lagless();
        net.minecraft.network.chat.MutableComponent line = Component.empty().append(ModChat.text("Blood Rush: "));
        for (int i = 0; i < rooms.size(); i++) {
            Room r = rooms.get(i);
            long[] t = totalSpan(r, null);
            if (i > 0) {
                line.append(ModChat.text(", "));
            }
            line.append(ModChat.value(r.name)).append(ModChat.text(" "))
                    .append(ModChat.value(t == null ? "?" : time(t, false)
                            + (lagless ? " (" + SplitTimersFeature.formatTime(t[1]) + ")" : "")));
        }
        client.player.sendSystemMessage(line.append(ModChat.text(".")));
    }


    /** Test hook: one chat line at {@code ms} with the lag clock at {@code lagMs}. */
    public static void lineForTest(String raw, long ms, long lagMs) { onLine(raw, new Stamp(ms, lagMs)); }

    /** Test hook: the door into the current room down, between the named rooms. */
    public static void doorDownForTest(String earlier, boolean earlierFairy, String later, long ms) {
        onDoorDown(new Stamp(ms, 0L), new Side(earlier, earlier.hashCode(), earlierFairy),
                new Side(later, later.hashCode(), false));
    }

    /** Test hook: a key stand appeared in {@code room}. */
    public static void keyForTest(String room, double distance, long ms) {
        onKeySpawned(new Stamp(ms, 0L), distance, room == null ? null : new Side(room, room.hashCode(), false));
    }

    /** Test hook: the HUD rows at {@code ms}, in {@code style} ("COMPACT"/"DETAILED"), formatting codes left in. */
    public static List<String> rowsForTest(long ms, String style) {
        return rows(new Stamp(ms, SplitLagClock.accumulatedLagMs()),
                BloodRushSplitsConfig.Style.valueOf(style.toUpperCase(Locale.ROOT)));
    }

    /** Test hook: forget the run. */
    public static void resetForTest() { reset(); }


    public static final class Hud implements HudElement {
        private static final int ROW_HEIGHT = 9;

        @Override
        public String id() { return "blood_rush_splits"; }

        @Override
        public String displayName() { return "Blood Rush Splits"; }

        @Override
        public int defaultX() { return 180; }

        @Override
        public int defaultY() { return 240; }

        private static boolean editor() {
            return com.killer560.hub.compat.McCompat.screen(Minecraft.getInstance())
                    instanceof com.killer560.hub.hud.HudEditorScreen;
        }

        private static List<String> lines() {
            if (editor()) {
                return List.of("§4Blood Rush", "§5Pipes§f: 9.45s §8| §ckey 8.70s", "§dDuncan§f: 7.20s §8| §ckey 6.05s §8| §7door 0.40s",
                        "§5Mural§f: 8.10s §8| §ckey 7.35s", "§6Average§f: 8.25s §8| §ckey 7.36s");
            }
            return rows(stamp(), BloodRushSplitsConfig.getInstance().getStyle());
        }

        @Override
        public int width() {
            int w = 20;
            for (String s : lines()) {
                w = Math.max(w, Minecraft.getInstance().font.width(s));
            }
            return w;
        }

        @Override
        public int height() { return Math.max(ROW_HEIGHT, lines().size() * ROW_HEIGHT); }

        @Override
        public boolean isEnabledInSettings() { return BloodRushSplitsConfig.getInstance().isActive(); }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            if (!isEnabledInSettings() || HudVisibility.hidesHud()) return;
            List<String> lines = lines();
            int dy = 0;
            for (String s : lines) {
                graphics.text(Minecraft.getInstance().font, s, x, y + dy, 0xFFFFFFFF, false);
                dy += ROW_HEIGHT;
            }
            if (!lines.isEmpty()) {
                HudSeen.markDrawn(id());
            }
        }
    }
}
