package com.killer560.hub.etherwarpoverlay;

import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.DryVegetationBlock;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.GrowingPlantBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.MushroomBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.SeagrassBlock;
import net.minecraft.world.level.block.ShortDryGrassBlock;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.SmallDripleafBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.TallFlowerBlock;
import net.minecraft.world.level.block.TallGrassBlock;
import net.minecraft.world.level.block.TallSeagrassBlock;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.WallSkullBlock;
import net.minecraft.world.level.block.WebBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Real Hypixel Skyblock Etherwarp landing-prediction overlay, ported from Odin's own {@code Etherwarp.kt}
 * - specifically just its real "Show Guess" box (the client-side-predicted-execution half of Odin's own
 * feature, which forcibly overrides the player's real position the instant Etherwarp is used, is
 * deliberately NOT ported here: getting a real voxel raycast even slightly wrong there could put the
 * player inside a wall or off a ledge, a real safety risk this pure-rendering version can't have since
 * it never touches the player's actual position - the real server-side Etherwarp still runs completely
 * unmodified).
 * <p>
 * Real mechanic: while holding a real Etherwarp-granting item (the Aspect of the Void / Etherwarp
 * Conduit, or any item with the real "ethermerge" NBT flag) and either holding shift or holding the
 * Conduit specifically, this raycasts a real voxel traversal along your exact look direction up to a
 * real max distance (57 blocks, extended by the real "tuned_transmission" reforge/enchant stat) using
 * the same real per-block-type passability rules Odin's own confirmed, shipped mod uses, and highlights
 * where you'd land - green if it's a real safe landing spot, red if it isn't.
 */
public final class EtherwarpOverlayFeature {

    private static final String ETHERWARP_CONDUIT_ID = "ETHERWARP_CONDUIT";
    private static final int PASSABLE = 1;
    private static final int BLOCKS_FEET = 2;

    private record EtherPos(boolean succeeded, BlockPos pos, BlockState state) {
        static final EtherPos NONE = new EtherPos(false, null, null);
    }

    private EtherwarpOverlayFeature() {
    }

    public static void register() {
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(EtherwarpOverlayFeature::onWorldRender);
    }

    private static void onWorldRender(LevelRenderContext context) {
        EtherwarpOverlayConfig cfg = EtherwarpOverlayConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        if (!cfg.isEnabled() || client.screen != null || client.player == null || client.level == null) {
            return;
        }

        ItemStack mainHand = client.player.getMainHandItem();
        CompoundTag etherData = getEtherwarpData(mainHand);
        if (etherData == null) {
            return;
        }
        boolean isConduit = ETHERWARP_CONDUIT_ID.equals(getSkyblockId(mainHand));
        if (!client.player.isShiftKeyDown() && !isConduit) {
            return;
        }

        double distance = 57.0 + etherData.getIntOr("tuned_transmission", 0);
        EtherPos etherPos = getEtherPos(client.level, client.player.position(), client.player, distance);
        if (!etherPos.succeeded() && !cfg.isShowWhenFailed()) {
            return;
        }
        if (etherPos.pos() == null) {
            return;
        }

        float r = etherPos.succeeded() ? 0.2f : 1.0f;
        float g = etherPos.succeeded() ? 1.0f : 0.2f;
        float b = 0.2f;
        AABB box = cfg.isFullBlock() ? new AABB(etherPos.pos()) : realBoxFor(client.level, etherPos.pos());
        WorldRenderUtils.renderOutlineBox(context, box, r, g, b, 1f, 2f);
    }

    private static AABB realBoxFor(Level level, BlockPos pos) {
        var shape = level.getBlockState(pos).getShape(level, pos);
        return (shape.isEmpty() ? new AABB(0, 0, 0, 1, 1, 1) : shape.bounds()).move(pos);
    }

    private static CompoundTag getEtherwarpData(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag tag = data.copyTag();
        boolean isEtherItem = tag.getIntOr("ethermerge", 0) == 1
                || ETHERWARP_CONDUIT_ID.equals(tag.contains("id") ? tag.getStringOr("id", null) : null);
        return isEtherItem ? tag : null;
    }

    private static String getSkyblockId(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag tag = data.copyTag();
        return tag.contains("id") ? tag.getStringOr("id", null) : null;
    }

    private static EtherPos getEtherPos(Level level, Vec3 position, net.minecraft.world.entity.player.Player player,
                                         double distance) {
        double eyeHeight = player.getPose() == Pose.SWIMMING ? 0.4 : player.isCrouching() ? 1.27 : 1.62;
        Vec3 start = new Vec3(position.x, position.y + eyeHeight, position.z);
        Vec3 lookAngle = player.getLookAngle();
        Vec3 end = start.add(lookAngle.x * distance, lookAngle.y * distance, lookAngle.z * distance);
        return traverseVoxels(level, start, end);
    }

