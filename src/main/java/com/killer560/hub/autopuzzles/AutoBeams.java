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
 * <p>
 * Mode "Melee" (killer560's to-do item 29, 2026-10-18; Shoot above stays the default): no bow and no centre platform -
 * it etherwarps to a spot within reach of each lantern and left-clicks it. See {@link #meleeTick}.
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
            // Melee picks its next pair at once, before the solver's list (from block updates) drops this one.
            MELEE_DONE.add(pair.first);
            MELEE_DONE.add(pair.second);
        }
    }

    static void tick(Minecraft client, String roomName) {
        // The solver's pairs (a fresh list) are only built when the guard can still learn from them or the auto runs.
        List<BlockPos[]> pairs = GUARD.observing() ? BeamsSolverFeature.getActivePairs() : null;
        if (pairs != null) {
            GUARD.observe(pairs.isEmpty());
        }
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
        if (pairs == null) {
            pairs = BeamsSolverFeature.getActivePairs();
        }
        wasInRoom = true;
        CAMERA.keep(client.player);
        if (!GUARD.solverOn(BeamsSolverConfig.getInstance().isEnabled())) {
            active = false;
            CAMERA.release(client.player);
            return;
        }
        active = true;
        boolean melee = cfg.isAutoBeamsMelee();
        // QUOI recalculateLanternPairs: drop the active pair once its first lantern is no longer lit. Melee hits the nearer
        // end first, which may be the solver's second, so it asks whether the pair itself is still listed.
        if (activePair != null && !(melee ? containsPair(pairs, activePair) : containsFirst(pairs, activePair.first))) {
            activePair = null;
        }
        if (lastPairCount >= 0 && pairs.size() < lastPairCount && ++solvedPairs == 4) {
            ModChat.log(AutoPuzzlesFeature.CHAT, ModChat.text("Creeper Beams: "), ModChat.good("done"), ModChat.text("."));
            REPOSITION.cancel(client);
            AutoReposition.releaseSneak(client);
            CAMERA.release(client.player);
            AutoPuzzleActivity.ended(); // the last pair is joined: nothing follows, so nothing waits on it
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
            say("waiting: the center platform at " + AutoPuzzleUtil.fmt(start) + " reads air (chunk not loaded yet?)");
            return;
        }
        if (REPOSITION.isActive()) {
            REPOSITION.tick(client);
            return;
        }
        boolean reposition = cfg.isEtherwarpReposition();
        if (melee && !meleeFallback) {
            meleeTick(client, player, pairs, reposition);
            if (!meleeFallback) {
                return;
            }
        }
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
                say("off the center platform - repositioning onto " + AutoPuzzleUtil.fmt(start));
                REPOSITION.start(client, start, true, false, false);
            } else {
                say("waiting: stand on the center platform (Etherwarp Reposition is off)");
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
                seen ? "" : " - no clear line to any face, aimed at the center");
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

    // ------------------------------------------------------------------ Melee mode

    /**
     * Ticks a melee hit waits for its answer - the hurt sound at the lantern ({@link #onSound}) or the lantern turning to
     * prismarine - before it counts as missed and is tried again from another spot. A round trip to Hypixel is 2-6 ticks;
     * the sim answers in 1-2. Long enough that a hit Hypixel did take is not hit a second time (which could drop a held
     * end), short enough that a refused one costs half a second.
     */
    private static final int MELEE_CONFIRM_TICKS = 12;
    /** Unanswered hits on one lantern before its pair is set aside for another. */
    private static final int MELEE_MAX_TRIES = 4;
    /** Spots are planned this far from the lantern's box, inside the 4.5 the server enforces, so the landing's own
     *  hover and rounding can never put the hit out of range; 4.5 itself is the fallback when nothing is that close. */
    private static final double MELEE_PLAN_REACH = 4.0;

    /** The hit awaiting its answer: the client tick it went, the pair, which end (stage) and where it was hit from. */
    private static long meleeClickTick = -1L;
    private static LanternPair meleePair = null;
    private static int meleeStage = -1;
    private static BlockPos meleeFrom = null;
    private static int meleeTries = 0;
    /** Per lantern, the spots a hit from went unanswered - the retry goes from somewhere else. */
    private static final java.util.Map<BlockPos, java.util.Set<BlockPos>> MELEE_FAILED =
            new java.util.HashMap<>();
    /** Lanterns no spot in the room reaches, or that never answered: pairs holding one are left to the others. */
    private static final java.util.Set<BlockPos> MELEE_SKIP = new java.util.HashSet<>();
    /** Both ends of every pair whose completing hurt sound came ({@link #onSound}). */
    private static final java.util.Set<BlockPos> MELEE_DONE = new java.util.HashSet<>();
    private static int meleeHits = 0;
    private static int meleeWarps = 0;
    /** Set when no pair is left that melee can finish: the rest of this room is played as Shoot mode plays it. */
    private static boolean meleeFallback = false;

    /**
     * Melee mode (killer560's to-do item 29, 2026-10-18: "etherwarps and teleports around the room and will mele the
     * blocks"). For the active pair's next lantern: if he stands within reach of it (eye to its box, and a point of it in
     * sight) and that spot has not already failed it, hit it - one left click; otherwise etherwarp (the same
     * {@link AutoReposition} every puzzle uses) to the nearest standable spot that reaches it. A hit counts once the hurt
     * sound moves the pair's stage or the lantern changes; with neither inside {@link #MELEE_CONFIRM_TICKS} the spot is
     * marked failed for that lantern and the next hit goes from another spot.
     *
     * <p>Only discrete inputs: the warp is AutoReposition's sneak and use, the hit is vanilla's own left click
     * ({@link #meleeClick}), and the body is turned through the puzzle autos' turn-first path with the camera held.
     */
    private static void meleeTick(Minecraft client, LocalPlayer player, List<BlockPos[]> pairs, boolean reposition) {
        long now = com.killer560.hub.util.ActionGate.currentTick();
        if (meleeClickTick >= 0) {
            LanternPair pair = activePair;
            boolean answered = pair == null || pair != meleePair || pair.stage != meleeStage
                    || !client.level.getBlockState(meleeTarget()).is(net.minecraft.world.level.block.Blocks.SEA_LANTERN);
            if (answered) {
                LOGGER.info("[AutoPuzzles] Beams (melee): hit on {} answered {} tick(s) after it", AutoPuzzleUtil.fmt(meleeTarget()),
                        now - meleeClickTick);
                meleeClickTick = -1L;
                meleeTries = 0;
            } else if (now - meleeClickTick < MELEE_CONFIRM_TICKS) {
                return; // the server's answer is on its way
            } else {
                BlockPos lantern = meleeTarget();
                if (meleeFrom != null) {
                    MELEE_FAILED.computeIfAbsent(lantern, k -> new java.util.HashSet<>()).add(meleeFrom);
                }
                meleeClickTick = -1L;
                if (++meleeTries >= MELEE_MAX_TRIES) {
                    LOGGER.info("[AutoPuzzles] Beams (melee): {} never answered {} hits - leaving its pair for another",
                            AutoPuzzleUtil.fmt(lantern), meleeTries);
                    MELEE_SKIP.add(lantern);
                    activePair = null;
                    meleeTries = 0;
                } else {
                    LOGGER.info("[AutoPuzzles] Beams (melee): no answer to the hit on {} in {} ticks - trying again from "
                            + "another spot", AutoPuzzleUtil.fmt(lantern), MELEE_CONFIRM_TICKS);
                }
            }
        }
        if (MELEE_DONE.size() >= 8) {
            // Four pairs joined by their own sounds; the solver's list catches up a few ticks later and ends the run above.
            say("melee: four pairs joined - waiting for the room to read solved");
            return;
        }
        if (activePair == null) {
            activePair = pickMeleePair(client, player, pairs);
            if (activePair == null) {
                StringBuilder sb = new StringBuilder();
                for (BlockPos[] p : pairs) {
                    sb.append(' ').append(AutoPuzzleUtil.fmt(p[0])).append(" (").append(meleeSpots(client, p[0]).size())
                            .append(") - ").append(AutoPuzzleUtil.fmt(p[1])).append(" (").append(meleeSpots(client, p[1]).size())
                            .append(");");
                }
                // Not stuck for good: the rest is shot, as Shoot mode does it (the platform, the shortbow), so a room where
                // fewer pairs than it needs can be reached on foot is still finished.
                LOGGER.info("[AutoPuzzles] Beams (melee): no lit pair left with both lanterns in reach of a spot -{} - "
                        + "shooting the rest", sb);
                meleeFallback = true;
                return;
            }
            meleeTries = 0;
        }
        LanternPair pair = activePair;
        BlockPos lantern = pair.stage == 0 ? pair.first : pair.second;
        if (AutoReposition.landedThisTick()) {
            // The landing tick only gets ready: the body onto a point of the lantern he sees from where he will stand, so the
            // hit next tick needs no turn of its own.
            aimAtVisible(client, player, lantern, AutoPuzzleUtil.settledEye(player));
            return;
        }
        if (player.isShiftKeyDown()) {
            say("melee: waiting one tick for the landing's sneak to be let go");
            return;
        }
        BlockPos standing = standingOn(client, player);
        java.util.Set<BlockPos> failed = MELEE_FAILED.getOrDefault(lantern, java.util.Set.of());
        Vec3 eye = player.getEyePosition();
        boolean inReach = com.killer560.hub.util.BlockHits.boxDistanceSq(eye, lantern) <= AutoPuzzleUtil.BLOCK_REACH_SQ
                && visiblePoint(client, eye, lantern) != null && (standing == null || !failed.contains(standing));
        if (!inReach) {
            if (!reposition) {
                say("melee: " + AutoPuzzleUtil.fmt(lantern) + " is out of reach and Etherwarp Reposition is off");
                return;
            }
            BlockPos spot = meleeSpot(client, player, lantern, failed);
            if (spot == null) {
                LOGGER.info("[AutoPuzzles] Beams (melee): no spot in the room reaches {} ({} tried) - leaving its pair",
                        AutoPuzzleUtil.fmt(lantern), failed.size());
                MELEE_SKIP.add(lantern);
                activePair = null;
                return;
            }
            say("melee: warping to " + AutoPuzzleUtil.fmt(spot) + " for " + AutoPuzzleUtil.fmt(lantern));
            if (REPOSITION.start(client, spot, false, false, false)) {
                meleeWarps++;
                lastSaid = null;
            } else {
                MELEE_FAILED.computeIfAbsent(lantern, k -> new java.util.HashSet<>()).add(spot);
            }
            return;
        }
        CAMERA.engage(player); // before any turn, so the held view is where he was looking
        if (aimAtVisible(client, player, lantern, eye)) {
            say("melee: turning onto " + AutoPuzzleUtil.fmt(lantern) + " - the hit goes next tick");
            return; // the body turned onto a point of it he sees; the hit goes next tick, on that reported look
        }
        if (!meleeClick(client, player, lantern)) {
            say("melee: the hit on " + AutoPuzzleUtil.fmt(lantern) + " was held this tick (gate or turn)");
            return; // gate held, or the body was turned onto it this tick: the hit goes next tick
        }
        meleeClickTick = now;
        meleePair = pair;
        meleeStage = pair.stage;
        meleeFrom = standing;
        meleeHits++;
        lastSaid = null;
        LOGGER.info("[AutoPuzzles] Beams (melee): hit {} (pair stage {}) from {}, eye to box {}", AutoPuzzleUtil.fmt(lantern),
                pair.stage, AutoPuzzleUtil.fmt(standing), String.format(java.util.Locale.ROOT, "%.2f",
                        Math.sqrt(com.killer560.hub.util.BlockHits.boxDistanceSq(eye, lantern))));
    }

    /**
     * One melee hit on {@code lantern}: vanilla's own left click - the dig start, the swing and the dig abort a tap sends -
     * on the face his eye sees. THE REACH CHECK LIVES HERE, where the hit is sent: eye to the block's box, at most the 4.5
     * the server enforces. The tick is claimed through {@link AutoPuzzleUtil#gateWorldClick(Minecraft, BlockPos)}, which
     * also turns the body onto the lantern first when the reported look does not meet it (then nothing is sent this tick).
     *
     * @return true when the hit was sent
     */
    static boolean meleeClick(Minecraft client, LocalPlayer player, BlockPos lantern) {
        if (client.level == null || client.gameMode == null || player == null) {
            return false;
        }
        Vec3 eye = player.getEyePosition();
        if (com.killer560.hub.util.BlockHits.boxDistanceSq(eye, lantern) > AutoPuzzleUtil.BLOCK_REACH_SQ) {
            say("melee: " + AutoPuzzleUtil.fmt(lantern) + " is out of reach from here - no hit sent");
            return false;
        }
        if (!AutoPuzzleUtil.gateWorldClick(client, lantern)) {
            return false;
        }
        // The face the reported look strikes, as a real click's would be; the eye's own surface hit when it meets nothing.
        net.minecraft.world.phys.BlockHitResult hit = lookHit(client, player, lantern);
        if (hit == null) {
            hit = com.killer560.hub.util.BlockHits.surfaceOrCentre(client.level, lantern, eye);
        }
        // The order a tap sends (Minecraft.startAttack, then continueAttack with the button up): START, the swing, ABORT.
        client.gameMode.startDestroyBlock(lantern, hit.getDirection());
        player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        client.gameMode.stopDestroyBlock();
        return true;
    }

    private static BlockPos meleeTarget() {
        LanternPair p = meleePair;
        if (p == null) {
            return BlockPos.ZERO;
        }
        return meleeStage == 0 ? p.first : p.second;
    }

    /** The block he stands on (the landing's hover included), or null in the air. */
    private static BlockPos standingOn(Minecraft client, LocalPlayer player) {
        BlockPos below = BlockPos.containing(player.getX(), player.getY() - 0.1, player.getZ());
        return client.level.getBlockState(below).isAir() ? null : below;
    }

    /**
     * The lit pair to melee next: of the pairs whose BOTH lanterns have a spot to hit them from (and none set aside), the
     * one with a lantern nearest him, that lantern first. Both, before the first hit: a pair left after its first end is
     * hit leaves that end held, and the next pair's first hit then reads as its partner - a wrong beam (seen in the sim's
     * first run, where every pair has one end that is easy and many have one hanging under the ceiling out of reach).
     */
    private static LanternPair pickMeleePair(Minecraft client, LocalPlayer player, List<BlockPos[]> pairs) {
        LanternPair best = null;
        double bestD = Double.MAX_VALUE;
        Vec3 at = player.position();
        for (BlockPos[] p : pairs) {
            // Both still SEA LANTERNS: a pair just joined turns to prismarine a tick or more before the solver's list drops
            // it, and picking it again in between hit a finished lantern and read the prismarine as an answer.
            if (MELEE_SKIP.contains(p[0]) || MELEE_SKIP.contains(p[1]) || MELEE_DONE.contains(p[0]) || MELEE_DONE.contains(p[1])
                    || !client.level.getBlockState(p[0]).is(net.minecraft.world.level.block.Blocks.SEA_LANTERN)
                    || !client.level.getBlockState(p[1]).is(net.minecraft.world.level.block.Blocks.SEA_LANTERN)
                    || meleeSpots(client, p[0]).isEmpty() || meleeSpots(client, p[1]).isEmpty()) {
                continue;
            }
            double d0 = Vec3.atCenterOf(p[0]).distanceToSqr(at);
            double d1 = Vec3.atCenterOf(p[1]).distanceToSqr(at);
            if (Math.min(d0, d1) < bestD) {
                bestD = Math.min(d0, d1);
                best = d0 <= d1 ? new LanternPair(p[0], p[1]) : new LanternPair(p[1], p[0]);
            }
        }
        return best;
    }

    /**
     * Where to warp to hit {@code lantern}: of {@link #meleeSpots}, the nearest the lantern a hit has not already failed
     * from that he can warp onto from here; failing that, a first hop from which one can be warped onto.
     */
    private static BlockPos meleeSpot(Minecraft client, LocalPlayer player, BlockPos lantern, java.util.Set<BlockPos> failed) {
        List<BlockPos> spots = new java.util.ArrayList<>(meleeSpots(client, lantern));
        spots.removeAll(failed);
        if (spots.isEmpty()) {
            return null;
        }
        for (BlockPos s : spots) {
            if (!AutoPuzzleUtil.at(player, s) && AutoPuzzleUtil.etherwarpAim(client.level, player, s) != null) {
                return s;
            }
        }
        return AutoPuzzleUtil.hopToward(client, player, spots);
    }

    /** Per lantern, every spot that reaches it (worked out once per room visit: the room's blocks do not move). */
    private static final java.util.Map<BlockPos, List<BlockPos>> MELEE_SPOTS = new java.util.HashMap<>();

    /**
     * Every standable, etherwarpable block in the room (solid, two air above, inside the live map's room) from whose
     * standing eye the lantern's box is within reach and a point of it is in sight ({@link #visiblePoint}), nearest the
     * lantern first. Planned at {@link #MELEE_PLAN_REACH}, with the server's 4.5 as the fallback when nothing is that
     * close. The hit goes standing (it waits out the landing's sneak), so the standing eye is the one measured.
     *
     * <p>A lantern set into a wall or under a ledge shows one or two faces, and a ray to its CENTRE from most spots passes
     * through a neighbour first; measured in the sim's room, that alone hid every spot for seven of the twenty-two lanterns.
     * Hence a point on any face, not the centre. Even so, about half the lanterns there (hung under the ceiling, or set in a
     * wall with nothing to stand on in reach) have no spot at all: their pairs are never picked ({@link #pickMeleePair}),
     * and four of the eleven pairs can be played to the end - exactly the four beams the room needs.
     */
    private static List<BlockPos> meleeSpots(Minecraft client, BlockPos lantern) {
        List<BlockPos> cached = MELEE_SPOTS.get(lantern);
        if (cached != null) {
            return cached;
        }
        int idx = LiveMapFeature.currentRoomIndex();
        int[] bounds = idx < 0 ? null : LiveMapFeature.roomWorldBounds(idx);
        List<BlockPos> near = new java.util.ArrayList<>();
        List<BlockPos> far = new java.util.ArrayList<>();
        int standable = 0;
        int inReach = 0;
        int seen = 0;
        double planSq = MELEE_PLAN_REACH * MELEE_PLAN_REACH;
        int r = (int) Math.ceil(com.killer560.hub.cheatutils.CheatUtilsConfig.MEASURED_MAX_REACH) + 1;
        for (int x = lantern.getX() - r; x <= lantern.getX() + r; x++) {
            for (int z = lantern.getZ() - r; z <= lantern.getZ() + r; z++) {
                if (bounds != null && (x < bounds[0] || x > bounds[2] || z < bounds[1] || z > bounds[3])) {
                    continue;
                }
                for (int y = lantern.getY() - r - 2; y <= lantern.getY() + r - 1; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (pos.equals(lantern) || AutoPuzzleUtil.isPassable(client.level.getBlockState(pos))
                            || !client.level.getBlockState(pos.above()).isAir()
                            || !client.level.getBlockState(pos.above(2)).isAir()) {
                        continue;
                    }
                    standable++;
                    Vec3 eye = new Vec3(x + 0.5, y + standTop(client, pos) + AutoPuzzleUtil.EYE_STANDING, z + 0.5);
                    double d = com.killer560.hub.util.BlockHits.boxDistanceSq(eye, lantern);
                    if (d > AutoPuzzleUtil.BLOCK_REACH_SQ) {
                        continue;
                    }
                    inReach++;
                    if (visiblePoint(client, eye, lantern) == null) {
                        continue;
                    }
                    seen++;
                    if (!com.killer560.hub.livemap.autoclear.EtherwarpPathfinder.isEtherwarpable(pos) && !stepBlock(client, pos)) {
                        continue;
                    }
                    (d <= planSq ? near : far).add(pos);
                }
            }
        }
        List<BlockPos> out = near.isEmpty() ? far : near;
        Vec3 c = Vec3.atCenterOf(lantern);
        out.sort(java.util.Comparator.comparingDouble((BlockPos b) -> Vec3.atCenterOf(b).distanceToSqr(c)));
        LOGGER.info("[AutoPuzzles] Beams (melee): {} - {} spot(s) to hit it from ({} within {}; of {} standable, {} in reach, "
                        + "{} in sight)", AutoPuzzleUtil.fmt(lantern), out.size(), near.size(), MELEE_PLAN_REACH, standable,
                inReach, seen);
        List<BlockPos> result = List.copyOf(out);
        MELEE_SPOTS.put(lantern, result);
        return result;
    }

    /**
     * A stair or a bottom slab: the floor graph's etherwarp search will not plan a landing on either (a route must not stop
     * on half a block), but a single warp lands on one like on any block - an etherwarp only needs a top face to aim at and
     * room to stand - and in the sim's Creeper Beams room the only spots that reach two of the lanterns are such steps
     * (2026-10-18). Whether the warp really lands is still AutoReposition's to find out, with its own aim check and timeout.
     */
    private static boolean stepBlock(Minecraft client, BlockPos pos) {
        net.minecraft.world.level.block.state.BlockState st = client.level.getBlockState(pos);
        return st.getBlock() instanceof net.minecraft.world.level.block.StairBlock || isBottomSlab(st);
    }

    private static boolean isBottomSlab(net.minecraft.world.level.block.state.BlockState st) {
        return st.getBlock() instanceof net.minecraft.world.level.block.SlabBlock
                && st.hasProperty(net.minecraft.world.level.block.SlabBlock.TYPE)
                && st.getValue(net.minecraft.world.level.block.SlabBlock.TYPE)
                == net.minecraft.world.level.block.state.properties.SlabType.BOTTOM;
    }

    /** Where his feet rest on {@code pos}: half a block up on a bottom slab, a whole one on anything else (a stair's centre
     *  is on its upper step's edge, which holds him at the top). */
    private static double standTop(Minecraft client, BlockPos pos) {
        return isBottomSlab(client.level.getBlockState(pos)) ? 0.5 : 1.0;
    }

    /** Points tried on a lantern for a line of sight: the centre, then on every face its centre and four quarter points,
     *  each a hair inside the block so the ray enters through that face. */
    private static final double[][] FACE_POINTS = facePoints();

    private static double[][] facePoints() {
        List<double[]> pts = new java.util.ArrayList<>();
        pts.add(new double[]{0.5, 0.5, 0.5});
        double in = 0.02;
        double[][] onFace = {{0.5, 0.5}, {0.25, 0.25}, {0.25, 0.75}, {0.75, 0.25}, {0.75, 0.75}};
        for (int axis = 0; axis < 3; axis++) {
            for (double side : new double[]{in, 1.0 - in}) {
                for (double[] uv : onFace) {
                    double[] p = new double[3];
                    p[axis] = side;
                    p[(axis + 1) % 3] = uv[0];
                    p[(axis + 2) % 3] = uv[1];
                    pts.add(p);
                }
            }
        }
        return pts.toArray(new double[0][]);
    }

    /**
     * A point of {@code lantern} the eye sees, or null: the look a turn onto it would report (its yaw and pitch, as floats)
     * meets the lantern first - and so do the looks a third of a degree either way in yaw and in pitch. The margin is not
     * optional: the sim's first full run aimed at a lantern's centre past a neighbour's corner, the exact ray passing by a
     * ten-thousandth of a block, and the turned body's float look met the neighbour every tick, so the hit never went.
     */
    private static Vec3 visiblePoint(Minecraft client, Vec3 eye, BlockPos lantern) {
        for (double[] p : FACE_POINTS) {
            Vec3 target = new Vec3(lantern.getX() + p[0], lantern.getY() + p[1], lantern.getZ() + p[2]);
            if (target.subtract(eye).lengthSqr() < 1.0e-6) {
                continue;
            }
            float[] d = AutoPuzzleUtil.direction(eye, target);
            if (looksMeet(client, eye, d[0], d[1], lantern) && looksMeet(client, eye, d[0] + LOOK_MARGIN, d[1], lantern)
                    && looksMeet(client, eye, d[0] - LOOK_MARGIN, d[1], lantern)
                    && looksMeet(client, eye, d[0], d[1] + LOOK_MARGIN, lantern)
                    && looksMeet(client, eye, d[0], d[1] - LOOK_MARGIN, lantern)) {
                return target;
            }
        }
        return null;
    }

    private static final float LOOK_MARGIN = 0.35f;

    /** Whether a look at {@code yaw}/{@code pitch} from {@code eye} meets {@code lantern} before any other block, in reach. */
    private static boolean looksMeet(Minecraft client, Vec3 eye, float yaw, float pitch, BlockPos lantern) {
        float y = yaw * net.minecraft.util.Mth.DEG_TO_RAD;
        float x = net.minecraft.util.Mth.clamp(pitch, -90f, 90f) * net.minecraft.util.Mth.DEG_TO_RAD;
        float cp = net.minecraft.util.Mth.cos(x);
        Vec3 look = new Vec3(-net.minecraft.util.Mth.sin(y) * cp, -net.minecraft.util.Mth.sin(x), net.minecraft.util.Mth.cos(y) * cp);
        Vec3 end = eye.add(look.scale(com.killer560.hub.cheatutils.CheatUtilsConfig.MEASURED_MAX_REACH + 1.0));
        net.minecraft.world.phys.BlockHitResult hit = client.level.clip(new net.minecraft.world.level.ClipContext(eye, end,
                net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE,
                client.player));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && lantern.equals(hit.getBlockPos());
    }

    /** What the reported look meets first within reach, from his eye: the lantern's hit (face included) or null. */
    private static net.minecraft.world.phys.BlockHitResult lookHit(Minecraft client, LocalPlayer player, BlockPos lantern) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(com.killer560.hub.util.TurnFirst.look(player).scale(
                com.killer560.hub.cheatutils.CheatUtilsConfig.MEASURED_MAX_REACH + 1.0));
        net.minecraft.world.phys.BlockHitResult hit = client.level.clip(new net.minecraft.world.level.ClipContext(eye, end,
                net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && lantern.equals(hit.getBlockPos()) ? hit : null;
    }

    /**
     * Turns the BODY (camera held) onto a point of {@code lantern} his eye sees, unless the reported look already meets it
     * first. Nothing is sent; the next movement packet reports the look, and the hit goes the tick after.
     *
     * @return true when the body was turned (the hit waits a tick)
     */
    private static boolean aimAtVisible(Minecraft client, LocalPlayer player, BlockPos lantern, Vec3 eye) {
        if (lookHit(client, player, lantern) != null) {
            turnRepeats = 0;
            return false;
        }
        Vec3 point = visiblePoint(client, eye, lantern);
        if (point == null) {
            return false;
        }
        CAMERA.engage(player);
        float[] dir = AutoPuzzleUtil.direction(eye, point);
        if (++turnRepeats > 3) {
            // Turned onto it three ticks running and the reported look still meets something else first: this spot does
            // not work for it after all. Given up for this lantern, so the next tick warps somewhere else.
            BlockPos standing = standingOn(client, player);
            LOGGER.info("[AutoPuzzles] Beams (melee): the look onto {} from {} keeps meeting another block first - "
                    + "trying another spot", AutoPuzzleUtil.fmt(lantern), AutoPuzzleUtil.fmt(standing));
            if (standing != null) {
                MELEE_FAILED.computeIfAbsent(lantern, k -> new java.util.HashSet<>()).add(standing);
            }
            turnRepeats = 0;
            return true;
        }
        AutoPuzzleUtil.rotateCamera(player, dir[0], dir[1]);
        return true;
    }

    private static int turnRepeats = 0;

    private static boolean containsPair(List<BlockPos[]> pairs, LanternPair pair) {
        for (BlockPos[] p : pairs) {
            if ((p[0].equals(pair.first) && p[1].equals(pair.second)) || (p[0].equals(pair.second) && p[1].equals(pair.first))) {
                return true;
            }
        }
        return false;
    }

    /** Melee hits sent and warps started since the room was entered (tests read them). */
    static int meleeHits() {
        return meleeHits;
    }

    static int meleeWarps() {
        return meleeWarps;
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
        meleeClickTick = -1L;
        meleePair = null;
        meleeStage = -1;
        meleeFrom = null;
        meleeTries = 0;
        MELEE_FAILED.clear();
        MELEE_SKIP.clear();
        MELEE_SPOTS.clear();
        MELEE_DONE.clear();
        meleeFallback = false;
        meleeHits = 0;
        meleeWarps = 0;
    }
}
