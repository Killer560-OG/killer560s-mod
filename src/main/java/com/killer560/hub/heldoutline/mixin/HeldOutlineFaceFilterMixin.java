package com.killer560.hub.heldoutline.mixin;

import com.killer560.hub.heldoutline.HeldOutlineRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** While Held Item Outline draws a face mask, item submits keep only the near or the far face
 *  ({@link HeldOutlineRenderer#filterSubmit}). {@code submitItem(PoseStack, ItemDisplayContext, int, int, int, int[],
 *  List<BakedQuad>, FoilType)} is identical in 26.1.2 and 26.2 (javap), and both versions' item layers submit through it.
 *  One static int read when the outline is not drawing. */
@Mixin(SubmitNodeStorage.class)
public abstract class HeldOutlineFaceFilterMixin {

    @Inject(method = "submitItem", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$heldOutlineFaces(PoseStack pose, ItemDisplayContext context, int light, int overlay,
                                                int outline, int[] tints, List<BakedQuad> quads,
                                                ItemStackRenderState.FoilType foil, CallbackInfo ci) {
        if (HeldOutlineRenderer.filterSubmit((SubmitNodeStorage) (Object) this, pose, context, light, overlay, outline,
                tints, quads, foil)) {
            ci.cancel();
        }
    }
}
