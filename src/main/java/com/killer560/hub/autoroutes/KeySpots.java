package com.killer560.hub.autoroutes;

import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.roomdatabase.RoomDatabase;
import com.killer560.hub.roomdatabase.RoomEntry;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Redstone Key's possible spots in the room a route runs in, and which of them the key never spawned at.
 *
 * <p>killer560, 2026-10-18: "Make it for redstone skulls specifically that in some rooms there are two spots they could be
 * in, so for auto routes it is a pain to make stuff check both because you need an await at each but then one you
 * manually have to click. SO make it such that if a redstone skull could be clicked from a spot but it didnt spawn there
 * then that spot has 1 await prefilled." The room database lists two key spots for Redstone Key (and one each for Golden
 * Oasis and Redstone Crypt); Hypixel puts the key in one of them. So a node with a secret await, standing where one of a
 * room's two or more key spots is in click reach (eye to the spot's block box within
 * {@link com.killer560.hub.cheatutils.CheatUtilsConfig#MEASURED_MAX_REACH}), starts its await one secret on for every
 * such spot that holds no key now and never held one this run ({@code RouteExecutor.beginAction}).
 *
 * <p>"Never held one" is what tells a spot the key was never at from one it was taken from: every tick in the room each
 * spot is looked at, and a key head seen there (or his click on it) marks it for the run. Any player head at the spot -
 * one whose block entity has not told the client its profile yet included - counts as not empty, so a spot is only ever
 * called empty when it plainly is.
 */
public final class KeySpots {

    private static final org.slf4j.Logger LOGGER = com.killer560.hub.util.ModLog.get("killer560smod-autoroutes");

    /** Spots a key head was seen at this run. */
    private static final Set<Long> SEEN = new HashSet<>();
    private static Object level;
    private static int generation = Integer.MIN_VALUE;

    private KeySpots() {
    }

    private static void check(Minecraft client) {
        int gen = LiveMapFeature.resetGeneration();
        if (client.level != level || gen != generation) {
            if (!SEEN.isEmpty()) {
                LOGGER.info("[AutoRoutes] key spots: new run (generation {}) - forgetting {} seen key spot(s)", gen,
                        SEEN.size());
            }
            level = client.level;
            generation = gen;
            SEEN.clear();
        }
    }

    /** The room's key spots in the world, in database order; empty for a room without a key or not in the database. */
    static List<BlockPos> spots(RouteCoords.Frame frame) {
        List<BlockPos> out = new ArrayList<>();
        if (frame == null) {
            return out;
        }
        RoomEntry entry = RoomDatabase.lookupByName(frame.roomName());
        if (entry == null || entry.secretCoords == null || entry.secretCoords.redstoneKey == null) {
            return out;
        }
        for (RoomEntry.Pos p : entry.secretCoords.redstoneKey) {
            out.add(RouteCoords.toRealBlock(frame, new BlockPos(p.x, p.y, p.z)));
        }
        return out;
    }

    private static final BlockPos[] NO_SPOTS = new BlockPos[0];
    private static RouteCoords.Frame tickFrame;
    private static int tickShift;
    private static BlockPos[] tickSpots = NO_SPOTS;

    /**
     * {@link #spots} for the per-tick scan, kept while the room and the sim's height shift stay the same: nearly every
     * room has no key spot, and this ran a list, a database lookup and a loop for it every tick of a clear. A room the
     * database cannot name yet is not remembered (it may load a moment later).
     */
    private static BlockPos[] tickSpots(RouteCoords.Frame frame) {
        int shift = com.killer560.hub.livemap.DungeonLayout.simYOffset();
        if (frame.equals(tickFrame) && shift == tickShift) {
            return tickSpots;
        }
        RoomEntry entry = RoomDatabase.lookupByName(frame.roomName());
        BlockPos[] found = spots(frame).toArray(NO_SPOTS);
        if (entry != null) {
            tickFrame = frame;
            tickShift = shift;
            tickSpots = found;
        }
        return found;
    }

    /** Every client tick: a key head standing on one of this room's spots marks it for the run. */
    static void tick(Minecraft client) {
        if (client.level == null) {
            return;
        }
        check(client);
        RouteCoords.Frame frame = RouteCoords.Frame.current();
        if (frame == null) {
            return;
        }
        for (BlockPos s : tickSpots(frame)) {
            if (client.level.isLoaded(s) && com.killer560.hub.secrets.SecretsFeature.isRedstoneKey(client.level, s)) {
                if (SEEN.add(s.asLong())) {
                    LOGGER.info("[AutoRoutes] key spots: key head seen at {} in {} - marked for this run", s.toShortString(),
                            frame.roomName());
                }
            }
        }
    }

    /** His click on a key skull: the key was there. */
    static void noteKeyAt(Minecraft client, BlockPos pos) {
        check(client);
        if (SEEN.add(pos.asLong())) {
            LOGGER.info("[AutoRoutes] key spots: your click on the key at {} marks it for this run", pos.toShortString());
        }
    }

    /**
     * The spots of a room with two or more that a player standing at {@code feet} could click and that the key never
     * spawned at: loaded, no player head there now, none seen there this run.
     */
    static List<BlockPos> emptyInReach(Minecraft client, RouteCoords.Frame frame, Vec3 feet) {
        List<BlockPos> out = new ArrayList<>();
        if (client.level == null || feet == null) {
            return out;
        }
        check(client);
        List<BlockPos> spots = spots(frame);
        if (spots.size() < 2) {
            return out;
        }
        Vec3 eye = feet.add(0, 1.62, 0);
        double reach = com.killer560.hub.cheatutils.CheatUtilsConfig.MEASURED_MAX_REACH;
        StringBuilder why = new StringBuilder();
        for (BlockPos s : spots) {
            if (SEEN.contains(s.asLong()) || !client.level.isLoaded(s) || boxDistance(eye, s) > reach) {
                why.append(' ').append(s.toShortString()).append(SEEN.contains(s.asLong()) ? "=key seen"
                        : !client.level.isLoaded(s) ? "=not loaded" : "=out of reach");
                continue;
            }
            BlockState there = client.level.getBlockState(s);
            if (there.is(Blocks.PLAYER_HEAD) || there.is(Blocks.PLAYER_WALL_HEAD)) {
                why.append(' ').append(s.toShortString()).append("=head there");
                continue;
            }
            why.append(' ').append(s.toShortString()).append("=empty, prefills 1");
            out.add(s);
        }
        LOGGER.info("[AutoRoutes] key spots in {} from {}: {} of {} empty in reach -{}",
                frame == null ? "?" : frame.roomName(),
                String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f", feet.x, feet.y, feet.z), out.size(), spots.size(),
                why);
        return out;
    }

    /** From a point to the nearest point of a block's unit box. */
    static double boxDistance(Vec3 from, BlockPos b) {
        double dx = Math.max(0, Math.max(b.getX() - from.x, from.x - (b.getX() + 1)));
        double dy = Math.max(0, Math.max(b.getY() - from.y, from.y - (b.getY() + 1)));
        double dz = Math.max(0, Math.max(b.getZ() - from.z, from.z - (b.getZ() + 1)));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** For the testkit: what the rule says for a node standing at {@code feet} in the room he is in now. */
    public static List<BlockPos> emptyInReachForTest(Vec3 feet) {
        Minecraft client = Minecraft.getInstance();
        return emptyInReach(client, RouteCoords.Frame.current(), feet);
    }
}
