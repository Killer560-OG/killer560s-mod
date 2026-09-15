package com.killer560.hub.autopuzzles;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.BigDripleafStemBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CandleBlock;
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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.function.Predicate;

/**
 * Shared helpers for the Auto Puzzles ports - straight Java ports of the QUOI utilities the puzzle autos use
 * ({@code VecUtils.getDirection/getArrowDirection/getArrowOrigin/isPathClear/getVisiblePoint/getLook},
 * {@code TeleportUtils.getEtherwarpDirection/traverseVoxels}, {@code PlayerUtils.useItem/at/eyePosition},
 * {@code ItemUtils.isShortbow/hasTerminator}, {@code SwapManager}).
 * <p>
 * Rotation rule (this mod): a use-with-rotation never sends a wrapped/clamped yaw - the yaw used is
 * {@code currentYaw + wrapDegrees(targetYaw - currentYaw)}. QUOI's {@code useItem(yaw, pitch)} puts the rotation only
 * in the {@code ServerboundUseItemPacket}; vanilla 26.1.2 {@code MultiPlayerGameMode.useItem} builds that packet from
 * {@code player.getYRot()/getXRot()} inside the synchronous prediction lambda (javap-verified), so the rotation is set
 * for the duration of that one call and restored straight after - the camera never visibly moves.
 */
public final class AutoPuzzleUtil {

    public static final float EYE_STANDING = 1.62f;
    public static final float EYE_SNEAKING = 1.27f;

    private static long lastSwapTick = Long.MIN_VALUE;

    private AutoPuzzleUtil() {
    }

    // ------------------------------------------------------------------ rotation / use

