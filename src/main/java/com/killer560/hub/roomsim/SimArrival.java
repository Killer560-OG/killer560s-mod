package com.killer560.hub.roomsim;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.util.ModLog;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;

import org.slf4j.Logger;

import java.util.Set;
import java.util.UUID;

/**
 * Every place the sim puts him (a floor's entrance, a single room, /goto, a boss phase, death's way home) lands here
 * (simspawn-1104, killer560 2026-11-04: "sometimes spawning into an area i am put into walls or blocks and it takes
 * forever to load and the loading bar never updates").
 *
 * <p>Two halves. On the SERVER the spot is checked: feet and head free of anything with a collision shape or fluid,
 * something solid underfoot; a spot that fails (the boss's fixed spawns against a capture whose floor sits elsewhere,
 * a room with no measured floor) gives way to the nearest one that passes. On the CLIENT he is held under the sim's
 * loading screen until his own world has the chunks round the spot: the teleport reaches him before the chunks do,
 * and a client standing in chunks it does not have yet falls through them, so once they are in he is put on the spot
 * again (only if he drifted) and the screen comes down. The sim's own server-side teleport, never the real player's
 * position: this world is ours (see {@link SimBuilder#snapPlayerTo}).
 */
public final class SimArrival {

    private static final Logger LOGGER = ModLog.get("killer560smod-roomsim");

    /** Chunks either side of the landing the client must hold before he is let go. */
    private static final int RADIUS = 1;

    /** Longest hold, client ticks: a client that never gets the chunks is let in rather than kept on the screen. */
    private static final int TIMEOUT_TICKS = 20 * 15;

    /** {x, y, z} he is held for, or null. Written on the server thread, read on the render thread. */
    private static volatile double[] pending;
    private static volatile UUID who;
    private static double[] seen;
    private static int waited;
    private static boolean replaced;
    private static boolean showedScreen;
    private static boolean missedChunks;
    private static float fraction;

    private SimArrival() {
    }

    /** Server thread: puts {@code sp} on the clear spot nearest {@code (x, y, z)} and holds his client for it. */
    public static void place(ServerPlayer sp, ServerLevel level, double x, double y, double z, float yaw, float pitch) {
        double[] at = clear(level, x, y, z);
        teleport(sp, level, at[0], at[1], at[2], Set.of(), yaw, pitch);
        var local = Minecraft.getInstance().player;   // only HIS client is held; sim minibosses are ServerPlayers too
        if (local != null && local.getUUID().equals(sp.getUUID())) {
            expect(sp.getUUID(), at);
        }
    }

    /**
     * Server thread: every sim landing's teleport. A server-side teleport moves him, and {@code ChunkMap.tick} moves the
     * chunks he is SENT, but the chunk TICKETS (DistanceManager) follow him only through {@code ChunkMap.move}, which
     * vanilla calls when his client's next movement packet arrives. A client still in its world-load wait sends none:
     * it waits for its own chunk, which is never sent, because a chunk is sent only once it ticks and nothing tickets
     * the landing (simchunk-1105: a floor from the title on 26.2 sat at ticket level 33 with his tickets still at the
     * login spot, 9 chunks away, until a 15 s timeout). So the tickets are moved here, as vanilla does for a camera.
     */
    static void teleport(ServerPlayer sp, ServerLevel level, double x, double y, double z, Set<Relative> relatives,
                         float yaw, float pitch) {
        move(sp, level, x, y, z, relatives, yaw, pitch);
        // A co-op guest's own client gets the same hold, kept from the host's server (SimCoop.hold); nothing for him.
        SimCoop.hold(sp, x, y, z);
    }

    /** The teleport alone, without starting a co-op hold (the hold's own put-back uses it). Server thread. */
    static void move(ServerPlayer sp, ServerLevel level, double x, double y, double z, Set<Relative> relatives,
                     float yaw, float pitch) {
        sp.teleportTo(level, x, y, z, relatives, yaw, pitch, false);
        sp.fallDistance = 0;
        level.getChunkSource().move(sp);
    }

