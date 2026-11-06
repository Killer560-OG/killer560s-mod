package com.killer560.hub.powdermining.mixin;

import com.killer560.hub.powdermining.TreasureChests;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hide Opened Chests (Treasure Chest Highlight): a chest the server confirmed opened has no render state extracted, so nothing of it
 * is drawn. Client-only and visual: the block is never changed (a client-side air block would be a ghost block) and no
 * packet is involved. {@code tryExtractRenderState} has the same descriptor on 26.3 and 26.2 (javap, 2026-11-04).
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class TreasureChestHideMixin {

    @Inject(method = "tryExtractRenderState", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$hideOpenedChest(BlockEntity blockEntity, float partialTick,
                                               ModelFeatureRenderer.CrumblingOverlay crumbling, boolean flag,
                                               CallbackInfoReturnable<BlockEntityRenderState> cir) {
        try {
            if (TreasureChests.shouldHide(blockEntity.getBlockPos())) {
                cir.setReturnValue(null);
            }
        } catch (RuntimeException e) {
            // never break rendering
        }
    }
}
