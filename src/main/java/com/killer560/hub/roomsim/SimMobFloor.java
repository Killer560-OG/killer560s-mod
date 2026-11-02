package com.killer560.hub.roomsim;

import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * A sim floor WITH MOBS (killer560, Doc 2026-11-01, "SIM MOB SPAWNS": "There should be an option for it to have mobs or not
 * have mobs when you create it. Make it so if they choose to generate a map with mobs then the wither doors and blood doors
 * are there and you have to get the key from killing the mobs in the room prior to it. The key should not go into the
 * inventory it should just be known they have it. Also make it so fels can spawn for the mobs.").
 *
 * <p>With it on, a generated floor gets, on top of the floor it always was:
 * <ul>
 *   <li><b>Mobs</b> in every mob room at GO ({@link SimLiveMobs}: {@link #PER_TILE} starred mobs a tile, one HP, standing,
 *       a miniboss in a miniboss room) and <b>Fels</b> beside some of them ({@link SimMobs#spawnFelLive}): a black skull on
 *       the ground that becomes an upside-down enderman when a player comes within {@link SimMobs#FEL_WAKE_RADIUS}.
 *       A room clears when its last starred mob dies, so clear-on-entry is off.</li>
 *   <li><b>Wither doors and the Blood door</b> on the path from the Entrance to Blood (the wiki: "The path to the Blood Room
 *       is guarded by a series of Wither Doors and ends at the Blood Door"). Each door after the first needs a key from a
 *       mob room before it: the room before a wither door holds a Wither Key, the last mob room before Blood the Blood Key
 *       ({@link SimKeyRoom}). The key drops where the room's last starred mob died and is the team's once someone walks
 *       near it ({@link SimKeys}: "has obtained Wither Key!"), never an inventory item. A door whose rooms before it have no
 *       mobs to give a key stays open, so every floor can be finished.</li>
 *   <li><b>The Fairy door</b> on that path is shut (coal) until the run starts and opens by itself 1.5 s after GO, as
 *       Hypixel's does (docs/LESSONS-DUNGEONS.md: "usually coal until the run starts and opens 1-2 s after").</li>
 *   <li><b>Hypixel's lines</b>, at Hypixel's moments: "Starting in 1 second.", Mort's opening line at GO, and "The BLOOD
 *       DOOR has been opened!". Nothing else the sim says changes.</li>
 * </ul>
 * Off, a floor is exactly what it was: no mobs, every room clears on entry, no wither doors, a Blood door with no key.
 */
public final class SimMobFloor {

    private static final Logger LOGGER = ModLog.get("killer560smod-sim");
    /** Starred mobs a tile in a floor with mobs (the sim's live-mob default, as testkit 1020-sim-300 plays it). */
    public static final int PER_TILE = 2;
    /** Ticks after GO the Fairy door opens by itself (Hypixel: 1-2 s after "Starting in 1 second."). */
    static final int FAIRY_OPEN_TICKS = 30;
    static final String MORT_LINE = "[NPC] Mort: Here, I found this map when I first entered the dungeon.";
    static final String BLOOD_DOOR_LINE = "The BLOOD DOOR has been opened!";

    private static volatile boolean enabled;
    /** This floor's plan, made when it was ready: the rooms whose kills drop a key, in rush order, and whether Blood. */
    private static final List<String[]> keyRooms = new ArrayList<>();
    private static volatile boolean bloodNeedsKey;
    private static volatile boolean planned;
    private static int fairyDoor = -1;
    /** This floor's wither door cells (11x11), for tests. */
    private static final List<Integer> WITHER_CELLS = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static int fairyTicks = -1;
    private static volatile String plan = "";
    /** Each path room's previous path room and the door between ({name, door cell}), by name. */
    private static final java.util.Map<String, Object[]> BEFORE = new java.util.concurrent.ConcurrentHashMap<>();
    /** Each path room's first tile center, by name, taken on the client thread when the floor is planned. */
    private static final java.util.Map<String, Vec3> CENTRES = new java.util.concurrent.ConcurrentHashMap<>();

    private SimMobFloor() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("SimMobFloor.tick", SimMobFloor::tick));
        net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register((d, r) ->
                d.register(ClientCommands.literal("simmobs")
                        .then(ClientCommands.literal("on").executes(ctx -> say(true)))
                        .then(ClientCommands.literal("off").executes(ctx -> say(false)))));
    }

    private static int say(boolean on) {
        setEnabled(on);
        ModChat.send("Sim", ModChat.text("Mobs " + (on ? "on" : "off") + " from the next floor"));
        return 1;
    }

    /** Whether the next generated floor has mobs, keys and wither doors. Kept for the session (the menu remembers it too). */
    public static void setEnabled(boolean on) {
        boolean was = enabled;
        enabled = on;
        if (on) {
            SimLiveMobs.configure(PER_TILE, 1.0, false, true);
            SimLiveMobs.setFels(true);
        } else if (was) {
            SimLiveMobs.configure(0, 1.0, false, true);
            SimLiveMobs.setFels(false);
        }
        LOGGER.info("[Sim] floor with mobs: {}", on ? "on" : "off");
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Whether the Blood door of this floor wants the Blood Key (a floor with mobs whose key room drops one). */
    static boolean bloodDoorNeedsKey() {
        return enabled && planned && bloodNeedsKey && SimState.isActive();
    }

    /** Whether this floor says Hypixel's run lines. */
    static boolean saysHypixelLines() {
        return enabled && planned && SimState.isActive();
    }

    /** The plan in words, for tests and the log: "rooms: A(wither) > B(blood); fairy door N; blood needs key". */
    public static String plan() {
        return plan;
    }

    /** This floor's wither door cells in rush order (11x11 index), for tests. */
    public static List<Integer> witherDoorCellsForTest() {
        return List.copyOf(WITHER_CELLS);
    }

    /** The key rooms of this floor in rush order, "name|wither" or "name|blood", for tests. */
    public static List<String> keyRoomsForTest() {
        List<String> out = new ArrayList<>();
        for (String[] k : keyRooms) {
            out.add(k[0] + "|" + k[1]);
        }
        return out;
    }

    // ---- the floor -----------------------------------------------------------------------------------------------------

    /**
     * {@link SimRun#autoStart}, the floor built and published, before the countdown: the doors on the path to Blood. Client
     * thread (reads the live map's layout); the doors are carved on the integrated server.
     */
    static void onFloorReady(Minecraft client) {
        keyRooms.clear();
        BEFORE.clear();
        CENTRES.clear();
        WITHER_CELLS.clear();
        bloodNeedsKey = false;
        planned = false;
        fairyDoor = -1;
        fairyTicks = -1;
        plan = "";
        if (!enabled || !SimState.canAct(client) || !SimState.isGeneratedFloor()) {
            return;
        }
        DungeonLayout layout = DungeonLayout.capture();
        int entrance = -1;
        int blood = -1;
        for (int r = 0; r < layout.roomCount(); r++) {
            String t = type(layout, r);
            if (entrance < 0 && ("ENTRANCE".equals(t) || "Entrance".equalsIgnoreCase(layout.name(r)))) {
                entrance = r;
            }
            if (blood < 0 && ("BLOOD".equals(t) || "Blood".equalsIgnoreCase(layout.name(r)))) {
                blood = r;
            }
        }
        if (entrance < 0 || blood < 0) {
            LOGGER.warn("[Sim] floor with mobs: no {} on the map - no doors or keys this floor", entrance < 0 ? "Entrance" : "Blood");
            planned = true;
            return;
        }
        int n = layout.roomCount();
        int[] from = new int[n];
        int[] via = new int[n];
        Arrays.fill(from, -2);
        from[entrance] = -1;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(entrance);
        while (!queue.isEmpty() && from[blood] == -2) {
            int r = queue.poll();
            for (int idx = 0; idx < DungeonLayout.GRID * DungeonLayout.GRID; idx++) {
                if (!layout.isDoor(idx)) {
                    continue;
                }
                int[] s = sides(layout, idx);
                if (s == null || (s[0] != r && s[1] != r)) {
                    continue;
                }
                int o = s[0] == r ? s[1] : s[0];
                if (o < n && from[o] == -2) {
                    from[o] = r;
                    via[o] = idx;
                    queue.add(o);
                }
            }
        }
        if (from[blood] == -2) {
            LOGGER.warn("[Sim] floor with mobs: Blood is not reachable from the Entrance on the map - no doors or keys");
            planned = true;
            return;
        }
        List<Integer> path = new ArrayList<>();
        List<Integer> doors = new ArrayList<>();
        for (int r = blood; r != entrance; r = from[r]) {
            path.add(0, r);
            doors.add(0, via[r]);
        }
        path.add(0, entrance);
        // doors.get(i) leads INTO path.get(i + 1).
        ArrayDeque<String> sources = new ArrayDeque<>();
        StringBuilder words = new StringBuilder();
        List<Integer> wither = new ArrayList<>();
        for (int i = 1; i < path.size(); i++) {
            int room = path.get(i);
            int door = doors.get(i - 1);
            String type = type(layout, room);
            String name = layout.name(room);
            boolean isBlood = room == blood;
            BlockPos c = DungeonLayout.cellCenter(layout.tiles(room)[0]);
            CENTRES.put(name, new Vec3(c.getX() + 0.5, c.getY(), c.getZ() + 0.5));
            if (i > 1 || isBlood) {
                if (isBlood) {
                    if (!sources.isEmpty()) {
                        keyRooms.add(new String[]{sources.pop(), "blood"});
                        bloodNeedsKey = true;
                    }
                } else if ("FAIRY".equals(type)) {
                    if (layout.doorType(door) == DungeonLayout.DOOR_NORMAL) {
                        fairyDoor = door;
                    }
                } else if (!sources.isEmpty() && layout.doorType(door) == DungeonLayout.DOOR_NORMAL) {
                    keyRooms.add(new String[]{sources.pop(), "wither"});
                    wither.add(door);
                }
            }
            if (!isBlood && isMobRoom(type)) {
                sources.push(name);
            }
            // The room before this one on the path and the door between: what makes it discovered (SimKeyRoom).
            BEFORE.put(name, new Object[]{layout.name(path.get(i - 1)), door});
            words.append(i > 1 ? " > " : "").append(name);
        }
        for (int door : wither) {
            SimDoors.makeWitherDoor(client, door);
        }
        WITHER_CELLS.addAll(wither);
        if (fairyDoor >= 0) {
            SimDoors.makeWitherDoor(client, fairyDoor);
        }
        planned = true;
        StringBuilder keys = new StringBuilder();
        for (String[] k : keyRooms) {
            keys.append(keys.length() > 0 ? ", " : "").append(k[0]).append(" (").append(k[1]).append(")");
        }
        plan = "path " + words + "; " + wither.size() + " wither door(s); key rooms " + (keys.length() == 0 ? "none" : keys)
                + "; fairy door " + (fairyDoor >= 0 ? "shut until GO" : "none") + "; blood door "
                + (bloodNeedsKey ? "needs the Blood Key" : "opens without a key");
        LOGGER.info("[Sim] floor with mobs: {}", plan);
    }

    /** {@link SimRun}, one second before GO. */
    static void onOneSecond(Minecraft client) {
        if (saysHypixelLines()) {
            broadcast(client, "Starting in 1 second.");
        }
    }

    /** {@link SimRun}, at GO, before the mobs are placed: Mort's line, the key rooms armed, the Fairy door's clock. */
    static void onGo(Minecraft client) {
        if (!saysHypixelLines()) {
            return;
        }
        broadcast(client, MORT_LINE);
        for (String[] k : keyRooms) {
            armKey(client, k);
        }
        fairyTicks = fairyDoor >= 0 ? FAIRY_OPEN_TICKS : -1;
    }

    /** Test aid: arms this floor's key rooms again, fresh (after a test disarmed them to move the player about). */
    public static void rearmKeyRoomsForTest(Minecraft client) {
        if (!saysHypixelLines()) {
            return;
        }
        for (String[] k : keyRooms) {
            armKey(client, k);
        }
    }

    private static void armKey(Minecraft client, String[] k) {
        Object[] b = BEFORE.get(k[0]);
        SimKeyRoom.arm(client, k[0], "blood".equals(k[1]), b == null ? null : (String) b[0], b == null ? -1 : (Integer) b[1]);
    }

    /** {@link SimLiveMobs}, server thread, right after the GO placement: a key room that got no mob drops its key now. */
    static void onMobsPlaced(Minecraft client) {
        if (!saysHypixelLines()) {
            return;
        }
        for (String[] k : keyRooms) {
            if (SimLiveMobs.placedIn(k[0]) > 0) {
                continue;
            }
            BlockPos at = SimLiveMobs.firstSpot(k[0]);
            Vec3 where = at != null ? Vec3.atBottomCenterOf(at) : CENTRES.get(k[0]);
            SimKeyRoom.dropWithoutMobs(k[0], where);
        }
    }

    /** The Blood door opened with its key: Hypixel's line. */
    static void onBloodDoorOpened(Minecraft client) {
        if (saysHypixelLines()) {
            broadcast(client, BLOOD_DOOR_LINE);
        }
    }

    private static void tick(Minecraft client) {
        if (fairyTicks < 0) {
            return;
        }
        if (!SimState.canAct(client)) {
            fairyTicks = -1;
            return;
        }
        if (--fairyTicks == 0) {
            fairyTicks = -1;
            boolean opened = SimDoors.openDoorAtCell(client, fairyDoor);
            LOGGER.info("[Sim] the Fairy door opens by itself {} ticks after GO ({})", FAIRY_OPEN_TICKS,
                    opened ? "opening" : "no door found");
        }
    }

    // ---- helpers --------------------------------------------------------------------------------------------------------

    private static String type(DungeonLayout layout, int room) {
        RoomEntry e = layout.entry(room);
        return e == null || e.type == null ? "" : e.type.toUpperCase(Locale.ROOT);
    }

    private static boolean isMobRoom(String type) {
        return type.equals("NORMAL") || type.equals("RARE") || type.equals("CHAMPION");
    }

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

    /** A line from the server to every player, as Hypixel's server sends it. */
    private static void broadcast(Minecraft client, String line) {
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                if (!SimMiniboss.isPlaced(sp)) {
                    sp.sendSystemMessage(Component.literal(line));
                }
            }
        });
    }
}
