package com.killer560.hub.secretwaypoints;

import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.roomdatabase.RoomDatabase;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.secrets.DungeonState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
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
 * Removed 2026-09-20 at killer560's request (change 62). Nothing else in the repo consumed it - the only
 * other references were this feature's own config flag and its button in {@code SecretWaypointsTab}. It was
 * also a hidden cost of its own: the trapped-chest count walked a whole room's 33x33x41 block volume
 * (~45k {@code getBlockState} calls) once a second for every identified room within 48 blocks.
 */
public final class SecretWaypointsFeature {

    /** What a waypoint actually marks - only used to pick the HITBOX-mode box shape. */
    enum Kind {
        /** Vanilla chest block shape: 14/16 wide and deep, 14/16 tall, inset 1/16. */
        CHEST,
        /** A dropped item (secret item, wither essence, redstone key): 0.25 cube on the floor. */
        ITEM,
        /** A secret bat: 0.5 wide and deep, 0.9 tall. */
        BAT
    }

    /** The name shown with Show Names on. */
    private static String labelFor(Kind kind, String group) {
        return switch (group) {
            case "wither" -> "Wither Essence";
            case "key" -> "Redstone Key";
            default -> switch (kind) {
                case CHEST -> "Chest";
                case BAT -> "Bat";
                case ITEM -> "Item";
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
    private static final java.util.Set<String> shownRooms = new java.util.HashSet<>();
    private static final java.util.Set<String> hiddenRooms = new java.util.HashSet<>();

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

    /** Interactive map: flips whether this room's waypoints render. @return the new shown state. */
    public static boolean toggleRoom(String roomName) {
        if (roomName == null) {
            return false;
        }
        boolean shown = !isRoomShown(roomName);
        if (SecretWaypointsConfig.getInstance().isEnabled()) {
            if (shown) hiddenRooms.remove(roomName); else hiddenRooms.add(roomName);
        } else {
            if (shown) shownRooms.add(roomName); else shownRooms.remove(roomName);
        }
        invalidateCache();
        return shown;
    }

    public static boolean isRoomShown(String roomName) {
        if (roomName == null) {
            return false;
        }
        return SecretWaypointsConfig.getInstance().isEnabled() ? !hiddenRooms.contains(roomName) : shownRooms.contains(roomName);
    }

    /** Forces the next client tick to rebuild the waypoint snapshot (config change, room toggle, world change). */
    public static void invalidateCache() {
        cacheStampMs = 0L;
    }

    public static void register() {
        SecretWaypointsRenderer.init();
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(SecretWaypointsFeature::onWorldRender);
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
            if (!items.containsKey(gone.getKey()) && player.position().distanceToSqr(gone.getValue()) <= 36.0) {
                markCollected(BlockPos.containing(gone.getValue()), Kind.ITEM, 5.0);
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
            shownRooms.clear();
            hiddenRooms.clear();
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
        // Per-room toggles from the Interactive Map still draw while the feature itself is off.
        boolean perRoomOnly = !cfg.isEnabled() && !shownRooms.isEmpty() && com.killer560.hub.util.SkyblockGate.allows();
        Minecraft client = Minecraft.getInstance();
        if ((!cfg.isEnabled() && !perRoomOnly) || !inDungeon || client.player == null) {
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
        RoomEntry current = LiveMapFeature.currentRoomEntry();
        if (current == null) {
            return;
        }
        java.util.Set<BlockPos> seen = new java.util.HashSet<>();
        for (int[] room : LiveMapFeature.identifiedRoomsWithRotation()) {
            RoomEntry entry = LiveMapFeature.roomEntryAt(room[0]);
            if (entry == null || entry.secretCoords == null || !isRoomShown(entry.name)
                    || !entry.name.equals(current.name)) {
                continue;
            }
            int clayX = room[1];
            int clayZ = room[2];
            int rotation = room[3];
            addGroup(entry.secretCoords.chest, clayX, clayZ, rotation, cfg.getChestColor(), Kind.CHEST, "chest", cfg, seen);
            addGroup(entry.secretCoords.item, clayX, clayZ, rotation, cfg.getItemColor(), Kind.ITEM, "item", cfg, seen);
            addGroup(entry.secretCoords.wither, clayX, clayZ, rotation, cfg.getWitherColor(), Kind.ITEM, "wither", cfg, seen);
            addGroup(entry.secretCoords.bat, clayX, clayZ, rotation, cfg.getBatColor(), Kind.BAT, "bat", cfg, seen);
            addGroup(entry.secretCoords.redstoneKey, clayX, clayZ, rotation, cfg.getRedstoneKeyColor(), Kind.ITEM, "key", cfg, seen);
        }
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
            AABB box = boxFor(real, kind, cfg.getBoxSize());
            CACHED.add(new Waypoint(box,
                    (box.minX + box.maxX) * 0.5, (box.minY + box.maxY) * 0.5, (box.minZ + box.maxZ) * 0.5,
                    r, g, b, a, real, kind, labelFor(kind, group)));
        }
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
