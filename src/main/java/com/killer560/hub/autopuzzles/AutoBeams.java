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

import java.util.List;
import com.killer560.hub.compat.McCompat;

/**
 * Auto Creeper Beams - port of QUOI {@code CreeperBeamsSolver.kt}'s {@code auto} on top of this mod's
 * {@link BeamsSolverFeature} lit-pair scan. Standing on the centre platform (y == 75), it picks the first lit pair and
 * shoots its first lantern, then its second, with the held shortbow. Progress comes from the elder guardian hurt
 * sound at the lantern (pitch 1.3968254 = first hit, 2.0 = pair done - QUOI's exact values, via
 * {@code PuzzlePacketMixin}). The shot aims at the lantern's etherwarp-visible face point (falls back to its
 * centre). If the creeper is between the player and the lantern it (with "Etherwarp Reposition" on) moves to the
 * first platform spot with a clear line; the same toggle also warps onto the platform when off it. After 4 solved
 * pairs it stops and releases sneak.
 * <p>
 * Miss cooldown removed (killer560, 2026-09-27: "For auto puzzles remove the miss cooldown."): it used to hold
 * every shot for an extra {@code missCooldownMs} on top of the Shoot cooldown whenever the hurt sound hadn't
 * arrived yet, in case that shot missed. Now every shot is paced by the Shoot cooldown alone, hit or miss.
 */
public final class AutoBeams {

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
    /** Held from the first aim to the fourth pair (see {@link FreeCam}). */
    private static final FreeCam CAMERA = new FreeCam("Beams");
    private static final AutoReposition REPOSITION = new AutoReposition("Beams", CAMERA);

    private static LanternPair activePair = null;
    private static long lastShotTime = 0L;
    private static int solvedPairs = 0;
    private static int lastPairCount = -1;
    private static boolean wasInRoom = false;
    private static boolean active = false;
    /** What the log last said about the bow, so it is said once per change. */
    private static AutoPuzzleUtil.BowState loggedBow = null;
    private static String lastSaid = null;
    /** The last platform spot that could not be warped onto, skipped by the next pick. */
    private static BlockPos failedSpot = null;
    private static final org.slf4j.Logger LOGGER = com.killer560.hub.util.ModLog.get("killer560smod-autopuzzles");

    private AutoBeams() {
    }

