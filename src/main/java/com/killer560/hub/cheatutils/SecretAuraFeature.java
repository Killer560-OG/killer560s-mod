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

/**
 * Secret Aura - interacts (no rotation) with secret chests, levers and Wither Essence skulls in reach, ported
 * from QUOI {@code dungeon/secrets/impl/SecretAura.kt} (itself from NoobRoutes):
 * <ul>
 * <li>Candidates: CHEST / TRAPPED_CHEST, LEVER, and skulls that {@link SecretsFeature#isWitherEssence} confirms
 * (QUOI's redstone-key skull/block handling is intentionally NOT ported - task scope is chests/levers/essence).
 * <li>Nearest candidate within range (QUOI defaults 6.2 chest/lever, 4.7 skull; eye -> block-center distance).
 * <li>QUOI's "click delay": a block must have been in range for the cooldown before its first click; a global
 * cooldown also spaces consecutive clicks. A retry is allowed once after 1s if the click didn't take (chest
 * never opened / lever didn't flip / skull still there), then the block is done for the run.
 * <li>Done detection (QUOI): chest open-ness &gt; 0, lever POWERED changed (incl. flipped by a teammate), essence
 * skull removed. Everything resets on world change / leaving the dungeon.
 * <li>QUOI room skips via LiveMap's current room: Three Weirdos (wrong chest fails the puzzle) skipped
 * entirely; no levers in Water Board / Tic Tac Toe. QUOI's conditional Ice Path / Ice Fill / Teleport Maze
 * checks need room-relative coordinates, so those rooms are skipped entirely instead (conservative).
 * <li>Boss: nothing, unless "Boss Levers" is on - then ONLY QUOI's hardcoded F7 P3 levers (with a
 * "Not Activated" stand above) and unpowered device levers.
 * <li>Never while a screen is open (QUOI "In container" default off), while sneaking (optional), or while
 * holding a configured item.
 * </ul>
 * Click is the {@code SimonSaysFeature#sendNoRotateInteract} precedent: {@code gameMode.useItemOn} with a
 * synthetic {@link BlockHitResult}.
 */
public final class SecretAuraFeature {

    private static final long RETRY_AFTER_MS = 1000;
    private static final int MAX_ATTEMPTS = 2;

    // QUOI SecretAura.kt - F7 P3 levers and device levers.
    private static final Set<BlockPos> BOSS_LEVERS = Set.of(
            new BlockPos(94, 124, 113), new BlockPos(106, 124, 113), new BlockPos(27, 124, 127),
            new BlockPos(23, 132, 138), new BlockPos(14, 122, 55), new BlockPos(2, 122, 55),
            new BlockPos(86, 128, 46), new BlockPos(84, 121, 34));
    private static final Set<BlockPos> DEVICE_LEVERS = Set.of(
            new BlockPos(62, 136, 142), new BlockPos(62, 133, 142), new BlockPos(60, 134, 142),
            new BlockPos(60, 135, 142), new BlockPos(58, 133, 142), new BlockPos(58, 136, 142));

    private static final Set<String> SKIP_ROOMS = Set.of("Three Weirdos", "Teleport Maze", "Ice Path", "Ice Fill");
    private static final Set<String> NO_LEVER_ROOMS = Set.of("Water Board", "Tic Tac Toe");

    private record Attempt(int count, long lastMs, Boolean leverPoweredAtClick) {
    }

    private static final Set<Long> done = new HashSet<>();
    private static final Map<Long, Long> firstSeenMs = new HashMap<>();
    private static final Map<Long, Attempt> attempts = new HashMap<>();
    /** Lever POWERED state when first observed - a change means it was flipped (by us or a teammate). */
    private static final Map<Long, Boolean> leverInitialState = new HashMap<>();
    private static long lastClickMs = 0;
    private static Long lastClickKey = null;
    private static Object lastLevel = null;
    private static boolean wasActive = false;
    private static String lastGateLog = null;

    private SecretAuraFeature() {
    }

