package com.killer560.hub.autoroutes.mixin;

import com.killer560.hub.autoroutes.RouteExecutor;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A crypt node's held right click in obvious mode acts on the block along the BODY's look, not the held camera's -
 * see {@link RouteExecutor#heldUsePick}. {@code private void pick(float)} on {@code Minecraft} (javap, 26.1.2 and 26.2)
 * runs in {@code tick()} after {@code START_CLIENT_TICK} and before {@code handleKeybinds}, which is where the held use
 * key turns into {@code startUseItem} reading {@code hitResult}. Entities are not re-picked: straight down there is only
 * the floor.
 */
@Mixin(Minecraft.class)
public abstract class HeldUsePickMixin {

    @Inject(method = "pick", at = @At("TAIL"), require = 0)
    private void killer560smod$heldUseBodyPick(float partialTick, CallbackInfo ci) {
        Minecraft self = (Minecraft) (Object) this;
        HitResult hit = RouteExecutor.heldUsePick(self);
        if (hit != null) {
            self.hitResult = hit;
            self.crosshairPickEntity = null;
        }
    }
}
