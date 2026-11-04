package com.killer560.hub.miningqol.mixin;

import com.killer560.hub.miningqol.MithrilStone;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Mithril As Stone: the chunk mesher draws a swapped block with stone's model (see {@link MithrilStone}). The call wrapped
 * is {@code ModelBlockRenderer.tesselateBlock(BlockQuadOutput, float, float, float, BlockAndTintGetter, BlockPos,
 * BlockState, BlockStateModel, long)} inside {@code SectionCompiler.compile} (javap, 26.3 and 26.2: the same descriptor in
 * both). A WrapOperation, so another mod wrapping the same call still chains; vanilla's call always runs.
 */
@Mixin(SectionCompiler.class)
public abstract class MithrilStoneSectionMixin {

    @Shadow
    @Final
    private BlockStateModelSet blockModelSet;

    @WrapOperation(method = "compile",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;tesselateBlock(Lnet/minecraft/client/renderer/block/BlockQuadOutput;FFFLnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;J)V"),
            require = 0)
    private void killer560smod$mithrilAsStone(ModelBlockRenderer renderer, BlockQuadOutput output, float x, float y,
                                              float z, BlockAndTintGetter level, BlockPos pos, BlockState state,
                                              BlockStateModel model, long seed, Operation<Void> original) {
        BlockState swap = null;
        try {
            swap = MithrilStone.replace(state, pos.getX(), pos.getY(), pos.getZ());
        } catch (RuntimeException ignored) {
            // never break the mesher
        }
        BlockQuadOutput out = output;
        try {
            // Dark Stone: the same quads with the vertex color multiplied (a swapped block is judged by what it is drawn as)
            float f = com.killer560.hub.miningqol.DarkStone.factor(swap != null ? swap : state);
            if (f < 1.0f) {
                int argb = com.killer560.hub.miningqol.DarkStone.argb(f);
                out = (qx, qy, qz, quad, inst) -> {
                    inst.multiplyColor(argb);
                    output.put(qx, qy, qz, quad, inst);
                };
            }
        } catch (RuntimeException ignored) {
            out = output;
        }
        if (swap != null) {
            original.call(renderer, out, x, y, z, level, pos, swap, blockModelSet.get(swap), seed);
        } else {
            original.call(renderer, out, x, y, z, level, pos, state, model, seed);
        }
    }
}
