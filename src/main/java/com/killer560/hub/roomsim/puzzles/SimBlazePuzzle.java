package com.killer560.hub.roomsim.puzzles;

import com.killer560.hub.roomsim.SimState;
import com.killer560.hub.util.FeatureGuard;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A small standalone practice arena for the real "Lower Blaze" / "Higher Blaze" ("Higher or Lower") dungeon
 * puzzle, playable inside the room sim.
 *
 * <p><b>The kill-order rule is the real one, not invented.</b> {@code puzzlesolvers/BlazeSolverFeature}'s own
 * class doc (the mod's real, live-Hypixel-ported solver) states it plainly: "Lower Blaze must be solved by
 * killing the HIGHEST-HP blaze first, Higher Blaze the LOWEST-HP first (both real, confirmed Hypixel
 * mechanics, ported directly)". This arena reproduces the <b>Lower Blaze</b> half of that rule - highest
 * health dies first - since only one fixed order was asked for here; a Higher-Blaze (lowest-first) variant
 * would just reverse {@link #KILL_ORDER_INDICES}. The five HP values and the five stand positions themselves
 * are this file's own invention (there is no bundled data for a Blaze arena's layout, only for the real kill
 * rule), spaced out around {@code origin} purely so a player has to visibly aim at a specific one rather than
 * whichever stands in front.
 *
 * <p>Gated on {@link SimState#canAct} throughout, same boundary as the rest of {@code roomsim}. Every write to
 * the world or to an entity happens inside {@code server.execute(...)} on the integrated server, matching
 * {@code roomsim.SimMobs} - this class never touches an entity from the client thread.
 *
 * <p>Blazes are spawned {@code setNoAi(true)} - stationary, like every other sim dummy in {@code SimMobs}: a
 * kill-order puzzle tests aim and target selection, not whether the player can track a moving target, and a
 * dummy that drifted would make the same route mean something different each attempt.
 *
 * <p>Progress is polled once a client tick (registered through {@link FeatureGuard}, matching this mod's own
 * convention) rather than hooked off a damage/death event: with entities this class owns and orders itself,
 * "did the required next one die, or did a later one die first" is a full answer to "shot in order or not",
 * and needs no hitbox or projectile logic of its own - {@code AutoBlaze}'s existing solver-facing hit logic is
 * a separate concern (aiming), not this puzzle's (ordering).
 */
public final class SimBlazePuzzle {

    /** Distinct HP values, arbitrary but ordered so the required kill sequence reads as a plain countdown. */
    private static final float[] HEALTHS = {5f, 4f, 3f, 2f, 1f};

    /** Stand positions relative to {@code origin}, spread out at head height so no two are lined up. */
    private static final BlockPos[] OFFSETS = {
            new BlockPos(0, 3, 5),
            new BlockPos(4, 3, 2),
            new BlockPos(3, 3, -4),
            new BlockPos(-3, 3, -3),
            new BlockPos(-4, 3, 3),
    };

    /** Indices into {@link #HEALTHS}/{@link #OFFSETS}, sorted by health DESCENDING - the real Lower Blaze
     *  rule ("kill the HIGHEST-HP blaze first"), computed once since the health list never changes. */
    private static final int[] KILL_ORDER_INDICES = descendingByHealth();

    private static int[] descendingByHealth() {
        Integer[] idx = new Integer[HEALTHS.length];
        for (int i = 0; i < idx.length; i++) {
            idx[i] = i;
        }
        java.util.Arrays.sort(idx, (a, b) -> Float.compare(HEALTHS[b], HEALTHS[a]));
        int[] out = new int[idx.length];
        for (int i = 0; i < idx.length; i++) {
            out[i] = idx[i];
        }
        return out;
    }

    /** Spawned blazes' UUIDs, in REQUIRED KILL ORDER (index 0 = must die first). Empty when nothing is built. */
    private static volatile List<UUID> spawnedIds = List.of();

    /** How many of {@link #spawnedIds}, from the front, have already died in the correct order. */
    private static volatile int nextRequired = 0;

    private static volatile boolean complete = false;

    /** Last origin passed to {@link #build}, kept only so a bad kill can rebuild the same arena in place. */
    private static volatile BlockPos storedOrigin = null;

    private static boolean registered = false;

    private SimBlazePuzzle() {
    }

    /** Registers the progress-polling tick hook. Reports back rather than wiring itself into a screen/menu -
     *  see the class handoff note for where this still needs to be called from. */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("SimBlazePuzzle.tick", SimBlazePuzzle::tick));
    }

    /** Clears any previous arena and spawns a fresh one at {@code origin}. Server thread only. */
    public static void build(Minecraft client, BlockPos origin) {
        if (!SimState.canAct(client)) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        despawnCurrent(client);
        storedOrigin = origin;
        server.execute(() -> {
            ServerLevel level = server.overworld();
            UUID[] byPlacement = new UUID[HEALTHS.length];
            for (int i = 0; i < HEALTHS.length; i++) {
                BlockPos pos = origin.offset(OFFSETS[i]);
                SimBlazeEntity blaze = new SimBlazeEntity(EntityType.BLAZE, level);
                blaze.getAttribute(Attributes.MAX_HEALTH).setBaseValue(HEALTHS[i]);
                blaze.setHealth(HEALTHS[i]);
                blaze.setPersistenceRequired();
                blaze.setNoAi(true);
                blaze.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
                level.addFreshEntity(blaze);
                byPlacement[i] = blaze.getUUID();
            }
            List<UUID> ordered = new ArrayList<>(HEALTHS.length);
            for (int idx : KILL_ORDER_INDICES) {
                ordered.add(byPlacement[idx]);
            }
            spawnedIds = List.copyOf(ordered);
            nextRequired = 0;
            complete = false;
        });
    }

    /** True once every blaze has died in the required order. */
    public static boolean isComplete() {
        return complete;
    }

    /** Despawns whatever is left of the current arena and clears progress. Takes no arguments - grabs the
     *  client singleton the same way {@code SimAbilities}'s item-use handler does, since the three-method
     *  shape asked for here has no room for one. */
    public static void reset() {
        Minecraft client = Minecraft.getInstance();
        if (!SimState.canAct(client)) {
            return;
        }
        despawnCurrent(client);
    }

    private static void despawnCurrent(Minecraft client) {
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        List<UUID> ids = spawnedIds;
        spawnedIds = List.of();
        nextRequired = 0;
        complete = false;
        if (ids.isEmpty()) {
            return;
        }
        server.execute(() -> {
            ServerLevel level = server.overworld();
            for (UUID id : ids) {
                Entity entity = level.getEntity(id);
                if (entity != null) {
                    entity.discard();
                }
            }
        });
    }

    private static void tick(Minecraft client) {
        if (!SimState.canAct(client)) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        List<UUID> ids = spawnedIds;
        if (ids.isEmpty() || complete) {
            return;
        }
        server.execute(() -> checkProgress(client, server, ids));
    }

    /**
     * Advances {@link #nextRequired} past any blazes that died in order, then checks whether any blaze BEYOND
     * that point has also died - which can only mean the player shot one out of turn - and if so, wipes the
     * arena and rebuilds it fresh at {@link #storedOrigin}. Server thread only.
     */
    private static void checkProgress(Minecraft client, MinecraftServer server, List<UUID> ids) {
        ServerLevel level = server.overworld();
        int idx = nextRequired;
        while (idx < ids.size() && isDead(level, ids.get(idx))) {
            idx++;
        }
        if (idx > nextRequired) {
            nextRequired = idx;
            if (nextRequired >= ids.size()) {
                complete = true;
                return;
            }
        }
        for (int j = nextRequired; j < ids.size(); j++) {
            if (isDead(level, ids.get(j))) {
                // Out-of-order kill: a later blaze died while an earlier-required one is still alive.
                failAndRebuild(client, server, level, ids);
                return;
            }
        }
    }

    private static boolean isDead(ServerLevel level, UUID id) {
        Entity entity = level.getEntity(id);
        return entity == null || !entity.isAlive();
    }

    private static void failAndRebuild(Minecraft client, MinecraftServer server, ServerLevel level, List<UUID> ids) {
        // Tells the Architect's First Draft feature a puzzle failed, so his existing
        // auto-get setting works in here the same as it does on Hypixel.
        SimPuzzles.reportFail("Blaze");
        for (UUID id : ids) {
            Entity entity = level.getEntity(id);
            if (entity != null && entity.isAlive()) {
                entity.discard();
            }
        }
        spawnedIds = List.of();
        nextRequired = 0;
        complete = false;
        BlockPos origin = storedOrigin;
        if (origin != null) {
            client.execute(() -> build(client, origin));
        }
    }

    /** Plain {@link Blaze} minus the peaceful-discard half of {@code checkDespawn()} - same reasoning and same
     *  fix as {@code SimMobs}'s {@code SimZombie}/{@code SimSkeleton}: {@code Mob.checkDespawn()} discards any
     *  hostile mob not allowed in peaceful before it ever looks at persistence, and the sim world runs on
     *  {@code Difficulty.PEACEFUL}. */
    private static final class SimBlazeEntity extends Blaze {
        SimBlazeEntity(EntityType<? extends Blaze> type, Level level) {
            super(type, level);
        }

        @Override
        public void checkDespawn() {
        }
    }
}
