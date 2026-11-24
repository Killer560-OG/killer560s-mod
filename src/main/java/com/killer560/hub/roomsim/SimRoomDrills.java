package com.killer560.hub.roomsim;

import com.google.gson.JsonObject;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.hud.BasicHud;
import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.roomdatabase.RoomDatabase;
import com.killer560.hub.shared.RecordCatalog;
import com.killer560.hub.shared.SharedRecords;
import com.killer560.hub.shared.SharedWriter;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Weak-Room Drills (idea-roomdrills-1124): every room loaded on its own in the sim is timed from the moment its
 * loading ends to the moment it is cleared (the map's checkmark, which the sim paints when the last starred mob dies,
 * at the sim's own game-tick pace), and the time is kept per room in the shared record
 * {@link RecordCatalog#SIM_ROOM_DRILLS}. "Weak Rooms" on the Route Practice page builds a set of the rooms with the
 * slowest average times, and steps through them with the
 * existing /next and /back. No data, no set: it says so rather than guessing.
 */
public final class SimRoomDrills {

    /** Rooms in a drill. */
    static final int SIZE = 5;
    /** Rooms that must have a time before a drill can say which are weak. */
    static final int MIN_ROOMS = 5;
    /** A longer time is a player who walked away, not a clear. */
    private static final long MAX_MS = 600_000;

    private static volatile JsonObject data;
    private static volatile boolean loading;
    /** This game session's best per room in ms (bounded by the room library). */
    private static final Map<String, Integer> SESSION = new ConcurrentHashMap<>();

    private static String room;
    /** A single room is loading and its timer has not started. */
    private static boolean armed;
    private static boolean saidNoMobs;
    private static long startTick = -1;
    private static boolean finished;
    private static long lastMs;
    private static int bestMs;
    private static boolean newBest;
    private static long shownTenths = -1;
    private static String shownText = "";

    public static final HudElement HUD = new DrillHud();

    private SimRoomDrills() {
    }

    /** Reads the record off the calling thread (the menu calls it when Route Practice opens, a load calls it too). */
    public static void preload() {
        if (data != null || loading) return;
        loading = true;
        SharedWriter.submit(() -> {
            data = SharedRecords.load(SharedRecords.file(RecordCatalog.SIM_ROOM_DRILLS));
            loading = false;
        });
    }

    /** {@link SimBuilder#buildSingleRoom}: a lone room is loading; its time starts when the loading screen goes. */
    static void noteLoad(String name) {
        room = name;
        armed = true;
        startTick = -1;
        finished = false;
        // A rebuilt room is a new room: its old clear would hide this one's (the sim never repaints a cleared room).
        SimRoomState.clearRoom(name);
        bestMs = (int) pbMs(name);
        preload();
    }

    /** Call once from the client entrypoint, beside the other sim registrations. */
    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("SimRoomDrills.tick", SimRoomDrills::tick));
    }

    private static void tick(Minecraft client) {
        if (room == null || client.level == null) return;
        if (finished) {
            if (!SimState.canAct(client)) room = null;
            return;
        }
        if (!SimState.canAct(client)) {
            room = null;
            armed = false;
            return;
        }
        long now = client.level.getGameTime();
        if (armed) {
            if (SimWorld.isBuilding() || SimBuildQueue.isBusy()) return;
            armed = false;
            // Without live mobs the sim clears a room the moment you enter it (SimMobs clear-on-entry), so a time would
            // only be the walk in: not timed, said once.
            if (!SimLiveMobs.isOn()) {
                room = null;
                if (!saidNoMobs) {
                    saidNoMobs = true;
                    ModChat.send("Drill", ModChat.text("Rooms are timed only with mobs on: the Map Designer's Mobs button (Weak Rooms turns them on)"));
                }
                return;
            }
            SimLiveMobs.onRunStart(client);
            startTick = now;
            return;
        }
        int state = SimRoomState.stateFor(room);
        if (state != 1 && state != 0) return;   // DungeonMapScanner.STATE_CLEARED / STATE_GREEN
        finished = true;
        lastMs = (now - startTick) * 50;
        if (lastMs >= 1000 && lastMs <= MAX_MS) {
            newBest = bestMs <= 0 || lastMs < bestMs;
            save(room, (int) lastMs);
            SESSION.merge(room, (int) lastMs, Math::min);
            ModChat.send("Drill", ModChat.value(room), ModChat.text(": "), ModChat.value(secs(lastMs)),
                    ModChat.text("   Best: "), ModChat.value(secs(newBest ? lastMs : bestMs)),
                    newBest ? ModChat.good(bestMs <= 0 ? "  First time!" : "  New best!") : ModChat.text(""));
            bestMs = (int) Math.min(bestMs <= 0 ? lastMs : bestMs, lastMs);
        }
    }

    private static String secs(long ms) {
        return String.format(Locale.ROOT, "%.1fs", ms / 1000.0);
    }

    /** One clear: best (smaller wins), last, total and runs (the shared store adds counters across instances). */
    private static void save(String name, int ms) {
        JsonObject d = data == null ? new JsonObject() : data.deepCopy();
        JsonObject rooms = d.has("rooms") ? d.getAsJsonObject("rooms") : new JsonObject();
        JsonObject e = rooms.has(name) ? rooms.getAsJsonObject(name) : new JsonObject();
        int best = e.has("bestMs") ? e.get("bestMs").getAsInt() : 0;
        e.addProperty("bestMs", best <= 0 ? ms : Math.min(best, ms));
        e.addProperty("lastMs", ms);
        e.addProperty("totalMs", (e.has("totalMs") ? e.get("totalMs").getAsLong() : 0) + ms);
        e.addProperty("runs", (e.has("runs") ? e.get("runs").getAsInt() : 0) + 1);
        rooms.add(name, e);
        d.add("rooms", rooms);
        data = d;
        SharedWriter.submit(() -> data = SharedRecords.save(SharedRecords.file(RecordCatalog.SIM_ROOM_DRILLS), d));
    }

    private static JsonObject entry(String name) {
        JsonObject d = data;
        if (d == null || !d.has("rooms")) return null;
        JsonObject rooms = d.getAsJsonObject("rooms");
        return rooms.has(name) ? rooms.getAsJsonObject(name) : null;
    }

    /** The saved best time for a room in ms, or 0. */
    public static long pbMs(String name) {
        JsonObject e = entry(name);
        return e != null && e.has("bestMs") ? e.get("bestMs").getAsLong() : 0;
    }

    /** The saved average clear time for a room in ms, or 0 with no clears. */
    public static long averageMs(String name) {
        JsonObject e = entry(name);
        int runs = e != null && e.has("runs") ? e.get("runs").getAsInt() : 0;
        return runs <= 0 ? 0 : e.get("totalMs").getAsLong() / runs;
    }

    /**
     * The drill's rooms: those inside All Rooms' filter with a saved time, the {@link #SIZE} with the highest average
     * (the furthest above the median of the player's own averages), slowest first. Empty while fewer than {@link #MIN_ROOMS} have a time.
     */
    public static List<String> weakest() {
        JsonObject d = data;
        if (d == null) {
            preload();
            return List.of();
        }
        if (!d.has("rooms") || !RoomDatabase.isReady()) return List.of();
        List<String> names = new ArrayList<>();
        for (String name : d.getAsJsonObject("rooms").keySet()) {
            if (averageMs(name) > 0 && RoomLibrary.get(name) != null && SimRoomFilters.CYCLE.matches(name)) {
                names.add(name);
            }
        }
        if (names.size() < MIN_ROOMS) return List.of();
        names.sort((a, b) -> Long.compare(averageMs(b), averageMs(a)));
        return new ArrayList<>(names.subList(0, Math.min(SIZE, names.size())));
    }

    /** Testkit: forgets the in-memory record and reads the file again (what a restart does). */
    public static void reloadForTest() {
        SharedWriter.drain(5000);
        data = SharedRecords.load(SharedRecords.file(RecordCatalog.SIM_ROOM_DRILLS));
    }

    /** Testkit: the timer as it stands. */
    public static String stats() {
        return "room=" + room + " armed=" + armed + " started=" + (startTick >= 0) + " finished=" + finished
                + " lastMs=" + lastMs + " bestMs=" + bestMs;
    }

    /** The timer line: "Room 0:12.3  Best 0:09.8", with "2/5" in front inside a drill. Rebuilt only when the tenths change. */
    private static String text(Minecraft client, boolean editor) {
        if (editor) return "Drill 2/5 Mossy 12.3s  Best 9.8s";
        if (room == null || armed || startTick < 0 || client.level == null) return "";
        long ms = finished ? lastMs : (client.level.getGameTime() - startTick) * 50;
        long tenths = ms / 100;
        if (tenths != shownTenths || finished) {
            shownTenths = tenths;
            String drill = SimRoomCycle.choice() == SimRoomCycle.Choice.WEAK && SimRoomCycle.isActive()
                    ? "Drill " + (SimRoomCycle.currentIndex() + 1) + "/" + SimRoomCycle.currentRooms().size() + " " : "";
            shownText = drill + room + " " + secs(ms) + (bestMs > 0 ? "  Best " + secs(bestMs) : "");
        }
        return shownText;
    }

    /** Movable in the HUD editor; drawn by {@code hud/HudInGameRenderer} (its id is in UNDRAWN_ELEMENT_IDS). */
    private static final class DrillHud extends BasicHud {
        DrillHud() {
            super("room_drill_timer", "Room Drill Timer", 200, 60);
        }

        private static String line() {
            Minecraft client = Minecraft.getInstance();
            return text(client, McCompat.screen(client) instanceof HudEditorScreen);
        }

        @Override
        public int width() {
            return Math.max(1, Minecraft.getInstance().font.width(line()));
        }

        @Override
        public int height() {
            return 9;
        }

        @Override
        public boolean isEnabledInSettings() {
            return room != null;
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            String s = line();
            if (s.isEmpty()) return;
            graphics.text(Minecraft.getInstance().font, s, x, y, 0xFFFFFFFF, true);
            HudSeen.markDrawn(id());
        }
    }
}
