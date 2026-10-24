package com.killer560.hub.cheatutils;

import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.secrets.SecretsFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import com.killer560.hub.compat.McCompat;

/**
 * Secret Aura - interacts (no rotation) with secret chests, levers and Wither Essence skulls in reach, ported
 * from QUOI {@code dungeon/secrets/impl/SecretAura.kt} (itself from NoobRoutes):
 * <ul>
 * <li>Candidates: CHEST / TRAPPED_CHEST, LEVER, and skulls that {@link SecretsFeature#isWitherEssence} or
 * {@link SecretsFeature#isRedstoneKey} confirms by skin profile id; and, once a key skull in reach has gone (picked up),
 * the room's REDSTONE_BLOCK once, to put the key on it (QUOI; Place Redstone Key).
 * <li>Nearest candidate within range: chests and levers 4.5 eye to block BOX (QUOI 6.2 to the centre, which the
 * server refused past 4.5); essence skulls QUOI's 4.7 eye to block CENTRE (3.8-4.2 to the box), and never past
 * the 4.5 box cap either. Every click also waits out ActionGate's 20-tick world-change settle (QUOI AuraManager).
 * <li>QUOI's "click delay": a block must have been in range for the cooldown before its first click; a global
 * cooldown also spaces consecutive clicks. A retry is allowed once after 1s if the click didn't take (chest
 * never opened / lever didn't flip / skull still there), then the block is done for the run.
 * <li>Done detection (QUOI): chest open-ness &gt; 0, lever POWERED changed (incl. flipped by a teammate), essence
 * skull removed. Everything resets on world change / leaving the dungeon / a live map reset (a new run, which
 * includes every sim rebuild - the same room rebuilt puts its secrets back on the same blocks).
 * <li>QUOI room skips via LiveMap's current room: Three Weirdos (wrong chest fails the puzzle) skipped
 * entirely; no levers in Water Board / Tic Tac Toe; nothing in Water Board and no chest in Lower / Higher Blaze
 * until that puzzle reads solved or failed on the tab list, or its room is cleared on the map (QUOI's
 * mid-solve checks, 2026-10-12). QUOI's conditional Ice Path / Ice Fill / Teleport Maze
 * checks need room-relative coordinates, so those rooms are skipped entirely instead (conservative).
 * <li>Boss: nothing, unless "In Boss" is on - then ONLY QUOI's hardcoded F7 P3 levers (with a
 * "Not Activated" stand above). Never the six S2 Lights levers, in boss or not, on an F7/M7 run: they are the Lights
 * Device's own feature since 2026-10-15 ({@code lightsdevice.LightsDeviceFeature}, killer560: "it should not be impacted
 * by other auras"), so Secret Aura neither clicks them nor competes for them, whatever either switch says.
 * <li>Never while a screen is open (QUOI "In container" default off), while sneaking (optional), or while
 * holding a configured item.
 * <li>While an Auto Routes route runs, only on a node waiting for secrets ({@code RouteExecutor.secretAuraMayClick},
 * killer560 2026-10-16); with no route running it works as above.
 * <li>Never while Auto Clear is killing with no route running ({@code AutoClearFeature.holdsSecretClicks}, killer560
 * 2026-10-20): a secret it took then was one the room's route later waited for.
 * </ul>
 * Click is the {@code SimonSaysFeature#sendNoRotateInteract} precedent: {@code gameMode.useItemOn} with a
 * synthetic {@link BlockHitResult}.
 */
public final class SecretAuraFeature {

    private static final long RETRY_AFTER_MS = 1000;
    private static final int MAX_ATTEMPTS = 2;

    // QUOI SecretAura.kt - F7 P3 levers. QUOI's device levers (the S2 Lights device) are the Lights Device's.
    private static final Set<BlockPos> BOSS_LEVERS = Set.of(
            new BlockPos(94, 124, 113), new BlockPos(106, 124, 113), new BlockPos(27, 124, 127),
            new BlockPos(23, 132, 138), new BlockPos(14, 122, 55), new BlockPos(2, 122, 55),
            new BlockPos(86, 128, 46), new BlockPos(84, 121, 34));

    private static final Set<String> SKIP_ROOMS = Set.of("Three Weirdos", "Teleport Maze", "Ice Path", "Ice Fill");
    private static final Set<String> NO_LEVER_ROOMS = Set.of("Water Board", "Tic Tac Toe");
    /** Puzzle rooms whose chest is the puzzle's reward: no chest is clicked there until the puzzle is over. */
    private static final Set<String> CHESTS_AFTER_SOLVE_ROOMS = Set.of("Water Board", "Lower Blaze", "Higher Blaze");

    /**
     * The current room's puzzle is over: solved or failed on the tab list (Score Calculator's reading), or the room
     * cleared on the dungeon map. Anything else - open, not listed yet, Score Calculator off - counts as mid-solve.
     */
    private static boolean puzzleDone(String roomName) {
        char tab = com.killer560.hub.scorecalc.ScoreCalculatorFeature.puzzleState(roomName);
        if (tab == '✔' || tab == '✖') {
            return true;
        }
        int idx = LiveMapFeature.currentRoomIndex();
        return idx >= 0 && LiveMapFeature.isRoomCleared(idx);
    }

    private record Attempt(int count, long lastMs, Boolean leverPoweredAtClick) {
    }

    private static final Set<Long> done = new HashSet<>();
    private static final Map<Long, Long> firstSeenMs = new HashMap<>();
    /** The scan's "seen this tick" set, reused (client thread only). */
    private static final Set<Long> SEEN_NOW = new HashSet<>();
    private static final Map<Long, Attempt> attempts = new HashMap<>();
    /** Lever POWERED state when first observed - a change means it was flipped (by us or a teammate). */
    private static final Map<Long, Boolean> leverInitialState = new HashMap<>();
    private static long lastClickMs = 0;
    private static Long lastClickKey = null;
    /** {@code util/ChestWindows.count} as the last chest click went out: a chest window since then is its answer, shown
     *  or not (Auto Close Chest cancels the screen, so "a screen opened" never fires with it on). */
    private static int lastClickWindows = 0;
    /** Redstone Key skulls seen in reach this run; one that stops being a key skull has been picked up (by anyone). */
    private static final Set<Long> keySkullsSeen = new HashSet<>();
    /**
     * A Redstone Key has been picked up and not yet placed. Since Hypixel 0.18.4 the key is no longer an item: once
     * found, any player can put it on the room's redstone block (hypixelskyblock.minecraft.wiki, Redstone Key).
     */
    private static boolean keyFound = false;
    private static Object lastLevel = null;
    private static int lastGeneration = 0;
    private static boolean wasActive = false;

    private SecretAuraFeature() {
    }

    // ---- logging only (no behaviour reads these) ----
    private static final org.slf4j.Logger LOGGER = CheatUtils.LOGGER;
    /** The gate that held the whole aura last tick ("" = running), logged on change only. */
    private static String lastGateLogged = null;
    /** Per candidate block, the last reason code logged for it (logged again only when it changes). */
    private static final Map<Long, String> candReasonLogged = new HashMap<>();
    /** The last chosen target logged (logged again only when it changes). */
    private static long lastChosenLogged = Long.MIN_VALUE;
    private static String lastHeldKey;
    private static long lastHeldTick;
    private static String lastRoomLogged;
    private static boolean lastBossLogged;

