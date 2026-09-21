package com.killer560.hub.autopuzzles;

import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.puzzlesolvers.BeamsSolverConfig;
import com.killer560.hub.puzzlesolvers.BeamsSolverFeature;
import com.killer560.hub.puzzlesolvers.PuzzleCoords;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Auto Creeper Beams - port of QUOI {@code CreeperBeamsSolver.kt}'s {@code auto} on top of this mod's
 * {@link BeamsSolverFeature} lit-pair scan. Standing on the centre platform (y == 75), it picks the first lit pair and
 * shoots its first lantern, then its second, with the held shortbow. Progress comes from the elder guardian hurt
 * sound at the lantern (pitch 1.3968254 = first hit, 2.0 = pair done - QUOI's exact values, via
 * {@code PuzzlePacketMixin}); a shot with no sound is retried after the Miss cooldown. The shot aims at the lantern's
 * etherwarp-visible face point (falls back to its centre). If the creeper is between the player and the lantern it
 * (with "Etherwarp Reposition" on) moves to the first platform spot with a clear line; the same toggle also warps onto
 * the platform when off it. After 4 solved pairs it stops and releases sneak.
 */
public final class AutoBeams {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-autopuzzles");
    private static final String ROOM = "Creeper Beams";
    private static final BlockPos[] PLATFORM_SPOTS = {
            new BlockPos(14, 74, 14), new BlockPos(14, 74, 15), new BlockPos(14, 74, 16),
            new BlockPos(15, 74, 14), new BlockPos(15, 74, 16),
            new BlockPos(16, 74, 14), new BlockPos(16, 74, 15), new BlockPos(16, 74, 16)
    };

    private static final class LanternPair {
        final BlockPos first;
        final BlockPos second;
        int stage = 0; // 0 = not hit, 1 = first hit, 2 = both hit

        LanternPair(BlockPos first, BlockPos second) {
            this.first = first;
            this.second = second;
        }
    }

    private static final AutoGuard GUARD = new AutoGuard("Auto Creeper Beams", "Creeper Beams Solver");
    private static final AutoReposition REPOSITION = new AutoReposition("Beams");

    private static LanternPair activePair = null;
    private static long lastShotTime = 0L;
    private static boolean waitingForUpdate = false;
    private static int solvedPairs = 0;
    private static int lastPairCount = -1;
    private static boolean wasInRoom = false;
    private static boolean active = false;

    private AutoBeams() {
    }

    static void levelChanged(Minecraft client) {
        GUARD.levelChanged();
        reset(client);
    }

    /** From {@code PuzzlePacketMixin} (main thread). */
    public static void onSound(ClientboundSoundPacket packet) {
        LanternPair pair = activePair;
        if (!active || pair == null || !AutoPuzzlesConfig.getInstance().isAutoBeamsEnabled()) {
            return;
        }
        if (!"minecraft:entity.elder_guardian.hurt".equals(packet.getSound().getRegisteredName())) {
            return;
        }
        BlockPos pos = pair.stage == 0 ? pair.first : pair.second;
        if (pos.getX() != packet.getX() || pos.getY() != packet.getY() || pos.getZ() != packet.getZ()) {
            return;
        }
        if (pair.stage == 0 && packet.getPitch() == 1.3968254f) {
            pair.stage = 1;
            waitingForUpdate = false;
            LOGGER.info("[AutoPuzzles] Beams: first lantern {} hit", pair.first);
        } else if (pair.stage == 1 && packet.getPitch() == 2.0f) {
            pair.stage = 2;
            waitingForUpdate = false;
            activePair = null;
            LOGGER.info("[AutoPuzzles] Beams: pair {} -> {} done", pair.first, pair.second);
        }
    }

