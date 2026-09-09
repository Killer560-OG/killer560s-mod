package com.killer560.hub.secrets.mixin;

import com.killer560.hub.secrets.SecretsFeature;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Expands a lever's interaction hitbox to the full block - ported 1:1 from both quoi's own
 *  {@code LeverBlockMixin} and NoammAddons' own {@code MixinLeverBlock} (independently identical
 *  approach, decompiled 2026-09-09). No collision-shape preservation needed here (unlike
 *  {@link ChestBlockMixin}/{@link SkullBlockMixin}) - a lever's real vanilla collision is hardcoded empty
 *  regardless of its outline shape, confirmed by neither reference mod bothering with that split for
 *  levers. */
@Mixin(LeverBlock.class)
public abstract class LeverBlockMixin {

    @Inject(method = "getShape", at = @At("HEAD"), cancellable = true)
    private void killer560smod$expandShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
        if (SecretsFeature.shouldExpandLevers()) {
            cir.setReturnValue(Shapes.block());
        }
    }
}
