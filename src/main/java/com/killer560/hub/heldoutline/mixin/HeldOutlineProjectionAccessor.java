package com.killer560.hub.heldoutline.mixin;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.Projection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** {@code private final Projection hudProjection} on GameRenderer (javap, 26.1.2 and 26.2): the first-person hand's
 *  projection, which Held Item Outline needs to put the Outline Side: Both connector lines on screen. */
@Mixin(GameRenderer.class)
public interface HeldOutlineProjectionAccessor {

    @Accessor("hudProjection")
    Projection killer560smod$hudProjection();
}
