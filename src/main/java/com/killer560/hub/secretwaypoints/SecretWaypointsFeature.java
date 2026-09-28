package com.killer560.hub.secretwaypoints;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.roomdatabase.RoomDatabase;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.secrets.DungeonState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Real, preloaded per-room secret waypoints - killer560's "preloaded waypoints for each room" request.
 * Every position rendered here comes from the real room database (see {@link com.killer560.hub.roomdatabase})
 * once a room is identified AND its real rotation/corner is found, transformed with the exact same
 * rotate+translate math NoammAddons' own {@code ScanUtils.getRealCoord} uses - not a guess, and not
 * hand-placed.
 *
 * <h2>Cost (2026-09-20 FPS pass)</h2>
 * This used to rebuild and draw <em>every secret of every identified room in the dungeon</em> on every
 * single frame: {@code identifiedRoomsWithRotation()} (a fresh {@code ArrayList} plus one {@code int[4]}
 * per room), then per secret a {@code RoomDatabase.toRealCoord}, a {@code new AABB} and two
 * {@code pushPose}/{@code popPose} + camera lookups - roughly 150 boxes in an F7, most of them on the far
 * side of the map. Now the transformed boxes are built at most once per second (or once per 8 blocks of
 * movement) on the client tick, only for rooms within the configured render distance, and the per-frame
 * path is a plain indexed walk of that snapshot with a squared-distance test and one matrix push for the
 * whole batch.
 *
 * <h2>Mimic detection</h2>
 * The ROOM-WIDE trapped-chest count this heading used to describe was removed 2026-09-20 at killer560's
 * request (change 62) - it walked a whole room's 33x33x41 block volume (~45k {@code getBlockState} calls)
 * once a second for every identified room within 48 blocks just to produce a number nothing consumed. What
 * replaced it 2026-09-27 is much narrower and cheap by comparison: "if a chest is ever a mimic then it
 * should be red on the cheat version. You can tell because it will be a trapped chest." That only ever
 * checks the world block at a secret CHEST's own already-known position (see {@link #addGroup}'s
 * {@code isMimic} call) - one lookup per chest secret in the current room, not a volume scan - and only on
 * the cheat build ({@code BuildVariant.CHEAT_FEATURES_ENABLED}).
 */
public final class SecretWaypointsFeature {

    /** What a waypoint actually marks - only used to pick the HITBOX-mode box shape. */
    enum Kind {
        /** Vanilla chest block shape: 14/16 wide and deep, 14/16 tall, inset 1/16. */
        CHEST,
        /** A dropped item (secret item, redstone key): 0.25 cube on the floor. */
        ITEM,
        /** A secret bat: 0.5 wide and deep, 0.9 tall. */
        BAT,
        /** killer560, 2026-09-27: "the wither essence one is too small and should be the size of a Minecraft
         *  skull" - a floor {@code SkullBlock}'s real shape ({@code Block.box(4,0,4,12,8,12)}), not the small
         *  dropped-item cube every other ITEM secret uses. */
        WITHER,
        /** killer560, 2026-09-27: "it also needs to highlight levers just like it does secrets but only during
         *  clear" - see the lever scan in {@link #rebuild}. Not part of the room database (NoammAddons has no
         *  lever secret coords), so these are found live rather than preloaded from a room's known positions. */
        LEVER
    }

    /** The name shown with Show Names on. */
    private static String labelFor(Kind kind, String group) {
        return switch (group) {
            case "key" -> "Redstone Key";
            case "lever" -> "Lever";
            default -> switch (kind) {
                case CHEST -> "Chest";
                case BAT -> "Bat";
                case ITEM -> "Item";
                case WITHER -> "Wither Essence";
                case LEVER -> "Lever";
            };
        };
    }

    /** One ready-to-draw box. Built on the tick, consumed by {@link SecretWaypointsRenderer} on the frame. */
    public record Waypoint(AABB box, double centerX, double centerY, double centerZ,
                           float r, float g, float b, float a, BlockPos pos, Kind kind, String label) {
    }

