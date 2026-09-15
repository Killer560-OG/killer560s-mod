package com.killer560.hub.motionblur.mixin;

import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** javap-verified 26.1.2: {@code private final List<PostPass> passes}. */
@Mixin(PostChain.class)
public interface PostChainAccessor {

    @Accessor("passes")
    List<PostPass> killer560smod$getPasses();
}
