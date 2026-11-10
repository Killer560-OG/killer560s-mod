package com.killer560.hub.bosssim;

import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.roomsim.SimState;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModLog;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

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
    private static volatile boolean watcherSeen;
    private static volatile boolean watcherDone;
    private static int bloodTicks;
    private static volatile BlockPos portal;
    private static int particleTick;
    /** Players who have been outside the portal since it opened: only a step INTO it takes one in. */
    private static final java.util.Set<java.util.UUID> steppedOut = new java.util.HashSet<>();
    /** Server tick the boss start was asked for, so the ticks before the build begins do not ask again. */
    private static int askedAt = -1000;
    private static int serverTicks;

    private BossPortal() {
    }

    static void register() {
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
        BlockPos p = portal;
        return p == null ? null : new int[]{p.getX(), p.getY(), p.getZ()};
    }

    /**
     * Server thread, every sim server tick (from {@link BossSim}): ANY player on the sim's server opens the portal by
     * standing in Blood and goes in by stepping into it (coopsim-1109; it read his client before, so a guest did
     * nothing). Its blocks are written here, on the server, as before.
     */
    static void serverTick(MinecraftServer server) {
        serverTicks++;
        String code = SimState.mapCode();
        if (!code.equals(mapCode)) {
            mapCode = code;
            watcherSeen = watcherDone = false;
            bloodTicks = 0;
            portal = null;
            steppedOut.clear();
        }
        Minecraft client = Minecraft.getInstance();
        if (!SimState.canAct(client) || !SimState.isGeneratedFloor() || BossFight.running() || BossSim.isBuilding()
                || serverTicks - askedAt < 40) {
            return;
        }
        var players = server.getPlayerList().getPlayers();
        BlockPos open = portal;
        if (open != null) {
            for (ServerPlayer p : players) {
                double dx = p.getX() - (open.getX() + 0.5), dz = p.getZ() - (open.getZ() + 0.5);
                double dy = p.getY() - (open.getY() + 1);
                boolean in = dx * dx + dz * dz <= 2.25 && dy > -0.5 && dy < 2.0;
                if (!in) {
                    steppedOut.add(p.getUUID());
                } else if (steppedOut.contains(p.getUUID())) {
                    // Only a step INTO it: standing on the spot when it opens (where /goto lands) does not take him in.
                    LOGGER.info("[BossSim] into the boss through the Blood room's portal ({})", p.getGameProfile().name());
                    askedAt = serverTicks;
                    boolean master = !"F7".equalsIgnoreCase(SimState.floorLabel());
                    client.execute(() -> BossSim.start(client, BossFight.Start.P1, master));
                    return;
                }
            }
            if (++particleTick % 10 == 0) {
                server.overworld().sendParticles(ParticleTypes.PORTAL, open.getX() + 0.5, open.getY() + 1.5,
                        open.getZ() + 0.5, 30, 0.8, 1.0, 0.8, 0.2);
            }
            return;
        }
        ServerPlayer inBlood = null;
        for (ServerPlayer p : players) {
            if ("Blood".equals(SimState.roomNameAt(p.getBlockX(), p.getBlockZ()))) {
                inBlood = p;
                break;
            }
        }
        if (inBlood == null) {
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
        int gx = 2 * Math.floorDiv(inBlood.getBlockX() - origin.getX() + step, 2 * step);
        int gz = 2 * Math.floorDiv(inBlood.getBlockZ() - origin.getZ() + step, 2 * step);
        BlockPos center = DungeonLayout.cellCenter(gx, gz);
        BlockPos at = new BlockPos(center.getX(), inBlood.getBlockY() - 1, center.getZ());
        portal = at;
        steppedOut.clear();
        LOGGER.info("[BossSim] Blood done ({}) - portal at {}", watcherDone ? "the Watcher's line" : "stand-in", at.toShortString());
        ServerLevel level = server.overworld();
        BossBlocks.fill(level, at.getX() - 1, at.getY(), at.getZ() - 1, at.getX() + 1, at.getY(), at.getZ() + 1,
                BossBlocks.of("minecraft:crying_obsidian"));
        BossBlocks.fill(level, at.getX() - 1, at.getY() + 1, at.getZ() - 1, at.getX() + 1, at.getY() + 3, at.getZ() + 1,
                BossBlocks.AIR);
    }
}