    private static EtherPos traverseVoxels(Level level, Vec3 start, Vec3 end) {
        double x0 = start.x, y0 = start.y, z0 = start.z;
        double x1 = end.x, y1 = end.y, z1 = end.z;

        double x = Math.floor(x0), y = Math.floor(y0), z = Math.floor(z0);
        double endX = Math.floor(x1), endY = Math.floor(y1), endZ = Math.floor(z1);

        double dirX = x1 - x0, dirY = y1 - y0, dirZ = z1 - z0;

        int stepX = (int) Math.signum(dirX);
        int stepY = (int) Math.signum(dirY);
        int stepZ = (int) Math.signum(dirZ);

        double invDirX = dirX != 0.0 ? 1.0 / dirX : Double.MAX_VALUE;
        double invDirY = dirY != 0.0 ? 1.0 / dirY : Double.MAX_VALUE;
        double invDirZ = dirZ != 0.0 ? 1.0 / dirZ : Double.MAX_VALUE;

        double tDeltaX = Math.abs(invDirX * stepX);
        double tDeltaY = Math.abs(invDirY * stepY);
        double tDeltaZ = Math.abs(invDirZ * stepZ);

        double tMaxX = Math.abs((x + Math.max(stepX, 0) - x0) * invDirX);
        double tMaxY = Math.abs((y + Math.max(stepY, 0) - y0) * invDirY);
        double tMaxZ = Math.abs((z + Math.max(stepZ, 0) - z0) * invDirZ);

        for (int i = 0; i < 1000; i++) {
            BlockPos blockPos = new BlockPos((int) x, (int) y, (int) z);
            BlockState state = level.getBlockState(blockPos);
            Block block = state.getBlock();

            boolean isPassable = isPassable(block);
            boolean isSolid = !isPassable;

            if (isSolid) {
                var collisionShape = state.getCollisionShape(level, blockPos);
                int collisionTop = (int) Math.ceil(collisionShape.isEmpty() ? 0.0 : collisionShape.max(Direction.Axis.Y));
                int clearanceBaseY = blockPos.getY() + Math.max(1, collisionTop);

                BlockPos feetPos = new BlockPos(blockPos.getX(), clearanceBaseY, blockPos.getZ());
                BlockState feetState = level.getBlockState(feetPos);
                if (!isPassable(feetState.getBlock()) || blocksFeet(feetState.getBlock())) {
                    return new EtherPos(false, blockPos, state);
                }

                BlockPos headPos = new BlockPos(blockPos.getX(), clearanceBaseY + 1, blockPos.getZ());
                BlockState headState = level.getBlockState(headPos);
                if (!isPassable(headState.getBlock()) || blocksFeet(headState.getBlock())) {
                    return new EtherPos(false, blockPos, state);
                }

                return new EtherPos(true, blockPos, state);
            }

            if (x == endX && y == endY && z == endZ) {
                return EtherPos.NONE;
            }

            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                tMaxX += tDeltaX;
                x += stepX;
            } else if (tMaxY <= tMaxZ) {
                tMaxY += tDeltaY;
                y += stepY;
            } else {
                tMaxZ += tDeltaZ;
                z += stepZ;
            }
        }
        return EtherPos.NONE;
    }

    private static boolean isPassable(Block block) {
        return block instanceof AirBlock
                || block instanceof FlowerBlock || block instanceof TallGrassBlock || block instanceof BushBlock
                || block instanceof TallFlowerBlock || block instanceof ShortDryGrassBlock
                || block instanceof TorchBlock || block instanceof RedstoneTorchBlock
                || block instanceof TripWireBlock || block instanceof TripWireHookBlock
                || block instanceof RailBlock
                || block instanceof FireBlock
                || block instanceof VineBlock
                || block instanceof LiquidBlock
                || block instanceof SaplingBlock
                || block instanceof CropBlock || block instanceof StemBlock
                || block instanceof SeagrassBlock || block instanceof TallSeagrassBlock
                || block instanceof SugarCaneBlock
                || block instanceof MushroomBlock
                || block instanceof NetherWartBlock
                || block instanceof RedStoneWireBlock || block instanceof ComparatorBlock || block instanceof RepeaterBlock
                || block instanceof SmallDripleafBlock
                || block instanceof DoublePlantBlock
                || block instanceof LeverBlock
                || block instanceof SnowLayerBlock
                || block instanceof BubbleColumnBlock
                || block instanceof GrowingPlantBlock
                || block instanceof PistonHeadBlock
                || block instanceof DryVegetationBlock
                || block instanceof ButtonBlock
                || block instanceof LanternBlock
                || block instanceof SkullBlock || block instanceof WallSkullBlock
                || block instanceof LadderBlock
                || block instanceof FlowerPotBlock
                || block instanceof WebBlock
                || block instanceof NetherPortalBlock;
    }

    private static boolean blocksFeet(Block block) {
        return block instanceof SkullBlock || block instanceof WallSkullBlock
                || block instanceof FlowerPotBlock
                || block instanceof LadderBlock
                || block instanceof VineBlock;
    }
}