    /** Secrets already taken in this run (block positions from the room database) - their waypoints are gone
     *  (killer560, 2026-09-21: "if I click/get a secret then that waypoint should disappear"). Cleared on leaving
     *  the dungeon. */
    private static final java.util.Set<BlockPos> COLLECTED = new java.util.HashSet<>();
    /** Item / bat entities near you last tick (id -> position), to notice one being picked up / killed. */
    private static final java.util.Map<Integer, net.minecraft.world.phys.Vec3> NEAR_ITEMS = new java.util.HashMap<>();
    private static final java.util.Map<Integer, net.minecraft.world.phys.Vec3> NEAR_BATS = new java.util.HashMap<>();

    private static boolean wasInDungeon = false;

    // [SecretWaypoints] diagnostics - logging only.
    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-secretwaypoints");
    private static String lastLoggedGates = null;
    private static String lastLoggedWaypointSummary = null;
    private static long lastWaypointSummaryMs = 0;

    /** Interactive map per-room toggles (room names): shown while the feature is off, hidden while it is on. */

    /** The per-tick snapshot the render path walks. Never rebuilt from inside a frame. */
    private static final List<Waypoint> CACHED = new ArrayList<>();
    private static long cacheStampMs = 0L;
    private static double cacheX = Double.NaN;
    private static double cacheZ = Double.NaN;

    /** How long a snapshot may live before a room that has just been identified gets picked up. */
    private static final long CACHE_TTL_MS = 1000L;
    /** ...and how far the player may walk before it is rebuilt early. Squared. */
    private static final double CACHE_MOVE_SQ = 8.0 * 8.0;
    /** Rooms are pre-filtered at render distance + this, to cover the staleness the two limits above allow. */
    private static final double CACHE_MARGIN = 16.0;

    private SecretWaypointsFeature() {
    }

    /**
     * Whether this room's waypoints render - now simply whether the feature is on.
     * <p>
     * There used to be a per-room override here, flipped by right-clicking a room on the Interactive Map, which
     * could even draw a room's waypoints while the feature itself was off. killer560 removed that control
     * (2026-09-27: "The toggle waypoint shouldn't exist", and "Do not have the map show the waypoints loaded or
     * anything"), so the override had no way left to be set and the two sets behind it could only ever be empty -
     * dead state that still had to be reasoned about at every call. Gone with it.
     */
    public static boolean isRoomShown(String roomName) {
        return roomName != null && SecretWaypointsConfig.getInstance().isEnabled();
    }

    /** Forces the next client tick to rebuild the waypoint snapshot (config change, room toggle, world change). */
    public static void invalidateCache() {
        cacheStampMs = 0L;
    }

