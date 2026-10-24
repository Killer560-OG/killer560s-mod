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
 * {@link BeamsSolverFeature} lit-pair scan. Standing on the center platform (y == 75), it picks the first lit pair and
 * shoots its first lantern, then its second, with the held shortbow. Progress comes from the elder guardian hurt
 * sound at the lantern (pitch 1.3968254 = first hit, 2.0 = pair done - QUOI's exact values, via
 * {@code PuzzlePacketMixin}). The shot aims at the lantern's etherwarp-visible face point (falls back to its
 * center). If the creeper is between the player and the lantern it (with "Etherwarp Reposition" on) moves to the
 * first platform spot with a clear line; the same toggle also warps onto the platform when off it. After 4 solved
 * pairs it stops and releases sneak.
 * <p>
 * Miss cooldown removed (killer560, 2026-09-27: "For auto puzzles remove the miss cooldown."): it used to hold
 * every shot for an extra {@code missCooldownMs} on top of the Shoot cooldown whenever the hurt sound hadn't
 * arrived yet, in case that shot missed. Now every shot is paced by the Shoot cooldown alone, hit or miss.
 * <p>
 * Mode "Melee" (killer560's to-do item 29, 2026-10-18; Shoot above stays the default): no bow and no center platform -
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
            LOGGER.info("[AutoPuzzles] Beams: guardian hurt sound at {}, {}, {} (pitch {}) is not at the expected lantern {}"
                            + " (stage {}) - ignored (tick {})", packet.getX(), packet.getY(), packet.getZ(), packet.getPitch(),
                    AutoPuzzleUtil.fmt(pos), pair.stage, com.killer560.hub.util.SessionRecorderSink.tick());
            return;
        }
        if (pair.stage == 0 && packet.getPitch() == 1.3968254f) {
            pair.stage = 1;
            LOGGER.info("[AutoPuzzles] Beams: first lantern {} hit (hurt sound pitch {}) - next {} (tick {})",
                    AutoPuzzleUtil.fmt(pair.first), packet.getPitch(), AutoPuzzleUtil.fmt(pair.second),
                    com.killer560.hub.util.SessionRecorderSink.tick());
        } else if (pair.stage == 1 && packet.getPitch() == 2.0f) {
            pair.stage = 2;
            activePair = null;
            // Melee picks its next pair at once, before the solver's list (from block updates) drops this one.
            MELEE_DONE.add(pair.first);
            MELEE_DONE.add(pair.second);
            LOGGER.info("[AutoPuzzles] Beams: pair {} - {} joined (hurt sound pitch 2.0), {} lantern(s) done (tick {})",
                    AutoPuzzleUtil.fmt(pair.first), AutoPuzzleUtil.fmt(pair.second), MELEE_DONE.size(),
                    com.killer560.hub.util.SessionRecorderSink.tick());
        } else {
            LOGGER.info("[AutoPuzzles] Beams: guardian hurt sound at the expected lantern {} with pitch {} at stage {} - not"
                    + " a stage change (tick {})", AutoPuzzleUtil.fmt(pos), packet.getPitch(), pair.stage,
                    com.killer560.hub.util.SessionRecorderSink.tick());
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
                LOGGER.info("[AutoPuzzles] Beams: visit over ({}) after {} tick(s): {} pair(s) solved, melee hits {}, warps"
                                + " {}, AOTV teleports {}, shots {}, fallback to Shoot {}",
                        !cfg.isAutoBeamsEnabled() ? "switched off" : "left the room", sinceVisit(), solvedPairs, meleeHits,
                        meleeWarps, meleeDashes, meleeShots, meleeFallback);
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
        if (!wasInRoom) {
            visitStartTick = com.killer560.hub.util.SessionRecorderSink.tick();
            LOGGER.info("[AutoPuzzles] Beams: entered with the auto on - mode {}, {} lit pair(s) listed, reposition {},"
                            + " shoot cooldown {} ms (tick {})", cfg.isAutoBeamsMelee() ? "melee" : "shoot", pairs.size(),
                    cfg.isEtherwarpReposition(), cfg.getShootCooldownMs(), visitStartTick);
        }
        wasInRoom = true;
        CAMERA.keep(client.player);
        if (!GUARD.solverOn(BeamsSolverConfig.getInstance().isEnabled())) {
            say("waiting: Creeper Beams Solver is off (or the guard has not trusted it yet)");
            active = false;
            CAMERA.release(client.player);
            return;
        }
        active = true;
        boolean melee = cfg.isAutoBeamsMelee();
        // QUOI recalculateLanternPairs: drop the active pair once its first lantern is no longer lit. Melee hits the nearer
        // end first, which may be the solver's second, so it asks whether the pair itself is still listed.
        if (activePair != null && !(melee ? containsPair(pairs, activePair) : containsFirst(pairs, activePair.first))) {
            LOGGER.info("[AutoPuzzles] Beams: active pair {} - {} (stage {}) no longer listed by the solver - dropped"
                            + " ({} pair(s) listed, tick {})", AutoPuzzleUtil.fmt(activePair.first),
                    AutoPuzzleUtil.fmt(activePair.second), activePair.stage, pairs.size(),
                    com.killer560.hub.util.SessionRecorderSink.tick());
            activePair = null;
        }
        if (lastPairCount >= 0 && pairs.size() < lastPairCount) {
            LOGGER.info("[AutoPuzzles] Beams: the solver's list fell {} -> {} - pair {} of 4 solved ({} tick(s) into the"
                    + " visit)", lastPairCount, pairs.size(), solvedPairs + 1, sinceVisit());
        }
        if (lastPairCount >= 0 && pairs.size() < lastPairCount && ++solvedPairs == 4) {
            LOGGER.info("[AutoPuzzles] Beams: done - four pairs joined after {} tick(s) (melee hits {}, warps {}, AOTV {},"
                    + " shots {})", sinceVisit(), meleeHits, meleeWarps, meleeDashes, meleeShots);
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
            LOGGER.debug("[AutoPuzzles] Beams: reposition under way");
            return;
        }
        boolean reposition = cfg.isEtherwarpReposition();
        if (melee && !meleeFallback) {
            meleeTick(client, player, pairs, reposition, cr);
            if (!meleeFallback) {
                return;
            }
        }
        // THE PLATFORM'S OWN HEIGHT, not the literal 75. killer560 (2026-10-01): "auto creeper beams would look
        // down and teleport and that was it."
        //
        // That is this check, and it could never pass in the sim. 75 is Hypixel's height for the top of the
        // center platform - start is room-relative (16,74,14), so one above it IS 75 on a real floor and the two
        // readings are identical there. The sim shifts the whole map vertically (SimAltitude), so the platform is
        // somewhere else entirely and player.getY() was never 75: it repositioned onto the platform, re-read 75,
        // found it still false, and repositioned again. Warp, look down, nothing, forever.
        double platformY = start.getY() + 1.0;
        // Within half a block, not exactly equal. An etherwarp lands 0.05 over the block and gravity settles him a
        // tick or two later; read in between, an exact test said "not on the platform" and started a SECOND warp
        // straight after the first had landed.
        if (Math.abs(player.getY() - platformY) > 0.5) {
            if (reposition) {
                say("off the center platform (platform feet y " + platformY + ") - repositioning onto "
                        + AutoPuzzleUtil.fmt(start));
                boolean started = REPOSITION.start(client, start, true, false, false);
                if (!started) {
                    held("platform", "no etherwarp line onto the center platform " + AutoPuzzleUtil.fmt(start) + " from "
                            + fmt(player.position()) + (melee ? " - trying a hop toward it" : " - waiting"));
                }
                if (!started && melee) {
                    // Melee's last resort only (Shoot mode is unchanged): no line onto the platform from wherever melee left
                    // him, so a hop toward it first - waiting here was the in-game stall of 2026-10-18.
                    BlockPos hop = AutoPuzzleUtil.hopToward(client, player, List.of(start));
                    if (hop != null) {
                        boolean hopped = REPOSITION.start(client, hop, true, false, false);
                        held("platformhop" + hop, "hop toward the platform via " + AutoPuzzleUtil.fmt(hop) + " "
                                + (hopped ? "started" : "refused"));
                    } else {
                        held("platformhop", "no hop toward the center platform either");
                    }
                }
            } else {
                say("waiting: stand on the center platform (Etherwarp Reposition is off)");
            }
            return;
        }
        if (activePair == null) {
            BlockPos[] first = pairs.get(0);
            activePair = new LanternPair(first[0], first[1]);
            LOGGER.info("[AutoPuzzles] Beams: next pair {} - {} (first of {} listed, {} solved, tick {})",
                    AutoPuzzleUtil.fmt(first[0]), AutoPuzzleUtil.fmt(first[1]), pairs.size(), solvedPairs,
                    com.killer560.hub.util.SessionRecorderSink.tick());
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
                    LOGGER.info("[AutoPuzzles] Beams: no etherwarp aim onto platform spot {} - skipping it next tick",
                            AutoPuzzleUtil.fmt(spot));
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
            LOGGER.debug("[AutoPuzzles] Beams: no shot this tick - bow {}, cooldown {} of {} ms", bow, now - lastShotTime,
                    cfg.getShootCooldownMs());
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
            held("gate" + lantern, "the action gate held the shot at " + AutoPuzzleUtil.fmt(lantern));
            return; // gate held this tick back - no shot, so lastShotTime must not move
        }
        long sinceLast = lastShotTime <= 0L ? -1L : now - lastShotTime;
        lastShotTime = now;
        lastSaid = null;
        LOGGER.info("[AutoPuzzles] Beams: shot at {} (pair stage {}) from {}, yaw {} pitch {}{}, holding {}, {} ms since"
                        + " the last shot, {} solved (tick {})", AutoPuzzleUtil.fmt(lantern),
                pair.stage, String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f", eye.x, eye.y, eye.z),
                String.format(java.util.Locale.ROOT, "%.1f", dir[0]), String.format(java.util.Locale.ROOT, "%.1f", dir[1]),
                seen ? "" : " - no clear line to any face, aimed at the center",
                player.getMainHandItem().getHoverName().getString(), sinceLast, solvedPairs,
                com.killer560.hub.util.SessionRecorderSink.tick());
    }

    /**
     * Where to aim so the shot's line meets {@code lantern} first: its center, then the middle of each face pulled
     * a little inside the block, each checked with a collider ray from the REAL eye.
     *
     * <p>This was {@link AutoPuzzleUtil#etherwarpDirection}, which aims from a SNEAKING eye whatever his stance and
     * at QUOI's etherwarp sample points - face edges 0.001 in, and a top center that sits exactly on the face. An
     * arrow is not an etherwarp: from the real eye an edge aim lands on the neighbor as often as on the lantern,
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
    /** The same for a shot: the arrow's flight comes on top of the round trip. */
    private static final int SHOT_CONFIRM_TICKS = 20;
    /** Ticks an Aspect of the Void teleport may take to land him where it was planned to before the plan is dropped. */
    private static final int AOTV_LAND_TIMEOUT = 20;
    /** Unanswered hits on one lantern before its pair is set aside for another. */
    private static final int MELEE_MAX_TRIES = 4;
    /** Teleports back into reach of one lantern after unanswered hits (or a teleport that missed it), per room visit: a
     *  hit that gets no answer while he is in the air or out of reach re-teleports from where he now is, at most this many
     *  times, then the usual spot, shot and Shoot-mode handling takes over. */
    private static final int MELEE_MAX_BACKS = 3;
    /** Spots are planned this far from the lantern's box, inside the 4.5 the server enforces, so the landing's own
     *  hover and rounding can never put the hit out of range; 4.5 itself is the fallback when nothing is that close. */
    private static final double MELEE_PLAN_REACH = 4.0;

    /** How a lantern is reached, cheapest first: an etherwarp spot in reach, an Aspect of the Void teleport into reach
     *  (from a spot it can be used from), a shot, or not at all. */
    private enum Way { MELEE, AOTV, SHOOT, NONE }

    /**
     * An Aspect of the Void teleport (Instant Transmission, no sneak) that leaves him within reach of a lantern: used from
     * {@code from} (null: from where he stands now) at {@code yaw}/{@code pitch}, it lands his feet at {@code land}.
     */
    private record AotvPlan(BlockPos from, float yaw, float pitch, Vec3 land) {
    }

    /** The hit awaiting its answer: the client tick it went, the pair, which end (stage) and where it was hit from. */
    private static long meleeClickTick = -1L;
    private static LanternPair meleePair = null;
    private static int meleeStage = -1;
    private static BlockPos meleeFrom = null;
    /** Whether that hit was a shot (its answer may take longer) and, for a hit made after a teleport, that teleport's plan. */
    private static boolean meleeWasShot = false;
    private static AotvPlan meleeFromPlan = null;
    private static int meleeTries = 0;
    /** Per lantern, the spots a hit from went unanswered - the retry goes from somewhere else. */
    private static final java.util.Map<BlockPos, java.util.Set<BlockPos>> MELEE_FAILED =
            new java.util.HashMap<>();
    /** Lanterns nothing reaches, or that never answered: pairs holding one are left to the others. */
    private static final java.util.Set<BlockPos> MELEE_SKIP = new java.util.HashSet<>();
    /** Both ends of every pair whose completing hurt sound came ({@link #onSound}). */
    private static final java.util.Set<BlockPos> MELEE_DONE = new java.util.HashSet<>();
    /** Per lantern, the teleports that reach it ({@link #aotvPlans}) and the spots with a clear shot at it ({@link #shootSpots}). */
    private static final java.util.Map<BlockPos, List<AotvPlan>> AOTV_PLANS = new java.util.HashMap<>();
    private static final java.util.Map<BlockPos, List<BlockPos>> SHOOT_SPOTS = new java.util.HashMap<>();
    /** Teleports back already asked for, per lantern, and the lantern whose next teleport goes from where he is now. */
    private static final java.util.Map<BlockPos, Integer> MELEE_BACKS = new java.util.HashMap<>();
    private static BlockPos meleeBack = null;
    /** The teleport in flight: when it was used, its plan, the lantern it is for and where he stood. */
    private static long dashTick = -1L;
    /** His feet when the teleport in flight was used. */
    private static Vec3 dashFeet = null;
    private static AotvPlan dashPlan = null;
    private static BlockPos dashLantern = null;
    /** The plan of the teleport that last landed, until the hit it was for goes (or he moves on). */
    private static AotvPlan airPlan = null;
    private static int meleeHits = 0;
    private static int meleeWarps = 0;
    private static int meleeDashes = 0;
    private static int meleeShots = 0;
    /** Set when no lit pair can be finished any way at all: the rest of this room is played as Shoot mode plays it. */
    private static boolean meleeFallback = false;

    /**
     * Teleport looks Hypixel refused with "There are blocks in the way!", as "feet block|yaw|pitch" (one decimal), so
     * {@link #planFrom} never hands the same look back. killer560's Mod Only Test log, 2026-10-19 15:49:30-35: the AOTV
     * teleport from (-132.5, 74, -54.5) at yaw -45.0 pitch -41.2 was refused six times in a row, each time waited out for
     * the full {@link #AOTV_LAND_TIMEOUT} and then planned again identically from where he stood ("dropped ... from where
     * he stood - 5 left" dropped nothing, because the from-here plan is not in the list) - the "stuck" he saw.
     */
    private static final java.util.Set<String> REFUSED_LOOKS = new java.util.HashSet<>();
    /** Set by {@link #onChat} when Hypixel refused the teleport in flight; read by {@link #meleeTick} the same tick. */
    private static boolean dashRefused = false;
    private static final java.util.regex.Pattern BLOCKS_IN_THE_WAY =
            java.util.regex.Pattern.compile("^There are blocks in the way!$");

    static {
        com.killer560.hub.util.ChatObserver.subscribe(AutoBeams::onChat);
    }

    private static String lookKey(Vec3 feet, float yaw, float pitch) {
        BlockPos b = BlockPos.containing(feet);
        return b.asLong() + String.format(java.util.Locale.ROOT, "|%.1f|%.1f", yaw, pitch);
    }

    /** Hypixel's refusal of an Instant Transmission (or etherwarp) of ours, anchored so no player's chat line matches. */
    private static void onChat(net.minecraft.network.chat.Component message) {
        try {
            if (!active) {
                return;
            }
            String plain = com.killer560.hub.util.ChatObserver.strip(message).trim();
            if (!BLOCKS_IN_THE_WAY.matcher(plain).matches()) {
                return;
            }
            Minecraft client = Minecraft.getInstance();
            LocalPlayer player = client.player;
            if (player == null || client.level == null) {
                return;
            }
            if (dashTick >= 0 && dashPlan != null) {
                dashRefused = true;
                logInTheWay(client, player, dashPlan.yaw(), dashPlan.pitch(), "the AOTV teleport toward "
                        + AutoPuzzleUtil.fmt(dashLantern));
            } else {
                logInTheWay(client, player, player.getYRot(), player.getXRot(), "no AOTV teleport of melee's in flight "
                        + "(an etherwarp, or a click with the AOTV held)");
            }
        } catch (RuntimeException e) {
            LOGGER.error("[AutoPuzzles] Beams: blocks-in-the-way handler failed", e);
        }
    }

    /** One INFO line naming what stands on the look: the eye's first block, and the first block the feet-to-head column
     *  meets one step along it - so the log can say which block Hypixel stopped on. */
    private static void logInTheWay(Minecraft client, LocalPlayer player, float yaw, float pitch, String what) {
        float y = yaw * net.minecraft.util.Mth.DEG_TO_RAD;
        float x = net.minecraft.util.Mth.clamp(pitch, -90f, 90f) * net.minecraft.util.Mth.DEG_TO_RAD;
        float cp = net.minecraft.util.Mth.cos(x);
        Vec3 look = new Vec3(-net.minecraft.util.Mth.sin(y) * cp, -net.minecraft.util.Mth.sin(x), net.minecraft.util.Mth.cos(y) * cp);
        Vec3 eye = player.getEyePosition();
        net.minecraft.world.phys.BlockHitResult hit = client.level.clip(new net.minecraft.world.level.ClipContext(eye,
                eye.add(look.scale(3.0)), net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        String eyeBlock = hit == null || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK ? "nothing within 3"
                : client.level.getBlockState(hit.getBlockPos()).getBlock().getName().getString() + " at "
                + AutoPuzzleUtil.fmt(hit.getBlockPos()) + String.format(java.util.Locale.ROOT, " (%.2f out)",
                hit.getLocation().distanceTo(eye));
        StringBuilder column = new StringBuilder();
        for (double d = 0.5; d <= 1.5 && column.isEmpty(); d += 0.5) {
            Vec3 feet = player.position().add(look.scale(d));
            for (int h = 0; h < 2; h++) {
                BlockPos p = BlockPos.containing(feet.x, feet.y + h + 0.01, feet.z);
                var st = client.level.getBlockState(p);
                if (!st.getCollisionShape(client.level, p).isEmpty()) {
                    column.append(st.getBlock().getName().getString()).append(" at ").append(AutoPuzzleUtil.fmt(p))
                            .append(String.format(java.util.Locale.ROOT, " (%.1f along, %s)", d, h == 0 ? "feet" : "head"));
                    break;
                }
            }
        }
        LOGGER.info("[AutoPuzzles] Beams: \"There are blocks in the way!\" for {} | from {} yaw {} pitch {} | eye ray meets "
                        + "{} | body column meets {} | holding {}, {} refused look(s) so far (tick {})", what,
                fmt(player.position()),
                String.format(java.util.Locale.ROOT, "%.1f", yaw), String.format(java.util.Locale.ROOT, "%.1f", pitch),
                eyeBlock, column.isEmpty() ? "nothing within 1.5" : column,
                player.getMainHandItem().getHoverName().getString(), REFUSED_LOOKS.size(),
                com.killer560.hub.util.SessionRecorderSink.tick());
    }

    /**
     * Melee mode (killer560's to-do item 29, 2026-10-18: "etherwarps and teleports around the room and will mele the
     * blocks"). For the active pair's next lantern: if he is within reach of it (eye to its box, and a point of it in
     * sight) and that spot has not already failed it, hit it - one left click. Otherwise, the cheapest way there:
     * <ol>
     *   <li>etherwarp (the same {@link AutoReposition} every puzzle uses) to the nearest standable spot that reaches it;</li>
     *   <li>with no such spot - a lantern hung high up (killer560, 2026-10-18: "use a regular Aspect of the Void teleport
     *   when etherwarp has no spot") - an Aspect of the Void teleport that lands him in reach, from where he stands or from
     *   a spot it is warped to first, and the hit in the air the tick after the landing;</li>
     *   <li>with neither, the shortbow, as Shoot mode shoots (from where he stands when the line is clear and misses the
     *   creeper, else from a spot that has such a line) - "it should shoot that one instead of stopping".</li>
     * </ol>
     * A hit counts once the hurt sound moves the pair's stage or the lantern changes; with neither inside
     * {@link #MELEE_CONFIRM_TICKS} (a shot: {@link #SHOT_CONFIRM_TICKS}) the spot, or the teleport, is marked failed for that
     * lantern and the next try goes another way.
     *
     * <p>Only discrete inputs: the warp is AutoReposition's sneak and use, the teleport and the shot are the puzzle autos'
     * use with its look in the packet ({@link AutoPuzzleUtil#useItemRotated}), the hit is vanilla's own left click
     * ({@link #meleeClick}), and the body is turned through the puzzle autos' turn-first path with the camera held.
     */
    private static void meleeTick(Minecraft client, LocalPlayer player, List<BlockPos[]> pairs, boolean reposition, int[] cr) {
        long now = com.killer560.hub.util.ActionGate.currentTick();
        if (dashTick >= 0) {
            AotvPlan plan = dashPlan;
            if (player.position().distanceToSqr(plan.land()) < 1.0) {
                // Landed where it was planned. Our own landing: the gate's teleport settle must not hold the hit for 6 ticks.
                com.killer560.hub.util.ActionGate.expectSelfTeleport(com.killer560.hub.util.ActionGate.Actor.PUZZLE_WORLD,
                        com.killer560.hub.util.ActionGate.TELEPORT_SETTLE_TICKS);
                LOGGER.info("[AutoPuzzles] Beams (melee): AOTV teleport landed at {} ({} tick(s) after the use), {} eye to "
                                + "box", fmt(player.position()), now - dashTick, String.format(java.util.Locale.ROOT, "%.2f",
                        Math.sqrt(com.killer560.hub.util.BlockHits.boxDistanceSq(player.getEyePosition(), dashLantern))));
                dashTick = -1L;
                airPlan = plan;
                // The landing tick turns the body onto the lantern; the hit goes the tick after, before he has fallen.
                aimAtVisible(client, player, dashLantern, player.getEyePosition());
                return;
            }
            if (dashRefused || now - dashTick > AOTV_LAND_TIMEOUT) {
                // Refused: Hypixel's "There are blocks in the way!" came (onChat) - handled the tick it arrives instead of
                // waiting out the timeout, and the look is never planned again from that block.
                if (dashFeet != null) {
                    REFUSED_LOOKS.add(lookKey(dashFeet, plan.yaw(), plan.pitch()));
                    LOGGER.info("[AutoPuzzles] Beams (melee): look {} / {} from block {} marked refused ({} refused look(s))",
                            String.format(java.util.Locale.ROOT, "%.1f", plan.yaw()),
                            String.format(java.util.Locale.ROOT, "%.1f", plan.pitch()),
                            AutoPuzzleUtil.fmt(BlockPos.containing(dashFeet)), REFUSED_LOOKS.size());
                }
                LOGGER.info("[AutoPuzzles] Beams (melee): the AOTV teleport for {} {} (planned landing {}, he is at {}) - "
                                + "dropping that teleport and its look", AutoPuzzleUtil.fmt(dashLantern),
                        dashRefused ? "was refused (\"There are blocks in the way!\") " + (now - dashTick) + " tick(s) "
                                + "after the use" : "never landed", fmt(plan.land()), fmt(player.position()));
                dashRefused = false;
                dropPlan(dashLantern, plan);
                requestBack(dashLantern);
                dashTick = -1L;
            } else {
                LOGGER.debug("[AutoPuzzles] Beams (melee): AOTV teleport in flight, {} tick(s), {} from the landing", now - dashTick,
                        Math.sqrt(player.position().distanceToSqr(plan.land())));
            }
            return;
        }
        if (meleeClickTick >= 0) {
            LanternPair pair = activePair;
            boolean answered = pair == null || pair != meleePair || pair.stage != meleeStage
                    || !client.level.getBlockState(meleeTarget()).is(net.minecraft.world.level.block.Blocks.SEA_LANTERN);
            if (answered) {
                LOGGER.info("[AutoPuzzles] Beams (melee): {} on {} answered {} tick(s) after it", meleeWasShot ? "shot" : "hit",
                        AutoPuzzleUtil.fmt(meleeTarget()), now - meleeClickTick);
                meleeClickTick = -1L;
                meleeTries = 0;
            } else if (now - meleeClickTick < (meleeWasShot ? SHOT_CONFIRM_TICKS : MELEE_CONFIRM_TICKS)) {
                LOGGER.debug("[AutoPuzzles] Beams (melee): waiting for the answer to the {} on {}, {} of {} tick(s)",
                        meleeWasShot ? "shot" : "hit", meleeTarget(), now - meleeClickTick,
                        meleeWasShot ? SHOT_CONFIRM_TICKS : MELEE_CONFIRM_TICKS);
                return; // the server's answer is on its way
            } else {
                BlockPos lantern = meleeTarget();
                if (meleeFromPlan != null) {
                    dropPlan(lantern, meleeFromPlan);
                } else if (meleeFrom != null) {
                    MELEE_FAILED.computeIfAbsent(lantern, k -> new java.util.HashSet<>()).add(meleeFrom);
                }
                meleeClickTick = -1L;
                if (!meleeWasShot && meleeTries + 1 < MELEE_MAX_TRIES) {
                    requestBack(lantern);
                }
                if (++meleeTries >= MELEE_MAX_TRIES) {
                    LOGGER.info("[AutoPuzzles] Beams (melee): {} never answered {} tries - leaving its pair for another",
                            AutoPuzzleUtil.fmt(lantern), meleeTries);
                    MELEE_SKIP.add(lantern);
                    activePair = null;
                    meleeTries = 0;
                } else {
                    LOGGER.info("[AutoPuzzles] Beams (melee): no answer to the {} on {} from {} within {} tick(s) - trying"
                                    + " again another way (try {}/{})", meleeWasShot ? "shot" : "hit", AutoPuzzleUtil.fmt(lantern),
                            meleeFrom == null ? "the air" : AutoPuzzleUtil.fmt(meleeFrom),
                            meleeWasShot ? SHOT_CONFIRM_TICKS : MELEE_CONFIRM_TICKS, meleeTries, MELEE_MAX_TRIES);
                }
            }
        }
        if (MELEE_DONE.size() >= 8) {
            // Four pairs joined by their own sounds; the solver's list catches up a few ticks later and ends the run above.
            say("melee: four pairs joined - waiting for the room to read solved");
            return;
        }
        BlockPos creeper = PuzzleCoords.real(15, 74, 15, cr);
        if (activePair == null) {
            activePair = pickMeleePair(client, player, pairs, creeper);
            if (activePair == null) {
                StringBuilder sb = new StringBuilder();
                for (BlockPos[] p : pairs) {
                    sb.append(' ').append(AutoPuzzleUtil.fmt(p[0])).append(" (").append(wayFor(client, player, p[0], creeper))
                            .append(") - ").append(AutoPuzzleUtil.fmt(p[1])).append(" (")
                            .append(wayFor(client, player, p[1], creeper)).append(");");
                }
                LOGGER.info("[AutoPuzzles] Beams (melee): no lit pair left that can be finished any way -{} - playing the "
                        + "rest as Shoot mode does", sb);
                meleeFallback = true;
                return;
            }
            meleeTries = 0;
            LOGGER.info("[AutoPuzzles] Beams (melee): next pair {} ({}) - {} ({})", AutoPuzzleUtil.fmt(activePair.first),
                    wayFor(client, player, activePair.first, creeper), AutoPuzzleUtil.fmt(activePair.second),
                    wayFor(client, player, activePair.second, creeper));
        }
        LanternPair pair = activePair;
        BlockPos lantern = pair.stage == 0 ? pair.first : pair.second;
        if (AutoReposition.landedThisTick()) {
            // The landing tick only gets ready: the body onto a point of the lantern he sees from where he will stand, so the
            // hit next tick needs no turn of its own.
            boolean turned = aimAtVisible(client, player, lantern, AutoPuzzleUtil.settledEye(player));
            LOGGER.info("[AutoPuzzles] Beams (melee): warp landed at {} - {} for {} (tick {})", fmt(player.position()),
                    turned ? "body turned onto it" : "no turn needed (or no visible point)", AutoPuzzleUtil.fmt(lantern),
                    com.killer560.hub.util.SessionRecorderSink.tick());
            return;
        }
        BlockPos standing = standingOn(client, player);
        java.util.Set<BlockPos> failed = MELEE_FAILED.getOrDefault(lantern, java.util.Set.of());
        Vec3 eye = player.getEyePosition();
        boolean inGeom = com.killer560.hub.util.BlockHits.boxDistanceSq(eye, lantern) <= AutoPuzzleUtil.BLOCK_REACH_SQ
                && visiblePoint(client, eye, lantern) != null;
        boolean inReach = !player.isShiftKeyDown() && inGeom && (standing == null || !failed.contains(standing));
        if (inReach) {
            hitNow(client, player, pair, lantern, standing, eye, now);
            return;
        }
        // A hit with no answer, after which he is in the air or out of reach, teleports him back in from where he is now:
        // the same Aspect of the Void teleport, planned, landed and reach-checked as always. Standing in reach on the ground
        // the spot logic below moves him instead, as before.
        boolean backFromHere = lantern.equals(meleeBack) && (!player.onGround() || !inGeom);
        Way way = backFromHere ? Way.AOTV : wayFor(client, player, lantern, creeper);
        if (way != Way.SHOOT && player.isShiftKeyDown()) {
            // A hit or a teleport goes standing: let go of a sneak an earlier warp or shot left held (only ever ours).
            AutoReposition.releaseSneak(client);
            say("melee: waiting one tick for the sneak to be let go");
            return;
        }
        if (!reposition && way != Way.SHOOT) {
            say("melee: " + AutoPuzzleUtil.fmt(lantern) + " is out of reach and Etherwarp Reposition is off");
            return;
        }
        switch (way) {
            case MELEE -> {
                BlockPos spot = meleeSpot(client, player, lantern, failed);
                if (spot == null) {
                    say("melee: no spot he can warp to reaches " + AutoPuzzleUtil.fmt(lantern) + " from here");
                    MELEE_FAILED.computeIfAbsent(lantern, k -> new java.util.HashSet<>()).addAll(meleeSpots(client, lantern));
                    return;
                }
                say("melee: warping to " + AutoPuzzleUtil.fmt(spot) + " for " + AutoPuzzleUtil.fmt(lantern));
                if (REPOSITION.start(client, spot, false, false, false)) {
                    meleeWarps++;
                    lastSaid = null;
                    LOGGER.info("[AutoPuzzles] Beams (melee): etherwarp {} onto {} for {} (stage {}, tick {})", meleeWarps,
                            AutoPuzzleUtil.fmt(spot), AutoPuzzleUtil.fmt(lantern), pair.stage,
                            com.killer560.hub.util.SessionRecorderSink.tick());
                } else {
                    LOGGER.info("[AutoPuzzles] Beams (melee): etherwarp onto {} for {} refused to start - spot marked failed",
                            AutoPuzzleUtil.fmt(spot), AutoPuzzleUtil.fmt(lantern));
                    MELEE_FAILED.computeIfAbsent(lantern, k -> new java.util.HashSet<>()).add(spot);
                }
            }
            case AOTV -> aotvStep(client, player, lantern, now, backFromHere);
            case SHOOT -> shootStep(client, player, pair, lantern, creeper, standing, reposition, now);
            case NONE -> {
                LOGGER.info("[AutoPuzzles] Beams (melee): nothing reaches {} any more (stage {}) - leaving its pair",
                        AutoPuzzleUtil.fmt(lantern), pair.stage);
                MELEE_SKIP.add(lantern);
                activePair = null;
            }
        }
    }

    /**
     * Asks for a teleport back into reach of {@code lantern} (an unanswered hit, or a teleport that never landed), at most
     * {@link #MELEE_MAX_BACKS} per lantern per room visit. Returns without asking once the cap is reached: the usual spot,
     * shot and Shoot-mode handling then carries on as it did before.
     */
    private static void requestBack(BlockPos lantern) {
        int n = MELEE_BACKS.getOrDefault(lantern, 0);
        if (n >= MELEE_MAX_BACKS) {
            LOGGER.info("[AutoPuzzles] Beams (melee): {} has had {} teleports back - no more, the usual handling takes over",
                    AutoPuzzleUtil.fmt(lantern), n);
            return;
        }
        MELEE_BACKS.put(lantern, n + 1);
        meleeBack = lantern;
        LOGGER.info("[AutoPuzzles] Beams (melee): no answer - teleporting back into reach of {} from where he is ({} of {})",
                AutoPuzzleUtil.fmt(lantern), n + 1, MELEE_MAX_BACKS);
    }

    /** The hit itself, from where he is (standing, or in the air after a teleport). */
    private static void hitNow(Minecraft client, LocalPlayer player, LanternPair pair, BlockPos lantern, BlockPos standing,
                               Vec3 eye, long now) {
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
        meleeWasShot = false;
        meleeFromPlan = standing == null ? airPlan : null;
        meleeBack = null;
        meleeHits++;
        lastSaid = null;
        LOGGER.info("[AutoPuzzles] Beams (melee): hit {} (pair stage {}) from {}, eye to box {}{}, holding {}, hit {}, try {}"
                        + " (tick {})", AutoPuzzleUtil.fmt(lantern),
                pair.stage, standing == null ? "the air at " + fmt(player.position()) : AutoPuzzleUtil.fmt(standing),
                String.format(java.util.Locale.ROOT, "%.2f", Math.sqrt(com.killer560.hub.util.BlockHits.boxDistanceSq(eye,
                        lantern))), meleeFromPlan != null ? " via aotv" : "",
                player.getMainHandItem().getHoverName().getString(), meleeHits, meleeTries + 1,
                com.killer560.hub.util.SessionRecorderSink.tick());
        airPlan = null;
    }

    /**
     * An Aspect of the Void teleport into reach of {@code lantern}: from where he stands if one of the planned looks works
     * from here, else from the nearest planned spot, etherwarped to first. Re-checked from his real position before the use
     * (it is the server that moves him, along the look in the use packet), and dropped when it no longer lands in reach.
     */
    private static void aotvStep(Minecraft client, LocalPlayer player, BlockPos lantern, long now, boolean fromAir) {
        List<AotvPlan> plans = aotvPlans(client, player, lantern);
        // A teleport back (fromAir): planned from wherever he is, in the air too - the landing is checked below as always.
        AotvPlan here = player.onGround() || fromAir ? planFrom(client, player, lantern, player.position(), null) : null;
        AotvPlan plan = here;
        if (plan == null) {
            for (AotvPlan p : plans) {
                if (AutoPuzzleUtil.at(player, p.from())) {
                    plan = p;
                    break;
                }
            }
        }
        if (plan == null) {
            // Not on a spot to teleport from yet: warp to the first he can aim an etherwarp at, else a hop toward them.
            BlockPos spot = null;
            for (AotvPlan p : plans) {
                if (AutoPuzzleUtil.etherwarpAim(client.level, player, p.from()) != null) {
                    spot = p.from();
                    break;
                }
            }
            if (spot == null && !plans.isEmpty()) {
                List<BlockPos> froms = new java.util.ArrayList<>();
                for (AotvPlan p : plans) {
                    froms.add(p.from());
                }
                spot = AutoPuzzleUtil.hopToward(client, player, froms);
            }
            if (spot == null) {
                LOGGER.info("[AutoPuzzles] Beams (melee): none of the {} spot(s) to AOTV up to {} from can be warped to - "
                        + "dropping them", plans.size(), AutoPuzzleUtil.fmt(lantern));
                AOTV_PLANS.put(lantern, List.of());
                meleeBack = null;
                return;
            }
            say("melee: warping to " + AutoPuzzleUtil.fmt(spot) + " to AOTV up to " + AutoPuzzleUtil.fmt(lantern));
            if (REPOSITION.start(client, spot, false, false, false)) {
                meleeWarps++;
                lastSaid = null;
                LOGGER.info("[AutoPuzzles] Beams (melee): etherwarp {} onto {} to AOTV from ({} plan(s), tick {})", meleeWarps,
                        AutoPuzzleUtil.fmt(spot), plans.size(), com.killer560.hub.util.SessionRecorderSink.tick());
            } else {
                held("aotvwarp" + spot, "etherwarp onto AOTV spot " + AutoPuzzleUtil.fmt(spot) + " refused to start");
            }
            return;
        }
        if (!AutoPuzzleUtil.isAotv(player.getMainHandItem())) {
            AutoPuzzleUtil.swapTo(client, player, AutoPuzzleUtil::isAotv);
            say("melee: the Aspect of the Void back in hand for the teleport");
            return; // the swap is heard this tick, the use goes next
        }
        // From where he really is: the landing must still put him in reach.
        Vec3 land = dashLanding(client, player, player.position(), plan.yaw(), plan.pitch());
        if (land == null || !reachesFromFeet(client, land, lantern)) {
            LOGGER.info("[AutoPuzzles] Beams (melee): the AOTV teleport for {} would land at {} from here - out of reach, "
                    + "dropping it", AutoPuzzleUtil.fmt(lantern), land == null ? "nowhere" : fmt(land));
            dropPlan(lantern, plan);
            meleeBack = null;
            return;
        }
        CAMERA.engage(player);
        if (!AutoPuzzleUtil.useItemRotated(client, player, plan.yaw(), plan.pitch())) {
            held("aotvgate" + lantern, "AOTV teleport toward " + AutoPuzzleUtil.fmt(lantern)
                    + " held (action gate, or the look goes out first)");
            return; // gate held, or the look must be heard first: next tick
        }
        com.killer560.hub.util.ActionGate.expectSelfTeleport(com.killer560.hub.util.ActionGate.Actor.PUZZLE_WORLD);
        dashTick = now;
        dashRefused = false;
        dashFeet = player.position();
        dashPlan = new AotvPlan(plan.from(), plan.yaw(), plan.pitch(), land);
        dashLantern = lantern;
        meleeDashes++;
        lastSaid = null;
        LOGGER.info("[AutoPuzzles] Beams (melee): AOTV teleport toward {} from {} (yaw {} pitch {}), landing planned at {},"
                        + " holding {}, {} plan, teleport {} (tick {})",
                AutoPuzzleUtil.fmt(lantern), fmt(player.position()), String.format(java.util.Locale.ROOT, "%.1f", plan.yaw()),
                String.format(java.util.Locale.ROOT, "%.1f", plan.pitch()), fmt(land),
                player.getMainHandItem().getHoverName().getString(),
                plan == here ? (fromAir ? "from-here (back)" : "from-here") : "listed", meleeDashes,
                com.killer560.hub.util.SessionRecorderSink.tick());
    }

    /**
     * A shot at {@code lantern}, as Shoot mode shoots: the shortbow in hand, its look in the use packet, paced by the Shoot
     * cooldown - from where he is when the line is clear and does not pass the creeper, else from the nearest spot with such
     * a line ({@link #shootSpots}), warped to with the sneak kept and the bow back in hand, as Shoot mode's reposition does.
     */
    private static void shootStep(Minecraft client, LocalPlayer player, LanternPair pair, BlockPos lantern, BlockPos creeper,
                                  BlockPos standing, boolean reposition, long now) {
        Vec3 eye = player.getEyePosition();
        float[] dir = clearShot(client, player, eye, lantern, creeper);
        if (dir == null) {
            if (!reposition) {
                say("melee: no clear shot at " + AutoPuzzleUtil.fmt(lantern) + " from here and Etherwarp Reposition is off");
                return;
            }
            List<BlockPos> spots = new java.util.ArrayList<>(shootSpots(client, player, lantern, creeper));
            spots.removeAll(MELEE_FAILED.getOrDefault(lantern, java.util.Set.of()));
            BlockPos spot = null;
            for (BlockPos s : spots) {
                if (!AutoPuzzleUtil.at(player, s) && AutoPuzzleUtil.etherwarpAim(client.level, player, s) != null) {
                    spot = s;
                    break;
                }
            }
            if (spot == null && !spots.isEmpty()) {
                spot = AutoPuzzleUtil.hopToward(client, player, spots);
            }
            if (spot == null) {
                LOGGER.info("[AutoPuzzles] Beams (melee): no spot with a clear shot at {} can be warped to - leaving it",
                        AutoPuzzleUtil.fmt(lantern));
                SHOOT_SPOTS.put(lantern, List.of());
                return;
            }
            say("melee: warping to " + AutoPuzzleUtil.fmt(spot) + " to shoot " + AutoPuzzleUtil.fmt(lantern));
            if (REPOSITION.start(client, spot, true, false, false)) {
                meleeWarps++;
                lastSaid = null;
                LOGGER.info("[AutoPuzzles] Beams (melee): etherwarp {} onto {} to shoot {} (tick {})", meleeWarps,
                        AutoPuzzleUtil.fmt(spot), AutoPuzzleUtil.fmt(lantern), com.killer560.hub.util.SessionRecorderSink.tick());
            } else {
                LOGGER.info("[AutoPuzzles] Beams (melee): etherwarp onto shoot spot {} refused to start - spot marked failed",
                        AutoPuzzleUtil.fmt(spot));
                MELEE_FAILED.computeIfAbsent(lantern, k -> new java.util.HashSet<>()).add(spot);
            }
            return;
        }
        AutoPuzzleUtil.BowState bow = AutoPuzzleUtil.holdShortbow(client, player);
        if (bow == AutoPuzzleUtil.BowState.NONE) {
            say("melee: no shortbow (\"" + AutoPuzzleUtil.SHORTBOW_LORE + "\" in its lore) in the hotbar to shoot "
                    + AutoPuzzleUtil.fmt(lantern));
            return;
        }
        long ms = System.currentTimeMillis();
        if (bow != AutoPuzzleUtil.BowState.HELD || ms - lastShotTime < AutoPuzzlesConfig.getInstance().getShootCooldownMs()) {
            LOGGER.debug("[AutoPuzzles] Beams (melee): no shot this tick - bow {}, cooldown {} of {} ms", bow, ms - lastShotTime,
                    AutoPuzzlesConfig.getInstance().getShootCooldownMs());
            return;
        }
        CAMERA.engage(player);
        if (!AutoPuzzleUtil.useItemRotated(client, player, dir[0], dir[1])) {
            held("meleeshotgate" + lantern, "melee-mode shot at " + AutoPuzzleUtil.fmt(lantern) + " held by the action gate");
            return;
        }
        lastShotTime = ms;
        meleeClickTick = now;
        meleePair = pair;
        meleeStage = pair.stage;
        meleeFrom = standing;
        meleeWasShot = true;
        meleeFromPlan = null;
        meleeShots++;
        lastSaid = null;
        LOGGER.info("[AutoPuzzles] Beams (melee): shot {} (pair stage {}) from {} yaw {} pitch {}, holding {} - nothing in"
                        + " reach of it on foot or by AOTV (shot {}, tick {})",
                AutoPuzzleUtil.fmt(lantern), pair.stage, fmt(eye), String.format(java.util.Locale.ROOT, "%.1f", dir[0]),
                String.format(java.util.Locale.ROOT, "%.1f", dir[1]), player.getMainHandItem().getHoverName().getString(),
                meleeShots, com.killer560.hub.util.SessionRecorderSink.tick());
    }

    /** {@link #shotAim} from {@code eye}, unless the line passes the creeper (the lantern under its feet excepted). */
    private static float[] clearShot(Minecraft client, LocalPlayer player, Vec3 eye, BlockPos lantern, BlockPos creeper) {
        if (!lantern.equals(creeper) && isPathBlocked(eye, Vec3.atCenterOf(lantern), creeper)) {
            return null;
        }
        return shotAim(client, player, eye, lantern);
    }

    /** How {@code lantern} is reached now - see {@link Way}. Plans are worked out once per lantern and kept. */
    private static Way wayFor(Minecraft client, LocalPlayer player, BlockPos lantern, BlockPos creeper) {
        java.util.Set<BlockPos> failed = MELEE_FAILED.getOrDefault(lantern, java.util.Set.of());
        for (BlockPos s : meleeSpots(client, lantern)) {
            if (!failed.contains(s)) {
                return Way.MELEE;
            }
        }
        if (!aotvPlans(client, player, lantern).isEmpty()) {
            return Way.AOTV;
        }
        if (clearShot(client, player, player.getEyePosition(), lantern, creeper) != null) {
            return Way.SHOOT;
        }
        for (BlockPos s : shootSpots(client, player, lantern, creeper)) {
            if (!failed.contains(s)) {
                return Way.SHOOT;
            }
        }
        return Way.NONE;
    }

    private static int cost(Way w) {
        return switch (w) {
            case MELEE -> 0;
            case AOTV -> 1;
            case SHOOT -> 3;
            case NONE -> 1000;
        };
    }

    private static void dropPlan(BlockPos lantern, AotvPlan plan) {
        List<AotvPlan> plans = AOTV_PLANS.get(lantern);
        if (plans == null) {
            return;
        }
        List<AotvPlan> rest = new java.util.ArrayList<>(plans);
        // The spot it was used from goes with it: its other looks were planned from the same block.
        rest.removeIf(p -> java.util.Objects.equals(p.from(), plan.from()));
        AOTV_PLANS.put(lantern, List.copyOf(rest));
        LOGGER.info("[AutoPuzzles] Beams (melee): dropped the AOTV teleport from {} for {} - {} left",
                plan.from() == null ? "where he stood" : AutoPuzzleUtil.fmt(plan.from()), AutoPuzzleUtil.fmt(lantern), rest.size());
    }

    private static String fmt(Vec3 v) {
        return String.format(java.util.Locale.ROOT, "%.2f, %.2f, %.2f", v.x, v.y, v.z);
    }

    /** Instant Transmission's reach with the Aspect of the Void in his hotbar: 8, plus one per Transmission Tuner. */
    private static double transmissionRange(LocalPlayer player) {
        for (int i = 0; i < 9; i++) {
            net.minecraft.world.item.ItemStack st = player.getInventory().getItem(i);
            if (AutoPuzzleUtil.isAotv(st)) {
                return com.killer560.hub.roomsim.SimAbilities.instantTransmissionRange(st);
            }
        }
        return 8.0;
    }

    /** Where an Instant Transmission used at {@code yaw}/{@code pitch} from feet {@code feet} lands his feet - the measured
     *  model the sim and Auto Clear share ({@code SimAbilities.dashTarget}) - or null when it goes nowhere. */
    private static Vec3 dashLanding(Minecraft client, LocalPlayer player, Vec3 feet, float yaw, float pitch) {
        float y = yaw * net.minecraft.util.Mth.DEG_TO_RAD;
        float x = net.minecraft.util.Mth.clamp(pitch, -90f, 90f) * net.minecraft.util.Mth.DEG_TO_RAD;
        float cp = net.minecraft.util.Mth.cos(x);
        Vec3 look = new Vec3(-net.minecraft.util.Mth.sin(y) * cp, -net.minecraft.util.Mth.sin(x), net.minecraft.util.Mth.cos(y) * cp);
        Vec3 land = com.killer560.hub.roomsim.SimAbilities.dashTarget(client.level, player, feet, look, transmissionRange(player));
        return land == null || land.distanceToSqr(feet) < 1.0 ? null : land;
    }

    /** From feet at {@code land}, standing: the lantern's box within the planning reach and a point of it in sight. */
    private static boolean reachesFromFeet(Minecraft client, Vec3 land, BlockPos lantern) {
        Vec3 eye = land.add(0.0, AutoPuzzleUtil.EYE_STANDING, 0.0);
        return com.killer560.hub.util.BlockHits.boxDistanceSq(eye, lantern) <= MELEE_PLAN_REACH * MELEE_PLAN_REACH
                && visiblePoint(client, eye, lantern) != null;
    }

    /**
     * A teleport from feet {@code feet} that lands in reach of {@code lantern}, or null. The looks tried put his feet on a
     * line toward points under the lantern (so his EYE ends beside it) and straight up; the landing is the shared model's.
     */
    private static AotvPlan planFrom(Minecraft client, LocalPlayer player, BlockPos lantern, Vec3 feet, BlockPos from) {
        Vec3 c = Vec3.atCenterOf(lantern);
        Vec3 flat = new Vec3(feet.x - c.x, 0.0, feet.z - c.z);
        Vec3 toward = flat.lengthSqr() < 1.0e-6 ? Vec3.ZERO : flat.normalize();
        Vec3[] targets = {
                c.add(0.0, -AutoPuzzleUtil.EYE_STANDING, 0.0),
                c.add(0.0, -AutoPuzzleUtil.EYE_STANDING - 1.0, 0.0),
                c.add(toward.scale(1.5)).add(0.0, -AutoPuzzleUtil.EYE_STANDING, 0.0),
                c.add(toward.scale(2.5)).add(0.0, -AutoPuzzleUtil.EYE_STANDING - 1.0, 0.0),
        };
        for (Vec3 t : targets) {
            float[] d = AutoPuzzleUtil.direction(feet, t);
            if (REFUSED_LOOKS.contains(lookKey(feet, d[0], d[1]))) {
                LOGGER.info("[AutoPuzzles] Beams (melee): skipping the look {} / {} from {} - Hypixel refused it before",
                        String.format(java.util.Locale.ROOT, "%.1f", d[0]), String.format(java.util.Locale.ROOT, "%.1f", d[1]),
                        fmt(feet));
                continue;
            }
            Vec3 land = dashLanding(client, player, feet, d[0], d[1]);
            if (land != null && reachesFromFeet(client, land, lantern)) {
                return new AotvPlan(from, d[0], d[1], land);
            }
        }
        Vec3 up = dashLanding(client, player, feet, player.getYRot(), -90f);
        if (up != null && reachesFromFeet(client, up, lantern)) {
            return new AotvPlan(from, player.getYRot(), -90f, up);
        }
        return null;
    }

    /**
     * Every planned teleport into reach of {@code lantern}: from each standable spot in the room near enough for a teleport
     * (nearest the lantern first, at most forty tried), the first look of {@link #planFrom} that works. Worked out once.
     */
    private static List<AotvPlan> aotvPlans(Minecraft client, LocalPlayer player, BlockPos lantern) {
        List<AotvPlan> cached = AOTV_PLANS.get(lantern);
        if (cached != null) {
            return cached;
        }
        int reachOut = (int) Math.ceil(transmissionRange(player)) + 3;
        List<BlockPos> stands = standables(client, lantern, reachOut, lantern.getY() - reachOut, lantern.getY() + 1);
        List<AotvPlan> out = new java.util.ArrayList<>();
        int tried = 0;
        for (BlockPos s : stands) {
            if (++tried > 40 || out.size() >= 6) {
                break;
            }
            Vec3 feet = new Vec3(s.getX() + 0.5, s.getY() + standTop(client, s), s.getZ() + 0.5);
            AotvPlan p = planFrom(client, player, lantern, feet, s);
            if (p != null) {
                out.add(p);
            }
        }
        LOGGER.info("[AutoPuzzles] Beams (melee): {} - {} AOTV teleport(s) into reach ({} spot(s) tried)",
                AutoPuzzleUtil.fmt(lantern), out.size(), Math.min(tried, stands.size()));
        List<AotvPlan> result = List.copyOf(out);
        AOTV_PLANS.put(lantern, result);
        return result;
    }

    /**
     * Spots with a clear shot at {@code lantern} (the sneaking eye, as a bow reposition leaves him, and not past the
     * creeper), nearest the lantern first, at most eight, of the room's standable blocks within twenty. Worked out once.
     */
    private static List<BlockPos> shootSpots(Minecraft client, LocalPlayer player, BlockPos lantern, BlockPos creeper) {
        List<BlockPos> cached = SHOOT_SPOTS.get(lantern);
        if (cached != null) {
            return cached;
        }
        List<BlockPos> stands = standables(client, lantern, 20, lantern.getY() - 16, lantern.getY() + 2);
        List<BlockPos> out = new java.util.ArrayList<>();
        int tried = 0;
        for (BlockPos s : stands) {
            if (++tried > 200 || out.size() >= 8) {
                break;
            }
            Vec3 eye = new Vec3(s.getX() + 0.5, s.getY() + standTop(client, s) + AutoPuzzleUtil.EYE_SNEAKING, s.getZ() + 0.5);
            if (clearShot(client, player, eye, lantern, creeper) != null) {
                out.add(s);
            }
        }
        LOGGER.info("[AutoPuzzles] Beams (melee): {} - {} spot(s) with a clear shot", AutoPuzzleUtil.fmt(lantern), out.size());
        List<BlockPos> result = List.copyOf(out);
        SHOOT_SPOTS.put(lantern, result);
        return result;
    }

    /** Standable, landable blocks in the live map's room within {@code horiz} of {@code lantern} and between the two
     *  heights, nearest the lantern first (the same test as {@link #meleeSpots}). */
    private static List<BlockPos> standables(Minecraft client, BlockPos lantern, int horiz, int minY, int maxY) {
        int idx = LiveMapFeature.currentRoomIndex();
        int[] bounds = idx < 0 ? null : LiveMapFeature.roomWorldBounds(idx);
        List<BlockPos> out = new java.util.ArrayList<>();
        for (int x = lantern.getX() - horiz; x <= lantern.getX() + horiz; x++) {
            for (int z = lantern.getZ() - horiz; z <= lantern.getZ() + horiz; z++) {
                if (bounds != null && (x < bounds[0] || x > bounds[2] || z < bounds[1] || z > bounds[3])) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (pos.equals(lantern) || AutoPuzzleUtil.isPassable(client.level.getBlockState(pos))
                            || !client.level.getBlockState(pos.above()).isAir()
                            || !client.level.getBlockState(pos.above(2)).isAir()) {
                        continue;
                    }
                    if (com.killer560.hub.livemap.autoclear.EtherwarpPathfinder.isEtherwarpable(pos) || stepBlock(client, pos)) {
                        out.add(pos);
                    }
                }
            }
        }
        Vec3 c = Vec3.atCenterOf(lantern);
        out.sort(java.util.Comparator.comparingDouble((BlockPos b) -> Vec3.atCenterOf(b).distanceToSqr(c)));
        return out;
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
     * The lit pair to play next: of the pairs whose BOTH lanterns can be reached some way ({@link #wayFor}) and none set
     * aside, the cheapest - both by etherwarp spots first, then teleports, shots last - and of those the one with a lantern
     * nearest him, that lantern first. Both, before the first hit: a pair left after its first end is hit leaves that end
     * held, and the next pair's first hit then reads as its partner - a wrong beam.
     *
     * <p>The first version only ever took pairs reachable by etherwarp spots alone, and his first in-game run (2026-10-18,
     * Mod Only Test) lit only four pairs: two were played, then none was left, the room went to Shoot mode's platform
     * reposition, which had no etherwarp line from where he stood, and it waited there until he moved 13 s later.
     */
    private static LanternPair pickMeleePair(Minecraft client, LocalPlayer player, List<BlockPos[]> pairs, BlockPos creeper) {
        Vec3 at = player.position();
        for (int pass = 0; pass < 2; pass++) {
            LanternPair best = null;
            int bestCost = Integer.MAX_VALUE;
            double bestD = Double.MAX_VALUE;
            for (BlockPos[] p : pairs) {
                // Both still SEA LANTERNS: a pair just joined turns to prismarine a tick or more before the solver's list
                // drops it, and picking it again in between hit a finished lantern and read the prismarine as an answer.
                if (MELEE_SKIP.contains(p[0]) || MELEE_SKIP.contains(p[1]) || MELEE_DONE.contains(p[0])
                        || MELEE_DONE.contains(p[1])
                        || !client.level.getBlockState(p[0]).is(net.minecraft.world.level.block.Blocks.SEA_LANTERN)
                        || !client.level.getBlockState(p[1]).is(net.minecraft.world.level.block.Blocks.SEA_LANTERN)) {
                    continue;
                }
                int c;
                if (pass == 0) {
                    // Cheap first: the etherwarp spots alone, which never need the teleport or shot planning.
                    if (meleeSpots(client, p[0]).isEmpty() || meleeSpots(client, p[1]).isEmpty()) {
                        continue;
                    }
                    c = 0;
                } else {
                    c = cost(wayFor(client, player, p[0], creeper)) + cost(wayFor(client, player, p[1], creeper));
                    if (c >= 1000) {
                        continue;
                    }
                }
                double d0 = Vec3.atCenterOf(p[0]).distanceToSqr(at);
                double d1 = Vec3.atCenterOf(p[1]).distanceToSqr(at);
                double d = Math.min(d0, d1);
                if (c < bestCost || (c == bestCost && d < bestD)) {
                    bestCost = c;
                    bestD = d;
                    best = d0 <= d1 ? new LanternPair(p[0], p[1]) : new LanternPair(p[1], p[0]);
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
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
     * <p>A lantern set into a wall or under a ledge shows one or two faces, and a ray to its CENTER from most spots passes
     * through a neighbor first; measured in the sim's room, that alone hid every spot for seven of the twenty-two lanterns.
     * Hence a point on any face, not the center. Even so, about half the lanterns there (hung under the ceiling, or set in a
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

    /** Where his feet rest on {@code pos}: half a block up on a bottom slab, a whole one on anything else (a stair's center
     *  is on its upper step's edge, which holds him at the top). */
    private static double standTop(Minecraft client, BlockPos pos) {
        return isBottomSlab(client.level.getBlockState(pos)) ? 0.5 : 1.0;
    }

    /** Points tried on a lantern for a line of sight: the center, then on every face its center and four quarter points,
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
     * optional: the sim's first full run aimed at a lantern's center past a neighbor's corner, the exact ray passing by a
     * ten-thousandth of a block, and the turned body's float look met the neighbor every tick, so the hit never went.
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
        LOGGER.info("[AutoPuzzles] Beams: {} (tick {})", what, com.killer560.hub.util.SessionRecorderSink.tick());
    }

    /** When {@link #held} last logged each key: a repeat of the same key logs at most once a second, even when two
     *  keys take turns in one tick. Cleared on reset, and whenever it grows past a few hundred keys. */
    private static final java.util.Map<String, Long> HELD_AT = new java.util.HashMap<>();

    /** For refusals that can repeat every tick beside a {@link #say} line (kept apart so the two never alternate). */
    private static void held(String key, String detail) {
        long now = com.killer560.hub.util.SessionRecorderSink.tick();
        Long last = HELD_AT.get(key);
        if (last != null && now - last < 20) {
            return;
        }
        if (HELD_AT.size() > 256) {
            HELD_AT.clear();
        }
        HELD_AT.put(key, now);
        LOGGER.info("[AutoPuzzles] Beams: held: {} (tick {})", detail, now);
    }

    /** Tick this room visit began with the auto on; -1 outside. */
    private static long visitStartTick = -1L;

    private static long sinceVisit() {
        return visitStartTick < 0 ? -1 : com.killer560.hub.util.SessionRecorderSink.tick() - visitStartTick;
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
        HELD_AT.clear();
        visitStartTick = -1L;
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
        MELEE_BACKS.clear();
        REFUSED_LOOKS.clear();
        dashRefused = false;
        dashFeet = null;
        meleeBack = null;
        meleeFallback = false;
        AOTV_PLANS.clear();
        SHOOT_SPOTS.clear();
        dashTick = -1L;
        dashPlan = null;
        dashLantern = null;
        airPlan = null;
        meleeWasShot = false;
        meleeFromPlan = null;
        meleeDashes = 0;
        meleeShots = 0;
        meleeHits = 0;
        meleeWarps = 0;
    }
}
