package com.killer560.hub.spotify.mixin;

import com.killer560.hub.spotify.SongTagDraw;
import com.killer560.hub.spotify.SongTagState;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Song Tag: picks this frame's song line for a player. TAIL of {@code AvatarRenderer.extractRenderState(Avatar,
 * AvatarRenderState, float)}, the target {@code SupportersScaleMixin} uses (javap, 26.1.2 and 26.2), after
 * {@code EntityRenderer.extractRenderState} has filled {@code distanceToCameraSq}. {@code require = 0}: a wrong target only
 * leaves the song line undrawn.
 */
@Mixin(AvatarRenderer.class)
public abstract class SongTagExtractMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;"
            + "Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
            at = @At("TAIL"), require = 0)
    private void killer560smod$songTag(Avatar entity, AvatarRenderState state, float partialTick, CallbackInfo ci) {
        if ((Object) state instanceof SongTagState tagged) {
            tagged.killer560smod$setSongTag(SongTagDraw.tagFor(entity, state.distanceToCameraSq));
        }
    }
}
