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
    private static final Map<Long, Attempt> attempts = new HashMap<>();
    /** Lever POWERED state when first observed - a change means it was flipped (by us or a teammate). */
    private static final Map<Long, Boolean> leverInitialState = new HashMap<>();
    private static long lastClickMs = 0;
    private static Long lastClickKey = null;
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
            if (McCompat.screen(client) != null && lastClickKey != null && now - lastClickMs < 2000) {
                markDone(lastClickKey, "screen opened after click");
                lastClickKey = null;
            }
            updateDone(client, now);
        }
        if (client.level != null && client.player != null && cfg.isSecretAuraEnabled()) {
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
            return;
        }
        boolean inBoss = LiveMapFeature.isInBoss();
        RoomEntry room = inBoss ? null : LiveMapFeature.currentRoomEntry();
        String roomName = room == null ? null : room.name;
        if (gate == null && inBoss && !cfg.isAuraBossLevers()) {
            gate = "in boss (boss levers off)";
        }
        if (gate == null && roomName != null && SKIP_ROOMS.contains(roomName)) {
            gate = "skipped room " + roomName;
        }
        // QUOI: nothing in Water Board while the board is being solved (its reward chest spawns on the solve).
        boolean puzzleOpen = roomName != null && CHESTS_AFTER_SOLVE_ROOMS.contains(roomName) && !puzzleDone(roomName);
        if (gate == null && puzzleOpen && "Water Board".equals(roomName)) {
            gate = "Water Board mid-solve";
        }
        if (gate != null) {
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

        BlockPos bestPos = null;
        String bestKind = null;
        double bestDist = Double.MAX_VALUE;
        Set<Long> seenNow = new HashSet<>();
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            long key = pos.asLong();
            if (done.contains(key)) {
                continue;
            }
            BlockState state = client.level.getBlockState(pos);
            String kind = classify(client, cfg, pos, state, inBoss, leversAllowed, chestsAllowed);
            if (kind == null) {
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
                    continue;
                }
            } else if (distSq > rangeSq) {
                continue;
            }
            seenNow.add(key);
            long first = firstSeenMs.computeIfAbsent(key, k -> now);
            if (now - first < cfg.getAuraCooldownMs()) {
                continue;
            }
            Attempt a = attempts.get(key);
            if (a != null && now - a.lastMs() < RETRY_AFTER_MS) {
                continue;
            }
            if (distSq < bestDist) {
                bestDist = distSq;
                bestPos = pos.immutable();
                bestKind = kind;
            }
        }
        firstSeenMs.keySet().retainAll(seenNow);

        if (bestPos == null || now - lastClickMs < cfg.getAuraCooldownMs()) {
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
        if (com.killer560.hub.livemap.autoclear.ClearExecutor.isBusy()
                || com.killer560.hub.livemap.autoclear.BloodRush.isRunning()) {
            return;
        }
        // Nor while Auto Routes' Kill Mimic is at work: it aims the camera straight down, and the aura's own turn (and
        // its hand-back) moved that aim - in legit mode the Hyperion then went off at pitch 50.9 and teleported him at
        // the mimic (2026-10-11, testkit 96-ar-644/651).
        if (com.killer560.hub.autoroutes.MimicKiller.isBusy()) {
            return;
        }
        // Shared one-interaction-per-tick gate. Must sit above the attempt/cooldown bookkeeping below so a refused
        // tick costs nothing - the same (nearest) secret is simply re-picked next tick.
        if (!com.killer560.hub.util.ActionGate.tryAct(com.killer560.hub.util.ActionGate.Actor.SECRET_AURA)) {
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
            return; // no exposed face from here: never click a face against another block
        }
        if (aimHit != null && !TURN.readyBlock(client.player, client.level, bestPos, aimHit.getLocation(),
                Math.sqrt(bestDist) + 2.0)) {
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
            return; // no outline to strike; skipping beats sending an impossible hit
        }
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
        if ("redstoneNode".equals(bestKind)) {
            keyFound = false; // one key, one redstone block
        }
        if (cfg.isAuraSwing()) {
            client.player.swing(InteractionHand.MAIN_HAND);
        }
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
            return leversAllowed ? "lever" : null;
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
    private static void watchKeys(Minecraft client) {
        Vec3 eye = client.player.getEyePosition();
        double r2 = KEY_WATCH_RANGE * KEY_WATCH_RANGE;
        var it = keySkullsSeen.iterator();
        while (it.hasNext()) {
            BlockPos p = BlockPos.of(it.next());
            if (Vec3.atCenterOf(p).distanceToSqr(eye) > r2) {
                it.remove();
            } else if (client.level.isLoaded(p) && !SecretsFeature.isRedstoneKey(client.level, p)) {
                it.remove();
                keyFound = true; // the skull went: the key was picked up
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
                    if (Vec3.atCenterOf(p).distanceToSqr(eye) <= r2 && isLooseKey(client, p)) {
                        keySkullsSeen.add(p.asLong());
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

    /** Shared with {@code secrettrigger.SecretTriggerbotFeature}: whether this secret is already looted/flipped. */
    public static boolean isDone(BlockPos pos) {
        return done.contains(pos.asLong());
    }

    /** Shared with {@code secrettrigger.SecretTriggerbotFeature}: record a secret it confirmed looted. */
    public static void markDone(BlockPos pos, String why) {
        markDone(pos.asLong(), why);
    }

    private static void markDone(long key, String why) {
        done.add(key);
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
        done.clear();
        firstSeenMs.clear();
        attempts.clear();
        leverInitialState.clear();
        lastClickKey = null;
        keySkullsSeen.clear();
        keyFound = false;
    }
}