    static void tick(Minecraft client, String roomName) {
        List<BlockPos[]> pairs = BeamsSolverFeature.getActivePairs();
        GUARD.observe(pairs.isEmpty());
        AutoPuzzlesConfig cfg = AutoPuzzlesConfig.getInstance();
        if (!cfg.isAutoBeamsEnabled() || !ROOM.equals(roomName)) {
            if (wasInRoom) {
                reset(client);
                GUARD.leftRoom();
            }
            wasInRoom = false;
            active = false;
            return;
        }
        wasInRoom = true;
        if (!GUARD.solverOn(BeamsSolverConfig.getInstance().isEnabled())) {
            active = false;
            return;
        }
        active = true;
        // QUOI recalculateLanternPairs: drop the active pair once its first lantern is no longer lit.
        if (activePair != null && !containsFirst(pairs, activePair.first)) {
            activePair = null;
            waitingForUpdate = false;
        }
        if (lastPairCount >= 0 && pairs.size() < lastPairCount && ++solvedPairs == 4) {
            LOGGER.info("[AutoPuzzles] Beams: 4 pairs solved - done");
            ModChat.send(AutoPuzzlesFeature.CHAT, ModChat.text("Creeper Beams: "), ModChat.good("done"), ModChat.text("."));
            REPOSITION.cancel(client);
            AutoReposition.releaseSneak(client);
        }
        lastPairCount = pairs.size();

        LocalPlayer player = client.player;
        int[] cr = LiveMapFeature.currentRoomClayAndRotation();
        if (!GUARD.fresh() || client.screen != null || solvedPairs >= 4 || pairs.isEmpty() || cr == null) {
            return;
        }
        BlockPos start = PuzzleCoords.real(16, 74, 14, cr);
        if (client.level.getBlockState(start).isAir()) {
            return;
        }
        if (REPOSITION.isActive()) {
            REPOSITION.tick(client);
            return;
        }
        boolean reposition = cfg.isEtherwarpReposition();
        if (player.getY() != 75.0) {
            if (reposition) {
                REPOSITION.start(client, start, true, false, false);
            }
            return;
        }
        if (activePair == null) {
            BlockPos[] first = pairs.get(0);
            activePair = new LanternPair(first[0], first[1]);
        }
        LanternPair pair = activePair;
        BlockPos lantern = pair.stage == 0 ? pair.first : pair.second;
        Vec3 lanternVec = Vec3.atCenterOf(lantern);
        BlockPos creeper = PuzzleCoords.real(15, 74, 15, cr);

        if (isPathBlocked(player.getEyePosition(), lanternVec, creeper)) {
            BlockPos spot = null;
            for (BlockPos rel : PLATFORM_SPOTS) {
                BlockPos real = PuzzleCoords.real(rel, cr);
                if (!isPathBlocked(Vec3.atCenterOf(real).add(0.0, 1.5, 0.0), lanternVec, creeper)) {
                    spot = real;
                    break;
                }
            }
            if (spot != null) {
                if (reposition) {
                    REPOSITION.start(client, spot, true, false, false);
                }
                return;
            }
        }

        long now = System.currentTimeMillis();
        if (waitingForUpdate) {
            if (now - lastShotTime > cfg.getMissCooldownMs()) {
                waitingForUpdate = false;
            } else {
                return;
            }
        }
        if (!AutoPuzzleUtil.isShortbow(player.getMainHandItem()) || now - lastShotTime < cfg.getShootCooldownMs()) {
            return;
        }
        float[] dir = AutoPuzzleUtil.etherwarpDirection(client.level, player, lantern);
        if (dir == null) {
            dir = AutoPuzzleUtil.direction(player.getEyePosition(), lanternVec);
        }
        if (!AutoPuzzleUtil.useItemRotated(client, player, dir[0], dir[1])) {
            return; // gate held this tick back - no shot, so lastShotTime / waitingForUpdate must not move
        }
        LOGGER.info("[AutoPuzzles] Beams: shot lantern {} (stage {}) yaw={} pitch={}", lantern, pair.stage, dir[0], dir[1]);
        lastShotTime = now;
        waitingForUpdate = true;
    }

    private static boolean containsFirst(List<BlockPos[]> pairs, BlockPos first) {
        for (BlockPos[] p : pairs) {
            if (p[0].equals(first)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPathBlocked(Vec3 from, Vec3 lantern, BlockPos creeper) {
        double x = lantern.x - from.x;
        double z = lantern.z - from.z;
        double dist = x * x + z * z;
        if (dist < 0.1) {
            return false;
        }
        double cx = creeper.getX() + 0.5 - from.x;
        double cz = creeper.getZ() + 0.5 - from.z;
        double dot = (cx * x + cz * z) / dist;
        if (dot < 0.0 || dot > 1.0) {
            return false;
        }
        double offset = Math.abs(cx * z - cz * x) / Math.sqrt(dist);
        return offset < 1.0;
    }

    private static void reset(Minecraft client) {
        REPOSITION.cancel(client);
        AutoReposition.releaseSneak(client);
        activePair = null;
        waitingForUpdate = false;
        lastShotTime = -1L;
        solvedPairs = 0;
        lastPairCount = -1;
    }
}