    private static long tickNow() {
        return com.killer560.hub.util.SessionRecorderSink.tick();
    }

    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.2f", d);
    }

    private static String xyz(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    /** One line when the gate holding the whole aura changes (never per tick). */
    private static void logGate(String gate, String detail) {
        String g = gate == null ? "" : gate;
        if (g.equals(lastGateLogged)) {
            return;
        }
        lastGateLogged = g;
        if (gate == null) {
            LOGGER.info("[SecretAura] active: {} tick={}", detail, tickNow());
        } else {
            LOGGER.info("[SecretAura] held: gate={} {} tick={}", gate, detail == null ? "" : detail, tickNow());
        }
    }

    /** A chosen target held back before its click; once per key per 20 ticks (BloodCampFeature.logRefusal model). */
    private static void logHeld(String key, String detail) {
        long now = tickNow();
        if (key.equals(lastHeldKey) && now - lastHeldTick < 20) {
            return;
        }
        lastHeldKey = key;
        lastHeldTick = now;
        LOGGER.info("[SecretAura] click held: {} tick={}", detail, now);
    }

    /** A candidate's state, logged when its reason code changes. */
    private static void logCand(long key, String code, String kind, BlockPos pos, double distSq, String extra) {
        if (code.equals(candReasonLogged.get(key))) {
            return;
        }
        candReasonLogged.put(key, code);
        LOGGER.info("[SecretAura] candidate {}: kind={} pos={} distToBox={} {} tick={}", code, kind, xyz(pos),
                fmt(Math.sqrt(distSq)), extra, tickNow());
    }

    private static String blockLabel(Block block) {
        if (block == Blocks.CHEST) {
            return "chest";
        }
        if (block == Blocks.TRAPPED_CHEST) {
            return "trappedChest";
        }
        if (block == Blocks.LEVER) {
            return "lever";
        }
        return block instanceof AbstractSkullBlock ? "skull" : "block";
    }

    /** Why classify() answered null for a chest, lever or skull (pure; for the log only). */
    private static String nullReason(Block block, boolean inBoss, boolean leversAllowed, boolean chestsAllowed) {
        if (inBoss) {
            return block == Blocks.LEVER ? "boss lever not clickable (not a P3 lever, owned by Lever Aura, lights lever, or no Not Activated stand)"
                    : "in boss: only P3 levers";
        }
        if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) {
            return chestsAllowed ? "chest refused" : "chests not allowed (option off or puzzle still open)";
        }
        if (block == Blocks.LEVER) {
            return leversAllowed ? "lights device or dummy lever" : "levers not allowed (option off or no-lever room)";
        }
        return "skull is not essence or a loose Redstone Key (or that option is off)";
    }

    /** The body turn for a secret the reported look does not reach (see util/TurnFirst); the camera never moves. */
    private static final com.killer560.hub.util.TurnFirst TURN = new com.killer560.hub.util.TurnFirst();

    static void tick(Minecraft client) {
        // First, always: a body turned for a click is held for it and given back the tick after, whatever gates below.
        TURN.tick(client.player);
        CheatUtilsConfig cfg = CheatUtilsConfig.getInstance();
        // A NEW RUN, not only a new world. The done-set is keyed by block position, and a rebuilt room puts its
        // secrets back on exactly the same blocks. killer560 (2026-10-06, Map Logger, sim): "I would regenerate
        // the same room I had a route in and the secret aura wasn't working really after the first run through" -
        // every rebuild of Museum happens inside the one sim world, so a reset keyed on the level alone never
        // fired and every chest and lever of run 1 still read "done". The live map's reset generation is bumped
        // on everything that starts a run - a world change, entering a dungeon, a sim floor being published - so
        // it is the run boundary the done-set belongs to, on Hypixel and in the sim alike.
        int generation = LiveMapFeature.resetGeneration();
        if (client.level != lastLevel) {
            lastLevel = client.level;
            lastGeneration = generation;
            reset("world change");
        } else if (generation != lastGeneration) {
            lastGeneration = generation;
            reset("map reset (new run)");
        }
        boolean inDungeon = DungeonState.isInDungeon();
        if (wasActive && !inDungeon) {
            reset("left dungeon");
        }
        wasActive = inDungeon;
        long now = System.currentTimeMillis();
        if (client.level != null && (!done.isEmpty() || !attempts.isEmpty() || !leverInitialState.isEmpty())) {
            // Runs even while a screen is open: a chest's GUI opening right after our click means it worked
            // (its lid open-ness can already be back to 0 by the time the GUI is closed again).
            if (lastClickKey != null && now - lastClickMs < 2000) {
                if (McCompat.screen(client) != null) {
                    markDone(lastClickKey, "screen opened after click");
                    lastClickKey = null;
                } else if (com.killer560.hub.util.ChestWindows.since(lastClickWindows)
                        || windowOpenedAtMs >= lastClickMs) {
                    markDone(lastClickKey, "chest window arrived after click");
                    lastClickKey = null;
                }
            }
            updateDone(client, now);
        }
        // In a dungeon only (fps-auto-1015): it walked every block entity within 10 blocks each tick in the hub and on
        // islands too, and whatever it found there is wiped by the reset that entering a dungeon brings (a new world
        // or a map reset generation) before anything reads it.
        if (inDungeon && client.level != null && client.player != null && cfg.isSecretAuraEnabled()) {
            watchKeys(client);
        }

        String gate = null;
        if (!cfg.isSecretAuraEnabled()) {
            gate = "disabled";
        } else if (client.level == null || client.player == null || client.gameMode == null) {
            gate = "no-player";
        } else if (!CheatUtils.isOnDungeonServer(client) && !inSim(client)) {
            // Or the dungeon sim. killer560 (2026-10-04): "My secret auras are not working on sim." The sim is
            // a singleplayer world, so getCurrentServer() is null and this gate refused every tick there.
            gate = "not on hypixel/p3sim/sim";
        } else if (!inDungeon) {
            gate = "not in dungeon";
        } else if (com.killer560.hub.util.ActionGate.containerScreenOpen(client)) {
            gate = "a container screen is open";
        } else if (cfg.isAuraPauseWhileSneaking() && client.player.isShiftKeyDown()) {
            gate = "sneaking";
        } else if (heldItemPaused(cfg, client.player.getMainHandItem())) {
            gate = "holding paused item";
        }
        if (gate != null) {
            // Review fix (2026-09-15): skip the room/boss lookups entirely while gated (incl. disabled).
            if (!gate.equals(lastGateLogged)) {
                logGate(gate, "holding paused item".equals(gate) && client.player != null
                        ? "held=" + CheatUtils.plainName(client.player.getMainHandItem()) : "");
            }
            return;
        }
        boolean inBoss = LiveMapFeature.isInBoss();
        RoomEntry room = inBoss ? null : LiveMapFeature.currentRoomEntry();
        String roomName = room == null ? null : room.name;
        if (!java.util.Objects.equals(roomName, lastRoomLogged) || inBoss != lastBossLogged) {
            LOGGER.info("[SecretAura] room: {} -> {} inBoss={} tick={}", lastRoomLogged, roomName, inBoss, tickNow());
            lastRoomLogged = roomName;
            lastBossLogged = inBoss;
        }
        if (gate == null && inBoss && !cfg.isAuraBossLevers()) {
            gate = "in boss (boss levers off)";
        }
        if (gate == null && roomName != null && SKIP_ROOMS.contains(roomName)) {
            gate = "skipped room"; // only ever tested for null: no per-tick concatenation
        }
        // QUOI: nothing in Water Board while the board is being solved (its reward chest spawns on the solve).
        boolean puzzleOpen = roomName != null && CHESTS_AFTER_SOLVE_ROOMS.contains(roomName) && !puzzleDone(roomName);
        if (gate == null && puzzleOpen && "Water Board".equals(roomName)) {
            gate = "Water Board mid-solve";
        }
        if (gate != null) {
            if (!gate.equals(lastGateLogged)) {
                logGate(gate, "room=" + roomName + " inBoss=" + inBoss + " puzzleOpen=" + puzzleOpen);
            }
            return;
        }

        boolean leversAllowed = cfg.isAuraLevers() && (roomName == null || !NO_LEVER_ROOMS.contains(roomName));
        // QUOI: the blaze rooms' chest waits for the puzzle too.
        boolean chestsAllowed = cfg.isAuraChests() && !puzzleOpen;
        Vec3 eye = client.player.getEyePosition();
        double range = cfg.getAuraRange();
        double rangeSq = range * range;
        double skullRangeSq = cfg.getAuraSkullRange() * cfg.getAuraSkullRange();
        double capSq = CheatUtilsConfig.MEASURED_MAX_REACH * CheatUtilsConfig.MEASURED_MAX_REACH;
        double maxRange = Math.max(range, cfg.getAuraSkullRange());
        BlockPos min = BlockPos.containing(eye.x - maxRange, eye.y - maxRange, eye.z - maxRange);
        BlockPos max = BlockPos.containing(eye.x + maxRange, eye.y + maxRange, eye.z + maxRange);
        if (!"".equals(lastGateLogged)) {
            logGate(null, "room=" + roomName + " inBoss=" + inBoss + " levers=" + leversAllowed + " chests=" + chestsAllowed
                    + " essence=" + cfg.isAuraEssence() + " key=" + cfg.isAuraRedstoneKey() + " range=" + fmt(range)
                    + " skullRange=" + fmt(cfg.getAuraSkullRange()) + " cap=" + fmt(CheatUtilsConfig.MEASURED_MAX_REACH)
                    + " cooldownMs=" + cfg.getAuraCooldownMs() + " keyFound=" + keyFound);
        }

        BlockPos bestPos = null;
        String bestKind = null;
        double bestDist = Double.MAX_VALUE;
        Set<Long> seenNow = SEEN_NOW;
        seenNow.clear();
        // Ask each chunk section under the cube whether it can hold anything classify() answers for, the way Secret
        // Waypoints' lever scan does: a section that cannot is skipped block by block, in the same order as before, so
        // the pick (and its tie-break) is unchanged while ~1,300 block reads a tick become a few palette checks.
        int sx0 = min.getX() >> 4;
        int sy0 = min.getY() >> 4;
        int sz0 = min.getZ() >> 4;
        int nsx = (max.getX() >> 4) - sx0 + 1;
        int nsy = (max.getY() >> 4) - sy0 + 1;
        int nsz = (max.getZ() >> 4) - sz0 + 1;
        boolean[] sectionMay = sectionsThatMayHold(client.level, sx0, sy0, sz0, nsx, nsy, nsz);
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (!sectionMay[(((pos.getX() >> 4) - sx0) * nsy + ((pos.getY() >> 4) - sy0)) * nsz + ((pos.getZ() >> 4) - sz0)]) {
                continue;
            }
            // The block first: done.contains boxes the key (a Long per block of the cube, ~33 KB a tick in a room
            // whose sections hold a decorative skull), and for a block classify() never answers for both orders
            // end in the same `continue` with nothing changed.
            BlockState state = client.level.getBlockState(pos);
            if (!mayClassify(state)) {
                continue;
            }
            long key = pos.asLong();
            if (done.contains(key)) {
                continue;
            }
            String kind = classify(client, cfg, pos, state, inBoss, leversAllowed, chestsAllowed);
            if (kind == null) {
                if (state.getBlock() != Blocks.REDSTONE_BLOCK) {
                    double d2 = com.killer560.hub.util.BlockHits.boxDistanceSq(eye, pos);
                    if (d2 <= capSq) { // only ones near enough to matter
                        logCand(key, "skipped", blockLabel(state.getBlock()), pos, d2,
                                nullReason(state.getBlock(), inBoss, leversAllowed, chestsAllowed));
                    }
                }
                continue;
            }
            if (state.getBlock() == Blocks.LEVER && !inBoss) {
                leverInitialState.putIfAbsent(key, state.getValue(LeverBlock.POWERED));
            }
            // To the block's nearest point, not its centre - the measure the server itself uses. See
            // BlockHits.boxDistanceSq: comparing centre distance against a range capped at vanilla's 4.5
            // made the effective reach nearer 4.0 and dropped clicks the server would have taken.
            double distSq = com.killer560.hub.util.BlockHits.boxDistanceSq(eye, pos);
            if ("essence".equals(kind) || "redstoneKey".equals(kind) || "redstoneNode".equals(kind)) {
                // A skull goes by its CENTRE, QUOI's 4.7 (see CheatUtilsConfig.MAX_AURA_SKULL_RANGE), and still never
                // past the 4.5-to-the-box cap every click has.
                if (Vec3.atCenterOf(pos).distanceToSqr(eye) > skullRangeSq || distSq > capSq) {
                    if (distSq <= capSq * 2.0) {
                        logCand(key, "outOfRange", kind, pos, distSq, "skull center limit=" + fmt(cfg.getAuraSkullRange())
                                + " boxCap=" + fmt(CheatUtilsConfig.MEASURED_MAX_REACH));
                    }
                    continue;
                }
            } else if (distSq > rangeSq) {
                if (distSq <= capSq * 2.0) {
                    logCand(key, "outOfRange", kind, pos, distSq, "range=" + fmt(range));
                }
                continue;
            }
            seenNow.add(key);
            long first = firstSeenMs.computeIfAbsent(key, k -> now);
            if (now - first < cfg.getAuraCooldownMs()) {
                logCand(key, "inRangeWaitingClickDelay", kind, pos, distSq, "inRangeMs=" + (now - first)
                        + " needMs=" + cfg.getAuraCooldownMs());
                continue;
            }
            Attempt a = attempts.get(key);
            if (a != null && now - a.lastMs() < RETRY_AFTER_MS) {
                logCand(key, "retryWait" + a.count(), kind, pos, distSq, "attempts=" + a.count() + " sinceClickMs="
                        + (now - a.lastMs()) + " retryAfterMs=" + RETRY_AFTER_MS);
                continue;
            }
            logCand(key, a == null ? "eligible" : "eligibleRetry" + a.count(), kind, pos, distSq,
                    a == null ? "attempts=0" : "attempts=" + a.count());
            if (distSq < bestDist) {
                bestDist = distSq;
                bestPos = pos.immutable();
                bestKind = kind;
            }
        }
        firstSeenMs.keySet().retainAll(seenNow);

        if (bestPos == null) {
            if (lastChosenLogged != Long.MIN_VALUE) {
                lastChosenLogged = Long.MIN_VALUE;
                LOGGER.info("[SecretAura] no target: nothing eligible in reach tick={}", tickNow());
            }
            return;
        }
        if (bestPos.asLong() != lastChosenLogged) {
            lastChosenLogged = bestPos.asLong();
            Attempt pa = attempts.get(lastChosenLogged);
            LOGGER.info("[SecretAura] chosen: kind={} pos={} distToBox={} attempts={} room={} tick={}", bestKind,
                    xyz(bestPos), fmt(Math.sqrt(bestDist)), pa == null ? 0 : pa.count(), roomName, tickNow());
        }
        if (now - lastClickMs < cfg.getAuraCooldownMs()) {
            logHeld("cooldown", "global click cooldown: sinceLastClickMs=" + (now - lastClickMs) + " cooldownMs="
                    + cfg.getAuraCooldownMs() + " target=" + bestKind + "@" + xyz(bestPos));
            return;
        }
        // STANDS DOWN WHILE THE INTERACTIVE MAP IS TRAVELLING. killer560 (2026-09-27): "For the aura make those
        // turn off while using the interactive map... if the etherwarp is pathinding to a start node it cannot
        // hit a random node halfway through my secret route and start doing the secret route midway through it
        // should only start from the start node."
        //
        // Auto Routes already refuses to START anywhere but a start node after a map teleport (its
        // mapArrivalGuard), but this aura is not Auto Routes - it grabs whatever secret comes into range, so
        // etherwarping ACROSS a room would have it collecting on the way past and leaving the route half done
        // from the middle. isBusy() covers the whole trip: searching, queued, executing, and the completion sync.
        // Placed here rather than at the top of the tick so the bookkeeping above still runs and nothing is
        // mis-remembered as attempted while it is held back.
        // A trip held at its start (Auto Secret's last-node wait) has not moved him: he is still in the room whose
        // last secret is being waited for, and standing down here is what made that wait never end (2026-10-15).
        if (com.killer560.hub.livemap.autoclear.ClearExecutor.isBusy()
                && !com.killer560.hub.livemap.autoclear.ClearExecutor.heldAtStart()
                || com.killer560.hub.livemap.autoclear.BloodRush.isRunning()) {
            logHeld("mapTravel", "interactive map trip or Blood Rush running (clearBusy="
                    + com.killer560.hub.livemap.autoclear.ClearExecutor.isBusy() + " bloodRush="
                    + com.killer560.hub.livemap.autoclear.BloodRush.isRunning() + ") target=" + bestKind + "@" + xyz(bestPos));
            return;
        }
        // Nor while Auto Routes' Kill Mimic is at work: it aims the camera straight down, and the aura's own turn (and
        // its hand-back) moved that aim - in legit mode the Hyperion then went off at pitch 50.9 and teleported him at
        // the mimic (2026-10-11, testkit 96-ar-644/651).
        if (com.killer560.hub.autoroutes.MimicKiller.isBusy()) {
            logHeld("mimic", "Kill Mimic is busy target=" + bestKind + "@" + xyz(bestPos));
            return;
        }
        // Nor while Auto Routes runs, except on a node waiting for secrets (killer560, 2026-10-16: "If it is not on an
        // await node it should never be able to grab a chest or skull or flick a lever"). Every click of this aura goes
        // through here - chests (the mimic's trapped chest too), levers, essence and key skulls, the key's placing - and
        // nothing is turned or booked as attempted before it, so a held-back secret is simply picked again on the await
        // node. Item pickups are walking, not this aura.
        if (!com.killer560.hub.autoroutes.RouteExecutor.secretAuraMayClick()) {
            logHeld("route", "Auto Routes running and not on a secret-await node target=" + bestKind + "@" + xyz(bestPos));
            return;
        }
        // Nor while Auto Clear kills (killer560, 2026-10-20): a secret it took then is one the route through that room
        // later found gone and waited for. Secret clicks are for a route (the gate above) or Auto Secret's routes.
        if (com.killer560.hub.autoclear.AutoClearFeature.holdsSecretClicks()) {
            logHeld("autoClear", "Auto Clear is killing mobs (no route running) target=" + bestKind + "@" + xyz(bestPos));
            return;
        }
        // A Shadow Assassin leaping at him (util/ShadowAssassinLeap): the client's world border leaves the secret outside
        // it, so vanilla would refuse the click before sending it - and booked as an attempt, two refusals a second apart
        // used to give the chest up for the run while an Auto Routes await waited for it forever (killer560, 2026-10-20).
        // Held instead, with nothing booked: it goes on the first tick the border lifts. Past HOLD_MAX_MS the border is no
        // leap's, and the click goes as it always did (refused, and counted against MAX_ATTEMPTS).
        if (holdForBorder(client, bestPos, bestKind, now)) {
            return;
        }
        // Shared one-interaction-per-tick gate. Must sit above the attempt/cooldown bookkeeping below so a refused
        // tick costs nothing - the same (nearest) secret is simply re-picked next tick.
        if (!com.killer560.hub.util.ActionGate.tryAct(com.killer560.hub.util.ActionGate.Actor.SECRET_AURA)) {
            logHeld("actionGate", "ActionGate refused SECRET_AURA (another actor this tick or world-change settle) target="
                    + bestKind + "@" + xyz(bestPos));
            return;
        }
        // A secret the look the server was told about does not reach - one behind him - is clicked with the BODY
        // turned to it: turned this tick, clicked next tick once the movement packet has reported it, given back the
        // tick after. Clicked as it was, a lever behind him drew GrimAC RotationPlace (testkit 419, 2026-10-07).
        // The Redstone Key's node is struck only on a face open to AIR (killer560, 2026-10-12); every other secret on the
        // face the eye-to-centre ray meets.
        boolean node = "redstoneNode".equals(bestKind);
        BlockHitResult aimHit = node
                ? com.killer560.hub.util.BlockHits.exposedSurface(client.level, bestPos, client.player.getEyePosition())
                : com.killer560.hub.util.BlockHits.surface(client.level, bestPos, client.player.getEyePosition());
        if (node && aimHit == null) {
            logHeld("noFace", "Redstone block has no face open to air from here pos=" + xyz(bestPos));
            return; // no exposed face from here: never click a face against another block
        }
        if (aimHit != null && !TURN.readyBlock(client.player, client.level, bestPos, aimHit.getLocation(),
                Math.sqrt(bestDist) + 2.0)) {
            logHeld("turn", "body turning first (reported look does not reach) target=" + bestKind + "@" + xyz(bestPos)
                    + " yaw=" + fmt(client.player.getYRot()) + " pitch=" + fmt(client.player.getXRot()));
            return;
        }
        long key = bestPos.asLong();
        Attempt prev = attempts.get(key);
        int count = prev == null ? 1 : prev.count() + 1;
        BlockState state = client.level.getBlockState(bestPos);
        Boolean powered = state.getBlock() == Blocks.LEVER ? state.getValue(LeverBlock.POWERED) : null;
        attempts.put(key, new Attempt(count, now, powered));
        lastClickMs = now;
        lastClickKey = "chest".equals(bestKind) ? key : null;
        lastClickWindows = com.killer560.hub.util.ChestWindows.count();

        // A SURFACE hit, not the block's centre with a hardcoded east face.
        //
        // Measured 2026-09-28 against a live GrimAC: clicking a lever at 4.50 blocks - inside vanilla's own
        // limit, and accepted by the server - drew a PositionPlace violation every single time, because the hit
        // was a point INSIDE the block on a face the player was not looking at. No raycast can produce that.
        // Breaker Aura, which has always clipped its hit from the eye through the shape, measured clean over
        // hundreds of interactions in the same harness. See BlockHits.
        BlockHitResult hit = node
                ? com.killer560.hub.util.BlockHits.exposedSurface(client.level, bestPos, client.player.getEyePosition())
                : com.killer560.hub.util.BlockHits.surface(client.level, bestPos, client.player.getEyePosition());
        if (hit == null) {
            LOGGER.info("[SecretAura] click skipped: no outline to strike kind={} pos={} attempt={} tick={}", bestKind,
                    xyz(bestPos), count, tickNow());
            return; // no outline to strike; skipping beats sending an impossible hit
        }
        if ("essence".equals(bestKind)) {
            // killer560, 2026-10-19: "whenever it has to click on a wither essence it swaps to my dungeon breaker" - the held
            // Aspect of the Void would teleport him toward the skull. With no breaker in the hotbar the click stays as it was.
            int breakerSlot = com.killer560.hub.autoroutes.ItemIdentity.findHotbarSlotById(client.player,
                    com.killer560.hub.autoroutes.ItemIdentity.BREAKER_ID);
            if (breakerSlot >= 0) {
                com.killer560.hub.util.HotbarSelect.select(client, client.player, breakerSlot);
            }
        }
        net.minecraft.world.InteractionResult result = client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
        LOGGER.info("[SecretAura] click sent: kind={} pos={} face={} hit={},{},{} distToBox={} attempt={}/{} lever={} held={} swing={} tick={}",
                bestKind, xyz(bestPos), hit.getDirection(), fmt(hit.getLocation().x), fmt(hit.getLocation().y),
                fmt(hit.getLocation().z), fmt(Math.sqrt(bestDist)), count, MAX_ATTEMPTS, powered,
                CheatUtils.plainName(client.player.getMainHandItem()), cfg.isAuraSwing(), tickNow());
        if (result == net.minecraft.world.InteractionResult.FAIL) {
            LOGGER.info("[SecretAura] Click on the {} at {} refused client-side (attempt {}/{}{})", bestKind,
                    bestPos.toShortString(), count, MAX_ATTEMPTS, com.killer560.hub.util.ShadowAssassinLeap.refuses(client,
                            bestPos) ? ", outside the world border - " + com.killer560.hub.util.ShadowAssassinLeap.describe(client)
                            : "");
        }
        if ("redstoneNode".equals(bestKind)) {
            keyFound = false; // one key, one redstone block
            LOGGER.info("[SecretAura] Redstone Key placing click sent on {} (keyFound cleared) tick={}", xyz(bestPos), tickNow());
        }
        if (cfg.isAuraSwing()) {
            client.player.swing(InteractionHand.MAIN_HAND);
        }
    }

    /** Every block {@link #classify} can answer for. A superset is fine; missing one would hide a secret. */
    private static boolean mayClassify(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.LEVER || block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST
                || block instanceof AbstractSkullBlock || block == Blocks.REDSTONE_BLOCK;
    }

    /**
     * Per chunk section of the scan cube (x-major, then y, then z), whether it may hold a block {@link #classify}
     * answers for. False only when that is certain: the chunk is loaded and the section is missing (outside the
     * build height, read as void air), all air, or its palette has none of {@link #mayClassify}'s blocks. Anything
     * else (a chunk the client does not have) stays true and is read block by block as before.
     */
    private static boolean[] sectionsThatMayHold(net.minecraft.world.level.Level level, int sx0, int sy0, int sz0,
                                                 int nsx, int nsy, int nsz) {
        boolean[] may = new boolean[nsx * nsy * nsz];
        for (int ix = 0; ix < nsx; ix++) {
            for (int iz = 0; iz < nsz; iz++) {
                net.minecraft.world.level.chunk.ChunkAccess chunk = null;
                try {
                    chunk = level.getChunk(sx0 + ix, sz0 + iz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
                } catch (RuntimeException e) {
                    chunk = null;
                }
                for (int iy = 0; iy < nsy; iy++) {
                    int idx = (ix * nsy + iy) * nsz + iz;
                    if (!(chunk instanceof net.minecraft.world.level.chunk.LevelChunk lc)) {
                        may[idx] = true;
                        continue;
                    }
                    int si = lc.getSectionIndexFromSectionY(sy0 + iy);
                    var sections = lc.getSections();
                    if (si < 0 || si >= sections.length) {
                        may[idx] = false; // outside the build height: void air, which classify never answers for
                        continue;
                    }
                    var section = sections[si];
                    may[idx] = section != null && !section.hasOnlyAir() && section.maybeHas(SecretAuraFeature::mayClassify);
                }
            }
        }
        return may;
    }

    /** @return "chest"/"lever"/"essence"/"redstoneKey"/"redstoneNode"/"bossLever" when the aura may click it, else null. */
    private static String classify(Minecraft client, CheatUtilsConfig cfg, BlockPos pos, BlockState state,
                                   boolean inBoss, boolean leversAllowed, boolean chestsAllowed) {
        Block block = state.getBlock();
        // The Lights Device's levers, in boss or not (a missed boss latch would read them as room levers).
        if (block == Blocks.LEVER && DungeonState.isF7OrM7() && com.killer560.hub.lightsdevice.LightsDeviceFeature.isLightsLever(pos)) {
            return null;
        }
        if (inBoss) {
            return block == Blocks.LEVER && isBossLeverClickable(client, pos, state) ? "bossLever" : null;
        }
        if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) {
            return chestsAllowed ? "chest" : null;
        }
        if (block == Blocks.LEVER) {
            // A dummy lever (Red Blue's two in the cage nook, killer560 2026-10-17) is never a secret.
            return leversAllowed && !com.killer560.hub.secrets.DummyLevers.isDummy(pos) ? "lever" : null;
        }
        if (block instanceof AbstractSkullBlock) {
            // Hypixel's essence skull profile, in the sim too: the sim places the same skull (SimSecrets), so
            // there is one rule and no sim branch.
            if (cfg.isAuraEssence() && SecretsFeature.isWitherEssence(client.level, pos)) {
                return "essence";
            }
            // QUOI clicks the Redstone Key's skull too, at the same skull range (killer560, 2026-10-12).
            return cfg.isAuraRedstoneKey() && isLooseKey(client, pos) ? "redstoneKey" : null;
        }
        // The key's target: the room's redstone block, QUOI's rule (it clicks a REDSTONE_BLOCK once the key skull is
        // gone). Only with a key found and not yet placed, and never with a block in hand - that click would place it.
        if (block == Blocks.REDSTONE_BLOCK && keyFound && cfg.isAuraPlaceRedstoneKey()
                && !(client.player.getMainHandItem().getItem() instanceof net.minecraft.world.item.BlockItem)
                && com.killer560.hub.util.BlockHits.exposedSurface(client.level, pos, client.player.getEyePosition()) != null) {
            return "redstoneNode";
        }
        return null;
    }

    /** How far a Redstone Key skull is watched for being picked up, eye to skull centre (killer560, 2026-10-12). */
    static final double KEY_WATCH_RANGE = 10.0;

    /**
     * The key counts as found when a key skull within {@link #KEY_WATCH_RANGE} of the eye goes - picked up by him or by
     * anyone (RSA watches the same, a skull turning to air, within ~6.3). Skulls are block entities, so the chunks around
     * him are asked for theirs rather than every block in a 21-block cube. One that leaves the range while still there
     * is forgotten: its later pickup is not counted (848: 9 blocks counts, 11 does not).
     */
    /** {@code Vec3.atCenterOf(p).distanceToSqr(eye)} without the Vec3: the same subtractions and sum. */
    private static double centreDistSq(BlockPos p, Vec3 eye) {
        double dx = eye.x - (p.getX() + 0.5);
        double dy = eye.y - (p.getY() + 0.5);
        double dz = eye.z - (p.getZ() + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }

    private static void watchKeys(Minecraft client) {
        Vec3 eye = client.player.getEyePosition();
        double r2 = KEY_WATCH_RANGE * KEY_WATCH_RANGE;
        var it = keySkullsSeen.iterator();
        while (it.hasNext()) {
            BlockPos p = BlockPos.of(it.next());
            if (centreDistSq(p, eye) > r2) {
                it.remove();
                LOGGER.info("[SecretAura] Redstone Key skull left watch range (forgotten): pos={} dist={} tick={}", xyz(p),
                        fmt(Math.sqrt(centreDistSq(p, eye))), tickNow());
            } else if (client.level.isLoaded(p) && !SecretsFeature.isRedstoneKey(client.level, p)) {
                it.remove();
                keyFound = true; // the skull went: the key was picked up
                LOGGER.info("[SecretAura] Redstone Key found: skull at {} gone (picked up) dist={} tick={}", xyz(p),
                        fmt(Math.sqrt(centreDistSq(p, eye))), tickNow());
            }
        }
        int r = (int) Math.ceil(KEY_WATCH_RANGE);
        for (int cx = ((int) Math.floor(eye.x) - r) >> 4; cx <= ((int) Math.floor(eye.x) + r) >> 4; cx++) {
            for (int cz = ((int) Math.floor(eye.z) - r) >> 4; cz <= ((int) Math.floor(eye.z) + r) >> 4; cz++) {
                net.minecraft.world.level.chunk.ChunkAccess chunk;
                try {
                    chunk = client.level.getChunk(cx, cz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
                } catch (RuntimeException e) {
                    continue;
                }
                if (!(chunk instanceof net.minecraft.world.level.chunk.LevelChunk lc)) {
                    continue;
                }
                for (BlockPos p : lc.getBlockEntities().keySet()) {
                    if (centreDistSq(p, eye) <= r2 && isLooseKey(client, p)) {
                        if (keySkullsSeen.add(p.asLong())) {
                            LOGGER.info("[SecretAura] Redstone Key skull watched: pos={} dist={} tick={}", xyz(p),
                                    fmt(Math.sqrt(centreDistSq(p, eye))), tickNow());
                        }
                    }
                }
            }
        }
    }

    /**
     * A Redstone Key skull still to be picked up - not one already PLACED on its redstone block. Without this the aura
     * picked the key straight back up off the block it had just put it on (testkit 848's first run: two uses, no key
     * left on the block).
     */
    private static boolean isLooseKey(Minecraft client, BlockPos pos) {
        return SecretsFeature.isRedstoneKey(client.level, pos)
                && client.level.getBlockState(pos.below()).getBlock() != Blocks.REDSTONE_BLOCK;
    }

    /** QUOI {@code isBossBlock}: P3 lever with a "Not Activated" stand above (the Lights levers never reach here). */
    private static boolean isBossLeverClickable(Minecraft client, BlockPos pos, BlockState state) {
        // In Boss's own lever clicking owns S2's two section levers while its Section Levers option is on - don't
        // double-click them here: its whole point is clicking each of them exactly once.
        if (com.killer560.hub.leveraura.LeverAuraFeature.owns(pos)) {
            return false;
        }
        if (!BOSS_LEVERS.contains(pos)) {
            return false;
        }
        Vec3 above = Vec3.atCenterOf(pos.above());
        AABB box = new AABB(above, above).inflate(1.5);
        List<ArmorStand> stands = client.level.getEntitiesOfClass(ArmorStand.class, box,
                s -> s.getDisplayName() != null && "Not Activated".equals(s.getDisplayName().getString()));
        return !stands.isEmpty();
    }

    private static void updateDone(Minecraft client, long now) {
        for (Map.Entry<Long, Boolean> e : leverInitialState.entrySet()) {
            if (done.contains(e.getKey())) {
                continue;
            }
            BlockPos pos = BlockPos.of(e.getKey());
            BlockState st = client.level.getBlockState(pos);
            if (st.getBlock() != Blocks.LEVER || !st.getValue(LeverBlock.POWERED).equals(e.getValue())) {
                markDone(e.getKey(), "lever flipped");
            }
        }
        for (Map.Entry<Long, Attempt> e : attempts.entrySet()) {
            long key = e.getKey();
            if (done.contains(key)) {
                continue;
            }
            BlockPos pos = BlockPos.of(key);
            BlockState st = client.level.getBlockState(pos);
            BlockEntity be = client.level.getBlockEntity(pos);
            Attempt a = e.getValue();
            if (be instanceof ChestBlockEntity chest && chest.getOpenNess(0f) > 0f) {
                markDone(key, "chest opened");
            } else if (a.leverPoweredAtClick() != null && (st.getBlock() != Blocks.LEVER
                    || !st.getValue(LeverBlock.POWERED).equals(a.leverPoweredAtClick()))) {
                markDone(key, "lever flipped");
            } else if (a.leverPoweredAtClick() == null && !(st.getBlock() instanceof AbstractSkullBlock)
                    && st.getBlock() != Blocks.CHEST && st.getBlock() != Blocks.TRAPPED_CHEST) {
                markDone(key, "block gone");
            } else if (a.count() >= MAX_ATTEMPTS && now - a.lastMs() >= RETRY_AFTER_MS) {
                markDone(key, "gave up after " + a.count() + " attempts");
            }
        }
    }

    /**
     * The secret this aura is still taking from where he stands, or null: a chest, lever or skull it would click, in its
     * range, not yet seen taken (chest open, lever flipped, skull gone) and not given up on. {@code clickedOnly}: only one
     * it has already clicked - a click on its way to the server, not yet answered. For Auto Secret, which must not leave a
     * room while its last secret is in the aura's hands (killer560, 2026-10-15: "it will leave a room before a secret is
     * grabbed and registered"). The aura's persistent gates apply (switch, options, range, room skips, held-item pause);
     * the momentary ones (a container, sneaking, the map's warp held at the start) do not: they pass.
     */
    public static String takingInReach(Minecraft client, boolean clickedOnly) {
        return scanTaking(client, clickedOnly ? Taking.CLICKED : Taking.ANY);
    }

    /**
     * Whether a click of this aura's is coming in reach: a secret it has not clicked yet, or one whose re-try is
     * {@link #RETRY_SOON_MS} or less away. Auto Secret lets the held warp's sneak up only then - a click goes at the
     * START of a tick, before that tick's input packet, so it is the sneak already sent that it would be made with.
     */
    public static boolean clickDue(Minecraft client) {
        return scanTaking(client, Taking.DUE) != null;
    }

    private enum Taking { ANY, CLICKED, DUE }

    /** How long before a re-try {@link #clickDue} counts it as coming: two ticks for the sneak to be let up first. */
    private static final long RETRY_SOON_MS = 150;

    private static String scanTaking(Minecraft client, Taking mode) {
        CheatUtilsConfig cfg = CheatUtilsConfig.getInstance();
        if (!cfg.isSecretAuraEnabled() || client.level == null || client.player == null
                || !DungeonState.isInDungeon() || LiveMapFeature.isInBoss()
                || !CheatUtils.isOnDungeonServer(client) && !inSim(client)
                || heldItemPaused(cfg, client.player.getMainHandItem())
                || !com.killer560.hub.autoroutes.RouteExecutor.secretAuraMayClick()
                || com.killer560.hub.autoclear.AutoClearFeature.holdsSecretClicks()) {
            return null;   // the last two: a route's non-await node, or Auto Clear killing (tick's same gates)
        }
        RoomEntry room = LiveMapFeature.currentRoomEntry();
        String roomName = room == null ? null : room.name;
        boolean puzzleOpen = roomName != null && CHESTS_AFTER_SOLVE_ROOMS.contains(roomName) && !puzzleDone(roomName);
        if (roomName != null && SKIP_ROOMS.contains(roomName) || puzzleOpen && "Water Board".equals(roomName)) {
            return null;
        }
        boolean leversAllowed = cfg.isAuraLevers() && (roomName == null || !NO_LEVER_ROOMS.contains(roomName));
        boolean chestsAllowed = cfg.isAuraChests() && !puzzleOpen;
        Vec3 eye = client.player.getEyePosition();
        double rangeSq = cfg.getAuraRange() * cfg.getAuraRange();
        double skullRangeSq = cfg.getAuraSkullRange() * cfg.getAuraSkullRange();
        double capSq = CheatUtilsConfig.MEASURED_MAX_REACH * CheatUtilsConfig.MEASURED_MAX_REACH;
        double maxRange = Math.max(cfg.getAuraRange(), cfg.getAuraSkullRange());
        long now = System.currentTimeMillis();
        String best = null;
        double bestDist = Double.MAX_VALUE;
        BlockPos min = BlockPos.containing(eye.x - maxRange, eye.y - maxRange, eye.z - maxRange);
        BlockPos max = BlockPos.containing(eye.x + maxRange, eye.y + maxRange, eye.z + maxRange);
        // The tick's own section prefilter, and the block first: Auto Secret and a route's await ask this every tick, and
        // it used to box a Long per block of the cube for the done-set before reading what the block was.
        int sx0 = min.getX() >> 4;
        int sy0 = min.getY() >> 4;
        int sz0 = min.getZ() >> 4;
        int nsy = (max.getY() >> 4) - sy0 + 1;
        int nsz = (max.getZ() >> 4) - sz0 + 1;
        boolean[] sectionMay = sectionsThatMayHold(client.level, sx0, sy0, sz0, (max.getX() >> 4) - sx0 + 1, nsy, nsz);
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (!sectionMay[(((pos.getX() >> 4) - sx0) * nsy + ((pos.getY() >> 4) - sy0)) * nsz + ((pos.getZ() >> 4) - sz0)]) {
                continue;
            }
            BlockState state = client.level.getBlockState(pos);
            if (!mayClassify(state)) {
                continue;
            }
            long key = pos.asLong();
            if (done.contains(key)) {
                continue;
            }
            Attempt a = attempts.get(key);
            if (mode == Taking.CLICKED && a == null
                    || a != null && a.count() >= MAX_ATTEMPTS && now - a.lastMs() >= RETRY_AFTER_MS) {
                continue;   // not clicked yet, or given up on (updateDone marks it so on its next tick)
            }
            if (mode == Taking.DUE && a != null
                    && (a.count() >= MAX_ATTEMPTS || now - a.lastMs() < RETRY_AFTER_MS - RETRY_SOON_MS)) {
                continue;   // clicked, and no re-try coming soon
            }
            String kind = classify(client, cfg, pos, state, false, leversAllowed, chestsAllowed);
            if (kind == null) {
                continue;
            }
            if (client.level.getBlockEntity(pos) instanceof ChestBlockEntity chest && chest.getOpenNess(1f) > 0f
                    || a != null && lastClickKey != null && lastClickKey == key && now - lastClickMs < 2000
                    && (com.killer560.hub.util.ActionGate.containerScreenOpen(client) || windowOpenedAtMs >= lastClickMs)) {
                continue;   // opened (its lid, or its window after our click): taken, the done-set has it next tick
            }
            if (a != null && a.leverPoweredAtClick() != null && state.getBlock() == Blocks.LEVER
                    && !state.getValue(LeverBlock.POWERED).equals(a.leverPoweredAtClick())) {
                continue;   // flipped
            }
            double distSq = com.killer560.hub.util.BlockHits.boxDistanceSq(eye, pos);
            // The redstone node too, once a key is found (killer560, 2026-10-16: the placing is the room's key secret,
            // so Auto Secret must not leave with the key found and not yet put in); the tick's own skull measure.
            if ("essence".equals(kind) || "redstoneKey".equals(kind) || "redstoneNode".equals(kind)) {
                if (Vec3.atCenterOf(pos).distanceToSqr(eye) > skullRangeSq || distSq > capSq) {
                    continue;
                }
            } else if (distSq > rangeSq) {
                continue;
            }
            if (distSq < bestDist) {
                bestDist = distSq;
                best = kind + " at " + pos.toShortString() + (a == null ? " (not clicked yet)"
                        : " (clicked " + a.count() + " time(s), " + (now - a.lastMs()) + " ms ago)");
            }
        }
        return best;
    }

    /** When the server last opened a container window for him, shown or not (ms). Written on the network thread. */
    private static volatile long windowOpenedAtMs = Long.MIN_VALUE;

    /**
     * The server opened a container window that Auto Close Chest closed before it was shown: the chest our click was
     * for opened. Without it a chest clicked with Auto Close Chest on was never seen taken (no screen, and the lid can
     * be shut again before a tick reads it) and Auto Secret waited out its whole limit (147, 2026-10-15). Any thread.
     */
    public static void noteWindowOpened() {
        windowOpenedAtMs = System.currentTimeMillis();
        LOGGER.debug("[SecretAura] container window opened (shown or not) sinceLastClickMs={}", windowOpenedAtMs - lastClickMs);
    }

    /**
     * A Redstone Key has gone (picked up, by him or anyone) within {@link #KEY_WATCH_RANGE} and this aura has not put it
     * in yet. Auto Routes' await counts his click on the redstone block as the key's secret at once while this holds.
     */
    public static boolean keyFoundNotPlaced() {
        return keyFound;
    }

    /** He put the found key in himself (Auto Routes' await saw his click): nothing is left for this aura to place. */
    public static void noteKeyPlaced() {
        if (keyFound) {
            LOGGER.info("[SecretAura] Redstone Key placed by the player (Auto Routes await saw it): nothing left to place tick={}",
                    tickNow());
        }
        keyFound = false;
    }

    /** Shared with {@code secrettrigger.SecretTriggerbotFeature}: whether this secret is already looted/flipped. */
    public static boolean isDone(BlockPos pos) {
        return done.contains(pos.asLong());
    }

    /** Shared with {@code secrettrigger.SecretTriggerbotFeature}: record a secret it confirmed looted. */
    public static void markDone(BlockPos pos, String why) {
        markDone(pos.asLong(), why);
    }

    private static void markDone(long key, String why) {
        if (done.add(key)) {
            Attempt a = attempts.get(key);
            if (a == null) {
                LOGGER.info("[SecretAura] done: pos={} why={} (never clicked by the aura) tick={}", xyz(BlockPos.of(key)), why,
                        tickNow());
            } else {
                LOGGER.info("[SecretAura] done: pos={} why={} attempts={} sinceLastClickMs={} tick={}", xyz(BlockPos.of(key)),
                        why, a.count(), System.currentTimeMillis() - a.lastMs(), tickNow());
            }
            if (why != null && why.startsWith("gave up")) {
                // Given up with the secret not taken: Auto Routes' await stops waiting on a click that is not coming.
                gaveUpCount++;
                lastGaveUp = BlockPos.of(key).toShortString();
                LOGGER.info("[SecretAura] {} at {} - left for the run", why, lastGaveUp);
            }
        }
    }

    // ------------------------------------------------------------------------------------------- Shadow Assassin leap

    /** Secrets given up this session (attempts spent, never taken) - read by Auto Routes' await as "no click is coming". */
    private static int gaveUpCount;
    private static String lastGaveUp;
    /** The block held for the world border to lift and since when (ms), or null. */
    private static Long borderHeldKey;
    private static long borderHeldSinceMs;
    private static boolean borderHoldExpiredSaid;

    /** How many secrets this aura gave up without taking (only ever goes up). */
    public static int gaveUpCount() {
        return gaveUpCount;
    }

    /** The last secret given up, "x, y, z", or null. */
    public static String lastGaveUp() {
        return lastGaveUp;
    }

    /**
     * Whether the click on {@code pos} is held this tick: the client's world border refuses it (a Shadow Assassin's leap,
     * util/ShadowAssassinLeap) and the hold is younger than {@code HOLD_MAX_MS}. Ends the tick the border lifts.
     */
    private static boolean holdForBorder(Minecraft client, BlockPos pos, String kind, long now) {
        long key = pos.asLong();
        if (!com.killer560.hub.util.ShadowAssassinLeap.refuses(client, pos)) {
            if (borderHeldKey != null) {
                LOGGER.info("[SecretAura] The world border lifted after {} ms - clicking the {} at {} now",
                        now - borderHeldSinceMs, kind, pos.toShortString());
                borderHeldKey = null;
            }
            return false;
        }
        if (borderHeldKey == null || borderHeldKey != key) {
            borderHeldKey = key;
            borderHeldSinceMs = now;
            borderHoldExpiredSaid = false;
            LOGGER.info("[SecretAura] The {} at {} is outside the world border ({}) - a click would be refused; held "
                    + "until the border lifts", kind, pos.toShortString(), com.killer560.hub.util.ShadowAssassinLeap.describe(client));
        }
        if (now - borderHeldSinceMs < com.killer560.hub.util.ShadowAssassinLeap.HOLD_MAX_MS) {
            return true;
        }
        if (!borderHoldExpiredSaid) {
            borderHoldExpiredSaid = true;
            LOGGER.info("[SecretAura] The border has refused the {} at {} for {} ms - clicking as usual", kind,
                    pos.toShortString(), now - borderHeldSinceMs);
        }
        return false;
    }

    /** The dungeon sim: a local singleplayer world, never a remote server (see {@code SimState.canAct}). */
    private static boolean inSim(Minecraft client) {
        return com.killer560.hub.roomsim.SimState.isActive() && com.killer560.hub.roomsim.SimState.canAct(client);
    }

    /**
     * Why Secret Aura would NOT open a chest {@code minReach} blocks from his eye right now, or null when it would -
     * for Auto Boulder, which only leaves the chest to the aura when the aura will actually take it (killer560,
     * 2026-10-06: "if I have chest aura off as well then it needs to go and press the buttons"). The same gates as
     * {@link #tick} and {@code classify} for a chest: the switch, the Chests option, the range, the held-item pause.
     * Sneaking is left out - it is momentary, and the run itself never sneaks.
     */
    public static String chestRefusal(Minecraft client, double minReach) {
        CheatUtilsConfig cfg = CheatUtilsConfig.getInstance();
        if (!cfg.isSecretAuraEnabled()) {
            return "Secret Aura is off";
        }
        if (!cfg.isAuraChests()) {
            return "Secret Aura's Chests option is off";
        }
        if (cfg.getAuraRange() < minReach) {
            return String.format(Locale.ROOT, "Secret Aura's range %.1f is short of the %.1f the chest needs",
                    cfg.getAuraRange(), minReach);
        }
        if (client.player != null && heldItemPaused(cfg, client.player.getMainHandItem())) {
            return "Secret Aura is paused for the held item";
        }
        return null;
    }

    private static boolean heldItemPaused(CheatUtilsConfig cfg, ItemStack held) {
        String list = cfg.getAuraPauseHolding();
        if (list == null || list.isBlank() || held == null || held.isEmpty()) {
            return false;
        }
        String id = CheatUtils.skyblockId(held);
        String idLower = id == null ? "" : id.toLowerCase(Locale.ROOT);
        String name = CheatUtils.plainName(held).toLowerCase(Locale.ROOT);
        for (String token : list.split(",")) {
            String t = token.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty() && (idLower.equals(t) || idLower.contains(t) || name.contains(t))) {
                return true;
            }
        }
        return false;
    }

    private static void reset(String why) {
        LOGGER.info("[SecretAura] reset: why={} done={} attempts={} levers={} keySkulls={} keyFound={} tick={}", why,
                done.size(), attempts.size(), leverInitialState.size(), keySkullsSeen.size(), keyFound, tickNow());
        candReasonLogged.clear();
        lastChosenLogged = Long.MIN_VALUE;
        lastHeldKey = null;
        done.clear();
        firstSeenMs.clear();
        attempts.clear();
        leverInitialState.clear();
        lastClickKey = null;
        keySkullsSeen.clear();
        keyFound = false;
        borderHeldKey = null;
    }
}