    public static void register() {
        SecretWaypointsRenderer.init();
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("SecretWaypointsFeature", client -> tick()));
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(SecretWaypointsFeature::onWorldRender);
        // A chest, wither essence or redstone key is taken by right-clicking its block - exactly its waypoint's block.
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() && hit != null) {
                markCollected(hit.getBlockPos(), null, 0);
            }
            return net.minecraft.world.InteractionResult.PASS;
        });
    }

    /**
     * Marks a secret taken. {@code kind == null}: the exact block (a click). ITEM / BAT: the nearest waypoint of
     * that kind within {@code maxDist} blocks of where it happened - NoammAddons' own radii (items 5, bats 12),
     * since an item can be kicked around and a bat flies before it dies.
     */
    private static void markCollected(BlockPos pos, Kind kind, double maxDist) {
        Waypoint best = null;
        double bestSq = maxDist * maxDist;
        for (Waypoint w : CACHED) {
            if (kind == null) {
                if (w.pos().equals(pos)) {
                    best = w;
                    break;
                }
                continue;
            }
            if (w.kind() != kind || COLLECTED.contains(w.pos())) {
                continue;
            }
            double d = w.pos().distSqr(pos);
            if (d <= bestSq) {
                bestSq = d;
                best = w;
            }
        }
        if (best != null && COLLECTED.add(best.pos())) {
            invalidateCache();
        }
    }

    /** Items picked up (they vanish while you're next to them) and bats killed near you. */
    private static void watchPickups(Minecraft client) {
        if (client.level == null || client.player == null || CACHED.isEmpty()) {
            NEAR_ITEMS.clear();
            NEAR_BATS.clear();
            return;
        }
        var player = client.player;
        AABB around = player.getBoundingBox().inflate(8.0);
        java.util.Map<Integer, net.minecraft.world.phys.Vec3> items = new java.util.HashMap<>();
        for (var e : client.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, around)) {
            items.put(e.getId(), e.position());
        }
        // Bats: a bat that flies out of range also leaves the entity list, so only a bat actually seen DYING
        // counts (its death animation keeps it around client-side for a moment) - once per bat.
        java.util.Map<Integer, net.minecraft.world.phys.Vec3> bats = new java.util.HashMap<>();
        for (var e : client.level.getEntitiesOfClass(net.minecraft.world.entity.ambient.Bat.class, around.inflate(8.0))) {
            if (e.isDeadOrDying()) {
                if (!NEAR_BATS.containsKey(e.getId())) {
                    markCollected(e.blockPosition(), Kind.BAT, 12.0);
                }
                bats.put(e.getId(), e.position());
            }
        }
        for (var gone : NEAR_ITEMS.entrySet()) {
            // Only an item that vanished right next to YOU (pickup range) - one a teammate grabbed a few blocks away,
            // or that merged / despawned, must not hide your waypoint - and only a waypoint right where it lay.
            if (!items.containsKey(gone.getKey())
                    && player.getBoundingBox().inflate(3.0, 1.5, 3.0).contains(gone.getValue())) {
                markCollected(BlockPos.containing(gone.getValue()), Kind.ITEM, 2.5);
            }
        }
        NEAR_ITEMS.clear();
        NEAR_ITEMS.putAll(items);
        NEAR_BATS.clear();
        NEAR_BATS.putAll(bats);
    }

    private static void logDiagnostics(boolean inDungeon) {
        SecretWaypointsConfig cfg = SecretWaypointsConfig.getInstance();
        String gates = "enabled=" + cfg.isEnabled() + " throughWalls=" + cfg.isThroughWalls()
                + " boxSize=" + cfg.getBoxSize() + " renderDistance=" + cfg.getRenderDistance()
                + " inDungeon=" + inDungeon
                // 2026-09-14: room scanning no longer requires Live Map to be enabled (see
                // LiveMapFeature.scanConsumers) - these confirm scanning is actually feeding this feature.
                + " roomDbReady=" + RoomDatabase.isReady() + " inBoss=" + LiveMapFeature.isInBoss();
        if (!gates.equals(lastLoggedGates)) {
            LOGGER.info("[SecretWaypoints] Gates changed: {}", gates);
            lastLoggedGates = gates;
        }
        long now = System.currentTimeMillis();
        if (!inDungeon || now - lastWaypointSummaryMs < 1000) {
            return;
        }
        lastWaypointSummaryMs = now;
        StringBuilder sb = new StringBuilder();
        int rooms = 0;
        int waypoints = 0;
        for (int[] room : LiveMapFeature.identifiedRoomsWithRotation()) {
            RoomEntry entry = LiveMapFeature.roomEntryAt(room[0]);
            if (entry == null) {
                continue;
            }
            rooms++;
            int count = 0;
            if (entry.secretCoords != null) {
                count += entry.secretCoords.chest != null ? entry.secretCoords.chest.size() : 0;
                count += entry.secretCoords.item != null ? entry.secretCoords.item.size() : 0;
                count += entry.secretCoords.wither != null ? entry.secretCoords.wither.size() : 0;
                count += entry.secretCoords.bat != null ? entry.secretCoords.bat.size() : 0;
                count += entry.secretCoords.redstoneKey != null ? entry.secretCoords.redstoneKey.size() : 0;
            }
            waypoints += count;
            sb.append(entry.name).append("[rot=").append(room[3]).append(" clay=").append(room[1]).append(',')
                    .append(room[2]).append(" wps=").append(count).append(entry.secretCoords == null ? " NO-COORDS" : "")
                    .append("] ");
        }
        String summary = rooms + " rooms / " + waypoints + " waypoints (" + CACHED.size() + " within range): "
                + sb.toString().trim();
        if (!summary.equals(lastLoggedWaypointSummary)) {
            LOGGER.info("[SecretWaypoints] Renderable rooms changed: {}", summary);
            lastLoggedWaypointSummary = summary;
        }
    }

    private static void tick() {
        boolean inDungeon = DungeonState.isInDungeon();
        if (!inDungeon && wasInDungeon) {
            COLLECTED.clear();
            invalidateCache();
        }
        wasInDungeon = inDungeon;
        logDiagnostics(inDungeon);
        refreshCacheIfStale(inDungeon);
        watchPickups(Minecraft.getInstance());
    }

    /** Rebuilds {@link #CACHED} at most once per {@link #CACHE_TTL_MS} / {@link #CACHE_MOVE_SQ}, on the tick. */
    private static void refreshCacheIfStale(boolean inDungeon) {
        SecretWaypointsConfig cfg = SecretWaypointsConfig.getInstance();
        // The "a per-room toggle draws even while the feature is off" case went with the map's toggle - see
        // isRoomShown. Off now simply means off.
        Minecraft client = Minecraft.getInstance();
        if (!cfg.isEnabled() || !inDungeon || client.player == null) {
            CACHED.clear();
            cacheStampMs = 0L;
            return;
        }
        double px = client.player.getX();
        double pz = client.player.getZ();
        long now = System.currentTimeMillis();
        double movedSq = Double.isNaN(cacheX) ? Double.MAX_VALUE
                : (px - cacheX) * (px - cacheX) + (pz - cacheZ) * (pz - cacheZ);
        if (cacheStampMs != 0L && now - cacheStampMs < CACHE_TTL_MS && movedSq < CACHE_MOVE_SQ) {
            return;
        }
        cacheStampMs = now;
        cacheX = px;
        cacheZ = pz;
        rebuild(cfg, px, pz);
    }

    private static void rebuild(SecretWaypointsConfig cfg, double px, double pz) {
        CACHED.clear();
        // Only the room you are in (killer560, 2026-09-21: "only show the waypoints for the room I am currently in
        // and remove the render distance option"). Rooms spanning several tiles are listed once per tile, so the
        // name decides and duplicate positions are skipped.
        // The exact room INSTANCE you stand in, not its name - a run can hold two rooms of the same template.
        int currentIdx = LiveMapFeature.currentRoomIndex();
        if (currentIdx < 0) {
            return;
        }
        java.util.Set<BlockPos> seen = new java.util.HashSet<>();
        for (int[] room : LiveMapFeature.identifiedRoomsWithRotation()) {
            RoomEntry entry = LiveMapFeature.roomEntryAt(room[0]);
            if (entry == null || entry.secretCoords == null || !isRoomShown(entry.name) || !roomHasCell(room[0], currentIdx)) {
                continue;
            }
            int clayX = room[1];
            int clayZ = room[2];
            int rotation = room[3];
            addGroup(entry.secretCoords.chest, clayX, clayZ, rotation, cfg.getChestColor(), Kind.CHEST, "chest", cfg, seen);
            addGroup(entry.secretCoords.item, clayX, clayZ, rotation, cfg.getItemColor(), Kind.ITEM, "item", cfg, seen);
            addGroup(entry.secretCoords.wither, clayX, clayZ, rotation, cfg.getWitherColor(), Kind.WITHER, "wither", cfg, seen);
            addGroup(entry.secretCoords.bat, clayX, clayZ, rotation, cfg.getBatColor(), Kind.BAT, "bat", cfg, seen);
            addGroup(entry.secretCoords.redstoneKey, clayX, clayZ, rotation, cfg.getRedstoneKeyColor(), Kind.ITEM, "key", cfg, seen);
        }
        // killer560, 2026-09-27: "it also needs to highlight levers just like it does secrets but only during
        // clear." Not in the room database, so scanned live - see scanLevers' own doc. "Only during clear" needs
        // no extra check here: currentRoomIndex() (the caller's currentIdx, above) is already -1 in boss, which
        // returned out of this method before this point was ever reached.
        scanLevers(cfg, currentIdx, seen);
    }

    /** killer560, 2026-09-27: "it also needs to highlight levers just like it does secrets but only during
     *  clear... You can choose the color for levers." NoammAddons' room database has no lever secret coords at
     *  all (levers aren't one of its five secret types), so unlike every other group in {@link #rebuild} these
     *  can't be looked up - they're found by scanning the room's own real footprint for actual
     *  {@code minecraft:lever} blocks.
     *  <p>
     *  Cost: the removed mimic-chest check (see this class's own doc above) walked a whole room's 33x33x41
     *  volume once a second and that was flagged as expensive only because it ran for EVERY identified room
     *  within range at once; this walks the exact same shape of volume but only for the ONE room
     *  {@link #rebuild} already narrowed everything else down to, on the same once-a-second/8-block cache. Same
     *  fixed floor-relative Y band as that removed check: dungeon room floors sit at y=68 (see
     *  {@code LiveMapFeature.classifyDoor}, which reads the door-colour block one above it) and 41 levels
     *  covers every normal and tall room without deriving a per-room ceiling. */
    private static final int LEVER_SCAN_MIN_Y = 68;
    private static final int LEVER_SCAN_MAX_Y = 108;

    private static void scanLevers(SecretWaypointsConfig cfg, int currentIdx, java.util.Set<BlockPos> seen) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return;
        }
        int[] bounds = LiveMapFeature.roomWorldBounds(currentIdx);
        if (bounds == null) {
            return;
        }
        int argb = cfg.getLeverColor();
        float a = ((argb >> 24) & 0xFF) / 255f;
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        if (a <= 0f) {
            a = 1f;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = bounds[0]; x <= bounds[2]; x++) {
            for (int z = bounds[1]; z <= bounds[3]; z++) {
                for (int y = LEVER_SCAN_MIN_Y; y <= LEVER_SCAN_MAX_Y; y++) {
                    pos.set(x, y, z);
                    if (!client.level.isLoaded(pos) || client.level.getBlockState(pos).getBlock() != Blocks.LEVER) {
                        continue;
                    }
                    BlockPos real = pos.immutable();
                    if (!seen.add(real)) {
                        continue;
                    }
                    AABB box = boxFor(real, Kind.LEVER, cfg.getBoxSize());
                    CACHED.add(new Waypoint(box,
                            (box.minX + box.maxX) * 0.5, (box.minY + box.maxY) * 0.5, (box.minZ + box.maxZ) * 0.5,
                            r, g, b, a, real, Kind.LEVER, "Lever"));
                }
            }
        }
    }

    /** Whether {@code cell} is one of the tiles of the room whose main tile is {@code mainIdx} (2x2, L and 1x4 rooms). */
    private static boolean roomHasCell(int mainIdx, int cell) {
        if (mainIdx == cell) {
            return true;
        }
        int[] cells = LiveMapFeature.roomCellIndices(mainIdx);
        if (cells != null) {
            for (int c : cells) {
                if (c == cell) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void addGroup(List<RoomEntry.Pos> positions, int clayX, int clayZ, int rotation, int argb,
                                 Kind kind, String group, SecretWaypointsConfig cfg, java.util.Set<BlockPos> seen) {
        if (positions == null || positions.isEmpty()) {
            return;
        }
        float a = ((argb >> 24) & 0xFF) / 255f;
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        if (a <= 0f) {
            a = 1f;
        }
        for (RoomEntry.Pos relative : positions) {
            BlockPos real = RoomDatabase.toRealCoord(relative, clayX, clayZ, rotation);
            if (COLLECTED.contains(real) || !seen.add(real)) {
                continue;
            }
            float wr = r;
            float wg = g;
            float wb = b;
            // killer560, 2026-09-27: "if a chest is ever a mimic then it should be red on the cheat version.
            // You can tell because it will be a trapped chest." A real secret chest is always a plain
            // minecraft:chest; a Mimic disguises itself as the SAME secret position but as a trapped chest
            // block, which the room database's own hash-based room identification already treats as
            // cosmetic variance (see RoomDatabase.IGNORED_CORE_BLOCKS) - i.e. it can't tell them apart
            // either, so this has to be a live world check, same as every other cheat-build detector in this
            // mod. Cheat-only: knowing a chest is a monster before it attacks you is exactly the kind of
            // information advantage the rest of this mod's cheat features are already gated on.
            if (kind == Kind.CHEST && com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && isMimic(real)) {
                wr = 1f;
                wg = 0f;
                wb = 0f;
            }
            AABB box = boxFor(real, kind, cfg.getBoxSize());
            CACHED.add(new Waypoint(box,
                    (box.minX + box.maxX) * 0.5, (box.minY + box.maxY) * 0.5, (box.minZ + box.maxZ) * 0.5,
                    wr, wg, wb, a, real, kind, labelFor(kind, group)));
        }
    }

    /** See the mimic note in {@link #addGroup}. */
    private static boolean isMimic(BlockPos pos) {
        var level = Minecraft.getInstance().level;
        return level != null && level.isLoaded(pos) && level.getBlockState(pos).getBlock() == Blocks.TRAPPED_CHEST;
    }

    /** killer560 (change 62): full-block waypoint vs hitbox-only waypoint. The HITBOX numbers are the real
     *  vanilla shapes of what the waypoint marks, not invented sizes. */
    private static AABB boxFor(BlockPos pos, Kind kind, SecretWaypointsConfig.BoxSize size) {
        double x = pos.getX();
        double y = pos.getY();
        double z = pos.getZ();
        if (size == SecretWaypointsConfig.BoxSize.FULL_BLOCK) {
            return new AABB(x, y, z, x + 1, y + 1, z + 1);
        }
        return switch (kind) {
            case CHEST -> new AABB(x + 0.0625, y, z + 0.0625, x + 0.9375, y + 0.875, z + 0.9375);
            case ITEM -> new AABB(x + 0.375, y, z + 0.375, x + 0.625, y + 0.25, z + 0.625);
            case BAT -> new AABB(x + 0.25, y, z + 0.25, x + 0.75, y + 0.9, z + 0.75);
            // killer560, 2026-09-27: "the wither essence one is too small and should be the size of a
            // Minecraft skull" - vanilla's real floor SkullBlock shape (Block.box(4,0,4,12,8,12), i.e.
            // 8/16 wide and deep, 8/16 tall), not the small dropped-item cube ITEM uses.
            case WITHER -> new AABB(x + 0.25, y, z + 0.25, x + 0.75, y + 0.5, z + 0.75);
            // No vanilla footprint to copy (a lever's real hitbox depends on which face it's mounted on
            // and is thin either way) - close to ITEM's box but a little larger, closer to how big a lever
            // actually reads on screen.
            case LEVER -> new AABB(x + 0.3125, y, z + 0.3125, x + 0.6875, y + 0.375, z + 0.6875);
        };
    }

    private static void onWorldRender(LevelRenderContext context) {
        // The tick decides what is in here; an empty snapshot means "off, not in a dungeon, or nothing near".
        if (CACHED.isEmpty()) {
            return;
        }
        SecretWaypointsConfig cfg = SecretWaypointsConfig.getInstance();
        // Everything cached is in your room, so no distance limit is applied any more.
        SecretWaypointsRenderer.draw(context, CACHED, cfg.getStyle(), cfg.isThroughWalls(), SecretWaypointsConfig.MAX_RENDER_DISTANCE);
        if (cfg.isShowNames()) {
            drawNames(context);
        }
    }

    /** The secret's name floating just above its box, always facing you, drawn through walls. */
    private static void drawNames(LevelRenderContext ctx) {
        var bufferSource = ctx.bufferSource();
        var poseStack = ctx.poseStack();
        if (bufferSource == null || poseStack == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        var camera = mc.gameRenderer.getMainCamera();
        var cam = camera.position();
        var font = mc.font;
        for (Waypoint w : CACHED) {
            double x = w.centerX();
            double y = w.box().maxY + 0.35;
            double z = w.centerZ();
            double dist = Math.sqrt(cam.distanceToSqr(x, y, z));
            float s = 0.025f * (float) Math.min(6.0, Math.max(1.0, dist / 10.0));
            int color = 0xFF000000 | ((int) (w.r() * 255) << 16) | ((int) (w.g() * 255) << 8) | (int) (w.b() * 255);
            if ((color & 0xFFFFFF) == 0) {
                color = 0xFFAAAAAA; // a black (essence) label would be invisible
            }
            poseStack.pushPose();
            try {
                poseStack.translate(x - cam.x, y - cam.y, z - cam.z);
                poseStack.mulPose(camera.rotation());
                poseStack.scale(s, -s, s);
                font.drawInBatch(w.label(), -font.width(w.label()) / 2f, -font.lineHeight / 2f, color, false,
                        poseStack.last().pose(), bufferSource, net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH,
                        0, 0xF000F0);
            } finally {
                poseStack.popPose();
            }
        }
    }
}
