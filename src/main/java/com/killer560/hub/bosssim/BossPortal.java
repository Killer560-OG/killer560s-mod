package com.killer560.hub.bosssim;

import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.roomsim.SimState;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModLog;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import org.slf4j.Logger;

/**
 * The floor's way into the boss: once Blood is done, a portal in the middle of the Blood room; stepping into it starts P1
 * (killer560, 2026-11-01: connect the boss from the sim's floor flow, after blood).
 *
 * <p>"Blood is done" is the Watcher's last line, {@code [BOSS] The Watcher: You have proven yourself. You may pass.}, the
 * moment Hypixel opens the way on. The sim's Blood room has no Watcher yet (another branch is adding the blood mobs), so
 * until a Watcher line has been seen on this floor, five seconds standing in the Blood room stands in for it.
 */
final class BossPortal {

    private static final Logger LOGGER = ModLog.get("killer560smod-bosssim");
    private static final String WATCHER = "[BOSS] The Watcher: ";
    private static final String WATCHER_DONE = "[BOSS] The Watcher: You have proven yourself. You may pass.";
    private static final int STAND_IN_TICKS = 100;

    private static String mapCode = "";
    private static boolean watcherSeen;
    private static boolean watcherDone;
    private static int bloodTicks;
    private static BlockPos portal;
    private static int particleTick;

    private BossPortal() {
    }

    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("BossPortal.tick", BossPortal::tick));
        ChatObserver.subscribe(message -> {
            String plain = ChatObserver.strip(message);
            if (plain == null || !SimState.isActive()) {
                return;
            }
            plain = plain.trim();
            if (plain.startsWith(WATCHER)) {
                watcherSeen = true;
            }
            if (plain.equals(WATCHER_DONE)) {
                watcherDone = true;
            }
        });
    }

    /** Testkit: where the portal is, or null. */
    public static int[] portal() {
        return portal == null ? null : new int[]{portal.getX(), portal.getY(), portal.getZ()};
    }

    private static void tick(Minecraft client) {
        String code = SimState.mapCode();
        if (!code.equals(mapCode)) {
            mapCode = code;
            watcherSeen = watcherDone = false;
            bloodTicks = 0;
            portal = null;
        }
        if (!SimState.canAct(client) || !SimState.isGeneratedFloor() || BossFight.running() || BossSim.isBuilding()) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        if (portal != null) {
            double dx = client.player.getX() - (portal.getX() + 0.5), dz = client.player.getZ() - (portal.getZ() + 0.5);
            double dy = client.player.getY() - (portal.getY() + 1);
            if (dx * dx + dz * dz <= 2.25 && dy > -0.5 && dy < 2.0) {
                LOGGER.info("[BossSim] into the boss through the Blood room's portal");
                BossSim.start(client, BossFight.Start.P1, !"F7".equalsIgnoreCase(SimState.floorLabel()));
                return;
            }
            if (++particleTick % 10 == 0) {
                BlockPos at = portal;
                server.execute(() -> server.overworld().sendParticles(ParticleTypes.PORTAL, at.getX() + 0.5, at.getY() + 1.5,
                        at.getZ() + 0.5, 30, 0.8, 1.0, 0.8, 0.2));
            }
            return;
        }
        if (!"Blood".equals(SimState.currentRoomName())) {
            return;
        }
        bloodTicks++;
        if (!(watcherDone || (!watcherSeen && bloodTicks >= STAND_IN_TICKS))) {
            return;
        }
        BlockPos origin = DungeonLayout.cellCenter(0);
        int step = DungeonLayout.cellCenter(1).getX() - origin.getX();
        if (step == 0) {
            return;
        }
        int gx = 2 * Math.floorDiv(client.player.getBlockX() - origin.getX() + step, 2 * step);
        int gz = 2 * Math.floorDiv(client.player.getBlockZ() - origin.getZ() + step, 2 * step);
        BlockPos center = DungeonLayout.cellCenter(gx, gz);
        BlockPos at = new BlockPos(center.getX(), client.player.getBlockY() - 1, center.getZ());
        portal = at;
        LOGGER.info("[BossSim] Blood done ({}) - portal at {}", watcherDone ? "the Watcher's line" : "stand-in", at.toShortString());
        server.execute(() -> {
            ServerLevel level = server.overworld();
            BossBlocks.fill(level, at.getX() - 1, at.getY(), at.getZ() - 1, at.getX() + 1, at.getY(), at.getZ() + 1,
                    BossBlocks.of("minecraft:crying_obsidian"));
            BossBlocks.fill(level, at.getX() - 1, at.getY() + 1, at.getZ() - 1, at.getX() + 1, at.getY() + 3, at.getZ() + 1,
                    BossBlocks.AIR);
        });
    }
}
