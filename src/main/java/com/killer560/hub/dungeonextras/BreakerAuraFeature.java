package com.killer560.hub.dungeonextras;

import com.killer560.hub.dungeonextras.mixin.MultiPlayerGameModeInvoker;
import com.killer560.hub.secrets.DungeonState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
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

    private BreakerAuraFeature() {
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
            RECENT.clear();
            lastSkipReason = null;
            spentSinceLore = 0;
            lastLoreCharges = -1;
        }
        if (!active) {
            return;
        }
        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }
        if (client.screen != null) {
            skip("screen open");
            return;
        }
        if (!(client.gameMode instanceof MultiPlayerGameModeInvoker invoker)) {
            skip("startPrediction invoker not applied");
            return;
        }
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        ItemStack held = player.getMainHandItem();
        int charges = getBreakerCharges(held);
        if (charges != lastLoreCharges) {
            lastLoreCharges = charges;
            spentSinceLore = 0;
        }
        int available = charges - spentSinceLore;
        if (available <= 0) {
            skip(charges <= 0 ? "no Dungeon Breaker charges in main hand" : "local charges spent, waiting for lore update");
            return;
        }

        long now = System.currentTimeMillis();
        RECENT.values().removeIf(t -> now - t > RETRY_MS);

        List<BlockPos> targets = collectPathTargets(player, level, cfg.getBreakerAuraReach(), now);
        if (targets.isEmpty()) {
            skip("no valid blocks in path");
            return;
        }
        lastSkipReason = null;
        int limit = Math.min(available, cfg.getBreakerAuraBlocksPerCycle());
        int broken = 0;
        for (BlockPos pos : targets) {
            if (broken >= limit) {
                break;
            }
            BlockHitResult hit = hitResult(player, level, pos);
            if (hit == null) {
                continue;
            }
            Block block = level.getBlockState(pos).getBlock();
            breakBlock(invoker, level, pos, hit.getDirection(), cfg.isBreakerAuraZeroPing());
            RECENT.put(pos, now);
            spentSinceLore++;
            broken++;
            LOGGER.info("[DungeonExtras] Breaker Aura sent START_DESTROY_BLOCK at {} ({}), charges {} -> {} (local).",
                    pos, block, charges, charges - spentSinceLore);
        }
        if (broken > 0) {
            player.swing(InteractionHand.MAIN_HAND);
            cooldownTicks = cfg.getBreakerAuraCooldownTicks();
        }
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
            AABB box = new AABB(cx - 0.3, feet.y, cz - 0.3, cx + 0.3, feet.y + 1.8, cz + 0.3);
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
