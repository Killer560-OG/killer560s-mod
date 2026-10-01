package com.killer560.hub.roomsim;

import com.killer560.hub.util.ModChat;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * One mimic per map, and a way to see which chests could have been it.
 *
 * <p>killer560 (2026-09-28): "make sure it has 1 mimic per map with mimic logic and make it customly on that
 * sim show any chest that can be a mimic. Not all chests can but if they can label them differently than
 * regular chests."
 *
 * <p>The second half is the useful half, and it is something the real game cannot give him. On Hypixel you find
 * the mimic by opening chests until one bites. Here every chest that was ELIGIBLE is marked, so he can see the
 * shape of the problem - which rooms were ever in play and which chests he can stop checking - and practise the
 * route that covers them rather than the route he happened to take.
 *
 * <p><b>Which chests are eligible.</b> A mimic does not appear in the entrance, in blood, in a fairy room, in a
 * puzzle or in a trap. Those are recognised from the ROOM NAME, which is what the sim has, and the matching is
 * deliberately generous: a room wrongly excluded silently shrinks the candidate set and would teach him to skip
 * a chest that really can bite, which is the more expensive mistake of the two.
 */
public final class SimMimic {

    private static final org.slf4j.Logger LOGGER = com.killer560.hub.util.ModLog.get("killer560smod-roomsim");

    private static final Random RNG = new Random();

    /** Room-name fragments that mean no mimic can spawn there. Lower case; matched as substrings. */
    private static final String[] INELIGIBLE = {
        "entrance", "blood", "fairy", "puzzle", "trap", "boss"
    };

    /**
     * Every chest that could have been the mimic on this map.
     *
     * <p>Concurrent, and ACTUALLY concurrent, which an earlier version only claimed to be: it was
     * {@code Collections.newSetFromMap(new LinkedHashMap<>(){...})} with a comment saying it existed to stop a
     * ConcurrentModificationException, and {@code newSetFromMap} adds no synchronisation at all. Kept concurrent
     * now that {@code SimMimicRenderer} is gone, because the two sides still do not share a thread: a build's
     * completion callback adds secret chests from the integrated SERVER thread while the picker reads the set.
     * A CopyOnWriteArraySet is genuinely safe to iterate under concurrent writes and keeps the insertion order
     * the picker relies on. The set is a few dozen chests, so copy-on-write costs nothing here.
     */
    private static final Set<BlockPos> CANDIDATES = new java.util.concurrent.CopyOnWriteArraySet<>();

    /** The one that actually is. */
    private static BlockPos mimic;
    private static boolean found;

    /** Chests already counted, so re-opening one does not count twice. */
    private static final Set<BlockPos> OPENED = new LinkedHashSet<>();

    private SimMimic() {
    }

