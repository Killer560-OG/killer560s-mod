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

/**
 * One room of the sim floor that holds a Wither Key, as Hypixel's room in front of a wither door does (killer560,
 * 2026-10-20: "Any room before a wither door is insta cleared, then you can no longer get a wither key"). TEST AID ONLY:
 * nothing happens unless {@link #armForTest} named a room, and generated floors still drop no keys (docs/SIM.md, "No
 * Wither Key drop").
 *
 * <p>While armed, judged on the SERVER from the server player's own positions (the sim emulates Hypixel's server):
 * <ul>
 *   <li>the room's first entry by a teleport (a jump of more than {@link #TELEPORT_BLOCKS} in one server tick - an
 *       etherwarp, an Instant Transmission) insta clears it, as the insta-clear tracker's evidence has Hypixel do for a
 *       room nobody has been in: it is marked cleared with its mobs standing, and its key is LOST;</li>
 *   <li>a first entry on foot changes nothing: the room clears when its last starred mob dies
 *       ({@code SimMobs.markClearedRooms}), and then its Wither Key drops where that mob stood ({@link SimKeys#drop}),
 *       to be picked up with Hypixel's "has obtained Wither Key!" line.</li>
 * </ul>
 */
public final class SimKeyRoom {

    private static final Logger LOGGER = ModLog.get("killer560smod-sim");
    /** A move longer than this in one server tick is a teleport, not a step (sprint-jumping covers under 1). */
    static final double TELEPORT_BLOCKS = 2.5;

    private static volatile String room;
    /** The room's tiles as world boxes {minX, minZ, maxX, maxZ}, from the map at arming. */
    private static volatile List<int[]> boxes = List.of();
    private static volatile boolean entered;
    private static volatile String enteredBy;
    private static volatile boolean lost;
    private static volatile boolean dropped;
    private static Vec3 lastPos;
    private static boolean registered;

    private SimKeyRoom() {
    }

    static void register() {
        if (registered) {
            return;
        }
        registered = true;
        ServerTickEvents.END_SERVER_TICK.register(SimKeyRoom::tick);
    }

    /**
     * Arms {@code roomName} as the floor's key room. Client thread (reads the live map). @return false when the sim
     * cannot act or the room is not on the map
     */
    public static boolean armForTest(Minecraft client, String roomName) {
        if (!SimState.canAct(client) || roomName == null) {
            return false;
        }
        DungeonLayout layout = DungeonLayout.capture();
        List<int[]> out = new ArrayList<>();
        for (int r = 0; r < layout.roomCount(); r++) {
            if (!roomName.equalsIgnoreCase(layout.name(r))) {
                continue;
            }
            for (int t : layout.tiles(r)) {
                BlockPos c = DungeonLayout.cellCenter(t);
                out.add(new int[]{c.getX() - 15, c.getZ() - 15, c.getX() + 16, c.getZ() + 16});
            }
        }
        if (out.isEmpty()) {
            return false;
        }
        boxes = List.copyOf(out);
        room = roomName;
        entered = false;
        enteredBy = null;
        lost = false;
        dropped = false;
        lastPos = null;
        LOGGER.info("Sim key room: {} holds the Wither Key ({} tile(s)) - a first entry by teleport insta clears it and"
                + " loses the key", roomName, out.size());
        return true;
    }

    public static void disarm() {
        room = null;
        boxes = List.of();
    }

    /** {@code room|entered|enteredBy|lost|dropped} for tests, or null when not armed. */
    public static String testState() {
        String r = room;
        return r == null ? null : r + "|" + entered + "|" + enteredBy + "|" + lost + "|" + dropped;
    }

    /** {@code SimMobs.markClearedRooms}: {@code name} just cleared because its last starred mob died, at {@code at}. */
    static void onClearedByKills(String name, double[] at) {
        String r = room;
        if (r == null || !r.equalsIgnoreCase(name) || lost || dropped || at == null) {
            return;
        }
        dropped = true;
        Vec3 pos = new Vec3(at[0], at[1], at[2]);
        LOGGER.info("Sim key room: {}'s last starred mob died - its Wither Key drops at {}", name,
                BlockPos.containing(pos).toShortString());
        SimKeys.drop(Minecraft.getInstance(), pos, false);
    }

    private static boolean inside(double x, double z) {
        for (int[] b : boxes) {
            if (x >= b[0] && x < b[2] && z >= b[1] && z < b[3]) {
                return true;
            }
        }
        return false;
    }

    private static void tick(MinecraftServer server) {
        String r = room;
        if (r == null || !SimState.isActive()) {
            return;
        }
        try {
            List<ServerPlayer> players = server.getPlayerList().getPlayers();
            if (players.isEmpty()) {
                return;
            }
            ServerPlayer sp = players.get(0);
            Vec3 pos = sp.position();
            Vec3 before = lastPos;
            lastPos = pos;
            if (entered || before == null || !inside(pos.x, pos.z)) {
                return;
            }
            entered = true;
            double jump = Math.hypot(pos.x - before.x, pos.z - before.z);
            boolean teleport = jump > TELEPORT_BLOCKS;
            enteredBy = teleport ? "teleport" : "walk";
            if (teleport) {
                lost = true;
                SimRoomState.markCleared(r);
                LOGGER.info(String.format(java.util.Locale.US, "Sim key room: %s insta cleared - its first entry was a"
                        + " teleport of %.1f blocks to %.1f %.1f %.1f; its Wither Key is lost", r, jump, pos.x, pos.y, pos.z));
            } else {
                LOGGER.info(String.format(java.util.Locale.US, "Sim key room: %s entered on foot at %.1f %.1f %.1f - it"
                        + " clears when its starred mobs die", r, pos.x, pos.y, pos.z));
            }
        } catch (RuntimeException e) {
            // never stall the server tick
        }
    }
}
