package com.killer560.hub.supporters.mixin;

import com.killer560.hub.supporters.CosmeticsStateMarker;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Attaches {@link CosmeticsStateMarker} to every {@code AvatarRenderState} - see that interface's doc for
 *  why. Purely an extra boolean field via {@code @Unique}, so this cannot collide with anything vanilla
 *  already declares on the class regardless of its exact real shape. */
@Mixin(AvatarRenderState.class)
public abstract class AvatarRenderStateMarkerMixin implements CosmeticsStateMarker {

    @Unique
    private boolean killer560smod$isLocalPlayer;

    @Override
    public boolean killer560smod$isLocalPlayer() {
        return killer560smod$isLocalPlayer;
    }

    @Override
    public void killer560smod$setLocalPlayer(boolean value) {
        killer560smod$isLocalPlayer = value;
    }
}