    /** Hooks chest interaction so opening one can be the mimic. */
    public static void register() {
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            Minecraft client = Minecraft.getInstance();
            if (SimState.canAct(client) && player == client.player) {
                net.minecraft.core.BlockPos pos = hit.getBlockPos();
                // A chest is a secret whether or not it bites. Counted here rather than in a second hook: one
                // place that sees a chest click is easier to keep honest than two that must agree.
                if (level.getBlockState(pos).is(Blocks.CHEST) && OPENED.add(pos.immutable())) {
                    SimScore.secretFound();
                }
                onChestOpened(client, pos);
            }
            // Never consumes the interaction: the chest should still open. This only decides whether it bites.
            return net.minecraft.world.InteractionResult.PASS;
        });
    }

    /** Whether a room of this name can hold the mimic. */
    public static boolean roomEligible(String roomName) {
        if (roomName == null) {
            return false;
        }
        String lower = roomName.toLowerCase(Locale.ROOT);
        for (String bad : INELIGIBLE) {
            if (lower.contains(bad)) {
                return false;
            }
        }
        return true;
    }

    /** Forgets the map's mimic, for a new one. */
    public static void reset() {
        CANDIDATES.clear();
        OPENED.clear();
        mimic = null;
        found = false;
    }

    /** Records a chest that could be the mimic, as a room is placed. */
    public static void addCandidate(BlockPos pos) {
        CANDIDATES.add(pos.immutable());
    }

    /**
     * Picks the one chest that is the mimic, once the whole map is placed.
     *
     * <p>Chosen after everything is down rather than while placing, because "one per map" cannot be decided a
     * room at a time - picking as you go gives the first eligible room a far better chance than the last.
     */
    public static void chooseForMap() {
        mimic = null;
        found = false;
        if (!floorHasMimic()) {
            LOGGER.info("Sim mimic: none on {} - mimics start at Floor 5", SimState.floorLabel());
            return;
        }
        if (CANDIDATES.isEmpty()) {
            // Loud, because "exactly one per run" is his rule and a run with none is the rule being broken,
            // not a quiet variation. It means every room on the floor was ineligible or chestless, which is a
            // generator problem rather than a mimic one.
            LOGGER.warn("Sim mimic: {} should have one and there is no eligible chest on the floor to be it",
                    SimState.floorLabel());
            return;
        }
        List<BlockPos> pool = new ArrayList<>(CANDIDATES);
        mimic = pool.get(RNG.nextInt(pool.size()));
        LOGGER.info("Sim mimic: 1 of {} candidate chest(s) on {}, at {}",
                pool.size(), SimState.floorLabel(), mimic);
    }

    /**
     * Whether this floor gets a mimic at all.
     *
     * <p>killer560 (2026-10-01): "always and only make 1 mimic per run. except if you are on floor 4 or below
     * then it should never have one." So it is a property of the FLOOR, not of chance: F5 and up have exactly
     * one, everything below has none, and there is no roll either way.
     *
     * <p>Note this is Floor 5, one lower than {@code SimScore}'s bonus note (which says VI and above, from the
     * wiki). His rule is the one implemented, because the sim is his practice tool; the two are flagged here
     * rather than quietly reconciled.
     */
    private static boolean floorHasMimic() {
        String floor = SimState.floorLabel();
        if (floor == null || floor.length() < 2 || floor.charAt(0) != 'F') {
            return false;   // the Entrance, and anything that did not name a floor
        }
        try {
            return Integer.parseInt(floor.substring(1)) >= 5;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * The grid cell the mimic's chest is in, or -1 - so the map can paint that room.
     *
     * <p>killer560 (2026-10-01): "make that room the red that the normal map uses as well for the map." The
     * map's own mimic highlight tests for a live {@code trapped_chest} at a database secret position, which a
     * sim never has: the sim's mimic is an ordinary chest that one of them happens to be. So the sim says
     * which cell instead, and {@code MapPainter} reads it.
     */
    public static int mimicCell() {
        BlockPos pos = mimic;
        if (pos == null) {
            return -1;
        }
        int best = -1;
        long bestDist = Long.MAX_VALUE;
        int cells = com.killer560.hub.livemap.DungeonLayout.GRID * com.killer560.hub.livemap.DungeonLayout.GRID;
        for (int i = 0; i < cells; i++) {
            BlockPos centre = com.killer560.hub.livemap.DungeonLayout.cellCenter(i);
            long dx = centre.getX() - pos.getX();
            long dz = centre.getZ() - pos.getZ();
            long dist = dx * dx + dz * dz;
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return best;
    }

    public static boolean hasMimic() {
        return mimic != null;
    }

    public static int candidateCount() {
        return CANDIDATES.size();
    }

    /** Every chest that could have been the mimic, for the highlighter. */
    public static Set<BlockPos> candidates() {
        return Set.copyOf(CANDIDATES);
    }

    public static boolean isCandidate(BlockPos pos) {
        return CANDIDATES.contains(pos);
    }

    /**
     * Called when a chest is opened. Returns whether it was the mimic.
     *
     * <p>Finding it counts toward the score's bonus, the same two points it is worth on a real floor - which is
     * the only reason a mimic matters to a 300 run and therefore the only reason it is modelled.
     */
    public static boolean onChestOpened(Minecraft client, BlockPos pos) {
        if (found || mimic == null || !mimic.equals(pos)) {
            return false;
        }
        found = true;
        SimScore.mimicKilled();
        var server = client.getSingleplayerServer();
        if (server != null) {
            server.execute(() -> {
                var level = server.overworld();
                // The chest becomes a mimic: on Hypixel it is a zombie in a chest. A stationary starred mob at
                // the chest is as close as the sim gets, and it counts for the bonus either way.
                level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            });
            SimMobs.spawnStarred(client, pos.above(), SimMobs.Kind.ZOMBIE);
        }
        ModChat.send("Sim", ModChat.text("MIMIC found - "), ModChat.value("+2 bonus"));
        return true;
    }

    // candidatesNear() is gone with SimMimicRenderer. It fed the only thing that ever drew the candidates, and
    // that highlighter was removed on 2026-09-30 because it outlined every secret chest on the floor with no
    // regard for the Secret Waypoints setting. The candidate SET stays - the picker is what needs it.
}
