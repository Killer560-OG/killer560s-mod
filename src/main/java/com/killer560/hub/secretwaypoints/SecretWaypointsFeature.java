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
    private enum Kind {
        /** Vanilla chest block shape: 14/16 wide and deep, 14/16 tall, inset 1/16. */
        CHEST,
        /** A dropped item (secret item, wither essence, redstone key): 0.25 cube on the floor. */
        ITEM,
        /** A secret bat: 0.5 wide and deep, 0.9 tall. */
        BAT
    }

    /** One ready-to-draw box. Built on the tick, consumed by {@link SecretWaypointsRenderer} on the frame. */
    public record Waypoint(AABB box, double centerX, double centerY, double centerZ,
                           float r, float g, float b, float a) {
    }

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
            invalidateCache();
        }
        wasInDungeon = inDungeon;
        logDiagnostics(inDungeon);
        refreshCacheIfStale(inDungeon);
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
        double reach = cfg.getRenderDistance() + CACHE_MARGIN;
        double reachSq = reach * reach;
        for (int[] room : LiveMapFeature.identifiedRoomsWithRotation()) {
            RoomEntry entry = LiveMapFeature.roomEntryAt(room[0]);
            if (entry == null || entry.secretCoords == null || !isRoomShown(entry.name)) {
                continue;
            }
            // Whole-room XZ bounds first: one nearest-point test drops a far room before any coordinate of
            // it is transformed. Y is ignored - dungeon rooms all sit in the same 60-140 band.
            int[] b = LiveMapFeature.roomWorldBounds(room[0]);
            double dx = Math.max(0.0, Math.max(b[0] - px, px - b[2]));
            double dz = Math.max(0.0, Math.max(b[1] - pz, pz - b[3]));
            if (dx * dx + dz * dz > reachSq) {
                continue;
            }
            int clayX = room[1];
            int clayZ = room[2];
            int rotation = room[3];
            addGroup(entry.secretCoords.chest, clayX, clayZ, rotation, cfg.getChestColor(), Kind.CHEST, cfg);
            addGroup(entry.secretCoords.item, clayX, clayZ, rotation, cfg.getItemColor(), Kind.ITEM, cfg);
            addGroup(entry.secretCoords.wither, clayX, clayZ, rotation, cfg.getWitherColor(), Kind.ITEM, cfg);
            addGroup(entry.secretCoords.bat, clayX, clayZ, rotation, cfg.getBatColor(), Kind.BAT, cfg);
            addGroup(entry.secretCoords.redstoneKey, clayX, clayZ, rotation, cfg.getRedstoneKeyColor(), Kind.ITEM, cfg);
        }
    }

    private static void addGroup(List<RoomEntry.Pos> positions, int clayX, int clayZ, int rotation, int argb,
                                 Kind kind, SecretWaypointsConfig cfg) {
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
            AABB box = boxFor(real, kind, cfg.getBoxSize());
            CACHED.add(new Waypoint(box,
                    (box.minX + box.maxX) * 0.5, (box.minY + box.maxY) * 0.5, (box.minZ + box.maxZ) * 0.5,
                    r, g, b, a));
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
        SecretWaypointsRenderer.draw(context, CACHED, cfg.getStyle(), cfg.isThroughWalls(), cfg.getRenderDistance());
    }
}
