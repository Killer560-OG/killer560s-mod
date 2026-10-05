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
import java.util.Map;
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
    private static final float[] HEALTHS = {10f, 9f, 8f, 7f, 6f, 5f, 4f, 3f, 2f, 1f};

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

    /** The four sides of the middle bar, two blocks out from it. */
    private static final BlockPos[] SIDES = {
            new BlockPos(2, 0, 0), new BlockPos(-2, 0, 0), new BlockPos(0, 0, 2), new BlockPos(0, 0, -2),
            new BlockPos(2, 0, 2), new BlockPos(-2, 0, 2), new BlockPos(2, 0, -2), new BlockPos(-2, 0, -2)};

    /** Most blocks between one blaze and the next one up. */
    private static final int MAX_SPACING = 5;

    /**
     * Where each blaze goes, indexed like {@link #HEALTHS}: around the middle bar rather than in a line up it.
     *
     * <p>killer560 (2026-10-01): "The blazes are working alot better for now for higher lower. Just get a few more
     * and instead of placing them in a line place them on any side of that middle bar with more verticle
     * spacing." Ten, like the real room. Heights are spread over the headroom the room has, up to
     * {@link #MAX_SPACING} apart, and HP is SHUFFLED against height - in a line ordered by height the kill order
     * could be read straight off the column, which is not the puzzle. Each blaze takes a random side that is clear
     * for its whole height; if no side is, it goes back on the bar itself rather than being dropped.
     */
    private static BlockPos[] layoutAround(ServerLevel level, BlockPos floorTop, int headroom) {
        int n = HEALTHS.length;
        int spacing = Math.max(1, Math.min(MAX_SPACING, (headroom - 3) / Math.max(1, n - 1)));
        java.util.Random rng = new java.util.Random();
        List<Integer> heights = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            heights.add(1 + i * spacing);
        }
        java.util.Collections.shuffle(heights, rng);
        BlockPos[] out = new BlockPos[n];
        for (int i = 0; i < n; i++) {
            BlockPos column = floorTop.above(heights.get(i));
            List<BlockPos> sides = new ArrayList<>(java.util.Arrays.asList(SIDES));
            java.util.Collections.shuffle(sides, rng);
            BlockPos chosen = column;
            for (BlockPos side : sides) {
                BlockPos at = column.offset(side);
                if (level.getBlockState(at).isAir() && level.getBlockState(at.above()).isAir()) {
                    chosen = at;
                    break;
                }
            }
            out[i] = chosen;
        }
        return out;
    }

    /** Clear air straight up from {@code from}, counted up to {@code cap}. */
    private static int headroomAbove(ServerLevel level, BlockPos from, int cap) {
        int h = 0;
        while (h < cap && level.getBlockState(from.above(h)).isAir()) {
            h++;
        }
        return h;
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

    /** Blaze UUID -> the armour stand carrying its label, so a dead blaze's label dies with it. */
    private static final Map<UUID, UUID> LABEL_STANDS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Hangs the label on an ARMOUR STAND over the blaze, which is the only thing the solver looks at.
     *
     * <p>killer560 (2026-10-01): "auto blaze wanted to look towards the middle" - and then nothing. This is why.
     * {@code BlazeSolverFeature.rescan} begins:
     *
     * <pre>  for (Entity entity : client.level.entitiesForRendering()) {
     *      if (!(entity instanceof ArmorStand)) continue;</pre>
     *
     * so a name set on the Blaze itself is invisible to it however well it matches the pattern. The solver
     * therefore found zero blazes in the sim, and {@code AutoBlaze} reads nothing BUT
     * {@code getOrderedBlazes()} - its very first line. What he saw was {@code seedDefaultView}, which runs
     * before the empty-list check, turning him towards the middle; everything after that was skipped.
     *
     * <p>This is also how the real room is built - Hypixel renders a mob's name on a separate stand - and how
     * {@code SimMobs} already tags its starred mobs, so the shape is proven rather than invented.
     *
     * <p><b>The stand is a MARKER, and the blaze carries no name of its own.</b> killer560 (2026-10-02): "the
     * blazes have the actual blaze then a hidden one above it. Remove that hidden one. It is just the nametag but
     * I cannot hit it." A plain invisible stand keeps its full 0.5 x 1.975 hitbox, so it sat on top of every
     * blaze catching arrows and punches meant for it, and the blaze's own visible name made a second label. A
     * marker stand has zero size, is not pickable and is skipped by projectiles, so every hit goes through to the
     * blaze. {@code setMarker} is private, so the flag is set through {@code DATA_CLIENT_FLAGS}, which is public
     * and identical in 26.1.2 and 26.2 (javap); {@code ArmorStand.onSyncedDataUpdated} calls
     * {@code refreshDimensions()} on exactly that key, so the box really does collapse.
     *
     * <p>A marker also fits Odin's offsets better than a full stand did: its box centre IS its position, so
     * "1 block under the stand" from {@code getY() + bbHeight + 0.1} lands at blaze y + 0.9 - the blaze's own
     * centre (it is 1.8 tall). The full stand put that point a block higher, at the blaze's head.
     *
     * <p><b>The one dial here, said out loud.</b> The stand sits just above the blaze, which is
     * {@code SimMobs}' own star-tag placement and the only one in this codebase shown not to swallow a kill.
     * Both the solver's highlight and Auto Blaze's aim are RECONSTRUCTED from the stand with offsets Odin tuned
     * against Hypixel's own stand geometry - {@code getBoundingBox().inflate(0.5, 1.0, 0.5).move(0, -1, 0)} for
     * the box, and {@code boundingBox.getCenter().y - 1.0} for the aim - and that geometry cannot be measured
     * from a capture, because a stand is an entity. So if the highlight reads as too tall or Auto Blaze shoots
     * over the blazes, the height on the line below is the dial, and nothing else needs touching.
     */
    private static void attachLabel(ServerLevel level, Entity blaze, float health) {
        net.minecraft.world.entity.decoration.ArmorStand tag =
                new net.minecraft.world.entity.decoration.ArmorStand(level,
                        blaze.getX(), blaze.getY() + blaze.getBbHeight() + 0.1, blaze.getZ());
        tag.setInvisible(true);
        tag.setNoGravity(true);
        tag.setNoBasePlate(true);
        tag.setInvulnerable(true);
        byte flags = tag.getEntityData().get(net.minecraft.world.entity.decoration.ArmorStand.DATA_CLIENT_FLAGS);
        tag.getEntityData().set(net.minecraft.world.entity.decoration.ArmorStand.DATA_CLIENT_FLAGS,
                (byte) (flags | net.minecraft.world.entity.decoration.ArmorStand.CLIENT_FLAG_MARKER));
        tag.setCustomName(blazeLabel(health));
        tag.setCustomNameVisible(true);
        if (level.addFreshEntity(tag)) {
            LABEL_STANDS.put(blaze.getUUID(), tag.getUUID());
        }
    }

    /** Takes a blaze's label away - called the tick its blaze is found dead, so the solver stops counting it. */
    private static void dropLabel(ServerLevel level, UUID blazeId) {
        UUID tagId = LABEL_STANDS.remove(blazeId);
        if (tagId == null) {
            return;
        }
        Entity tag = level.getEntity(tagId);
        if (tag != null) {
            tag.discard();
        }
    }

    /** Every label stand this arena put up, dropped with the arena. */
    private static void dropAllLabels(ServerLevel level) {
        for (UUID tagId : List.copyOf(LABEL_STANDS.values())) {
            Entity tag = level.getEntity(tagId);
            if (tag != null) {
                tag.discard();
            }
        }
        LABEL_STANDS.clear();
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
        server.execute(() -> {
            ServerLevel level = server.overworld();
            final BlockPos[] placed = layoutAround(level, origin, headroomAbove(level, origin, 60));
            UUID[] byPlacement = new UUID[HEALTHS.length];
            for (int i = 0; i < HEALTHS.length; i++) {
                BlockPos pos = placed[i];
                SimBlazeEntity blaze = new SimBlazeEntity(McEntities.BLAZE, level);
                blaze.getAttribute(Attributes.MAX_HEALTH).setBaseValue(HEALTHS[i]);
                blaze.setHealth(HEALTHS[i]);
                blaze.setPersistenceRequired();
                blaze.setNoAi(true);
                // No name on the blaze itself: the label stand carries it, and two names on one blaze read as
                // two blazes - see attachLabel.
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
                remember(blaze, HEALTHS[i]);
            // The label the SOLVER reads lives on its own armour stand - see attachLabel.
            attachLabel(level, blaze, HEALTHS[i]);
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
        // THE ROOM'S OWN BAND, not a remembered Hypixel height.
        //
        // This started at a hardcoded 66 (120 for Higher), and Lower Blaze's capture runs y 15..83 - so the
        // search began 46 blocks ABOVE its floor, found the first air-over-solid it met on the way up, and put
        // the chain in the roof. killer560 (2026-10-01): "The blazes are now in lower blaze but they are way up
        // in the ceeling". Every room carries its own band (RoomLibrary.Room.minY/maxY) and has since captures
        // stopped being indexed against a global constant; starting there is the same fix that file documents.
        //
        // FROM THE BOTTOM IN BOTH ROOMS. Higher Blaze used to search down from the room's top, and the first
        // air-over-solid on the way down is the cobblestone landing at capture y 118 - so all ten blazes went in
        // the 11 blocks between that landing and the ceiling (capture 119..129), stacked one apart. killer560's
        // log, 2026-10-04 18:06: Auto Blaze repositioned onto relative y 88 (one of QUOI's HIGHER_SPOTS, 85..118)
        // and its first target's stand was 37 blocks over his head - "they were all way too high and it made the
        // auto solver bug out". Decoding the two captures settles the shape: Higher Blaze (y 65..133) and Lower
        // Blaze (y 15..83) are the same shaft 50 blocks apart - floor block at 69 / 19, open air from 70 / 20 up to
        // a polished-andesite ceiling at 130 / 80, the middle bar of iron bars running up the centre. On Hypixel
        // the blazes float at different heights through that shaft, around the bar ("a tall chamber with blazes",
        // wiki); only the kill order differs between the two rooms. So both now start on the shaft's floor and
        // spread up through the clear air above it (layoutAround, up to 5 apart: relative 71..116 in Higher Blaze),
        // which is the band QUOI's standing spots were chosen to shoot into.
        int from = p.room().minY;
        int step = 1;
        int span = p.room().maxY - p.room().minY + 1;
        BlockPos floorTop = null;
        for (int i = 0; i < span && floorTop == null; i++) {
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
        final BlockPos[] placed = layoutAround(level, floorTop, headroomAbove(level, floorTop, 60));
        despawnCurrent(Minecraft.getInstance());
        storedOrigin = found;
        boundOrigin = found;
        lowestFirst = higher;
        boundRoom = p.room().name;
        final BlockPos origin = found;
        UUID[] byPlacement = new UUID[HEALTHS.length];
        for (int i = 0; i < HEALTHS.length; i++) {
            BlockPos pos = placed[i];
            SimBlazeEntity blaze = new SimBlazeEntity(McEntities.BLAZE, level);
            blaze.getAttribute(Attributes.MAX_HEALTH).setBaseValue(HEALTHS[i]);
            blaze.setHealth(HEALTHS[i]);
            blaze.setPersistenceRequired();
            blaze.setNoAi(true);
            blaze.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            if (!level.addFreshEntity(blaze)) {
                com.killer560.hub.util.ModLog.get("killer560smod-roomsim")
                        .warn("Sim blaze puzzle: the level refused a blaze at {}", pos);
                continue;
            }
            byPlacement[i] = blaze.getUUID();
            remember(blaze, HEALTHS[i]);
            // The label the SOLVER reads lives on its own armour stand - see attachLabel.
            attachLabel(level, blaze, HEALTHS[i]);
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
                "Sim blaze puzzle: {} blaze(s) in {} around {}, {}-HP first, on all sides of the bar at heights "
                        + "{}, each labelled for BlazeSolverFeature",
                ordered.size(), p.room().name, origin, higher ? "lowest" : "highest",
                java.util.Arrays.stream(placed).map(b -> b.getY() - origin.getY()).sorted().toList());
        return true;
    }

    /** Non-null while this puzzle is bound inside a real captured room rather than a standalone arena. */
    private static volatile BlockPos boundOrigin = null;
    /** Whether the CURRENT arena drills the Higher Blaze half of the rule (lowest HP first). */
    private static volatile boolean lowestFirst = false;
    /** The bound room's own name - "Higher Blaze" or "Lower Blaze". Held rather than derived from
     *  {@link #lowestFirst} so the chat label and the map's red square say which room, not which rule. */
    private static volatile String boundRoom = null;

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

    /** The room the current arena is bound in ("Higher Blaze" / "Lower Blaze"), or null for none or a standalone one. */
    public static String boundRoom() {
        return boundRoom;
    }

    /** Blazes killed in order so far in the current chain, for tests and logs. */
    public static int killedInOrder() {
        return nextRequired;
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
        // with it - nothing is discarded here, which is the whole point of forget(). Their label stands go the
        // same way, so the map of them is only dropped, never walked.
        LABEL_STANDS.clear();
        SEEN_DEAD.clear();
        SPOTS.clear();
        HP.clear();
        MISSING.clear();
        spawnedIds = List.of();
        nextRequired = 0;
        complete = false;
        storedOrigin = null;
        boundOrigin = null;
        boundRoom = null;
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
        // The labels to drop are taken NOW, not when the queued task runs: bindAt calls this on the server
        // thread and then attaches the new chain's labels before the task gets its turn, and dropping "whatever
        // is in the map by then" would take the new labels with the old.
        List<UUID> tags = List.copyOf(LABEL_STANDS.values());
        LABEL_STANDS.clear();
        if (ids.isEmpty() && tags.isEmpty()) {
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
            // And their labels, which are separate entities - a stand left standing keeps the solver counting a
            // blaze that is not there any more.
            for (UUID tagId : tags) {
                Entity tag = level.getEntity(tagId);
                if (tag != null) {
                    tag.discard();
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
        if (putBackMissing(level, ids)) {
            return; // the chain's ids changed; the next tick reads the new list
        }
        int idx = nextRequired;
        while (idx < ids.size() && isDead(level, ids.get(idx))) {
            dropLabel(level, ids.get(idx));
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

    /**
     * Whether this blaze has been SEEN dead.
     *
     * <p>Seen, not inferred from absence. This was {@code getEntity(id) == null || !isAlive()}, and a null only means
     * the server is not showing that entity's section - which is every blaze, for the ~27 s a freshly opened sim world
     * takes to bring its chunks up. So the whole chain read as killed in order before anyone had fired, and
     * {@link #isComplete()} was already true when the 93-solve run of 2026-10-04 switched Auto Blaze on; mid-run, a
     * blaze in an unloaded section could equally have read as an out-of-order kill. A killed blaze stays in the level,
     * not alive, for its 20 death ticks before it is removed, and this is asked every tick, so it is always seen.
     */
    private static boolean isDead(ServerLevel level, UUID id) {
        if (SEEN_DEAD.contains(id)) {
            return true;
        }
        // Dying, not merely gone: a discarded entity is also "not alive", and the put-back below relies on a
        // blaze that vanished without dying never being counted as a kill.
        if (level.getEntity(id) instanceof net.minecraft.world.entity.LivingEntity living && living.isDeadOrDying()) {
            SEEN_DEAD.add(id);
            return true;
        }
        return false;
    }

    /** Where each blaze of the chain was put and its health, so a lost one can be put back exactly as it was. */
    private static final Map<UUID, net.minecraft.world.phys.Vec3> SPOTS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, Float> HP = new java.util.concurrent.ConcurrentHashMap<>();
    /** Consecutive ticks each chain blaze has been missing from a section the server IS showing. */
    private static final Map<UUID, Integer> MISSING = new java.util.concurrent.ConcurrentHashMap<>();
    /** Ticks a blaze must be missing from a ticking section before it is put back. */
    private static final int MISSING_TICKS = 20;

    private static void remember(Entity blaze, float health) {
        SPOTS.put(blaze.getUUID(), blaze.position());
        HP.put(blaze.getUUID(), health);
    }

    /**
     * Puts back any living chain blaze that has vanished WITHOUT dying.
     *
     * <p>The blazes are a sim subclass whose only change is that peaceful does not discard them. That subclass only
     * exists while the entity stays loaded: when its chunk unloads the blaze is saved as "minecraft:blaze" and comes
     * back as a plain {@link Blaze}, which the sim's PEACEFUL world discards on its first tick. A single-room load
     * puts him ~170 blocks from the room until the build hands over, and a floor puts the Blaze room wherever it
     * lands, so the chunks go and the blazes with them - while their label stands, which peaceful leaves alone,
     * stayed up. 93-solve, 2026-10-04: ten blazes alive on the server at 20:02:41, none three seconds later, and Auto
     * Blaze shooting at ten labels with nothing under them for a minute. (Before {@link #isDead} learned to tell
     * "dying" from "absent" the same loss read as ten in-order kills, and the room was "complete" before anyone
     * fired.) {@code SimIcePathPuzzle} puts its silverfish back for the same reason.
     *
     * <p>Only where the section is entity-ticking, so a blaze the server is simply not showing yet is never doubled.
     *
     * @return whether any id in the chain was replaced
     */
    private static boolean putBackMissing(ServerLevel level, List<UUID> ids) {
        List<UUID> next = null;
        for (int j = nextRequired; j < ids.size(); j++) {
            UUID id = ids.get(j);
            if (SEEN_DEAD.contains(id) || level.getEntity(id) != null) {
                MISSING.remove(id);
                continue;
            }
            net.minecraft.world.phys.Vec3 at = SPOTS.get(id);
            Float health = HP.get(id);
            if (at == null || health == null || !level.isPositionEntityTicking(BlockPos.containing(at))) {
                MISSING.remove(id);
                continue;
            }
            int missing = MISSING.merge(id, 1, Integer::sum);
            if (missing < MISSING_TICKS) {
                continue;
            }
            SimBlazeEntity blaze = new SimBlazeEntity(McEntities.BLAZE, level);
            blaze.getAttribute(Attributes.MAX_HEALTH).setBaseValue(health);
            blaze.setHealth(health);
            blaze.setPersistenceRequired();
            blaze.setNoAi(true);
            blaze.setPos(at.x, at.y, at.z);
            if (!level.addFreshEntity(blaze)) {
                MISSING.put(id, 0);
                continue;
            }
            MISSING.remove(id);
            remember(blaze, health);
            UUID tag = LABEL_STANDS.remove(id);
            if (tag != null) {
                LABEL_STANDS.put(blaze.getUUID(), tag);
            } else {
                attachLabel(level, blaze, health);
            }
            if (next == null) {
                next = new ArrayList<>(ids);
            }
            next.set(j, blaze.getUUID());
            com.killer560.hub.util.ModLog.get("killer560smod-roomsim").info(
                    "Sim blaze puzzle: the {}-HP blaze in {} vanished without dying (its chunk was unloaded, and a "
                            + "reloaded blaze is not the sim's) - put back at {}", (int) (float) health, boundRoom,
                    BlockPos.containing(at).toShortString());
        }
        if (next == null) {
            return false;
        }
        if (spawnedIds == ids) {
            spawnedIds = List.copyOf(next);
        }
        return true;
    }

    /** Blazes of the current chain seen dead - see {@link #isDead}. */
    private static final java.util.Set<UUID> SEEN_DEAD = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void failAndRebuild(Minecraft client, MinecraftServer server, ServerLevel level, List<UUID> ids) {
        // Tells the Architect's First Draft feature a puzzle failed, so his existing
        // auto-get setting works in here the same as it does on Hypixel.
        SimPuzzles.reportFail(boundRoom == null ? "Blaze" : boundRoom, boundRoom);
        for (UUID id : ids) {
            Entity entity = level.getEntity(id);
            if (entity != null && entity.isAlive()) {
                entity.discard();
            }
        }
        // AND THEIR LABELS. Only an in-order kill ever dropped a label, so a fail left every remaining stand -
        // the out-of-order victim's included - floating where its blaze had been, and the rebuild added ten more.
        // The solver reads stands, not blazes, so Auto Blaze went on shooting at a "10/10" that was no longer
        // there ("auto higher lower is really bugging out", 2026-10-04).
        dropAllLabels(level);
        com.killer560.hub.util.ModLog.get("killer560smod-roomsim").info(
                "Sim blaze puzzle: out-of-order kill in {} - rebuilding the chain", boundRoom);
        client.execute(() -> com.killer560.hub.util.ModChat.send("Sim", com.killer560.hub.util.ModChat.bad(
                "Blaze killed out of order - new chain.")));
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
