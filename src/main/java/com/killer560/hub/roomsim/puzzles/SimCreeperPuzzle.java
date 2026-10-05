package com.killer560.hub.roomsim.puzzles;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.killer560.hub.roomsim.SimState;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A small standalone practice arena for the real "Creeper Beams" dungeon puzzle, playable inside the room sim.
 *
 * <p><b>The lantern positions are the real bundled data, not invented.</b> {@code puzzlesolvers/BeamsSolverFeature}
 * loads {@code data/killer560smod/puzzles/creeper-beams-solutions.json} - Odin's own bundled candidate list of
 * real Sea Lantern position PAIRS from the real room, copied verbatim - and this class loads the exact same
 * resource the exact same way, then places a real Sea Lantern at each of those real relative positions around
 * {@code origin}. Nothing about where the lanterns sit is generated. (The bundled list contains a couple of
 * exact/reversed duplicate pairs - {@link #dedupe} drops them so the arena doesn't place two lanterns on top of
 * each other.) Only the fallback in {@link #FALLBACK_PAIRS} is synthetic, and it is used ONLY if that resource
 * cannot be loaded at runtime - see its own comment.
 *
 * <p><b>What is simplified, and why.</b> On the real room, connecting a pair means physically walking the room
 * and rotating stained-glass panes elsewhere to redirect a light beam onto both lanterns of a pair at once -
 * that needs the panes' own positions and vanilla's beam propagation through them, and this mod has neither: per
 * this project's own {@code CLAUDE.md}, "No public dungeon dataset ships room GEOMETRY", and the bundled JSON is
 * only ever the lantern pair list, never the room around them. So here, right-clicking either lantern of a pair
 * connects that pair directly. That keeps the real detection convention {@code BeamsSolverFeature} already
 * uses - an unconnected lantern is {@code SEA_LANTERN}, a connected one has turned to {@code PRISMARINE} - and
 * lets the puzzle test recognising and locating real pairs, which is what the bundled data actually encodes;
 * it does not attempt to test the pane-rotation mechanic itself.
 *
 * <p>Gated on {@link SimState#canAct} throughout, same boundary as the rest of {@code roomsim}. The block writes
 * in {@link #build}, {@link #reset} and the connect handler all happen inside {@code server.execute(...)} on the
 * integrated server - this class never calls {@code setBlockAndUpdate} from the client thread. No tick hook is
 * needed (completion is a plain read of {@link #connected}), so unlike {@code SimBlazePuzzle} there is nothing
 * here for {@code FeatureGuard} to wrap - the same is true of this codebase's other {@code UseBlockCallback}
 * interaction hooks ({@code SecretSound}, {@code autoroutes/AutoRoutesEditInput}), which also register plain.
 */
public final class SimCreeperPuzzle {

    private record Pair(BlockPos a, BlockPos b) {
    }

    /**
     * Used only if {@code creeper-beams-solutions.json} fails to load at runtime (missing resource, bad JSON).
     * Five made-up pairs on a small ring around the origin, spaced apart so no two overlap - NOT taken from any
     * real room data, purely so the puzzle still has something to build.
     */
    private static final int[][] FALLBACK_PAIRS = {
            {0, 3, 6, 0, 3, -6},
            {6, 3, 0, -6, 3, 0},
            {4, 4, 4, -4, 4, -4},
            {4, 4, -4, -4, 4, 4},
            {0, 6, 0, 0, 2, 0},
    };

    private static final List<Pair> PAIRS = loadPairs();

    private static volatile List<Pair> arenaPairs = List.of();
    /**
     * The current arena's lantern pairs in ABSOLUTE world positions, one entry per {@link #arenaPairs} entry.
     *
     * <p>Everything used to be {@code storedOrigin.offset(pair.a())}, which cannot express a bind to a real
     * captured room: there the lanterns are the room's own blocks and their positions come from the room's
     * clay corner and rotation, not from an origin plus a fixed offset. Resolved once, here, so
     * {@link #connectPair} and {@link #reset} cannot disagree with whatever placed them.
     */
    private static volatile List<Pair> worldPairs = List.of();
    private static volatile boolean[] connected = new boolean[0];
    private static volatile BlockPos storedOrigin = null;
    private static volatile Map<BlockPos, Integer> lanternToPairIndex = Map.of();
    private static volatile boolean built = false;

    private static boolean registered = false;

    private SimCreeperPuzzle() {
    }

    /** Registers the right-click-to-connect handler. Reports back rather than wiring itself into a screen/menu -
     *  see the class handoff note for where this still needs to be called from. */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (!level.isClientSide() || hitResult == null) {
                return InteractionResult.PASS;
            }
            Minecraft client = Minecraft.getInstance();
            if (!SimState.canAct(client) || player != client.player) {
                return InteractionResult.PASS;
            }
            if (!tryConnectAt(hitResult.getBlockPos())) {
                return InteractionResult.PASS;
            }
            return InteractionResult.SUCCESS;
        });
    }

    /**
     * Connects the pair this block belongs to, if it is one of this arena's lanterns.
     *
     * <p>Shared by the right-click hook above and by the LEFT-click path, which is the one killer560 was
     * actually using: "creeper beams does nothing when i shoot the lanterns" (2026-10-01). His log shows the
     * room binding correctly - "Sim puzzle Creeper Beams: bound at database rotation 0 - 22 of 22 expected
     * block(s) present" - so the lanterns were there and the puzzle was armed; only right-clicking reached it,
     * and shooting a lantern with the Mage beam or an arrow is a left click.
     *
     * <p>The left click arrives through {@code SimItems}' own {@code AttackBlockCallback} rather than a second
     * one registered here, and that is deliberate: {@code SimItems} returns SUCCESS for every left click in the
     * sim in order to stop a stray swing mining a room, which consumes the event. A listener registered
     * afterwards would never run - so the dispatch has to live at the gate that already owns left clicks, not
     * beside it.
     *
     * @return whether this was one of this puzzle's lanterns
     */
    public static boolean tryConnectAt(BlockPos pos) {
        Minecraft client = Minecraft.getInstance();
        if (!SimState.canAct(client) || pos == null) {
            return false;
        }
        Integer idx = lanternToPairIndex.get(pos);
        if (idx == null) {
            sayNotAPairLantern(client, pos);
            return false;
        }
        // THREE TICKS before the same lantern answers again.
        //
        // killer560 (2026-10-01): "make the delay between selecting a block and unslecting that same block 3
        // ticks", and in the same breath "it wouldnt let my shots render the thing as hit most of the time".
        // They are one fault. A cancelled block break re-enters AttackBlockCallback every TICK rather than
        // once per click (see CLAUDE.md), so a single held shot picked the lantern, cancelled it, picked it
        // again - twenty times a second. From in front of it that is a lantern that mostly does not respond.
        //
        // And a HELD button is one click, however long it is held. The stamp is refreshed on every re-entry,
        // ignored or not, so while the button stays down the gap is always one tick and nothing fires again.
        // With the stamp only written on a pick, a hold on a lantern re-fired every third tick - which on the
        // SECOND lantern of a pair meant "Not that pair", then three ticks later that lantern picked as a new
        // first end, then dropped, and so on: killer560 (2026-10-02) "if I hit another light then it will not
        // light up and draw a beam between them."
        long now = client.level == null ? 0L : client.level.getGameTime();
        Long last = lastPickTick.put(pos.immutable(), now);
        if (last != null && now - last < REPICK_DELAY_TICKS) {
            return true;   // ours, and deliberately ignored - never falls through to a block break
        }
        pick(client, idx, pos.immutable());
        return true;
    }

    /**
     * A SHOT at a lantern from any distance - the Terminator's arrows, Salvation, the Mage beam.
     *
     * <p>killer560 (2026-10-04): "I still cannot shoot the second lantern." Until this, the only ways in were a
     * LEFT click on the block ({@code SimItems}' {@code AttackBlockCallback}) and a right click on it - and both
     * of those only exist when the crosshair's block pick found the lantern, which vanilla does out to the
     * player's block interaction range (4.5 blocks) and no further. The Terminator's arrows are real entities
     * that hit nothing this class listens to, and the Mage beam only ever looked for mobs. So the near end of a
     * pair, which he could walk up to, picked; the far end, across the room, was a click on nothing at all.
     * The 14:08 log shows exactly that: "Beam held" twice, and no second shot ever arriving.
     *
     * <p>Resolved as a ray on the CLIENT's level at the moment of the shot, along the aim, stopping at the first
     * block - so a pane or a wall in the way blocks it as an arrow would. Hitscan rather than waiting for an
     * arrow to land: an arrow drops and the outer two fly 8 degrees off, so a correctly aimed shot at the far
     * end of the room could miss or, worse, burn a neighbouring lantern as a wrong pair. The same-lantern
     * three-tick guard in {@link #tryConnectAt} absorbs the case where this and a left click on a lantern in
     * reach arrive in the same tick.
     *
     * @return whether the shot landed on one of this puzzle's lanterns
     */
    public static boolean shotAlong(Minecraft client, net.minecraft.world.phys.Vec3 eye,
                                    net.minecraft.world.phys.Vec3 dir, double range) {
        if (!SimState.canAct(client) || client.level == null || client.player == null
                || lanternToPairIndex.isEmpty() || eye == null || dir == null || dir.lengthSqr() < 1.0e-8) {
            return false;
        }
        net.minecraft.world.phys.Vec3 end = eye.add(dir.normalize().scale(range));
        net.minecraft.world.phys.BlockHitResult hit = client.level.clip(new net.minecraft.world.level.ClipContext(
                eye, end, net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, client.player));
        if (hit == null || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
            return false;
        }
        BlockPos pos = hit.getBlockPos().immutable();
        if (!lanternToPairIndex.containsKey(pos)) {
            return false;
        }
        LOGGER.info("Sim creeper beams: shot landed on lantern {} (pair {}) from {} blocks", pos,
                lanternToPairIndex.get(pos), String.format(java.util.Locale.ROOT, "%.1f",
                        Math.sqrt(eye.distanceToSqr(hit.getLocation()))));
        return tryConnectAt(pos);
    }

    private static final org.slf4j.Logger LOGGER = com.killer560.hub.util.ModLog.get("killer560smod-roomsim");

    /**
     * Says so when he shoots a sea lantern that is not one of the puzzle's, once per lantern.
     *
     * <p>Decoding the capture settles that this is a real thing to be confused by rather than a bug: the room
     * holds <b>35</b> lantern blocks and only <b>22</b> of them are in {@code creeper-beams-solutions.json}'s
     * eleven pairs. The other thirteen are the room's own sea lanterns, in the walls and floor, and they are
     * there on Hypixel too - so shooting one does nothing there either. What the sim can do that the real room
     * cannot is say which it was, instead of a shot that silently achieves nothing. (The solver's coloured boxes
     * mark the twenty-two while it is on; this is for when it is not.)
     */
    private static void sayNotAPairLantern(Minecraft client, BlockPos pos) {
        // IN THIS ROOM, and a lantern, before the "said once" set is touched at all.
        //
        // tryConnectAt runs for EVERY left click anywhere in the sim (SimItems owns that callback), so without
        // the range test a decorative sea lantern in some other room would be told it is not part of a beam
        // pair - which is true and useless. And testing the block first keeps the set from churning on every
        // ordinary block he hits: a cancelled break re-enters that callback every tick.
        BlockPos origin = storedOrigin;
        if (lanternToPairIndex.isEmpty() || client.level == null || origin == null
                || pos.distSqr(origin) > SAME_ROOM_RANGE_SQR) {
            return;
        }
        BlockState state = client.level.getBlockState(pos);
        if (!state.is(Blocks.SEA_LANTERN) && !state.is(Blocks.PRISMARINE)) {
            return;
        }
        if (!NOT_A_PAIR_SAID.add(pos.immutable())) {
            return;
        }
        com.killer560.hub.util.ModChat.send("Sim", com.killer560.hub.util.ModChat.dim(
                "That lantern is not part of a beam pair - this room has 13 of those as well as the 22 that are."));
    }

    /** Far enough to cover the whole Creeper Beams room from any of its lanterns, and no further. */
    private static final double SAME_ROOM_RANGE_SQR = 40.0 * 40.0;

    private static final Set<BlockPos> NOT_A_PAIR_SAID = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Hypixel's own two pitches for an elder guardian hurt sound at a shot lantern. Not invented here and not
     * reverse-engineered for this - {@code AutoBeams.onSound} already carries them as QUOI's exact values:
     * {@code 1.3968254f} is "first lantern of a pair registered", {@code 2.0f} is "pair completed".
     */
    private static final float FIRST_HIT_PITCH = 1.3968254f;
    private static final float PAIR_DONE_PITCH = 2.0f;

    /**
     * The progress sound Auto Creeper Beams reads, played at the lantern that was just shot.
     *
     * <p>killer560 (2026-10-01): "auto creeper beams would look down and teleport and that was it." Two faults,
     * and this is the second. {@code AutoBeams} does not watch the blocks at all: it advances a pair's stage
     * ONLY when a {@code ClientboundSoundPacket} for {@code entity.elder_guardian.hurt} arrives at the exact
     * lantern it just shot, with one of the two pitches above. That is how the real room reports a hit, and the
     * sim was reporting nothing - so even with the aim fixed it would have shot the first lantern forever and
     * never moved on to the second.
     *
     * <p>Played at INTEGER coordinates on purpose. {@code AutoBeams.onSound} compares the packet's position to
     * the lantern's {@code BlockPos} component by component, and a sound played at a block's centre would arrive
     * as {@code x + 0.5} and never match.
     */
    private static void hitSound(Minecraft client, BlockPos pos, float pitch) {
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        server.execute(() -> server.overworld().playSound(null, pos.getX(), pos.getY(), pos.getZ(),
                net.minecraft.sounds.SoundEvents.ELDER_GUARDIAN_HURT,
                net.minecraft.sounds.SoundSource.BLOCKS, 1.0f, pitch));
    }

    /** Ticks a lantern ignores a second hit, so one held shot is one answer - see {@link #tryConnectAt}. */
    private static final int REPICK_DELAY_TICKS = 3;

    private static final Map<BlockPos, Long> lastPickTick = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * TWO SHOTS make a beam, not one.
     *
     * <p>killer560 (2026-10-01): "once i shoot one square if it is right it shouldnt insta fill the other it
     * should let me choose where it goes then draw a beacon line between the two." Shooting a lantern used to
     * turn BOTH ends of its pair at once, which answers the puzzle for him - the whole skill in the real room
     * is working out which far lantern a near one belongs to, and a one-click solve removes exactly that.
     *
     * <p>So the first shot lights one end and leaves it waiting; the second decides. The same lantern again
     * cancels. Its partner completes the pair: the beam is drawn and both ends turn to prismarine.
     *
     * <p><b>A wrong second lantern costs nothing.</b> killer560 (2026-10-04): "for sim there should be no way of
     * failing creeper beams." Until then a wrong pair burned both lanterns to prismarine and drew a permanent red
     * line, so a misclick could use up a pair the room needed. The wiki (Catacombs Puzzle Rooms) calls Creeper Beams
     * non-failable - it is solved by any four beams through the creeper and there is no fail message or penalty on
     * Hypixel. Now a wrong pair flashes a red line for {@link #WRONG_BEAM_TICKS} ticks, drops the held end, and
     * leaves both lanterns lit and shootable; no block changes and no sound, so neither the solver (which reads
     * prismarine as "burned") nor Auto Creeper Beams (which reads the hurt sound) sees anything happen.
     *
     * <p>It used to be "Not that pair - the beam goes out" with nothing drawn at all, which from in front of it is a
     * second shot that did nothing (killer560, 2026-10-02) - hence the brief line and the chat message.
     */
    private static void pick(Minecraft client, int idx, BlockPos pos) {
        boolean[] c = connected;
        if (idx < 0 || idx >= c.length || c[idx]) {
            return;   // already joined - its beam is already drawn
        }
        BlockPos held = pendingPos;
        if (held == null) {
            pendingIndex = idx;
            pendingPos = pos;
            hitSound(client, pos, FIRST_HIT_PITCH);
            com.killer560.hub.util.ModChat.send("Sim", com.killer560.hub.util.ModChat.text("Beam held - "),
                    com.killer560.hub.util.ModChat.dim("now shoot the lantern it pairs with."));
            return;
        }
        if (held.equals(pos)) {
            pendingPos = null;
            pendingIndex = -1;
            com.killer560.hub.util.ModChat.send("Sim", com.killer560.hub.util.ModChat.dim("Beam dropped."));
            return;
        }
        if (idx == pendingIndex) {
            pendingPos = null;
            pendingIndex = -1;
            hitSound(client, pos, PAIR_DONE_PITCH);
            connectPair(client, idx);
            return;
        }
        // Not its partner: show the miss, let go of the held end, change nothing in the room.
        pendingPos = null;
        pendingIndex = -1;
        long now = client.level == null ? 0L : client.level.getGameTime();
        wrongBeams.add(new WrongBeam(new Pair(held, pos), now + WRONG_BEAM_TICKS));
        com.killer560.hub.util.ModChat.send("Sim", com.killer560.hub.util.ModChat.bad("Not a pair"),
                com.killer560.hub.util.ModChat.dim(" - that beam misses the creeper. Both lanterns are still lit; "
                        + "shoot a lantern to start again."));
    }

    /** How long a wrong pair's red line stays up: two seconds. */
    private static final int WRONG_BEAM_TICKS = 40;

    private record WrongBeam(Pair pair, long untilTick) {
    }

    /** Wrong pairs, drawn in red until their tick runs out. Nothing about the room changes for them. */
    private static final List<WrongBeam> wrongBeams = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Right beams through the creeper that solve the room - the wiki's "four different beams". */
    private static final int BEAMS_TO_SOLVE = 4;

    /**
     * The lantern this run is holding, and its pair, or null/-1 when nothing is held.
     *
     * <p><b>A HELD LANTERN IS STILL A SEA LANTERN.</b> killer560 (2026-10-01): "For creeper beams lanters still
     * are messed up i am not even sure what all is wrong but it is just wrong."
     *
     * <p>Holding used to turn the lantern to {@code PRISMARINE}, and that is the one thing it must never do.
     * {@code BeamsSolverFeature.rescan} reads exactly that difference, and it reads it as the FAILURE state:
     *
     * <pre>  litA != litB && (usedUp(a) || usedUp(b))  ->  misaligned, paint both ends RED</pre>
     *
     * <p>which is its "one of this pair was burned on the wrong partner and can never be finished". So the first
     * correct shot of every pair made his own solver light that pair up red and draw a red line across the room -
     * the exact opposite of what had just happened. The hold is this class's bookkeeping, not a change to the
     * room, so it is drawn by {@link #registerRender} instead and the block is left alone until the pair is
     * actually joined.
     */
    private static volatile BlockPos pendingPos = null;
    private static volatile int pendingIndex = -1;

    /**
     * The beams themselves, drawn between the two ends of every joined pair.
     *
     * <p>Through {@code SolverEspRender}, which is the pipeline every highlight in this mod already goes
     * through - a second one registered for this would be a second thing to keep in step with the render
     * phase, and this draws after translucent terrain for the same reason the solvers do.
     */
    public static void registerRender() {
        com.killer560.hub.puzzlesolvers.SolverEspRender.init();
        net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN
                .register(context -> {
                    boolean[] c = connected;
                    List<Pair> world = worldPairs;
                    if (!SimState.canAct(Minecraft.getInstance()) || c.length == 0 || world.isEmpty()) {
                        return;
                    }
                    // The held end, drawn rather than built. See pendingPos: changing the block is what made
                    // BeamsSolverFeature call a correct first shot a misaligned pair.
                    BlockPos held = pendingPos;
                    if (held != null) {
                        com.killer560.hub.puzzlesolvers.SolverEspRender.renderWaypoint(context,
                                new net.minecraft.world.phys.AABB(held), 1.0f, 1.0f, 0.4f, 2f);
                    }
                    long nowTick = Minecraft.getInstance().level == null ? 0L
                            : Minecraft.getInstance().level.getGameTime();
                    wrongBeams.removeIf(w -> w.untilTick() < nowTick);
                    for (WrongBeam w : wrongBeams) {
                        Pair wrong = w.pair();
                        com.killer560.hub.puzzlesolvers.SolverEspRender.renderLineStrip(context, List.of(
                                new net.minecraft.world.phys.Vec3(wrong.a().getX() + 0.5,
                                        wrong.a().getY() + 0.5, wrong.a().getZ() + 0.5),
                                new net.minecraft.world.phys.Vec3(wrong.b().getX() + 0.5,
                                        wrong.b().getY() + 0.5, wrong.b().getZ() + 0.5)),
                                1.0f, 0.25f, 0.25f, 1f, 3f);
                    }
                    for (int i = 0; i < c.length && i < world.size(); i++) {
                        if (!c[i]) {
                            continue;
                        }
                        Pair pair = world.get(i);
                        List<net.minecraft.world.phys.Vec3> line = List.of(
                                new net.minecraft.world.phys.Vec3(pair.a().getX() + 0.5,
                                        pair.a().getY() + 0.5, pair.a().getZ() + 0.5),
                                new net.minecraft.world.phys.Vec3(pair.b().getX() + 0.5,
                                        pair.b().getY() + 0.5, pair.b().getZ() + 0.5));
                        com.killer560.hub.puzzlesolvers.SolverEspRender.renderLineStrip(
                                context, line, 0.4f, 0.9f, 1.0f, 1f, 3f);
                    }
                });
    }

    /** Clears any previous arena and places a fresh, unconnected one (all Sea Lantern) at {@code origin}. */
    public static void build(Minecraft client, BlockPos origin) {
        if (!SimState.canAct(client)) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        List<Pair> pairs = PAIRS;
        storedOrigin = origin;
        arenaPairs = pairs;
        List<Pair> world = new ArrayList<>(pairs.size());
        for (Pair pair : pairs) {
            world.add(new Pair(origin.offset(pair.a()).immutable(), origin.offset(pair.b()).immutable()));
        }
        worldPairs = List.copyOf(world);
        connected = new boolean[pairs.size()];
        pendingPos = null;
        pendingIndex = -1;
        wrongBeams.clear();
        Map<BlockPos, Integer> lookup = new HashMap<>();
        for (int i = 0; i < world.size(); i++) {
            lookup.put(world.get(i).a(), i);
            lookup.put(world.get(i).b(), i);
        }
        lanternToPairIndex = Map.copyOf(lookup);
        built = true;
        server.execute(() -> {
            ServerLevel level = server.overworld();
            BlockState lit = Blocks.SEA_LANTERN.defaultBlockState();
            for (Pair pair : world) {
                level.setBlockAndUpdate(pair.a(), lit);
                level.setBlockAndUpdate(pair.b(), lit);
            }
        });
    }

    /**
     * Arms this puzzle on a REAL captured Creeper Beams room, on the room's own sea lanterns.
     *
     * <p>{@link #PAIRS} is the bundled candidate list {@code BeamsSolverFeature} uses, in room-relative
     * coordinates, and all 22 of its distinct lanterns are already in the shipped capture - measured 22 of 22
     * at database rotation 0 against 1, 2 and 1 at the other three. So none is placed. Thirteen of those 22
     * are {@code prismarine} in the capture rather than {@code sea_lantern}, which is that room having been
     * captured part-solved (the solver's own convention: unconnected is a sea lantern, connected is
     * prismarine), so arming puts every one of them back to unconnected - the puzzle's state, not its arena.
     *
     * <p>Server thread only; called from {@code SimBuilder}'s post-build block.
     *
     * @return whether the puzzle was armed
     */
    public static boolean bindAt(ServerLevel level, com.killer560.hub.roomsim.SimRoomPuzzles.Placement p) {
        List<Pair> pairs = PAIRS;
        if (pairs.isEmpty()) {
            return false;
        }
        List<int[]> rels = new ArrayList<>(pairs.size() * 2);
        for (Pair pair : pairs) {
            rels.add(new int[]{pair.a().getX(), pair.a().getY(), pair.a().getZ()});
            rels.add(new int[]{pair.b().getX(), pair.b().getY(), pair.b().getZ()});
        }
        com.killer560.hub.roomsim.SimRoomPuzzles.Anchor anchor =
                com.killer560.hub.roomsim.SimRoomPuzzles.bestAnchor(level, p, rels,
                        com.killer560.hub.roomsim.SimRoomPuzzles.is(Blocks.SEA_LANTERN, Blocks.PRISMARINE),
                        new int[]{0}, Math.max(4, rels.size() * 3 / 4));
        if (anchor == null) {
            return false;
        }
        List<Pair> world = new ArrayList<>(pairs.size());
        for (Pair pair : pairs) {
            world.add(new Pair(anchor.world(pair.a().getX(), pair.a().getY(), pair.a().getZ()),
                    anchor.world(pair.b().getX(), pair.b().getY(), pair.b().getZ())));
        }
        arenaPairs = pairs;
        worldPairs = List.copyOf(world);
        storedOrigin = world.get(0).a();   // only used as a "something is built" marker now
        connected = new boolean[pairs.size()];
        pendingPos = null;
        pendingIndex = -1;
        wrongBeams.clear();
        Map<BlockPos, Integer> lookup = new HashMap<>();
        for (int i = 0; i < world.size(); i++) {
            lookup.put(world.get(i).a(), i);
            lookup.put(world.get(i).b(), i);
        }
        lanternToPairIndex = Map.copyOf(lookup);
        BlockState lit = Blocks.SEA_LANTERN.defaultBlockState();
        for (Pair pair : world) {
            if (!level.getBlockState(pair.a()).is(Blocks.SEA_LANTERN)) {
                level.setBlockAndUpdate(pair.a(), lit);
            }
            if (!level.getBlockState(pair.b()).is(Blocks.SEA_LANTERN)) {
                level.setBlockAndUpdate(pair.b(), lit);
            }
        }
        built = true;
        return true;
    }

    /** True once every pair in the current arena has been connected. False when nothing has been built yet. */
    public static boolean isComplete() {
        boolean[] c = connected;
        if (!built || c.length == 0) {
            return false;
        }
        int joined = 0;
        for (boolean b : c) {
            if (b) {
                joined++;
            }
        }
        return joined >= Math.min(BEAMS_TO_SOLVE, c.length);
    }

    /**
     * Pairs joined so far - a right beam through the creeper each. Read by tests (reflection) next to
     * {@link #isComplete()}, which is true at {@value #BEAMS_TO_SOLVE}.
     */
    public static int joinedCount() {
        int joined = 0;
        for (boolean b : connected) {
            if (b) {
                joined++;
            }
        }
        return joined;
    }

    /** Puts every lantern in the current arena back to unconnected (Sea Lantern) without moving anything. Takes
     *  no arguments - grabs the client singleton the same way {@code SimAbilities}'s item-use handler does. */
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
        arenaPairs = List.of();
        worldPairs = List.of();
        lanternToPairIndex = Map.of();
        connected = new boolean[0];
        storedOrigin = null;
        built = false;
        lastPickTick.clear();
        NOT_A_PAIR_SAID.clear();
        pendingPos = null;
        pendingIndex = -1;
        wrongBeams.clear();
    }

    public static void reset() {
        Minecraft client = Minecraft.getInstance();
        if (!SimState.canAct(client)) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        List<Pair> world = worldPairs;
        if (server == null || world.isEmpty()) {
            return;
        }
        connected = new boolean[world.size()];
        pendingPos = null;
        pendingIndex = -1;
        wrongBeams.clear();
        lastPickTick.clear();
        server.execute(() -> {
            ServerLevel level = server.overworld();
            BlockState lit = Blocks.SEA_LANTERN.defaultBlockState();
            for (Pair pair : world) {
                level.setBlockAndUpdate(pair.a(), lit);
                level.setBlockAndUpdate(pair.b(), lit);
            }
        });
    }

    private static void connectPair(Minecraft client, int idx) {
        boolean[] c = connected;
        List<Pair> world = worldPairs;
        if (idx < 0 || idx >= c.length || idx >= world.size() || c[idx]) {
            return;
        }
        c[idx] = true;
        if (isComplete()) {
            com.killer560.hub.util.ModChat.send("Sim", com.killer560.hub.util.ModChat.good("Creeper Beams"),
                    com.killer560.hub.util.ModChat.text(" solved - four beams through the creeper."));
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        Pair pair = world.get(idx);
        server.execute(() -> {
            ServerLevel level = server.overworld();
            BlockState solved = Blocks.PRISMARINE.defaultBlockState();
            level.setBlockAndUpdate(pair.a(), solved);
            level.setBlockAndUpdate(pair.b(), solved);
        });
    }

    /** Loads and dedupes the real bundled candidate list; falls back to {@link #FALLBACK_PAIRS} only if that
     *  resource is missing or malformed. */
    private static List<Pair> loadPairs() {
        try (InputStream stream = SimCreeperPuzzle.class.getClassLoader()
                .getResourceAsStream("data/killer560smod/puzzles/creeper-beams-solutions.json")) {
            if (stream == null) {
                return dedupe(FALLBACK_PAIRS);
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                Type type = new TypeToken<List<int[]>>() {
                }.getType();
                List<int[]> raw = new Gson().fromJson(reader, type);
                if (raw == null || raw.isEmpty()) {
                    return dedupe(FALLBACK_PAIRS);
                }
                return dedupe(raw.toArray(new int[0][]));
            }
        } catch (Exception e) {
            com.killer560.hub.util.ModLog.get("killer560smod-roomsim")
                    .warn("[SimCreeperPuzzle] Failed to load creeper-beams-solutions.json, using generated pairs", e);
            return dedupe(FALLBACK_PAIRS);
        }
    }

    /** Drops exact and reversed duplicate pairs - the bundled JSON has a couple of both. */
    private static List<Pair> dedupe(int[][] raw) {
        List<Pair> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int[] entry : raw) {
            if (entry.length != 6) {
                continue;
            }
            BlockPos a = new BlockPos(entry[0], entry[1], entry[2]);
            BlockPos b = new BlockPos(entry[3], entry[4], entry[5]);
            String key = canonicalKey(a, b);
            if (seen.add(key)) {
                out.add(new Pair(a, b));
            }
        }
        return List.copyOf(out);
    }

    private static String canonicalKey(BlockPos a, BlockPos b) {
        String sa = a.getX() + "," + a.getY() + "," + a.getZ();
        String sb = b.getX() + "," + b.getY() + "," + b.getZ();
        return sa.compareTo(sb) <= 0 ? sa + "|" + sb : sb + "|" + sa;
    }
}
