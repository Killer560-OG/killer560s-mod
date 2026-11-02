package com.killer560.hub.mobskin.mixin;

import com.killer560.hub.mobskin.MobSkinRender;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps Play as Mob off the local player's OWN render state on 26.3. javap 26.3: {@code LevelExtractor.extractPlayerState
 * (Camera, DeltaTracker, float, PlayerRenderState)} extracts the local player a second time, through the same
 * {@code EntityRenderDispatcher.extractEntity} that {@link MobSkinDispatcherMixin} swaps, and throws
 * {@code IllegalStateException("Expected an AvatarRenderState for the local player")} when it gets anything else; that
 * state feeds the first-person hands, not the world. With My Mob set, the swap handed it a PigRenderState and crashed the
 * render frame (UI suite 1018-mob-own, 2026-11-01). The world-entity pass (your mob in F5) is a separate call and still
 * swaps. 26.2 has no {@code extractPlayerState}; {@code require = 0} leaves this a no-op there.
 */
@Mixin(LevelExtractor.class)
public abstract class MobSkinLocalPlayerStateMixin {

    @Inject(method = "extractPlayerState", at = @At("HEAD"), require = 0)
    private void killer560smod$ownStateBegin(CallbackInfo ci) {
        MobSkinRender.setExtractingOwnState(true);
    }

    @Inject(method = "extractPlayerState", at = @At("RETURN"), require = 0)
    private void killer560smod$ownStateEnd(CallbackInfo ci) {
        MobSkinRender.setExtractingOwnState(false);
    }
}
