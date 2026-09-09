package com.killer560.hub.secrets.mixin;

import com.killer560.hub.secrets.OriginalCollisionShapeProvider;
import com.killer560.hub.secrets.SecretsFeature;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.SkullBlock;
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

/** Expands a floor-standing skull's interaction hitbox to the full block - covers Wither Essence, per
 *  killer560's own naming (2026-09-09), when it's placed standing rather than on a wall (see
 *  {@link WallSkullBlockMixin} for the wall-mounted case; quoi covers both under one "Skulls" toggle,
 *  NoammAddons only covers this floor variant - ported the more complete quoi coverage). Implements
 *  {@link OriginalCollisionShapeProvider} for the same real reason {@link ChestBlockMixin} does - see
 *  {@link BlockBehaviourMixin}'s own doc. Piglin heads get their own distinct real collision shape in
 *  vanilla (confirmed via javap: {@code SHAPE_PIGLIN} is a separate shadowed field from {@code SHAPE}) -
 *  NoammAddons' own {@code MixinSkullBlock} branches on that too, so this does the same rather than
 *  assuming every skull variant shares one shape. */
@Mixin(SkullBlock.class)
public abstract class SkullBlockMixin implements OriginalCollisionShapeProvider {

    @Shadow
    @Final
    private static VoxelShape SHAPE;

    @Shadow
    @Final
    private static VoxelShape SHAPE_PIGLIN;

    @Inject(method = "getShape", at = @At("HEAD"), cancellable = true)
    private void killer560smod$expandShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
        if (SecretsFeature.shouldExpandEssence()) {
            cir.setReturnValue(Shapes.block());
        }
    }

    @Override
    public VoxelShape killer560smod$getOriginalCollisionShape(BlockState state) {
        SkullBlock self = (SkullBlock) (Object) this;
        return self.getType() == SkullBlock.Types.PIGLIN ? SHAPE_PIGLIN : SHAPE;
    }
}
