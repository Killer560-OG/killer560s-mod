package com.killer560.hub.puzzlesolvers;

import com.killer560.hub.util.FeatureGuard;
import com.google.gson.Gson;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.secrets.DungeonState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Real Hypixel dungeon "Ice Fill" puzzle solver, ported from Odin's own {@code IceFillSolver.kt}. The
 * puzzle has 3 real floors, each with several possible real ice layouts; each floor's layout is
 * identified by checking 2 fixed real relative positions (the first must be real air, the second must
 * NOT be air - a unique fingerprint for that layout, ported directly from Odin's own real
 * {@code identifier} data) and, once identified, that layout's known real safe walking path (a real,
 * pre-solved list of relative positions, {@code easy} or {@code hard} depending on
 * {@link IceFillSolverConfig#isOptimizedPath()}) is drawn as a connected line through all 3 floors. All
 * real position/path data is bundled verbatim from Odin in
 * {@code data/killer560smod/puzzles/ice-fill-floors.json}. Never walks for you - only draws the path.
 */
public final class IceFillSolverFeature {

    private record Pos(int x, int y, int z) {
    }

    private record IceFillData(List<List<List<Pos>>> identifier, List<List<List<Pos>>> easy,
                                List<List<List<Pos>>> hard) {
    }

    private static final IceFillData DATA = loadData();
    private static final List<Vec3> currentPath = new ArrayList<>();
    private static RoomEntry lastRoomEntry = null;
    /** The Alternate Path setting the drawn path was built with: flipping it in the room redraws instead of keeping the old line. */
    private static boolean pathOptimized = false;

    private static final org.slf4j.Logger LOGGER = com.killer560.hub.util.ModLog.get("killer560smod-puzzles");
    /** Logging only: last room state and last scan failure logged, so lines are written on CHANGE, never per tick. */
    private static String roomLogKey = "none";
    private static String scanLogKey = null;
    private static String noPathLogKey = null;

    private static void logRoomState(String key, String detail) {
        if (key.equals(roomLogKey)) {
            return;
        }
        roomLogKey = key;
        scanLogKey = null;
        LOGGER.info("[IceFillSolver] {}", detail);
    }

    private IceFillSolverFeature() {
    }

    /** Copy of the drawn path (block center x/z, floor y + 0.1) - for AutoPuzzles. Empty until all 3 floors scan. */
    public static List<Vec3> getCurrentPath() { return new ArrayList<>(currentPath); }

    public static void register() {
        // Shared solver highlight pipelines must exist before the level renderer precompiles them.
        SolverEspRender.init();
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("IceFillSolverFeature", com.killer560.hub.util.FeatureBits.register("IceFill Solver tick", () -> IceFillSolverConfig.getInstance().isEnabledRaw()), client -> tick(client)));
        // After translucent TERRAIN, not features: water is drawn after the features pass, so a highlight
        // drawn there ended up painted over by any water behind/around it (killer560, 2026-09-21).
        LevelRenderEvents.COLLECT_SUBMITS.register(com.killer560.hub.util.FeatureGuard.collectTerrain(
                com.killer560.hub.util.FeatureBits.register("IceFill Solver render", () -> IceFillSolverConfig.getInstance().isEnabledRaw()), IceFillSolverFeature::onWorldRender));
    }

    private static IceFillData loadData() {
        try (InputStream stream = IceFillSolverFeature.class.getClassLoader()
                .getResourceAsStream("data/killer560smod/puzzles/ice-fill-floors.json")) {
            if (stream == null) {
                // Inline logger: this runs in the static initializer, before LOGGER is assigned.
                com.killer560.hub.util.ModLog.get("killer560smod-puzzles").warn(
                        "[IceFillSolver] floors resource missing - no floor will ever be identified");
                return new IceFillData(List.of(), List.of(), List.of());
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                IceFillData parsed = new Gson().fromJson(reader, IceFillData.class);
                return parsed != null ? parsed : new IceFillData(List.of(), List.of(), List.of());
            }
        } catch (Exception e) {
            com.killer560.hub.util.ModLog.get("killer560smod-puzzles").warn("[IceFillSolver] Failed to load floors data", e);
            return new IceFillData(List.of(), List.of(), List.of());
        }
    }

    private static void tick(Minecraft client) { tickInner(client); }

    private static void tickInner(Minecraft client) {
        // Boss check: NoammAddons e42d3316 "reset when entering boss" (2026-09-14 port).
        if (!IceFillSolverConfig.getInstance().isEnabled() || !DungeonState.isInDungeon() || LiveMapFeature.isInBoss()) {
            if (!"none".equals(roomLogKey)) {
                logRoomState("none", "solver idle: enabled=" + IceFillSolverConfig.getInstance().isEnabled()
                        + " inDungeon=" + DungeonState.isInDungeon() + " inBoss=" + LiveMapFeature.isInBoss()
                        + " - path cleared");
            }
            reset();
            return;
        }
        RoomEntry current = LiveMapFeature.currentRoomEntry();
        inIceFill = current != null && "Ice Fill".equals(current.name);
        if (current != lastRoomEntry) {
            lastRoomEntry = current;
            currentPath.clear();
        }
        if ((current == null || !"Ice Fill".equals(current.name)) && !"none".equals(roomLogKey)) {
            logRoomState("none", "left Ice Fill: now in " + (current == null ? "no known room" : current.name));
        }
        if (!currentPath.isEmpty() && pathOptimized != IceFillSolverConfig.getInstance().isOptimizedPath()) {
            currentPath.clear();
        }
        if (current == null || !"Ice Fill".equals(current.name) || !currentPath.isEmpty()) {
            return;
        }
        int[] clayAndRotation = LiveMapFeature.currentRoomClayAndRotation();
        Level level = client.level;
        if (clayAndRotation == null || level == null) {
            logRoomState("wait", "in Ice Fill but not scanning: clay/rotation known=" + (clayAndRotation != null)
                    + " level=" + (level != null) + " - waiting for the map to resolve the room");
            return;
        }
        String roomKey = "room " + clayAndRotation[0] + "," + clayAndRotation[1] + " rot " + clayAndRotation[2];
        if (!roomKey.equals(roomLogKey)) {
            logRoomState(roomKey, "Ice Fill detected: clay=" + clayAndRotation[0] + "," + clayAndRotation[1]
                    + " rotation=" + clayAndRotation[2] + " floors=" + DATA.identifier().size()
                    + " optimizedPath=" + IceFillSolverConfig.getInstance().isOptimizedPath());
        }
        scanFloors(level, clayAndRotation);
    }

    private static void scanFloors(Level level, int[] clayAndRotation) {
        List<List<List<Pos>>> paths = IceFillSolverConfig.getInstance().isOptimizedPath() ? DATA.hard() : DATA.easy();
        pathOptimized = IceFillSolverConfig.getInstance().isOptimizedPath();
        StringBuilder patterns = new StringBuilder(); // logging only: which layout each floor matched
        for (int floorIndex = 0; floorIndex < DATA.identifier().size(); floorIndex++) {
            List<List<Pos>> floorIdentifiers = DATA.identifier().get(floorIndex);
            boolean found = false;
            for (int patternIndex = 0; patternIndex < floorIdentifiers.size(); patternIndex++) {
                List<Pos> pair = floorIdentifiers.get(patternIndex);
                BlockPos mustBeAir = realPos(pair.get(0), clayAndRotation);
                BlockPos mustBeSolid = realPos(pair.get(1), clayAndRotation);
                if (level.getBlockState(mustBeAir).isAir() && !level.getBlockState(mustBeSolid).isAir()) {
                    if (floorIndex < paths.size() && patternIndex < paths.get(floorIndex).size()) {
                        for (Pos step : paths.get(floorIndex).get(patternIndex)) {
                            BlockPos real = realPos(step, clayAndRotation);
                            currentPath.add(new Vec3(real.getX() + 0.5, real.getY() + 0.1, real.getZ() + 0.5));
                        }
                    } else {
                        String noPathKey = floorIndex + ":" + patternIndex;
                        if (!noPathKey.equals(noPathLogKey)) {
                            noPathLogKey = noPathKey;
                            LOGGER.warn("[IceFillSolver] floor {} matched layout {} but the bundled path list has no"
                                    + " entry for it - that floor draws no line", floorIndex + 1, patternIndex);
                        }
                    }
                    patterns.append(' ').append(floorIndex + 1).append('=').append(patternIndex);
                    found = true;
                    break;
                }
            }
            if (!found) {
                // Odin logs a per-floor failure and still draws whatever floors DID identify; simplified
                // here to retry the whole scan next tick instead - self-healing once the real ice finishes
                // spawning, and avoids ever showing a path that's missing a floor in the middle.
                logScan("miss " + floorIndex, "scan failed: floor " + (floorIndex + 1) + " matched none of its "
                        + floorIdentifiers.size() + " layouts (floors matched so far:" + patterns
                        + ") - ice not spawned/loaded yet or wrong clay/rotation; retrying every tick");
                currentPath.clear();
                return;
            }
        }
        // Keyed: an all-empty path leaves currentPath empty and the scan runs again next tick.
        logScan("solved" + patterns, "solution computed: layouts" + patterns + " -> path of " + currentPath.size()
                + " points, " + (IceFillSolverConfig.getInstance().isOptimizedPath() ? "optimized" : "easy")
                + " path, starts at " + (currentPath.isEmpty() ? "none" : String.format(java.util.Locale.ROOT,
                "%.1f,%.1f,%.1f", currentPath.get(0).x, currentPath.get(0).y, currentPath.get(0).z)));
    }

    /** Logging only: a scan line, once per distinct reason until the room state changes. */
    private static void logScan(String key, String detail) {
        if (key.equals(scanLogKey)) {
            return;
        }
        scanLogKey = key;
        LOGGER.info("[IceFillSolver] {}", detail);
    }

    /** Delegating to {@link PuzzleCoords#real} - see the note there about the sim's floor shift. */
    private static BlockPos realPos(Pos pos, int[] clayAndRotation) {
        return PuzzleCoords.real(pos.x(), pos.y(), pos.z(), clayAndRotation);
    }

    private static void onWorldRender(LevelRenderContext context) {
        if (!IceFillSolverConfig.getInstance().isEnabled() || currentPath.isEmpty()) {
            return;
        }
        RoomEntry current = LiveMapFeature.currentRoomEntry();
        if (current == null || !"Ice Fill".equals(current.name)) {
            return;
        }
        // killer560, 2026-09-20: "ice fill's line shouldn't be blue, hard to see - make it configurable".
        int argb = IceFillSolverConfig.getInstance().getLineColorArgb();
        float a = ((argb >>> 24) & 0xFF) / 255f;
        SolverEspRender.renderLineStrip(com.killer560.hub.puzzlesolvers.SolverEspConfig.Solver.ICE_FILL, context, currentPath,
                ((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f, a <= 0f ? 1f : a, 3f);
    }

    private static void reset() {
        lastRoomEntry = null;
        currentPath.clear();
        inIceFill = false;
    }


    /** He is in the Ice Fill room (the map says so), set every tick by the solver's tick. */
    private static boolean inIceFill = false;
    private static int blockedWarps = 0;
    private static long lastBlockChatMs = 0L;
    private static String lastVerdict = "none";

    /**
     * Etherwarp Protection (killer560, 2026-11-01): "If i am on the ice and etherwarp and the etherwarp would put me to the
     * propper next spot then it will allow it as long as the block below my feet is already updated to the packed or blue
     * ice thing that it updates too. If the block i have chosen is wrong or the block below my feet isnt updated then it
     * wont take the etherwarp. Also if i am not standing on ice at all but in the room then i should be able to etherwarp
     * freely."
     *
     * <p>The tile under his feet decides: plain ICE (not registered yet) blocks every warp; packed or blue ice (registered)
     * allows only a warp whose landing is the next tile of the drawn path (or the next tile still to fill, so a warp from
     * the end of one floor onto the next floor's first tile is not refused); anything else (the stone around the floors,
     * the stairs) allows everything. With no path known, nothing is refused.
     *
     * @param feet   where he stands
     * @param target the block the etherwarp would land ON (the overlay's own trace), or null when it would fail
     * @return "allow: ..." or "block: ..."
     */
    public static String etherwarpVerdict(Level level, Vec3 feet, BlockPos target, List<Vec3> path) {
        BlockPos here = BlockPos.containing(feet.x, feet.y + 0.01, feet.z);
        var under = level.getBlockState(here.below());
        if (under.is(net.minecraft.world.level.block.Blocks.ICE)) {
            return "block: the tile under you has not turned to packed ice yet";
        }
        if (!under.is(net.minecraft.world.level.block.Blocks.PACKED_ICE)
                && !under.is(net.minecraft.world.level.block.Blocks.BLUE_ICE)) {
            return "allow: not standing on the ice";
        }
        if (path == null || path.isEmpty()) {
            return "allow: no path known";
        }
        if (target == null) {
            return "allow: the etherwarp has nowhere to land";
        }
        List<BlockPos> tiles = pathTiles(path);
        int at = -1;
        for (int i = 0; i < tiles.size(); i++) {
            BlockPos t = tiles.get(i);
            if (t.getX() == here.getX() && t.getZ() == here.getZ() && Math.abs(t.getY() - here.getY()) <= 1) {
                at = i;
            }
        }
        if (at < 0) {
            return "block: you are on packed ice off the path";
        }
        BlockPos landing = target.above();
        if (at + 1 < tiles.size() && sameTile(tiles.get(at + 1), landing)) {
            return "allow: the next spot on the path";
        }
        for (int j = at + 1; j < tiles.size(); j++) {
            if (level.getBlockState(tiles.get(j).below()).is(net.minecraft.world.level.block.Blocks.ICE)) {
                if (sameTile(tiles.get(j), landing)) {
                    return "allow: the next tile still to fill";
                }
                break;
            }
        }
        return "block: " + landing.toShortString() + " is not the next spot on the path";
    }

    private static boolean sameTile(BlockPos tile, BlockPos landing) {
        return tile.getX() == landing.getX() && tile.getZ() == landing.getZ() && Math.abs(tile.getY() - landing.getY()) <= 1;
    }

    /** The path as the blocks his feet pass through, in order: each straight run filled in, repeats dropped. */
    static List<BlockPos> pathTiles(List<Vec3> path) {
        List<BlockPos> out = new ArrayList<>();
        for (int i = 0; i < path.size(); i++) {
            Vec3 p1 = path.get(i);
            if (i + 1 < path.size()) {
                Vec3 p2 = path.get(i + 1);
                double dx = p2.x - p1.x;
                double dy = p2.y - p1.y;
                double dz = p2.z - p1.z;
                int steps = (int) Math.max(1, Math.round(Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)))));
                for (int s = 0; s < steps; s++) {
                    double r = (double) s / steps;
                    addTile(out, BlockPos.containing(p1.x + dx * r, p1.y + dy * r, p1.z + dz * r));
                }
            } else {
                addTile(out, BlockPos.containing(p1));
            }
        }
        return out;
    }

    private static void addTile(List<BlockPos> out, BlockPos p) {
        if (out.isEmpty() || !out.get(out.size() - 1).equals(p)) {
            out.add(p);
        }
    }

    /**
     * From {@code IceFillEtherwarpMixin} at the start of vanilla's right click: true to drop it (nothing is sent). Only
     * his own right click (and Left Click Etherwarp, which goes through the same method); Auto Ice Fill's warps do not
     * come this way, and Auto Routes / Auto Clear holding the use key are let through.
     */
    public static boolean shouldBlockRightClick(Minecraft client) {
        IceFillSolverConfig cfg = IceFillSolverConfig.getInstance();
        if (!cfg.isEnabled() || !cfg.isEtherwarpProtection() || !inIceFill || client.player == null
                || client.level == null || !DungeonState.isInDungeon() || LiveMapFeature.isInBoss()) {
            return false;
        }
        if (com.killer560.hub.autoroutes.RouteExecutor.isRunning() || com.killer560.hub.autoclear.AutoClearFeature.isBusy()) {
            return false;
        }
        double distance = com.killer560.hub.etherwarpoverlay.EtherwarpOverlayFeature.etherwarpDistance(client.player);
        if (distance <= 0) {
            return false;
        }
        Vec3 feet = client.player.position();
        // Judged on the warp the SERVER will do, not the one the crosshair shows (icefill-1102): Hypixel traces the
        // etherwarp along the look and sneak it last heard in a movement packet, from where that packet put him, and a
        // right click made while the camera turns goes before this tick's movement packet. His run 2026-11-02 17:06:08
        // was allowed on the crosshair's -121 70 -57 (the next tile) and landed on the heard look's -121 70 -56, diagonal:
        // "Don't move diagonally! Bad!" - twice. A refused click is tried again next tick by vanilla (the use key's
        // delay is only set by a click that goes), by when the server has heard the look, so holding it costs one tick.
        BlockPos aimed = com.killer560.hub.etherwarpoverlay.EtherwarpOverlayFeature.resolveTarget(client.level, feet,
                client.player, distance);
        float[] heard = com.killer560.hub.util.RightClick.heardLook();
        boolean heardSneak = client.player.getLastSentInput().shift();
        BlockPos target = heard == null ? aimed
                : com.killer560.hub.etherwarpoverlay.EtherwarpOverlayFeature.resolveTargetAlong(client.level,
                com.killer560.hub.util.RightClick.heardFeet(client.player), client.player, distance, heard[0], heard[1],
                heardSneak);
        String verdict = etherwarpVerdict(client.level, feet, target, currentPath);
        if (verdict.startsWith("block") && !java.util.Objects.equals(target, aimed)) {
            verdict = verdict + " (the server still has last tick's look: it lands on "
                    + (target == null ? "nothing" : target.above().toShortString()) + ", not "
                    + (aimed == null ? "nothing" : aimed.above().toShortString()) + ")";
        }
        lastVerdict = verdict;
        if (!verdict.startsWith("block")) {
            return false;
        }
        blockedWarps++;
        LOGGER.info("[IceFillSolver] Etherwarp Protection: warp to {} not sent - {} ({} blocked so far)",
                target == null ? "nowhere" : target.toShortString(), verdict.substring(7), blockedWarps);
        long now = System.currentTimeMillis();
        if (now - lastBlockChatMs > 2000L) {
            lastBlockChatMs = now;
            com.killer560.hub.util.ModChat.send("Ice Fill Solver", com.killer560.hub.util.ModChat.text("Etherwarp blocked: "),
                    com.killer560.hub.util.ModChat.dim(verdict.substring(7) + "."));
        }
        return true;
    }

    /** For the testkit: warps Etherwarp Protection has dropped, and its last verdict. */
    public static int blockedWarps() { return blockedWarps; }

    public static String lastVerdict() { return lastVerdict; }
}