    /** {yaw, pitch} from {@code from} to {@code to} (QUOI getDirection, wrapped - only ever used as a target). */
    public static float[] direction(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double distXZ = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) -Math.toDegrees(Math.atan2(dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, distXZ));
        return new float[]{Mth.wrapDegrees(yaw), Mth.wrapDegrees(pitch)};
    }

    /** QUOI getLook. */
    public static Vec3 look(float yaw, float pitch) {
        double f2 = -Math.cos(-pitch * 0.017453292f);
        return new Vec3(Math.sin(-yaw * 0.017453292f - 3.1415927f) * f2, Math.sin(-pitch * 0.017453292f),
                Math.cos(-yaw * 0.017453292f - 3.1415927f) * f2);
    }

    /** Right-click the held item with the given rotation on the use packet only (see class doc). */
    public static void useItemRotated(Minecraft client, LocalPlayer player, float targetYaw, float targetPitch) {
        float realYaw = player.getYRot();
        float realPitch = player.getXRot();
        float yaw = realYaw + Mth.wrapDegrees(targetYaw - realYaw);
        float pitch = Mth.clamp(targetPitch, -90f, 90f);
        player.setYRot(yaw);
        player.setXRot(pitch);
        try {
            client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        } finally {
            player.setYRot(realYaw);
            player.setXRot(realPitch);
        }
    }

    /** Visible camera rotation (QUOI {@code player.rotate(dir)}), unwrapped per this mod's rule. */
    public static void rotateCamera(LocalPlayer player, float targetYaw, float targetPitch) {
        float yaw = player.getYRot() + Mth.wrapDegrees(targetYaw - player.getYRot());
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot(Mth.clamp(targetPitch, -90f, 90f));
    }

    // ------------------------------------------------------------------ player state

    public static Vec3 eyePosition(LocalPlayer player, boolean forceSneak) {
        float h = player.isCrouching() || forceSneak ? EYE_SNEAKING : EYE_STANDING;
        return new Vec3(player.getX(), player.getY() + h, player.getZ());
    }

    /** QUOI {@code player.at(pos)}: {@code BlockPos(x, ceil(y - 1), z) == pos}. */
    public static boolean at(LocalPlayer player, BlockPos pos) {
        BlockPos floor = new BlockPos(Mth.floor(player.getX()), (int) Math.floor(Math.ceil(player.getY() - 1.0)), Mth.floor(player.getZ()));
        return floor.equals(pos);
    }

    /** QUOI {@code isMoving}. */
    public static boolean isMoving(LocalPlayer player) {
        return player.getDeltaMovement().x != 0.0 || player.getDeltaMovement().z != 0.0 || player.input.hasForwardImpulse();
    }

    // ------------------------------------------------------------------ items

    public static String skyblockId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag tag = data.copyTag();
        return tag.contains("id") ? tag.getStringOr("id", null) : null;
    }

    public static boolean loreContains(ItemStack stack, String needle) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return false;
        }
        for (Component line : lore.lines()) {
            String s = ChatFormatting.stripFormatting(line.getString());
            if (s != null && s.toLowerCase(java.util.Locale.ROOT).contains(needle.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    public static final String SHORTBOW_LORE = "Shortbow: Instantly shoots!";

    public static boolean isShortbow(ItemStack stack) {
        return loreContains(stack, SHORTBOW_LORE);
    }

    public static boolean isAotv(ItemStack stack) {
        String id = skyblockId(stack);
        return "ASPECT_OF_THE_VOID".equals(id) || "ASPECT_OF_THE_END".equals(id);
    }

    public static boolean hasTerminator(LocalPlayer player) {
        for (int i = 0; i < 9; i++) {
            if ("TERMINATOR".equals(skyblockId(player.getInventory().getItem(i)))) {
                return true;
            }
        }
        return false;
    }

    /** QUOI SwapManager: hotbar swap to the first matching item, at most one swap per client tick.
     *  @return true if that item is now (or already was) selected */
    public static boolean swapTo(Minecraft client, LocalPlayer player, Predicate<ItemStack> predicate) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || !predicate.test(stack)) {
                continue;
            }
            if (inv.getSelectedSlot() == i) {
                return true;
            }
            long tick = client.level == null ? 0 : client.level.getGameTime();
            if (tick == lastSwapTick) {
                return false;
            }
            lastSwapTick = tick;
            inv.setSelectedSlot(i);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ arrows (QUOI VecUtils)

    public static Vec3 arrowOrigin(Vec3 from, float yaw, boolean terminator) {
        if (terminator) {
            return new Vec3(from.x, from.y - 0.01, from.z);
        }
        double r = Math.toRadians(yaw);
        return new Vec3(from.x - Math.cos(r) * 0.16, from.y - 0.1, from.z - Math.sin(r) * 0.16);
    }

    /** QUOI getArrowDirection(from, to, isTerminator): yaw from the XZ angle, pitch by binary search on a 3.0
     *  speed / 0.99 drag / 0.05 gravity arrow simulation. @return {yaw, pitch} (yaw NOT wrapped - raw atan2 - 90) */
    public static float[] arrowDirection(Vec3 from, Vec3 to, boolean terminator) {
        float yaw = (float) Math.toDegrees(Math.atan2(to.z - from.z, to.x - from.x)) - 90.0f;
        if (!terminator) {
            Vec3 origin = arrowOrigin(from, yaw, false);
            yaw = (float) Math.toDegrees(Math.atan2(to.z - origin.z, to.x - origin.x)) - 90.0f;
        }
        Vec3 origin = arrowOrigin(from, yaw, terminator);
        double dist = (to.x - origin.x) * (to.x - origin.x) + (to.z - origin.z) * (to.z - origin.z);
        float minPitch = -90.0f;
        float maxPitch = 90.0f;
        for (int i = 0; i < 20; i++) {
            float mid = (minPitch + maxPitch) / 2f;
            if (simulateHitY(origin, yaw, mid, dist) > to.y) {
                minPitch = mid;
            } else {
                maxPitch = mid;
            }
        }
        return new float[]{yaw, (minPitch + maxPitch) / 2f};
    }

    private static double simulateHitY(Vec3 origin, float yaw, float pitch, double dist) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double px = origin.x;
        double py = origin.y;
        double pz = origin.z;
        double mx = -Math.sin(yawRad) * Math.cos(pitchRad) * 3.0;
        double my = -Math.sin(pitchRad) * 3.0;
        double mz = Math.cos(yawRad) * Math.cos(pitchRad) * 3.0;
        for (int tick = 0; tick <= 100; tick++) {
            px += mx;
            py += my;
            pz += mz;
            double currDist = (px - origin.x) * (px - origin.x) + (pz - origin.z) * (pz - origin.z);
            if (currDist >= dist) {
                return py;
            }
            mx *= 0.99;
            my = my * 0.99 - 0.05;
            mz *= 0.99;
        }
        return py;
    }

    /** QUOI isPathClear: vanilla collider clip from {@code from} to {@code target} misses. */
    public static boolean isPathClear(Level level, LocalPlayer player, Vec3 from, Vec3 target) {
        BlockHitResult result = level.clip(new ClipContext(from, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return result.getType() == HitResult.Type.MISS;
    }

    // ------------------------------------------------------------------ etherwarp (QUOI TeleportUtils)

    /** @return {yaw, pitch} to etherwarp onto {@code to} from {@code from}, or null if no visible face point. */
    public static float[] etherwarpDirection(Level level, Vec3 from, BlockPos to, double dist) {
        if (from.distanceToSqr(to.getX(), to.getY(), to.getZ()) > (dist + 2) * (dist + 2)) {
            return null;
        }
        Vec3 visible = visiblePoint(level, from, to);
        return visible == null ? null : direction(from, visible);
    }

    public static float[] etherwarpDirection(Level level, LocalPlayer player, BlockPos to) {
        return etherwarpDirection(level, eyePosition(player, true), to, 61.0);
    }

    private static final double[][] VISIBLE_OFFSETS = {
            {0.5, 1.0, 0.5}, {0.0, 0.5, 0.5}, {1.0, 0.5, 0.5}, {0.5, 0.5, 0.0}, {0.5, 0.5, 1.0}, {0.5, 0.0, 0.5},
            {0.0, 0.001, 0.001}, {0.0, 0.001, 0.999}, {0.0, 0.999, 0.001}, {0.0, 0.999, 0.999},
            {1.0, 0.001, 0.001}, {1.0, 0.001, 0.999}, {1.0, 0.999, 0.001}, {1.0, 0.999, 0.999},
            {0.001, 0.001, 0.0}, {0.001, 0.999, 0.0}, {0.999, 0.001, 0.0}, {0.999, 0.999, 0.0},
            {0.001, 0.001, 1.0}, {0.001, 0.999, 1.0}, {0.999, 0.001, 1.0}, {0.999, 0.999, 1.0},
            {0.001, 0.0, 0.001}, {0.001, 0.0, 0.999}, {0.999, 0.0, 0.001}, {0.999, 0.0, 0.999},
            {0.001, 1.0, 0.001}, {0.001, 1.0, 0.999}, {0.999, 1.0, 0.001}, {0.999, 1.0, 0.999},
    };

    /** QUOI getVisiblePoint with the default etherwarp rayCast. */
    public static Vec3 visiblePoint(Level level, Vec3 from, BlockPos to) {
        for (double[] o : VISIBLE_OFFSETS) {
            Vec3 target = new Vec3(to.getX() + o[0], to.getY() + o[1], to.getZ() + o[2]);
            BlockPos hit = traverseEtherwarp(level, from, target);
            if (to.equals(hit)) {
                return target;
            }
        }
        return null;
    }

    /** QUOI traverseVoxels(etherwarp = true): the first non-passable block along the segment (only its
     *  position is used by getVisiblePoint), or null. */
    public static BlockPos traverseEtherwarp(Level level, Vec3 start, Vec3 end) {
        double x0 = start.x, y0 = start.y, z0 = start.z;
        double x = Math.floor(x0), y = Math.floor(y0), z = Math.floor(z0);
        double endX = Math.floor(end.x), endY = Math.floor(end.y), endZ = Math.floor(end.z);
        double dirX = end.x - x0, dirY = end.y - y0, dirZ = end.z - z0;
        int stepX = (int) Math.signum(dirX), stepY = (int) Math.signum(dirY), stepZ = (int) Math.signum(dirZ);
        double invDirX = dirX != 0.0 ? 1.0 / dirX : Double.MAX_VALUE;
        double invDirY = dirY != 0.0 ? 1.0 / dirY : Double.MAX_VALUE;
        double invDirZ = dirZ != 0.0 ? 1.0 / dirZ : Double.MAX_VALUE;
        double tDeltaX = Math.abs(invDirX * stepX);
        double tDeltaY = Math.abs(invDirY * stepY);
        double tDeltaZ = Math.abs(invDirZ * stepZ);
        double tMaxX = Math.abs((x + Math.max(stepX, 0) - x0) * invDirX);
        double tMaxY = Math.abs((y + Math.max(stepY, 0) - y0) * invDirY);
        double tMaxZ = Math.abs((z + Math.max(stepZ, 0) - z0) * invDirZ);
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        for (int i = 0; i < 1000; i++) {
            mut.set(x, y, z);
            BlockState state = level.getBlockState(mut);
            if (!isPassable(state)) {
                return mut.immutable();
            }
            if (x == endX && y == endY && z == endZ) {
                return null;
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
        return null;
    }

    /** QUOI TeleportUtils.blockFlags PASSABLE classification. */
    public static boolean isPassable(BlockState state) {
        Block b = state.getBlock();
        return b instanceof AirBlock || b instanceof FlowerBlock || b instanceof TallGrassBlock || b instanceof BushBlock
                || b instanceof TallFlowerBlock || b instanceof ShortDryGrassBlock || b instanceof TorchBlock
                || b instanceof RedstoneTorchBlock || b instanceof TripWireBlock || b instanceof TripWireHookBlock
                || b instanceof RailBlock || b instanceof FireBlock || b instanceof VineBlock || b instanceof LiquidBlock
                || b instanceof SaplingBlock || b instanceof CropBlock || b instanceof StemBlock
                || b instanceof SeagrassBlock || b instanceof TallSeagrassBlock || b instanceof SugarCaneBlock
                || b instanceof MushroomBlock || b instanceof NetherWartBlock || b instanceof RedStoneWireBlock
                || b instanceof ComparatorBlock || b instanceof RepeaterBlock || b instanceof SmallDripleafBlock
                || b instanceof BigDripleafStemBlock || b instanceof DoublePlantBlock || b instanceof LeverBlock
                || b instanceof SnowLayerBlock || b instanceof BubbleColumnBlock || b instanceof GrowingPlantBlock
                || b instanceof PistonHeadBlock || b instanceof DryVegetationBlock || b instanceof ButtonBlock
                || b instanceof LanternBlock || b instanceof SkullBlock || b instanceof WallSkullBlock
                || b instanceof LadderBlock || b instanceof FlowerPotBlock || b instanceof WebBlock
                || b instanceof NetherPortalBlock || b instanceof CandleBlock;
    }

    // ------------------------------------------------------------------ block interact

    /** No-rotate block interact - same as {@code AutoPuzzlesFeature}'s (QUOI {@code BlockPos.getHitResult()}):
     *  {@code useItemOn} with the eye-to-shape-centre ray clipped against the real shape, then a main-hand swing.
     *  @return false (nothing sent) if the block has no shape */
    public static boolean interactBlock(Minecraft client, BlockPos pos) {
        BlockState state = client.level.getBlockState(pos);
        VoxelShape shape = state.getShape(client.level, pos);
        if (shape.isEmpty()) {
            return false;
        }
        Vec3 eyes = client.player.getEyePosition();
        Vec3 centre = shape.bounds().getCenter().add(pos.getX(), pos.getY(), pos.getZ());
        Vec3 dir = centre.subtract(eyes).normalize();
        Vec3 end = eyes.add(dir.scale(eyes.distanceTo(centre) + 1.5));
        BlockHitResult hit = shape.clip(eyes, end, pos);
        if (hit == null) {
            hit = new BlockHitResult(centre, Direction.getApproximateNearest(eyes.subtract(centre)), pos, false);
        }
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
        client.player.swing(InteractionHand.MAIN_HAND);
        return true;
    }
}
