package com.killer560.hub.roomsim;

import com.killer560.hub.util.ModChat;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

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

    private static final Random RNG = new Random();

    /** Room-name fragments that mean no mimic can spawn there. Lower case; matched as substrings. */
    private static final String[] INELIGIBLE = {
        "entrance", "blood", "fairy", "puzzle", "trap", "boss"
    };

    /**
     * Every chest that could have been the mimic on this map.
     *
     * <p>Concurrent, because the two sides do not share a thread: the SERVER thread adds to it while a build
     * places chests, and the unguarded render callback in {@code SimMimicRenderer} iterates it every frame.
     * A plain LinkedHashSet there is a ConcurrentModificationException waiting for a build to run while he is
     * looking at the room - which is most of them. Insertion order is preserved, which the picker relies on.
     */
    private static final Set<BlockPos> CANDIDATES =
            java.util.Collections.newSetFromMap(new java.util.LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<BlockPos, Boolean> eldest) {
                    return false;
                }
            });

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
        if (CANDIDATES.isEmpty()) {
            return;
        }
        List<BlockPos> pool = new ArrayList<>(CANDIDATES);
        mimic = pool.get(RNG.nextInt(pool.size()));
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

    /** Candidates near the player, for drawing. Bounded so a big map does not redraw the world every frame. */
    public static List<BlockPos> candidatesNear(BlockPos around, double radius) {
        AABB box = new AABB(around).inflate(radius);
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p : CANDIDATES) {
            if (box.contains(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5)) {
                out.add(p);
            }
        }
        return out;
    }
}
