package com.killer560.hub.secrets.mixin;

import com.killer560.hub.secrets.OriginalCollisionShapeProvider;
import com.killer560.hub.secrets.SecretsFeature;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.WallSkullBlock;
import net.minecraft.world.level.block.state.BlockState;
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

/** Expands a wall-mounted skull's interaction hitbox to the full block - the wall-mounted counterpart to
 *  {@link SkullBlockMixin} (both share the one "Essence"/Skulls toggle, matching quoi's own real
 *  coverage - NoammAddons only covers the floor variant). Implements
 *  {@link OriginalCollisionShapeProvider} for the same real reason {@link ChestBlockMixin} does - see
 *  {@link BlockBehaviourMixin}'s own doc. Also checks {@link SecretsFeature#isWitherEssence} for the
 *  same real reason {@link SkullBlockMixin} does - see its own doc. */
@Mixin(WallSkullBlock.class)
public abstract class WallSkullBlockMixin implements OriginalCollisionShapeProvider {

    @Shadow
    @Final
    private static Map<Direction, VoxelShape> SHAPES;

    @Inject(method = "getShape", at = @At("HEAD"), cancellable = true)
    private void killer560smod$expandShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
        if (SecretsFeature.shouldExpandEssence() && SecretsFeature.isWitherEssence(level, pos)) {
            cir.setReturnValue(Shapes.block());
        }
    }

    @Override
    public VoxelShape killer560smod$getOriginalCollisionShape(BlockState state) {
        return SHAPES.get(state.getValue(WallSkullBlock.FACING));
    }
}
