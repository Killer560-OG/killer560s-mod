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
import com.killer560.hub.compat.McEntities;

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

    /**
     * The blazes stand in a VERTICAL CHAIN up the middle, not a ring at head height.
     *
     * <p>killer560 (2026-10-01): "For higher/lower blaze you need to space the blazes out along the vertical
     * chain in the middle." That is the real room: a shaft with the blazes at different heights, which is what
     * makes Higher and Lower different puzzles in the first place - you are picking by height as well as by HP.
     * The old ring put all five at {@code y+3} spread around in x and z, which is a different puzzle.
     *
     * <p>Spacings are tried in order and the first that fits the room's own centre column wins, so a shorter
     * shaft still gets a chain rather than no blazes at all - see {@link #bindAt}.
     */
    private static final int[] SPACINGS = {3, 2};

    /** The chain for one spacing: straight up, {@code spacing} blocks apart, from the origin. */
    private static BlockPos[] offsetsFor(int spacing) {
        BlockPos[] out = new BlockPos[HEALTHS.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = new BlockPos(0, i * spacing, 0);
        }
        return out;
    }

    /** How tall the chain is for a spacing, in blocks of clearance needed above the origin. */
    private static int chainHeight(int spacing) {
        return (HEALTHS.length - 1) * spacing + 2;
    }

    /** The spacing the current arena was built with. */
    private static volatile int spacing = SPACINGS[0];

    /**
     * The name the solver reads.
     *
     * <p>{@code BlazeSolverFeature} matches {@code ^\[Lv\d+].*Blaze [\d,]+/([\d,]+)❤$} against
     * {@code entity.getName().getString()} and orders by the captured MAX HP, so a sim blaze with no custom name
     * is invisible to it however it is arranged - killer560 (2026-10-01): "they need to have lables to know what
     * order to shoot them in for my solver to pick up."
     *
     * <p>The numbers are this arena's own {@link #HEALTHS}, so the order the solver computes from the labels is
     * the same order {@link #KILL_ORDER_INDICES} requires. Using prettier, more Hypixel-looking HP values would
     * have let the two disagree, which is the one thing a practice target must not do.
     */
    private static net.minecraft.network.chat.Component blazeLabel(float health) {
        int hp = (int) health;
        return net.minecraft.network.chat.Component.literal("[Lv1] Blaze " + hp + "/" + hp + "\u2764");
    }

    /** Indices into {@link #HEALTHS} and the chain positions, sorted by health DESCENDING - the real Lower Blaze
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
    /**
     * How many blazes are currently standing in the arena.
     *
     * <p>For scenario 78, which cannot count them out of the world: a {@code getEntitiesOfClass} query over
     * the arena returns nothing at all in a gametest client, for every puzzle, while this puzzle's own log
     * says it spawned five. Rather than assert on an instrument that reads zero whatever is there, the test
     * asks the puzzle - and the puzzle only counts a blaze the level actually accepted.
     */
    public static int spawnedCount() {
        return spawnedIds == null ? 0 : spawnedIds.size();
    }

    public static void build(Minecraft client, BlockPos origin) {
        // A standalone arena, not a bind to a captured room, and always the Lower Blaze half of the rule -
        // which is what this method has always drilled.
        boundOrigin = null;
        lowestFirst = false;
        rebuild(client, origin);
    }

    /** Spawns a fresh arena at {@code origin} in whichever kill order the current arena is drilling. */
    private static void rebuild(Minecraft client, BlockPos origin) {
        if (!SimState.canAct(client)) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        despawnCurrent(client);
        storedOrigin = origin;
        final boolean higher = lowestFirst;
        final BlockPos[] chain = offsetsFor(spacing);
        server.execute(() -> {
            ServerLevel level = server.overworld();
            UUID[] byPlacement = new UUID[HEALTHS.length];
            for (int i = 0; i < HEALTHS.length; i++) {
                BlockPos pos = origin.offset(chain[i]);
                SimBlazeEntity blaze = new SimBlazeEntity(McEntities.BLAZE, level);
                blaze.getAttribute(Attributes.MAX_HEALTH).setBaseValue(HEALTHS[i]);
                blaze.setHealth(HEALTHS[i]);
                blaze.setPersistenceRequired();
                blaze.setNoAi(true);
                // The label is what BlazeSolverFeature reads - see blazeLabel.
                blaze.setCustomName(blazeLabel(HEALTHS[i]));
                blaze.setCustomNameVisible(true);
                blaze.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
                if (!level.addFreshEntity(blaze)) {
                    // Said out loud rather than silently skipped. A blaze arena with no blazes in it looks
                    // exactly like a puzzle that was never built, and scenario 78 found this puzzle building
                    // nothing with nothing in the log to say why.
                    com.killer560.hub.util.ModLog.get("killer560smod-roomsim")
                            .warn("Sim blaze puzzle: the level refused a blaze at {}", pos);
                    continue;
                }
                byPlacement[i] = blaze.getUUID();
            }
            List<UUID> ordered = new ArrayList<>(HEALTHS.length);
            for (int idx : killOrder(higher)) {
                ordered.add(byPlacement[idx]);
            }
            com.killer560.hub.util.ModLog.get("killer560smod-roomsim")
                    .info("Sim blaze puzzle: {} blaze(s) spawned at {}", ordered.size(), origin);
            spawnedIds = List.copyOf(ordered);
            nextRequired = 0;
            complete = false;
        });
    }

    /**
     * Arms this puzzle inside a REAL captured Higher Blaze or Lower Blaze room.
     *
     * <p><b>This is the one puzzle where the geometry genuinely is not in the capture, and could not be.</b>
     * A blaze is an entity; a room capture is blocks. Nothing in this repo's bundled data says where a blaze
     * stands either - {@code BlazeSolverFeature} reads live entities and has no position table - so the five
     * stand positions stay this file's own invention, as its class doc already says. What changes is only
     * WHERE they are invented: an air pocket found by scanning the room's own centre column, instead of four
     * blocks in front of wherever he was standing when he typed a command.
     *
     * <p>The kill order does change with the room, and that part is real: {@code BlazeSolverFeature}'s class
     * doc states both halves of the rule - Lower Blaze is highest-HP first, Higher Blaze is lowest-HP first -
     * so {@code higher} reverses the required order rather than always drilling the Lower half.
     *
     * <p>Server thread only; called from {@code SimBuilder}'s post-build block.
     *
     * @param higher true for Higher Blaze (lowest HP dies first), false for Lower Blaze (highest first)
     * @return whether the puzzle was armed
     */
    public static boolean bindAt(net.minecraft.server.level.ServerLevel level,
                                 com.killer560.hub.roomsim.SimRoomPuzzles.Placement p, boolean higher) {
        com.killer560.hub.roomsim.SimRoomPuzzles.Anchor anchor = p.anchor();
        // ANCHORED ON THE FLOOR, with the chain fitted to whatever headroom is above it.
        //
        // This searched for the first gap tall enough for the whole chain, and that was a regression: a chain of
        // five at spacing 3 needs 14 clear blocks, so the search sailed past the floor and found the first
        // 14-block gap higher up. killer560's log has them at world y=6 on a floor shifted -78 - fourteen blocks
        // over his head, which read as "lower blaze still isnt generating any mobs" even though the same line
        // says five spawned. Before the chain existed it only wanted 6 blocks and landed near the ground.
        //
        // So: find the standing floor first, then fit the chain into the air above it. The widest spacing that
        // fits wins, and spacing 1 always does, so this can no longer fail to arm a room it used to arm.
        BlockPos found = null;
        int chosenSpacing = 1;
        int from = higher ? 120 : 66;
        int step = higher ? -1 : 1;
        BlockPos floorTop = null;
        for (int i = 0; i < 70 && floorTop == null; i++) {
            int y = from + i * step;
            BlockPos here = anchor.world(15, y, 16);
            // The first air with something solid under it: that is where a player stands.
            if (level.getBlockState(here).isAir() && !level.getBlockState(here.below()).isAir()) {
                floorTop = here;
            }
        }
        if (floorTop == null) {
            com.killer560.hub.util.ModLog.get("killer560smod-roomsim").warn(
                    "Sim blaze puzzle: no standable floor in {}'s centre column - not armed", p.room().name);
            return false;
        }
        // How much clear air is above that floor, up to the tallest chain worth building.
        int headroom = 0;
        int maxNeeded = chainHeight(SPACINGS[0]);
        while (headroom <= maxNeeded && level.getBlockState(floorTop.above(headroom)).isAir()) {
            headroom++;
        }
        for (int candidateSpacing : SPACINGS) {
            if (chainHeight(candidateSpacing) <= headroom) {
                chosenSpacing = candidateSpacing;
                break;
            }
        }
        // Spacing 1 is the floor of the fallback: five blazes stacked is still a vertical chain, and it is
        // always better than none. Said out loud when the room is too short for a real one.
        if (chainHeight(chosenSpacing) > headroom) {
            chosenSpacing = 1;
            com.killer560.hub.util.ModLog.get("killer560smod-roomsim").warn(
                    "Sim blaze puzzle: {} has only {} block(s) of headroom in its centre column - stacking the "
                            + "chain at 1 block apart rather than skipping the room", p.room().name, headroom);
        }
        found = floorTop;
        spacing = chosenSpacing;
        final BlockPos[] chain = offsetsFor(chosenSpacing);
        despawnCurrent(Minecraft.getInstance());
        storedOrigin = found;
        boundOrigin = found;
        lowestFirst = higher;
        final BlockPos origin = found;
        UUID[] byPlacement = new UUID[HEALTHS.length];
        for (int i = 0; i < HEALTHS.length; i++) {
            BlockPos pos = origin.offset(chain[i]);
            SimBlazeEntity blaze = new SimBlazeEntity(McEntities.BLAZE, level);
            blaze.getAttribute(Attributes.MAX_HEALTH).setBaseValue(HEALTHS[i]);
            blaze.setHealth(HEALTHS[i]);
            blaze.setPersistenceRequired();
            blaze.setNoAi(true);
            blaze.setCustomName(blazeLabel(HEALTHS[i]));
            blaze.setCustomNameVisible(true);
            blaze.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            if (!level.addFreshEntity(blaze)) {
                com.killer560.hub.util.ModLog.get("killer560smod-roomsim")
                        .warn("Sim blaze puzzle: the level refused a blaze at {}", pos);
                continue;
            }
            byPlacement[i] = blaze.getUUID();
        }
        List<UUID> ordered = new ArrayList<>(HEALTHS.length);
        int[] order = killOrder(higher);
        for (int idx : order) {
            if (byPlacement[idx] != null) {
                ordered.add(byPlacement[idx]);
            }
        }
        if (ordered.isEmpty()) {
            return false;
        }
        spawnedIds = List.copyOf(ordered);
        nextRequired = 0;
        complete = false;
        com.killer560.hub.util.ModLog.get("killer560smod-roomsim").info(
                "Sim blaze puzzle: {} blaze(s) in {} at {}, {}-HP first, chained vertically {} block(s) apart, "
                        + "each labelled for BlazeSolverFeature",
                ordered.size(), p.room().name, origin, higher ? "lowest" : "highest", chosenSpacing);
        return true;
    }

    /** Non-null while this puzzle is bound inside a real captured room rather than a standalone arena. */
    private static volatile BlockPos boundOrigin = null;
    /** Whether the CURRENT arena drills the Higher Blaze half of the rule (lowest HP first). */
    private static volatile boolean lowestFirst = false;

    /** {@link #KILL_ORDER_INDICES}, reversed for the Higher Blaze half of the real rule. */
    private static int[] killOrder(boolean higher) {
        if (!higher) {
            return KILL_ORDER_INDICES;
        }
        int[] out = new int[KILL_ORDER_INDICES.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = KILL_ORDER_INDICES[out.length - 1 - i];
        }
        return out;
    }

    /** True once every blaze has died in the required order. */
    public static boolean isComplete() {
        return complete;
    }

    /** Despawns whatever is left of the current arena and clears progress. Takes no arguments - grabs the
     *  client singleton the same way {@code SimAbilities}'s item-use handler does, since the three-method
     *  shape asked for here has no room for one. */
    /**
     * Drops this puzzle's bookkeeping WITHOUT touching the world.
     *
     * <p>{@link #reset} is the right thing while the arena is still standing: it puts blocks back, un-presses,
     * re-lights. It is the wrong thing when the floor those blocks belonged to no longer exists, which is
     * exactly the case {@code SimRoomPuzzles.armFloor} has to handle - the positions it holds are absolute and
     * the next floor is built over them, so a queued "set it back to air" lands inside the new floor and
     * punches a hole in it. Just as bad the other way: a stale click index left in place makes a click on some
     * unrelated block on the new floor count as a move in a puzzle that is not on it.
     */
    public static void forget() {
        // The blazes themselves are entities in a level that is about to be wiped and rebuilt, so they go
        // with it - nothing is discarded here, which is the whole point of forget().
        spawnedIds = List.of();
        nextRequired = 0;
        complete = false;
        storedOrigin = null;
        boundOrigin = null;
    }

    public static void reset() {
        Minecraft client = Minecraft.getInstance();
        if (!SimState.canAct(client)) {
            return;
        }
        despawnCurrent(client);
        boundOrigin = null;
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
            // rebuild(), not build(): build() would reset the arena to the Lower Blaze half of the rule, so a
            // failed Higher Blaze would silently start drilling the opposite order.
            client.execute(() -> rebuild(client, origin));
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