    static void levelChanged(Minecraft client) {
        GUARD.levelChanged();
        CAMERA.drop();
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
        } else if (pair.stage == 1 && packet.getPitch() == 2.0f) {
            pair.stage = 2;
            activePair = null;
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
        CAMERA.keep(client.player);
        if (!GUARD.solverOn(BeamsSolverConfig.getInstance().isEnabled())) {
            active = false;
            CAMERA.release(client.player);
            return;
        }
        active = true;
        // QUOI recalculateLanternPairs: drop the active pair once its first lantern is no longer lit.
        if (activePair != null && !containsFirst(pairs, activePair.first)) {
            activePair = null;
        }
        if (lastPairCount >= 0 && pairs.size() < lastPairCount && ++solvedPairs == 4) {
            ModChat.send(AutoPuzzlesFeature.CHAT, ModChat.text("Creeper Beams: "), ModChat.good("done"), ModChat.text("."));
            REPOSITION.cancel(client);
            AutoReposition.releaseSneak(client);
            CAMERA.release(client.player);
        }
        lastPairCount = pairs.size();

        LocalPlayer player = client.player;
        int[] cr = LiveMapFeature.currentRoomClayAndRotation();
        if (!GUARD.fresh()) {
            return; // AutoGuard logs this one itself
        }
        if (McCompat.screen(client) != null) {
            say("waiting: a screen is open");
            return;
        }
        if (solvedPairs >= 4) {
            say("done: four pairs joined");
            return;
        }
        if (pairs.isEmpty()) {
            say("waiting: the solver lists no lit pair");
            return;
        }
        if (cr == null) {
            say("waiting: the room's clay corner / rotation is not resolved");
            return;
        }
        BlockPos start = PuzzleCoords.real(16, 74, 14, cr);
        if (client.level.getBlockState(start).isAir()) {
            say("waiting: the centre platform at " + AutoPuzzleUtil.fmt(start) + " reads air (chunk not loaded yet?)");
            return;
        }
        if (REPOSITION.isActive()) {
            REPOSITION.tick(client);
            return;
        }
        boolean reposition = cfg.isEtherwarpReposition();
        // THE PLATFORM'S OWN HEIGHT, not the literal 75. killer560 (2026-10-01): "auto creeper beams would look
        // down and teleport and that was it."
        //
        // That is this check, and it could never pass in the sim. 75 is Hypixel's height for the top of the
        // centre platform - start is room-relative (16,74,14), so one above it IS 75 on a real floor and the two
        // readings are identical there. The sim shifts the whole map vertically (SimAltitude), so the platform is
        // somewhere else entirely and player.getY() was never 75: it repositioned onto the platform, re-read 75,
        // found it still false, and repositioned again. Warp, look down, nothing, forever.
        double platformY = start.getY() + 1.0;
        // Within half a block, not exactly equal. An etherwarp lands 0.05 over the block and gravity settles him a
        // tick or two later; read in between, an exact test said "not on the platform" and started a SECOND warp
        // straight after the first had landed.
        if (Math.abs(player.getY() - platformY) > 0.5) {
            if (reposition) {
                say("off the centre platform - repositioning onto " + AutoPuzzleUtil.fmt(start));
                REPOSITION.start(client, start, true, false, false);
            } else {
                say("waiting: stand on the centre platform (Etherwarp Reposition is off)");
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

        // The lantern under the creeper's own feet is one end of a pair; the creeper is "between" him and it from
        // every spot, so asking would only ever end in the shoot-anyway branch below with a misleading line.
        if (!lantern.equals(creeper) && isPathBlocked(player.getEyePosition(), lanternVec, creeper)) {
            BlockPos spot = null;
            for (BlockPos rel : PLATFORM_SPOTS) {
                BlockPos real = PuzzleCoords.real(rel, cr);
                if (AutoPuzzleUtil.at(player, real) || real.equals(failedSpot)) {
                    continue;
                }
                if (!isPathBlocked(Vec3.atCenterOf(real).add(0.0, 1.5, 0.0), lanternVec, creeper)) {
                    spot = real;
                    break;
                }
            }
            if (spot != null && reposition) {
                say("the creeper is between him and " + AutoPuzzleUtil.fmt(lantern) + " - moving to "
                        + AutoPuzzleUtil.fmt(spot));
                if (!REPOSITION.start(client, spot, true, false, false)) {
                    failedSpot = spot; // no aim onto it: the next spot next tick, not this one forever
                }
                return;
            }
            say("the creeper is between him and " + AutoPuzzleUtil.fmt(lantern) + " and "
                    + (reposition ? "no platform spot clears it" : "Etherwarp Reposition is off") + " - shooting anyway");
        }

        long now = System.currentTimeMillis();
        // Swap to the bow here too - it only ever checked the hand, so with the AOTV still held from the reposition
        // it aimed and never fired (killer560, 2026-10-04: "the auto solver isn't swapping to terminator to shoot").
        AutoPuzzleUtil.BowState bow = AutoPuzzleUtil.holdShortbow(client, player);
        if (bow != loggedBow) {
            loggedBow = bow;
            if (bow != AutoPuzzleUtil.BowState.HELD) {
                LOGGER.info("[AutoPuzzles] Beams: {}", bow == AutoPuzzleUtil.BowState.NONE
                        ? "no shortbow (\"" + AutoPuzzleUtil.SHORTBOW_LORE + "\" in its lore) in the hotbar - not shooting"
                        : "swapping to the shortbow");
            }
        }
        if (bow != AutoPuzzleUtil.BowState.HELD || now - lastShotTime < cfg.getShootCooldownMs()) {
            return;
        }
        Vec3 eye = player.getEyePosition();
        float[] dir = shotAim(client, player, eye, lantern);
        boolean seen = dir != null;
        if (dir == null) {
            dir = AutoPuzzleUtil.direction(eye, lanternVec);
        }
        CAMERA.engage(player); // before the aim turns him
        if (!AutoPuzzleUtil.useItemRotated(client, player, dir[0], dir[1])) {
            return; // gate held this tick back - no shot, so lastShotTime must not move
        }
        lastShotTime = now;
        lastSaid = null;
        LOGGER.info("[AutoPuzzles] Beams: shot at {} (pair stage {}) from {}, yaw {} pitch {}{}", AutoPuzzleUtil.fmt(lantern),
                pair.stage, String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f", eye.x, eye.y, eye.z),
                String.format(java.util.Locale.ROOT, "%.1f", dir[0]), String.format(java.util.Locale.ROOT, "%.1f", dir[1]),
                seen ? "" : " - no clear line to any face, aimed at the centre");
    }

    /**
     * Where to aim so the shot's line meets {@code lantern} first: its centre, then the middle of each face pulled
     * a little inside the block, each checked with a collider ray from the REAL eye.
     *
     * <p>This was {@link AutoPuzzleUtil#etherwarpDirection}, which aims from a SNEAKING eye whatever his stance and
     * at QUOI's etherwarp sample points - face edges 0.001 in, and a top centre that sits exactly on the face. An
     * arrow is not an etherwarp: from the real eye an edge aim lands on the neighbour as often as on the lantern,
     * and the etherwarp ray treats ladders, buttons and lanterns as see-through where an arrow does not.
     */
    private static float[] shotAim(Minecraft client, LocalPlayer player, Vec3 eye, BlockPos lantern) {
        double[][] points = {{0.5, 0.5, 0.5}, {0.5, 0.95, 0.5}, {0.5, 0.05, 0.5}, {0.05, 0.5, 0.5},
                {0.95, 0.5, 0.5}, {0.5, 0.5, 0.05}, {0.5, 0.5, 0.95}};
        for (double[] p : points) {
            Vec3 target = new Vec3(lantern.getX() + p[0], lantern.getY() + p[1], lantern.getZ() + p[2]);
            float[] dir = AutoPuzzleUtil.direction(eye, target);
            Vec3 end = eye.add(AutoPuzzleUtil.look(dir[0], dir[1]).scale(eye.distanceTo(target) + 2.0));
            net.minecraft.world.phys.BlockHitResult hit = client.level.clip(new net.minecraft.world.level.ClipContext(
                    eye, end, net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, player));
            if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && lantern.equals(hit.getBlockPos())) {
                return dir;
            }
        }
        return null;
    }

    /** One INFO line per change of reason - every place this auto declines to act says why, once. */
    private static void say(String what) {
        if (what.equals(lastSaid)) {
            return;
        }
        lastSaid = what;
        LOGGER.info("[AutoPuzzles] Beams: {}", what);
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
        CAMERA.release(client.player);
        activePair = null;
        lastSaid = null;
        failedSpot = null;
        lastShotTime = -1L;
        solvedPairs = 0;
        lastPairCount = -1;
    }
}