    static void tick(Minecraft client) {
        CheatUtilsConfig cfg = CheatUtilsConfig.getInstance();
        if (client.level != lastLevel) {
            lastLevel = client.level;
            reset("world change");
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
            if (client.screen != null && lastClickKey != null && now - lastClickMs < 2000) {
                markDone(lastClickKey, "screen opened after click");
                lastClickKey = null;
            }
            updateDone(client, now);
        }

        String gate = null;
        if (!cfg.isSecretAuraEnabled()) {
            gate = "disabled";
        } else if (client.level == null || client.player == null || client.gameMode == null) {
            gate = "no-player";
        } else if (!CheatUtils.isOnDungeonServer(client)) {
            gate = "not on hypixel/p3sim";
        } else if (!inDungeon) {
            gate = "not in dungeon";
        } else if (client.screen != null) {
            gate = "screen open";
        } else if (cfg.isAuraPauseWhileSneaking() && client.player.isShiftKeyDown()) {
            gate = "sneaking";
        } else if (heldItemPaused(cfg, client.player.getMainHandItem())) {
            gate = "holding paused item";
        }
        if (gate != null) {
            // Review fix (2026-09-15): skip the room/boss lookups entirely while gated (incl. disabled).
            logGate(gate);
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
        logGate(gate == null ? "active" + (inBoss ? " (boss levers)" : "") : gate);
        if (gate != null) {
            return;
        }

        boolean leversAllowed = cfg.isAuraLevers() && (roomName == null || !NO_LEVER_ROOMS.contains(roomName));
        Vec3 eye = client.player.getEyePosition();
        double range = cfg.getAuraRange();
        double rangeSq = range * range;
        double skullRangeSq = cfg.getAuraSkullRange() * cfg.getAuraSkullRange();
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
            String kind = classify(client, cfg, pos, state, inBoss, leversAllowed);
            if (kind == null) {
                continue;
            }
            if (state.getBlock() == Blocks.LEVER && !inBoss) {
                leverInitialState.putIfAbsent(key, state.getValue(LeverBlock.POWERED));
            }
            double distSq = eye.distanceToSqr(Vec3.atCenterOf(pos));
            if (distSq > ("essence".equals(kind) ? skullRangeSq : rangeSq)) {
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
        long key = bestPos.asLong();
        Attempt prev = attempts.get(key);
        int count = prev == null ? 1 : prev.count() + 1;
        BlockState state = client.level.getBlockState(bestPos);
        Boolean powered = state.getBlock() == Blocks.LEVER ? state.getValue(LeverBlock.POWERED) : null;
        attempts.put(key, new Attempt(count, now, powered));
        lastClickMs = now;
        lastClickKey = "chest".equals(bestKind) ? key : null;

        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(bestPos), Direction.EAST, bestPos, false);
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
        if (cfg.isAuraSwing()) {
            client.player.swing(InteractionHand.MAIN_HAND);
        }
        CheatUtils.LOGGER.info("[CheatUtils] SecretAura clicked {} at {} (attempt {}/{}, dist={}, room={})",
                bestKind, bestPos.toShortString(), count, MAX_ATTEMPTS,
                String.format(Locale.US, "%.2f", Math.sqrt(bestDist)), roomName == null ? (inBoss ? "boss" : "?") : roomName);
    }

    /** @return "chest"/"lever"/"essence"/"bossLever" when this block is a secret the aura may click, else null. */
    private static String classify(Minecraft client, CheatUtilsConfig cfg, BlockPos pos, BlockState state,
                                   boolean inBoss, boolean leversAllowed) {
        Block block = state.getBlock();
        if (inBoss) {
            return block == Blocks.LEVER && isBossLeverClickable(client, pos, state) ? "bossLever" : null;
        }
        if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) {
            return cfg.isAuraChests() ? "chest" : null;
        }
        if (block == Blocks.LEVER) {
            return leversAllowed ? "lever" : null;
        }
        if (block instanceof AbstractSkullBlock) {
            return cfg.isAuraEssence() && SecretsFeature.isWitherEssence(client.level, pos) ? "essence" : null;
        }
        return null;
    }

    /** QUOI {@code isBossBlock}: P3 lever with a "Not Activated" stand above, or an unpowered device lever. */
    private static boolean isBossLeverClickable(Minecraft client, BlockPos pos, BlockState state) {
        if (DEVICE_LEVERS.contains(pos)) {
            return state.hasProperty(LeverBlock.POWERED) && !state.getValue(LeverBlock.POWERED);
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

    private static void markDone(long key, String why) {
        if (done.add(key)) {
            // Opening a chest opens its GUI, so this logs at click-rate at most.
            CheatUtils.LOGGER.info("[CheatUtils] SecretAura done {} ({})", BlockPos.of(key).toShortString(), why);
        }
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
        if (!done.isEmpty() || !attempts.isEmpty()) {
            CheatUtils.LOGGER.info("[CheatUtils] SecretAura reset ({}): {} done, {} attempted", why, done.size(), attempts.size());
        }
        done.clear();
        firstSeenMs.clear();
        attempts.clear();
        leverInitialState.clear();
        lastClickKey = null;
    }

    private static void logGate(String gate) {
        if (!gate.equals(lastGateLog)) {
            lastGateLog = gate;
            CheatUtils.LOGGER.info("[CheatUtils] SecretAura state: {}", gate);
        }
    }
}