    /**
     * Server thread: the clear spot nearest {@code (x, y, z)} (feet coordinates), the same point when it already is
     * one. Searches 6 up and 4 down, then rings out to 4 sideways; with nothing standable the point is kept.
     */
    public static double[] clear(ServerLevel level, double x, double y, double z) {
        BlockPos at = BlockPos.containing(x, y, z);
        if (standable(level, at)) return new double[]{x, y, z};
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int r = 0; r <= 4; r++) {
            for (int dy : DY) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                        m.set(at.getX() + dx, at.getY() + dy, at.getZ() + dz);
                        if (standable(level, m)) {
                            LOGGER.info("[SimPhase] landing {} was not clear - moved to {}", at.toShortString(),
                                    m.toShortString());
                            return new double[]{x + dx, m.getY(), z + dz};
                        }
                    }
                }
            }
        }
        LOGGER.warn("[SimPhase] no clear standing spot within 4 of {} - landing there anyway", at.toShortString());
        return new double[]{x, y, z};
    }

    private static final int[] DY = {0, 1, -1, 2, -2, 3, -3, 4, -4, 5, 6};

    /** Feet and head free (no collision, no fluid) and something to stand on. */
    public static boolean standable(ServerLevel level, BlockPos feet) {
        BlockPos below = feet.below();
        return open(level, feet) && open(level, feet.above())
                && !level.getBlockState(below).getCollisionShape(level, below).isEmpty();
    }

    private static boolean open(ServerLevel level, BlockPos p) {
        var s = level.getBlockState(p);
        return s.getCollisionShape(level, p).isEmpty() && s.getFluidState().isEmpty();
    }

    /** Any thread: hold {@code uuid}'s client until it has the world round {@code at} ({x, y, z}). */
    public static void expect(UUID uuid, double[] at) {
        who = uuid;
        pending = at;
    }

    /** Whether a landing is still being waited on (the loading screen stays up for it). */
    public static boolean holding() { return pending != null; }

    /** 0..1: the share of the landing's chunks the client has. */
    static float fraction() { return fraction; }

    /** Number of chunks the hold waits for, for the loading screen's line. */
    static String detail() {
        int side = 2 * RADIUS + 1;
        return "Loading the area (" + Math.round(fraction * side * side) + " of " + side * side + " chunks)";
    }

    /** Client ticks without the landing's chunks before the server is asked to track them again. */
    private static final int STALL_TICKS = 40;

    /**
     * Server thread: the landing's chunks have not reached his client after {@link #STALL_TICKS}. Logs what the server
     * thinks (once per landing), so a stall says whether the chunk is unticketed or unsent.
     */
    private static void logStall(net.minecraft.server.MinecraftServer server, UUID id, double[] at) {
        ServerPlayer sp = server.getPlayerList().getPlayer(id);
        if (sp == null || !(sp.level() instanceof ServerLevel level)) return;
        int cx = (int) Math.floor(at[0]) >> 4;
        int cz = (int) Math.floor(at[2]) >> 4;
        var map = level.getChunkSource().chunkMap;
        var holder = map.getUpdatingChunkIfPresent(net.minecraft.world.level.ChunkPos.pack(cx, cz));
        LOGGER.warn("[SimPhase] landing chunk {},{} not on his client after {} ticks: server has him in chunk {}, his tickets"
                        + " at section {}, chunk loaded {}, tracked {}, ticket level {}", cx, cz, STALL_TICKS,
                sp.chunkPosition(), sp.getLastSectionPos(), level.getChunkSource().getChunkNow(cx, cz) != null,
                map.isChunkTracked(sp, cx, cz), holder == null ? -1 : holder.getTicketLevel());
    }

    /** Render thread, every client tick (one volatile read when nothing is held). */
    static void clientTick(Minecraft client) {
        double[] at = pending;
        if (at == null) return;
        if (at != seen) {   // a new landing: its own wait (these fields are the render thread's alone)
            seen = at;
            waited = 0;
            replaced = false;
            missedChunks = false;
        }
        if (client.level == null || client.player == null) {
            pending = null;
            return;
        }
        int cx = (int) Math.floor(at[0]) >> 4;
        int cz = (int) Math.floor(at[2]) >> 4;
        int have = 0;
        int side = 2 * RADIUS + 1;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                if (client.level.getChunkSource().hasChunk(cx + dx, cz + dz)) have++;
            }
        }
        fraction = have / (float) (side * side);
        boolean timedOut = ++waited > TIMEOUT_TICKS;
        if (have < side * side && !timedOut) {
            missedChunks = true;
            if (waited == STALL_TICKS && client.getSingleplayerServer() != null && who != null) {
                var srv = client.getSingleplayerServer();
                UUID id = who;
                srv.execute(() -> logStall(srv, id, at));
            }
            // Only now, so a /goto into chunks he already has never flashes the screen.
            if (McCompat.screen(client) == null) {
                SimWorld.resetBar();
                SimLoadingScreen.show(client, "Loading the area");
                showedScreen = true;
            }
            return;
        }
        if (timedOut) {
            LOGGER.warn("[SimPhase] the client still had {} of {} chunks round the landing after {} ticks - letting him in",
                    have, side * side, waited);
        }
        var server = client.getSingleplayerServer();
        UUID uuid = who;
        // Only after a wait for chunks (with them all there from the first tick he cannot have fallen), and only when he
        // is below the spot: one he left sideways was a later teleport (a test's /tp), not a fall.
        double dx = client.player.getX() - at[0], dz = client.player.getZ() - at[2];
        if (missedChunks && client.player.getY() < at[1] - 0.2 && dx * dx + dz * dz < 4 && !timedOut) {
            if (replaced || server == null || uuid == null) return;   // the put-back is on its way
            // He fell while the chunks were on their way: put him back on the spot. The
            // rotation is kept relative, so his look is untouched.
            replaced = true;
            server.execute(() -> {
                ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
                if (sp != null) {
                    teleport(sp, (ServerLevel) sp.level(), at[0], at[1], at[2], Set.of(Relative.Y_ROT, Relative.X_ROT), 0f, 0f);
                }
            });
            return;
        }
        if (pending == at) pending = null;
        LOGGER.info("[SimPhase] arrived at {} {} {} after {} tick(s) of waiting for chunks", Math.round(at[0]),
                Math.round(at[1]), Math.round(at[2]), waited);
        if (!SimWorld.isBuilding() && (showedScreen || McCompat.screen(client) instanceof SimLoadingScreen)) {
            SimLoadingScreen.dismiss(client);
        }
        showedScreen = false;
        missedChunks = false;
    }
}
