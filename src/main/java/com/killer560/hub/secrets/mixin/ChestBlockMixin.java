package com.killer560.hub.secrets.mixin;

import com.killer560.hub.secrets.OriginalCollisionShapeProvider;
import com.killer560.hub.secrets.SecretsFeature;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/** Expands a chest's interaction hitbox to the full block - ported 1:1 from quoi's own
 *  {@code ChestBlockMixin} (decompiled via CFR, 2026-09-09; NoammAddons has no chest equivalent, so this
 *  side is quoi-only). Unlike Levers/Buttons, a chest has REAL solid collision - implements
 *  {@link OriginalCollisionShapeProvider} so {@link BlockBehaviourMixin} can hand the player's own
 *  movement collision back the chest's real (non-expanded) shape instead of silently becoming a full
 *  solid cube. */
@Mixin(ChestBlock.class)
public abstract class ChestBlockMixin implements OriginalCollisionShapeProvider {

    @Shadow
    @Final
    private static VoxelShape SHAPE;

    @Shadow
    @Final
    private static Map<Direction, VoxelShape> HALF_SHAPES;

    @Shadow
    public static Direction getConnectedDirection(BlockState blockState) {
        throw new AssertionError();
    }

    @Inject(method = "getShape", at = @At("HEAD"), cancellable = true)
    private void killer560smod$expandShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
        if (SecretsFeature.shouldExpandChests()) {
            cir.setReturnValue(Shapes.block());
        }
    }

    @Override
    public VoxelShape killer560smod$getOriginalCollisionShape(BlockState state) {
        if (state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return SHAPE;
        }
        return HALF_SHAPES.get(getConnectedDirection(state));
    }
}
