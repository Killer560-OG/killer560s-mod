package com.killer560.hub.secretwaypoints;

import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.notify.ModOverlayMessage;
import com.killer560.hub.roomdatabase.RoomDatabase;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Real, preloaded per-room secret waypoints - killer560's "preloaded waypoints for each room" request.
 * Every position rendered here comes from the real room database (see {@link com.killer560.hub.roomdatabase})
 * once a room is identified AND its real rotation/corner is found, transformed with the exact same
 * rotate+translate math NoammAddons' own {@code ScanUtils.getRealCoord} uses - not a guess, and not
 * hand-placed.
 * <p>
 * Also does real mimic-chest detection the same way NoammAddons does (from its
 * {@code DungeonScanner.findMimicRoom}): a room's database entry says exactly how many real trapped
 * chests it's SUPPOSED to have; if this session counts more trapped chests than that in a room, the
 * extra one is a mimic.
 */
public final class SecretWaypointsFeature {

    private static boolean wasInDungeon = false;
    private static long lastMimicCheckMs = 0;
    private static boolean mimicAnnounced = false;

    // [SecretWaypoints] diagnostics - logging only.
    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-secretwaypoints");
    private static String lastLoggedGates = null;
    private static String lastLoggedWaypointSummary = null;
    private static long lastWaypointSummaryMs = 0;
    private static final java.util.Map<Integer, String> lastLoggedMimicChecks = new java.util.HashMap<>();

    private SecretWaypointsFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(SecretWaypointsFeature::onWorldRender);
    }

    private static void logDiagnostics(boolean inDungeon) {
        SecretWaypointsConfig cfg = SecretWaypointsConfig.getInstance();
        String gates = "enabled=" + cfg.isEnabled() + " mimicDetection=" + cfg.isMimicDetection()
                + " inDungeon=" + inDungeon + " mimicAnnounced=" + mimicAnnounced
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
        String summary = rooms + " rooms / " + waypoints + " waypoints: " + sb.toString().trim();
        if (!summary.equals(lastLoggedWaypointSummary)) {
            LOGGER.info("[SecretWaypoints] Renderable rooms changed: {}", summary);
            lastLoggedWaypointSummary = summary;
        }
    }

    private static void tick() {
        boolean inDungeon = DungeonState.isInDungeon();
        if (!inDungeon && wasInDungeon) {
            mimicAnnounced = false;
            lastLoggedMimicChecks.clear();
        }
        wasInDungeon = inDungeon;
        logDiagnostics(inDungeon);

        SecretWaypointsConfig cfg = SecretWaypointsConfig.getInstance();
        if (!cfg.isEnabled() || !cfg.isMimicDetection() || !inDungeon || mimicAnnounced) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastMimicCheckMs < 1000) {
            return;
        }
        lastMimicCheckMs = now;
        checkForMimic();
    }

    private static void checkForMimic() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return;
        }
        for (int[] room : LiveMapFeature.identifiedRoomsWithRotation()) {
            RoomEntry entry = LiveMapFeature.roomEntryAt(room[0]);
            if (entry == null) {
                continue;
            }
            int gx = room[0] % 11;
            int gz = room[0] / 11;
            int roomWorldX = -185 + gx * 16;
            int roomWorldZ = -185 + gz * 16;
            AABB roomBox = new AABB(roomWorldX - 16, 0, roomWorldZ - 16, roomWorldX + 16, 255, roomWorldZ + 16);
            if (!client.player.getBoundingBox().inflate(48).intersects(roomBox)) {
                continue; // only bother checking rooms actually near the player, cheap early-out
            }
            int realTrappedChests = countTrappedChests(client, roomBox);
            String check = entry.name + "@" + room[0] + " trapped=" + realTrappedChests + " expected=" + entry.trappedChests;
            if (!check.equals(lastLoggedMimicChecks.put(room[0], check))) {
                LOGGER.info("[SecretWaypoints] Mimic check: {}", check);
            }
            if (realTrappedChests > entry.trappedChests) {
                LOGGER.info("[SecretWaypoints] Mimic announced in \"{}\" (trapped={} > expected={})",
                        entry.name, realTrappedChests, entry.trappedChests);
                mimicAnnounced = true;
                ModOverlayMessage.show("§d[Secrets] Mimic likely in \"" + entry.name + "\" (extra trapped chest found)!", 5000);
                return;
            }
        }
    }

    private static int countTrappedChests(Minecraft client, AABB box) {
        int count = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = (int) box.minX; x <= (int) box.maxX; x += 1) {
            for (int z = (int) box.minZ; z <= (int) box.maxZ; z += 1) {
                for (int y = 60; y <= 100; y++) {
                    pos.set(x, y, z);
                    if (client.level.getBlockState(pos).is(Blocks.TRAPPED_CHEST)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private static void onWorldRender(LevelRenderContext context) {
        SecretWaypointsConfig cfg = SecretWaypointsConfig.getInstance();
        if (!cfg.isEnabled() || !DungeonState.isInDungeon()) {
            return;
        }
        for (int[] room : LiveMapFeature.identifiedRoomsWithRotation()) {
            RoomEntry entry = LiveMapFeature.roomEntryAt(room[0]);
            if (entry == null || entry.secretCoords == null) {
                continue;
            }
            int clayX = room[1];
            int clayZ = room[2];
            int rotation = room[3];

            renderGroup(context, entry.secretCoords.chest, clayX, clayZ, rotation, cfg.getChestColor(), cfg);
            renderGroup(context, entry.secretCoords.item, clayX, clayZ, rotation, cfg.getItemColor(), cfg);
            renderGroup(context, entry.secretCoords.wither, clayX, clayZ, rotation, cfg.getWitherColor(), cfg);
            renderGroup(context, entry.secretCoords.bat, clayX, clayZ, rotation, cfg.getBatColor(), cfg);
            renderGroup(context, entry.secretCoords.redstoneKey, clayX, clayZ, rotation, cfg.getRedstoneKeyColor(), cfg);
        }
    }

    private static void renderGroup(LevelRenderContext context, List<RoomEntry.Pos> positions,
                                     int clayX, int clayZ, int rotation, int argb, SecretWaypointsConfig cfg) {
        if (positions == null) {
            return;
        }
        float a = ((argb >> 24) & 0xFF) / 255f;
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        for (RoomEntry.Pos relative : positions) {
            BlockPos real = RoomDatabase.toRealCoord(relative, clayX, clayZ, rotation);
            AABB box = new AABB(real.getX(), real.getY(), real.getZ(),
                    real.getX() + 1, real.getY() + 1, real.getZ() + 1);
            switch (cfg.getStyle()) {
                case FILL -> WorldRenderUtils.renderFilledBox(context, box, r, g, b, a);
                case OUTLINE -> WorldRenderUtils.renderOutlineBox(context, box, r, g, b, 1f, 2f);
                case FILL_OUTLINE -> {
                    WorldRenderUtils.renderFilledBox(context, box, r, g, b, a * 0.5f);
                    WorldRenderUtils.renderOutlineBox(context, box, r, g, b, 1f, 2f);
                }
            }
        }
    }
}
