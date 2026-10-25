package com.killer560.hub.roomsim;


import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
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
 * shape of the problem - which rooms were ever in play and which chests he can stop checking - and practice the
 * route that covers them rather than the route he happened to take.
 *
 * <p><b>Which chests are eligible.</b> A mimic does not appear in the entrance, in blood, in a fairy room, in a
 * puzzle or in a trap. Those are recognized from the ROOM NAME, which is what the sim has, and the matching is
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
     * ConcurrentModificationException, and {@code newSetFromMap} adds no synchronization at all. Kept concurrent
     * now that {@code SimMimicRenderer} is gone, because the two sides still do not share a thread: a build's
     * completion callback adds secret chests from the integrated SERVER thread while the picker reads the set.
     * A CopyOnWriteArraySet is genuinely safe to iterate under concurrent writes and keeps the insertion order
     * the picker relies on. The set is a few dozen chests, so copy-on-write costs nothing here.
     */
    private static final Set<BlockPos> CANDIDATES = new java.util.concurrent.CopyOnWriteArraySet<>();

    /** The one that actually is. */
    private static volatile BlockPos mimic;
    private static volatile boolean found;
    /** The mimic itself once its chest is opened - a baby zombie, as on Hypixel. Server thread. */
    private static volatile java.util.UUID mimicMob;
    /** Where the mimic mob was last seen (server thread), to tell "dead" from "in a section not ticking". */
    private static volatile BlockPos mimicAt;

    /** Chests already counted, so re-opening one does not count twice. */
    // Added to on the render thread (the chest-open hook below), cleared on the server thread by a build's reset.
    private static final Set<BlockPos> OPENED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Puzzle reward chests (Water Board's, Boulder's): opening one is the puzzle's reward, not a secret - the room
     * database lists no secrets for either room, so counting them made the run's secret total read high. Added on
     * the server thread when the puzzle places the chest, read on the render thread by the hook below. Not cleared
     * by {@link #reset}: a puzzle can place its chest before a build's reset runs, so each puzzle removes its own.
     */
    private static final Set<BlockPos> PUZZLE_REWARDS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Marks a puzzle's reward chest, so opening it is not counted as a secret. */
    public static void markPuzzleReward(BlockPos pos) {
        PUZZLE_REWARDS.add(pos.immutable());
    }

    /** Forgets a puzzle reward chest the puzzle has taken away (or whose floor is gone). */
    public static void unmarkPuzzleReward(BlockPos pos) {
        PUZZLE_REWARDS.remove(pos);
    }

    private SimMimic() {
    }

    /** Hooks chest interaction so opening one can be the mimic. */
    public static void register() {
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            // ON THE SERVER, when the use-on-block packet arrives - the sim answers packets, as Hypixel's server does.
            // This was the CLIENT's copy of the event, which queued the mimic's spawn onto the server from the render
            // thread; a click that did not go through the client's gameMode (Secret Aura's), followed a tick later by
            // Kill Mimic's Hyperion, could then reach the server BEFORE the mimic existed, and the one Wither Impact
            // hit nothing (2026-10-11, testkit 96-ar-644: the mimic left alive in one aura run of five, and the log
            // showing the Kill Mimic use before "mimic spawned").
            if (player instanceof net.minecraft.server.level.ServerPlayer sp && SimAbilities.simServer(sp)) {
                net.minecraft.core.BlockPos pos = hit.getBlockPos();
                // A chest is a secret whether or not it bites. Counted here rather than in a second hook: one
                // place that sees a chest click is easier to keep honest than two that must agree.
                if ((level.getBlockState(pos).is(Blocks.CHEST) || level.getBlockState(pos).is(Blocks.TRAPPED_CHEST))
                        && !PUZZLE_REWARDS.contains(pos) && OPENED.add(pos.immutable())) {
                    SimScore.secretFound(pos.immutable());
                }
                openOnServer((net.minecraft.server.level.ServerLevel) level, pos);
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
        // A puzzle's name rarely says "puzzle" (Tic Tac Toe, Quiz, Boulder...): ask the room database for its type.
        // Tic Tac Toe's reward chest bit in fullclear-1016 run 047, and Kill Mimic then used the sceptre for 5 s while
        // Auto Tic Tac Toe warped him back to the board, 9 blocks off.
        com.killer560.hub.roomdatabase.RoomEntry entry = com.killer560.hub.roomdatabase.RoomDatabase.lookupByName(roomName);
        if (entry != null && entry.type != null) {
            String type = entry.type.toLowerCase(Locale.ROOT);
            for (String bad : INELIGIBLE) {
                if (type.contains(bad)) {
                    return false;
                }
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
        mimicMob = null;
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
    public static void chooseForMap(net.minecraft.server.level.ServerLevel level) {
        chooseForMap(level, null);
    }

    /** The chest chosen as this map's mimic, or null when it has none. */
    public static BlockPos chosen() {
        return mimic;
    }

    /**
     * As {@link #chooseForMap(net.minecraft.server.level.ServerLevel)}, but {@code prefer} is the mimic when it is
     * still a candidate: {@code /reset} rebuilds a solo room exactly as it was loaded, mimic included.
     */
    public static void chooseForMap(net.minecraft.server.level.ServerLevel level, BlockPos prefer) {
        mimic = null;
        found = false;
        mimicMob = null;
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
        // On Hypixel the mimic is always one of the room's SECRET chests - a database position - and that is where the
        // map's mimic check (MapPainter.hasLiveMimic) and Secret Waypoints look for its trapped chest. A chest baked
        // into a capture away from every database position could be picked before 2026-10-13, and the map then had
        // to be told the cell by a sim-only branch. Only when no secret chest is a candidate does any chest do.
        List<BlockPos> pool = new ArrayList<>();
        for (BlockPos c : CANDIDATES) {
            if (SimSecrets.PLACED_CHESTS.contains(c)) {
                pool.add(c);
            }
        }
        if (pool.isEmpty()) {
            pool.addAll(CANDIDATES);
        }
        mimic = prefer != null && pool.contains(prefer) ? prefer : pool.get(RNG.nextInt(pool.size()));
        LOGGER.info("Sim mimic: 1 of {} candidate chest(s) on {}, at {}",
                pool.size(), SimState.floorLabel(), mimic);
        // On Hypixel the mimic's chest is a TRAPPED chest - that is how the map, Secret Waypoints and Auto Routes'
        // Kill Mimic tell it apart - so the chosen one becomes one, facing the way it did.
        var state = level.getBlockState(mimic);
        if (SimSecretItems.trapPendingChest(mimic)) {
            // A secret chest that appears when he comes near (2026-10-18): it appears as the trapped one.
            LOGGER.info("Sim mimic: its chest appears when he comes near it");
        } else if (state.is(Blocks.CHEST)) {
            level.setBlockAndUpdate(mimic, Blocks.TRAPPED_CHEST.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.ChestBlock.FACING,
                            state.getValue(net.minecraft.world.level.block.ChestBlock.FACING)));
        }
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
     * The chest at {@code pos} was opened, on the server thread: when it is the mimic, it bites, in the same tick.
     *
     * <p>As on Hypixel: the trapped chest goes and a baby zombie - the mimic - stands where it was. The mimic counts
     * toward the score's bonus (the same two points it is worth on a real floor) when it DIES, not when the chest
     * opens ({@link #tickServer}), so a client feature that watches the mimic die (the score calculator, Auto Routes'
     * Kill Mimic) has something to see.
     */
    static boolean openOnServer(net.minecraft.server.level.ServerLevel level, BlockPos pos) {
        if (found || mimic == null || !mimic.equals(pos)) {
            return false;
        }
        found = true;
        BlockPos at = pos.immutable();
        level.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
        mimicMob = SimMobs.spawnMimic(level, at);
        mimicAt = at;
        // Logged, never said: Hypixel sends no chat line for a mimic (killer560, 2026-10-16: "in sim remove the mimic line").
        LOGGER.info("Sim mimic: chest at {} opened, mimic {} spawned", at.toShortString(), mimicMob);
        return true;
    }

    /** Server thread, every tick (from {@code SimMobs}): the mimic's death is the kill. */
    static void tickServer(net.minecraft.server.level.ServerLevel level) {
        java.util.UUID id = mimicMob;
        if (id == null) {
            return;
        }
        var e = level.getEntity(id);
        if (e == null && (mimicAt == null || !level.isPositionEntityTicking(mimicAt))) {
            return;   // missing in a section the server is not ticking: unknown, not killed
        }
        if (e != null) {
            mimicAt = e.blockPosition();
        }
        if (e == null || !e.isAlive()) {
            mimicMob = null;
            SimScore.mimicKilled();
            LOGGER.info("Sim mimic: killed");
        }
    }

    /** Its mob was discarded (a run restart), not killed - so it must not score. */
    static void forgetMob() {
        mimicMob = null;
    }

    /** The live mimic's id, or null - for tests. */
    public static java.util.UUID mimicMob() {
        return mimicMob;
    }

    /** Where the mimic's chest is (or was), or null. */
    public static BlockPos position() {
        return mimic;
    }
}
