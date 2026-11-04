package com.killer560.hub.roomsim;

import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The rooms of the sim floor that hold a key, as Hypixel's room in front of a wither door (or the Blood door) does
 * (killer560, 2026-10-20: "Any room before a wither door is insta cleared, then you can no longer get a wither key").
 * Armed by a floor with mobs ({@link SimMobFloor}, one room per key the floor needs) or by a test
 * ({@link #armForTest}); nothing happens for a room that is not armed.
 *
 * <p>Judged on the SERVER from the server player's own positions (the sim emulates Hypixel's server):
 * <ul>
 *   <li>a key room's first entry by a teleport (a jump of more than {@link #TELEPORT_BLOCKS} in one server tick - an
 *       etherwarp, an Instant Transmission) insta clears it, as the insta-clear tracker's evidence has Hypixel do for a
 *       room nobody has been in: it is marked cleared with its mobs standing, and its key is LOST;</li>
 *   <li>a first entry on foot changes nothing: the room clears when its last starred mob dies
 *       ({@code SimMobs.markClearedRooms}), and then its key (Wither, or Blood for the room before the Blood door) drops
 *       where that mob stood ({@link SimKeys#drop}), to be picked up with Hypixel's "has obtained Wither Key!" line.</li>
 * </ul>
 */
public final class SimKeyRoom {

    private static final Logger LOGGER = ModLog.get("killer560smod-sim");
    /** A move longer than this in one server tick is a teleport, not a step (sprint-jumping covers under 1). */
    static final double TELEPORT_BLOCKS = 2.5;

    /** One armed room. */
    private static final class Armed {
        final String name;
        final boolean blood;
        /** The room's tiles as world boxes {minX, minZ, maxX, maxZ}, from the map at arming. */
        final List<int[]> boxes;
        volatile boolean entered;
        volatile String enteredBy;
        volatile boolean lost;
        volatile boolean dropped;
        /** The room before it on the path to Blood (its tile boxes) and the door between, or empty / -1 (a test room). */
        List<int[]> before = List.of();
        int doorIn = -1;
        /** Discovered: he has stood in the room before it with the door between open (the map would show it). */
        volatile boolean discovered;

        Armed(String name, boolean blood, List<int[]> boxes) {
            this.name = name;
            this.blood = blood;
            this.boxes = boxes;
        }

        boolean insideBefore(double x, double z) {
            for (int[] b : before) {
                if (x >= b[0] && x < b[2] && z >= b[1] && z < b[3]) return true;
            }
            return false;
        }

        boolean inside(double x, double z) {
            for (int[] b : boxes) {
                if (x >= b[0] && x < b[2] && z >= b[1] && z < b[3]) return true;
            }
            return false;
        }
    }

    private static final Map<String, Armed> ARMED = new ConcurrentHashMap<>();
    private static Vec3 lastPos;
    private static boolean registered;

    private SimKeyRoom() {
    }

    static void register() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_SERVER_TICK.register(SimKeyRoom::tick);
    }

    /**
     * Arms {@code roomName} as the floor's (only) Wither Key room. Client thread (reads the live map). @return false when
     * the sim cannot act or the room is not on the map
     */
    public static boolean armForTest(Minecraft client, String roomName) {
        ARMED.clear();
        return arm(client, roomName, false);
    }

    /**
     * Arms {@code roomName} beside any room already armed: its key is a Blood Key when {@code blood}. Client thread.
     * @return false when the sim cannot act or the room is not on the map
     */
    static boolean arm(Minecraft client, String roomName, boolean blood) { return arm(client, roomName, blood, null, -1); }

    /**
     * {@link #arm} for a floor with mobs: {@code beforeRoom} is the room before it on the path to Blood and {@code doorIn}
     * the door between. Once he has stood in that room with that door open the key room is DISCOVERED, and a teleport into
     * it no longer insta clears it - the evidence (insta-clear.json, 2026-10-20) is an etherwarp into an undiscovered room
     * insta clearing it 13 times of 15 and into a discovered one 1 of 75.
     */
    static boolean arm(Minecraft client, String roomName, boolean blood, String beforeRoom, int doorIn) {
        if (!SimState.canAct(client) || roomName == null) return false;
        DungeonLayout layout = DungeonLayout.capture();
        List<int[]> out = boxes(layout, roomName);
        if (out.isEmpty()) return false;
        Armed armed = new Armed(roomName, blood, out);
        if (beforeRoom != null) {
            armed.before = boxes(layout, beforeRoom);
            armed.doorIn = doorIn;
        }
        ARMED.put(key(roomName), armed);
        lastPos = null;
        LOGGER.info("Sim key room: {} holds the {} ({} tile(s)) - a first entry by teleport insta clears it and loses the"
                + " key", roomName, blood ? "Blood Key" : "Wither Key", out.size());
        return true;
    }

    private static List<int[]> boxes(DungeonLayout layout, String roomName) {
        List<int[]> out = new ArrayList<>();
        for (int r = 0; r < layout.roomCount(); r++) {
            if (!roomName.equalsIgnoreCase(layout.name(r))) continue;
            for (int t : layout.tiles(r)) {
                BlockPos c = DungeonLayout.cellCenter(t);
                out.add(new int[]{c.getX() - 15, c.getZ() - 15, c.getX() + 16, c.getZ() + 16});
            }
        }
        return List.copyOf(out);
    }

    private static String key(String name) { return name.toLowerCase(Locale.ROOT); }

    public static void disarm() { ARMED.clear(); }

    /** {@code room|entered|enteredBy|lost|dropped} for tests (the first armed room), or null when none is armed. */
    public static String testState() {
        for (Armed a : ARMED.values()) {
            return state(a);
        }
        return null;
    }

    /** {@link #testState} of the named room, or null when it is not armed. */
    public static String testState(String roomName) {
        Armed a = roomName == null ? null : ARMED.get(key(roomName));
        return a == null ? null : state(a);
    }

    private static String state(Armed a) {
        return a.name + "|" + a.entered + "|" + a.enteredBy + "|" + a.lost + "|" + a.dropped + "|" + (a.blood ? "blood" : "wither");
    }

    /** Every armed room's name, for tests and the log. */
    public static List<String> armedRooms() {
        List<String> out = new ArrayList<>();
        for (Armed a : ARMED.values()) {
            out.add(a.name);
        }
        return out;
    }

    /** {@code SimMobs.markClearedRooms}: {@code name} just cleared because its last starred mob died, at {@code at}. */
    static void onClearedByKills(String name, double[] at) {
        Armed a = name == null ? null : ARMED.get(key(name));
        if (a == null || a.lost || a.dropped || at == null) return;
        a.dropped = true;
        Vec3 pos = new Vec3(at[0], at[1], at[2]);
        LOGGER.info("Sim key room: {}'s last starred mob died - its {} drops at {}", name,
                a.blood ? "Blood Key" : "Wither Key", BlockPos.containing(pos).toShortString());
        SimKeys.drop(Minecraft.getInstance(), pos, a.blood);
    }

    /** A key room with no starred mob to kill (placement found no spot): its key drops at {@code at} straight away. */
    static void dropWithoutMobs(String name, Vec3 at) {
        Armed a = name == null ? null : ARMED.get(key(name));
        if (a == null || a.dropped || a.lost || at == null) return;
        a.dropped = true;
        LOGGER.warn("Sim key room: {} has no starred mob - its {} drops at {} at once", name,
                a.blood ? "Blood Key" : "Wither Key", BlockPos.containing(at).toShortString());
        SimKeys.drop(Minecraft.getInstance(), at, a.blood);
    }

    private static void tick(MinecraftServer server) {
        if (ARMED.isEmpty() || !SimState.isActive()) return;
        try {
            List<ServerPlayer> players = server.getPlayerList().getPlayers();
            if (players.isEmpty()) return;
            ServerPlayer sp = players.get(0);
            Vec3 pos = sp.position();
            Vec3 before = lastPos;
            lastPos = pos;
            if (before == null) return;
            for (Armed a : ARMED.values()) {
                if (!a.discovered && a.doorIn >= 0 && a.insideBefore(pos.x, pos.z)
                        && SimDoors.isOpenAtCell(server.overworld(), a.doorIn)) {
                    a.discovered = true;
                    LOGGER.info("Sim key room: {} discovered (he is in the room before it and its door is open)", a.name);
                }
                if (a.entered || !a.inside(pos.x, pos.z)) continue;
                a.entered = true;
                double jump = Math.hypot(pos.x - before.x, pos.z - before.z);
                boolean teleport = jump > TELEPORT_BLOCKS && !a.inside(before.x, before.z);
                a.enteredBy = teleport ? "teleport" : "walk";
                if (teleport && a.discovered) {
                    LOGGER.info(String.format(Locale.US, "Sim key room: %s entered by a teleport of %.1f blocks, but it was"
                            + " discovered - no insta clear; it clears when its starred mobs die", a.name, jump));
                } else if (teleport) {
                    a.lost = true;
                    SimRoomState.markCleared(a.name);
                    LOGGER.info(String.format(Locale.US, "Sim key room: %s insta cleared - its first entry was a"
                            + " teleport of %.1f blocks to %.1f %.1f %.1f; its %s is lost", a.name, jump, pos.x, pos.y,
                            pos.z, a.blood ? "Blood Key" : "Wither Key"));
                } else {
                    LOGGER.info(String.format(Locale.US, "Sim key room: %s entered on foot at %.1f %.1f %.1f - it"
                            + " clears when its starred mobs die", a.name, pos.x, pos.y, pos.z));
                }
            }
        } catch (RuntimeException e) {
            // never stall the server tick
        }
    }
}
