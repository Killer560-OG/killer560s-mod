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
 *  ({@link HeldOutlineRenderer#filterSubmit}). 26.2 copy: {@code submitItem(PoseStack, ItemDisplayContext, int, int, int,
 *  int[], List<BakedQuad>, FoilType)} (javap); 26.3 takes an {@code ItemQuads} there instead. One static int read when
 *  the outline is not drawing. */
@Mixin(SubmitNodeStorage.class)
public abstract class HeldOutlineFaceFilterMixin {

    @Inject(method = "submitItem", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$heldOutlineFaces(PoseStack pose, ItemDisplayContext context, int light, int overlay,
                                                int outline, int[] tints, List<BakedQuad> quads,
                                                ItemStackRenderState.FoilType foil, CallbackInfo ci) {
        if (!HeldOutlineRenderer.isFiltering()) {
            return; // every item submit in the game comes here: no allocation unless a face mask is being drawn
        }
        SubmitNodeStorage self = (SubmitNodeStorage) (Object) this;
        if (HeldOutlineRenderer.filterSubmit(pose, quads,
                only -> self.submitItem(pose, context, light, overlay, outline, tints, only, foil))) {
            ci.cancel();
        }
    }
}
