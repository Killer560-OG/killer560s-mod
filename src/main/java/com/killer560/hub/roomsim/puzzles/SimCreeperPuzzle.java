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
            Integer idx = lanternToPairIndex.get(hitResult.getBlockPos());
            if (idx == null) {
                return InteractionResult.PASS;
            }
            connectPair(client, idx);
            return InteractionResult.SUCCESS;
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
        connected = new boolean[pairs.size()];
        Map<BlockPos, Integer> lookup = new HashMap<>();
        for (int i = 0; i < pairs.size(); i++) {
            lookup.put(origin.offset(pairs.get(i).a()), i);
            lookup.put(origin.offset(pairs.get(i).b()), i);
        }
        lanternToPairIndex = Map.copyOf(lookup);
        built = true;
        server.execute(() -> {
            ServerLevel level = server.overworld();
            BlockState lit = Blocks.SEA_LANTERN.defaultBlockState();
            for (Pair pair : pairs) {
                level.setBlockAndUpdate(origin.offset(pair.a()), lit);
                level.setBlockAndUpdate(origin.offset(pair.b()), lit);
            }
        });
    }

    /** True once every pair in the current arena has been connected. False when nothing has been built yet. */
    public static boolean isComplete() {
        boolean[] c = connected;
        if (!built || c.length == 0) {
            return false;
        }
        for (boolean b : c) {
            if (!b) {
                return false;
            }
        }
        return true;
    }

    /** Puts every lantern in the current arena back to unconnected (Sea Lantern) without moving anything. Takes
     *  no arguments - grabs the client singleton the same way {@code SimAbilities}'s item-use handler does. */
    public static void reset() {
        Minecraft client = Minecraft.getInstance();
        if (!SimState.canAct(client)) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        BlockPos origin = storedOrigin;
        List<Pair> pairs = arenaPairs;
        if (server == null || origin == null || pairs.isEmpty()) {
            return;
        }
        connected = new boolean[pairs.size()];
        server.execute(() -> {
            ServerLevel level = server.overworld();
            BlockState lit = Blocks.SEA_LANTERN.defaultBlockState();
            for (Pair pair : pairs) {
                level.setBlockAndUpdate(origin.offset(pair.a()), lit);
                level.setBlockAndUpdate(origin.offset(pair.b()), lit);
            }
        });
    }

    private static void connectPair(Minecraft client, int idx) {
        boolean[] c = connected;
        List<Pair> pairs = arenaPairs;
        BlockPos origin = storedOrigin;
        if (idx < 0 || idx >= c.length || c[idx] || origin == null) {
            return;
        }
        c[idx] = true;
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        Pair pair = pairs.get(idx);
        server.execute(() -> {
            ServerLevel level = server.overworld();
            BlockState solved = Blocks.PRISMARINE.defaultBlockState();
            level.setBlockAndUpdate(origin.offset(pair.a()), solved);
            level.setBlockAndUpdate(origin.offset(pair.b()), solved);
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
            org.slf4j.LoggerFactory.getLogger("killer560smod-roomsim")
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
