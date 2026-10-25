package com.killer560.hub.zpew.mixin;

import com.killer560.hub.util.ModLog;
import com.killer560.hub.zpew.ZeroPingEtherwarp;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Zero Ping Etherwarp's only write: the camera position at the TAIL of {@code Camera.alignWithEntity(float)}, the method
 * and members {@code SmoothTpCameraMixin} uses (identical on 26.1.2 and 26.2). Priority 1100, so it is applied after
 * Smooth Teleport's (1000) and a pending prediction wins the frame. Crosshair picks still cast from the player's eye.
 */
@Mixin(value = Camera.class, priority = 1100)
public abstract class ZpewCameraMixin {

    @Shadow
    public abstract Vec3 position();

    @Shadow
    protected abstract void setPosition(Vec3 position);

    @Shadow
    public abstract Entity entity();

    @Shadow
    public abstract boolean isDetached();

    @Inject(method = "alignWithEntity", at = @At("TAIL"))
    private void killer560smod$zpewCamera(float partialTicks, CallbackInfo ci) {
        try {
            Vec3 vanilla = position();
            Vec3 drawn = ZeroPingEtherwarp.cameraPosition(entity(), isDetached(), vanilla);
            if (drawn != vanilla) {
                setPosition(drawn);
            }
        } catch (RuntimeException e) {
            killer560smod$zpew$threw(e);
        }
    }

    @Unique
    private static void killer560smod$zpew$threw(RuntimeException e) {
        ModLog.get("killer560smod-zpew").error("[ZPEW] camera hook threw - vanilla camera kept", e);
    }
}
