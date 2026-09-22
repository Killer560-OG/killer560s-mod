package com.killer560.hub.dungeonextras;

import com.killer560.hub.dungeonextras.mixin.MultiPlayerGameModeInvoker;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Breaker Aura (cheat build) - while holding a DUNGEONBREAKER with charges, breaks blocks that obstruct the
 * player's path (the player's 0.6x1.8 hitbox swept forward along movement direction, or look direction when
 * standing still) within reach.
 * <p>
 * Sources: QUOI {@code DungeonBreaker.kt} auto-db tick loop (charges cap per cycle, eye-to-centre reach, skip
 * recently-attempted positions) and {@code AuraManager.breakBlock(immediate = true)} (one
 * {@code START_DESTROY_BLOCK} sent through {@code MultiPlayerGameMode#startPrediction} + main-hand swing, 6-tick
 * cooldown); QUOI {@code VecUtils.getHitResult} for the face; target validity = QUOI
 * {@code Dungeon.isProtectedBlock} (any block entity, or {@code blacklistedDBBlocks}) plus NoammAddons
 * {@code BreakerHelper.kt} (player heads, obsidian excluded from zero-ping). Charges are read from lore exactly like
 * {@code DungeonBreakerFeature} ("Charges: N/M"), with locally-spent charges subtracted until the lore changes.
 */
public final class BreakerAuraFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-dungeonextras");
    private static final String DUNGEON_BREAKER_SKYBLOCK_ID = "DUNGEONBREAKER";
    private static final Pattern CHARGES_PATTERN = Pattern.compile("Charges: (\\d+)/(\\d+)");
    private static final long RETRY_MS = 1_500L;
    private static final double FLOOR_CLEARANCE = 0.1;

    private static final Set<Block> BLACKLIST = Set.of(
            Blocks.BARRIER, Blocks.BEDROCK, Blocks.COMMAND_BLOCK, Blocks.CHAIN_COMMAND_BLOCK,
            Blocks.REPEATING_COMMAND_BLOCK, Blocks.SKELETON_SKULL, Blocks.SKELETON_WALL_SKULL,
            Blocks.WITHER_SKELETON_SKULL, Blocks.WITHER_SKELETON_WALL_SKULL, Blocks.TNT,
            Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.END_PORTAL_FRAME, Blocks.END_PORTAL,
            Blocks.PISTON, Blocks.PISTON_HEAD, Blocks.STICKY_PISTON, Blocks.MOVING_PISTON,
            Blocks.LEVER, Blocks.STONE_BUTTON, Blocks.PLAYER_HEAD, Blocks.PLAYER_WALL_HEAD, Blocks.OBSIDIAN);

    private static final Map<BlockPos, Long> RECENT = new HashMap<>();
    private static int cooldownTicks = 0;
    private static int lastLoreCharges = -1;
    private static int spentSinceLore = 0;
    private static String lastSkipReason = null;
    private static boolean wasActive = false;

    // ---- Auto Swap (killer560, 2026-09-20: "an option to make it swap to the breaker or not swap to the breaker
    // automatically ... If it swaps automatically it should whenever the blocks are in range swap to the breaker and
    // then break them"). A slot change and a block break on the same tick is not a thing a hand does, so the swap
    // arms a configurable delay and the first break only goes out once that delay has run down.
    private static int swappedFromSlot = -1;
    private static int swapArmedTicks = 0;
    private static int idleSinceSwapTicks = 0;
    private static boolean warnedNoBreakerInHotbar = false;

    private BreakerAuraFeature() {
    }

    private static void resetState() {
        RECENT.clear();
        lastSkipReason = null;
        spentSinceLore = 0;
        lastLoreCharges = -1;
        swappedFromSlot = -1;
        swapArmedTicks = 0;
        idleSinceSwapTicks = 0;
        warnedNoBreakerInHotbar = false;
    }

    private static void skip(String reason) {
        if (!reason.equals(lastSkipReason)) {
            lastSkipReason = reason;
            LOGGER.info("[DungeonExtras] Breaker Aura idle: {}", reason);
        }
    }

    static void onClientTick(Minecraft client) {
        DungeonExtrasConfig cfg = DungeonExtrasConfig.getInstance();
        boolean active = cfg.isBreakerAuraEnabled() && client.player != null && client.level != null
                && client.gameMode != null && DungeonState.isInDungeon();
        if (active != wasActive) {
            wasActive = active;
            LOGGER.info("[DungeonExtras] Breaker Aura {}.", active ? "active" : "inactive");
            if (!active) {
                restoreSlot(client, "turned off");
            }
            resetState();
        }
        if (!active) {
            return;
        }
        // killer560 (2026-09-20): "It needs an edit mode where when I am in the edit mode, the breaker aura will not
        // work." Placing route nodes means right-clicking blocks you are standing next to, which is exactly what the
        // aura wants to chew through. Auto Routes owns the only real edit mode in the mod; AP3 has none (see notes).
        if (cfg.isBreakerAuraRespectEditMode() && isRouteEditModeActive()) {
            restoreSlot(client, "edit mode");
            skip("Auto Routes edit mode is active");
            return;
        }
        if (com.killer560.hub.util.ActionGate.containerScreenOpen(client)) {
            // Mirrors ActionGate's WORLD rule (container screens only, not this mod's own menu),
            // but cheaper, and it keeps the swap-back from firing mid-menu.
            skip("a container screen is open");
            return;
        }
        if (!(client.gameMode instanceof MultiPlayerGameModeInvoker invoker)) {
            skip("startPrediction invoker not applied");
            return;
        }
        LocalPlayer player = client.player;
        ClientLevel level = client.level;

        int breakerSlot = findBreakerHotbarSlot(player);
        int selected = player.getInventory().getSelectedSlot();
        boolean autoSwap = cfg.isBreakerAuraAutoSwap();
        if (autoSwap && breakerSlot < 0) {
            // "If the breaker is not in the hotbar at all, do nothing and say so once - never rummage the inventory."
            if (!warnedNoBreakerInHotbar) {
                warnedNoBreakerInHotbar = true;
                ModChat.send("Breaker Aura", ModChat.text("Auto Swap is on but there is no "),
                        ModChat.value("Dungeon Breaker"), ModChat.text(" in your hotbar - leaving your hand alone."));
            }
            // The breaker running out of charges mid-run lands here, so this is also where the hand has to go
            // back - otherwise Auto Swap leaves you holding a spent breaker for the rest of the floor.
            restoreSlot(client, "no Dungeon Breaker with charges left in the hotbar");
            skip("auto swap on, no Dungeon Breaker in the hotbar");
            return;
        }
        // Without Auto Swap the breaker has to already be in the main hand, exactly as before.
        ItemStack source = autoSwap && breakerSlot >= 0 ? player.getInventory().getItem(breakerSlot) : player.getMainHandItem();
        int charges = getBreakerCharges(source);
        if (charges != lastLoreCharges) {
            lastLoreCharges = charges;
            spentSinceLore = 0;
        }
        int available = charges - spentSinceLore;
        if (available <= 0) {
            skip(charges <= 0
                    ? (autoSwap ? "no Dungeon Breaker charges in the hotbar" : "no Dungeon Breaker charges in main hand")
                    : "local charges spent, waiting for lore update");
            return;
        }

        long now = System.currentTimeMillis();
        RECENT.values().removeIf(t -> now - t > RETRY_MS);

        List<BlockPos> targets = collectPathTargets(player, level, cfg.getBreakerAuraReach(), now);
        if (targets.isEmpty()) {
            skip("no valid blocks in path");
            if (autoSwap && swappedFromSlot >= 0 && cfg.isBreakerAuraSwapBack()
                    && ++idleSinceSwapTicks >= cfg.getBreakerAuraSwapBackIdleTicks()) {
                restoreSlot(client, "nothing left in the path");
            }
            return;
        }
        lastSkipReason = null;
        idleSinceSwapTicks = 0;

        if (autoSwap && selected != breakerSlot) {
            if (swapArmedTicks <= 0) {
                if (swappedFromSlot < 0) {
                    swappedFromSlot = selected;
                }
                player.getInventory().setSelectedSlot(breakerSlot);
                player.connection.send(new ServerboundSetCarriedItemPacket(breakerSlot));
                swapArmedTicks = cfg.getBreakerAuraSwapDelayTicks();
                LOGGER.info("[DungeonExtras] Breaker Aura swapped {} -> {} for the breaker, first break in {} ticks.",
                        selected, breakerSlot, swapArmedTicks);
            }
            return;
        }
        if (swapArmedTicks > 0) {
            // Settling window after a slot change; the break goes out once the server has plausibly seen the swap.
            swapArmedTicks--;
            return;
        }
        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }

        // killer560: "if I have two levers in my range at once or two chests have it only pick one and then the other
        // on the next tick". One block per cycle, nearest to the eye first - the old loop broke up to five in a
        // single tick, which is the classic aura signature.
        Vec3 eye = player.getEyePosition();
        BlockPos chosen = null;
        BlockHitResult chosenHit = null;
        double bestSq = Double.MAX_VALUE;
        for (BlockPos pos : targets) {
            double distSq = Vec3.atCenterOf(pos).distanceToSqr(eye);
            if (distSq >= bestSq) {
                continue;
            }
            BlockHitResult hit = hitResult(player, level, pos);
            if (hit == null) {
                continue;
            }
            chosen = pos;
            chosenHit = hit;
            bestSq = distSq;
        }
        if (chosen == null) {
            skip("no reachable face on any block in the path");
            return;
        }
        // Last thing before any state changes: nothing below may run if the gate refuses the tick.
        if (!ActionGate.tryAct(ActionGate.Actor.BREAKER_AURA)) {
            return;
        }
        Block block = level.getBlockState(chosen).getBlock();
        breakBlock(invoker, level, chosen, chosenHit.getDirection(), cfg.isBreakerAuraZeroPing());
        RECENT.put(chosen, now);
        spentSinceLore++;
        player.swing(InteractionHand.MAIN_HAND);
        cooldownTicks = cfg.getBreakerAuraCooldownTicks();
        LOGGER.info("[DungeonExtras] Breaker Aura sent START_DESTROY_BLOCK at {} ({}), charges {} -> {} (local).",
                chosen, block, charges, charges - spentSinceLore);
    }

    /** True while Auto Routes is in its block-placing edit mode. AP3 has no edit mode to ask about (see notes). */
    private static boolean isRouteEditModeActive() {
        try {
            return com.killer560.hub.autoroutes.AutoRoutesFeature.isEditMode();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Puts the hand back where Auto Swap found it. No-op unless we are the ones who moved it. */
    private static void restoreSlot(Minecraft client, String why) {
        int back = swappedFromSlot;
        swappedFromSlot = -1;
        swapArmedTicks = 0;
        idleSinceSwapTicks = 0;
        LocalPlayer player = client.player;
        if (back < 0 || back > 8 || player == null || player.connection == null) {
            return;
        }
        if (player.getInventory().getSelectedSlot() == back) {
            return;
        }
        player.getInventory().setSelectedSlot(back);
        player.connection.send(new ServerboundSetCarriedItemPacket(back));
        LOGGER.info("[DungeonExtras] Breaker Aura swapped back to slot {} ({}).", back, why);
    }

    /** First hotbar slot holding a DUNGEONBREAKER with charges left, or -1. Hotbar only - never the inventory. */
    private static int findBreakerHotbarSlot(LocalPlayer player) {
        for (int i = 0; i < 9; i++) {
            if (getBreakerCharges(player.getInventory().getItem(i)) > 0) {
                return i;
            }
        }
        return -1;
    }

    private static void breakBlock(MultiPlayerGameModeInvoker invoker, ClientLevel level, BlockPos pos,
                                   Direction face, boolean zeroPing) {
        BlockState state = level.getBlockState(pos);
        invoker.killer560smod$invokeStartPrediction(level, sequence -> {
            if (zeroPing && !state.is(Blocks.OBSIDIAN)) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
            return new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, face, sequence);
        });
    }

    /** Blocks intersecting the player's hitbox swept forward up to reach, nearest first. */
    private static List<BlockPos> collectPathTargets(LocalPlayer player, ClientLevel level, double reach, long now) {
        Vec3 motion = player.getDeltaMovement();
        Vec3 dir = new Vec3(motion.x, 0, motion.z);
        if (dir.lengthSqr() < 0.0025) {
            Vec3 look = player.getViewVector(1f);
            dir = new Vec3(look.x, 0, look.z);
        }
        if (dir.lengthSqr() < 1.0E-6) {
            return List.of();
        }
        dir = dir.normalize();
        Vec3 feet = player.position();
        Vec3 eye = player.getEyePosition();
        double reachSq = reach * reach;
        Set<BlockPos> ordered = new LinkedHashSet<>();
        for (double t = 0; t <= reach; t += 0.25) {
            double cx = feet.x + dir.x * t;
            double cz = feet.z + dir.z * t;
            // Review fix (2026-09-15): start the swept box FLOOR_CLEARANCE above the feet. At exactly feet.y a
            // player whose y is a hair under an integer (float error, soul sand/farmland, setbacks) got the
            // block they are STANDING ON counted as "in the path" and broken out from under them.
            AABB box = new AABB(cx - 0.3, feet.y + FLOOR_CLEARANCE, cz - 0.3, cx + 0.3, feet.y + 1.8, cz + 0.3);
            int minX = (int) Math.floor(box.minX), maxX = (int) Math.floor(box.maxX);
            int minY = (int) Math.floor(box.minY), maxY = (int) Math.floor(box.maxY - 1.0E-4);
            int minZ = (int) Math.floor(box.minZ), maxZ = (int) Math.floor(box.maxZ);
            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (ordered.contains(pos) || RECENT.containsKey(pos)) {
                            continue;
                        }
                        if (Vec3.atCenterOf(pos).distanceToSqr(eye) > reachSq) {
                            continue;
                        }
                        if (!isValidTarget(level, pos)) {
                            continue;
                        }
                        VoxelShape collision = level.getBlockState(pos).getCollisionShape(level, pos);
                        if (collision.isEmpty() || !collision.bounds().move(pos).intersects(box)) {
                            continue;
                        }
                        ordered.add(pos);
                    }
                }
            }
        }
        return new ArrayList<>(ordered);
    }

    /**
     * Whether a route planner should treat this block as air - killer560 (2026-09-22): "If a block is selected for
     * breaker aura treat that block as not being there when you go to run through it". True only while Breaker Aura
     * is actually on and the block is one it would break.
     */
    public static boolean plannerTreatsAsAir(ClientLevel level, BlockPos pos) {
        return DungeonExtrasConfig.getInstance().isBreakerAuraEnabled() && isValidTarget(level, pos);
    }

    private static boolean isValidTarget(ClientLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.hasBlockEntity() || level.getBlockEntity(pos) != null) {
            return false;
        }
        if (BLACKLIST.contains(state.getBlock())) {
            return false;
        }
        return state.getDestroySpeed(level, pos) >= 0f;
    }

    /** QUOI {@code BlockPos.getHitResult}: clip the block's own outline shape from the eyes through its centre. */
    private static BlockHitResult hitResult(LocalPlayer player, ClientLevel level, BlockPos pos) {
        VoxelShape shape = level.getBlockState(pos).getShape(level, pos);
        if (shape.isEmpty()) {
            return null;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 centre = shape.bounds().getCenter().add(pos.getX(), pos.getY(), pos.getZ());
        Vec3 dir = centre.subtract(eye).normalize();
        Vec3 end = eye.add(dir.scale(eye.distanceTo(centre) + 1.5));
        return shape.clip(eye, end, pos);
    }

    /** Same logic as the private {@code DungeonBreakerFeature.getBreakerCharges}. */
    private static int getBreakerCharges(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return 0;
        }
        CompoundTag tag = data.copyTag();
        if (!DUNGEON_BREAKER_SKYBLOCK_ID.equals(tag.getStringOr("id", null))) {
            return 0;
        }
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return 0;
        }
        for (Component line : lore.lines()) {
            Matcher m = CHARGES_PATTERN.matcher(line.getString());
            if (m.find()) {
                try {
                    return Integer.parseInt(m.group(1));
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }
}
