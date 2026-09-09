package com.killer560.hub.secrets.mixin;

import com.killer560.hub.secrets.SecretsFeature;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Expands a button's interaction hitbox - killer560's explicit request for a Flat/Full Box choice
 *  (2026-09-09), matching quoi's own real "Expanded" vs "FullBlock" {@code ButtonHitbox} selector
 *  (decompiled via CFR) - "Flat" here is quoi's "Expanded": a face-aware shape that covers the button's
 *  whole attached face but stays thin, rather than a full cube. No collision-shape preservation needed
 *  (see {@link LeverBlockMixin}'s own doc) - a button's real vanilla collision is hardcoded empty
 *  regardless of outline shape. */
@Mixin(ButtonBlock.class)
public abstract class ButtonBlockMixin {

    @Inject(method = "getShape", at = @At("HEAD"), cancellable = true)
    private void killer560smod$expandShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
        if (!SecretsFeature.shouldExpandButtons()) {
            return;
        }
        if (SecretsFeature.shouldUseFullBoxButtons()) {
            cir.setReturnValue(Shapes.block());
            return;
        }
        AttachFace face = state.getValue(ButtonBlock.FACE);
        switch (face) {
            case FLOOR -> cir.setReturnValue(SecretsFeature.BUTTON_FLOOR_SHAPE);
            case CEILING -> cir.setReturnValue(SecretsFeature.BUTTON_CEILING_SHAPE);
            case WALL -> {
                Direction facing = state.getValue(ButtonBlock.FACING);
                cir.setReturnValue(switch (facing) {
                    case NORTH -> SecretsFeature.BUTTON_NORTH_SHAPE;
                    case SOUTH -> SecretsFeature.BUTTON_SOUTH_SHAPE;
                    case WEST -> SecretsFeature.BUTTON_WEST_SHAPE;
                    case EAST -> SecretsFeature.BUTTON_EAST_SHAPE;
                    default -> Shapes.block();
                });
            }
        }
    }
}
