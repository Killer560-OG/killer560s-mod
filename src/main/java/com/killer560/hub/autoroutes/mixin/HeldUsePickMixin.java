package com.killer560.hub.autoroutes.mixin;

import com.killer560.hub.autoroutes.RouteExecutor;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A crypt node's held right click in obvious mode acts on the block along the BODY's look, not the held camera's -
 * see {@link RouteExecutor#heldUsePick}. {@code private void pick(float)} on {@code Minecraft} (javap, 26.1.2 and 26.2)
 * runs in {@code tick()} after {@code START_CLIENT_TICK} and before {@code handleKeybinds}, which is where the held use
 * key turns into {@code startUseItem} reading {@code hitResult}. Entities are not re-picked: straight down there is only
 * the floor.
 * <p>
 * Under a screen the route runs beneath (the Interactive Map with Run While Map Open) {@code tick()} skips
 * {@code handleKeybinds} altogether - it runs only while {@code overlay == null && screen == null} (javap 26.1.2:
 * {@code Minecraft.tick} offsets 353-377; 26.2: the same test on {@code Gui.overlay()}/{@code Gui.screen()}) - so a held
 * use key sent nothing and the crypt node never killed anything with the map open (killer560, 2026-10-07). There the
 * held use is stepped right after the tick's own pick by vanilla's very rule from {@code handleKeybinds}: key down,
 * {@code rightClickDelay == 0}, not already using an item, then {@code startUseItem()} (which sets the delay to 4 again).
 * {@code rightClickDelay} counts down at the top of {@code tick()} whatever the screen, so the cadence is a held right
 * click's, packet for packet. See {@link RouteExecutor#heldUseUnderScreen}.
 */
@Mixin(Minecraft.class)
public abstract class HeldUsePickMixin {

    @Shadow
    private int rightClickDelay;

    @Shadow
    private void startUseItem() {
    }

    @Inject(method = "pick", at = @At("TAIL"), require = 0)
    private void killer560smod$heldUseBodyPick(float partialTick, CallbackInfo ci) {
        Minecraft self = (Minecraft) (Object) this;
        HitResult hit = RouteExecutor.heldUsePick(self);
        if (hit == null) {
            // Auto Clear holds the use key with the body turned and the camera held, by the same rule.
            hit = com.killer560.hub.autoclear.AutoClearFeature.heldUsePick(self);
        }
        if (hit != null) {
            self.hitResult = hit;
            self.crosshairPickEntity = null;
        }
    }

    /** The tick's own pick (renderFrame picks too, between ticks, and must not use anything). */
    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;pick(F)V",
            shift = At.Shift.AFTER), require = 0)
    private void killer560smod$heldUseUnderScreen(CallbackInfo ci) {
        Minecraft self = (Minecraft) (Object) this;
        if (rightClickDelay == 0 && self.player != null && !self.player.isUsingItem()
                && self.options.keyUse.isDown() && RouteExecutor.heldUseUnderScreen(self)) {
            startUseItem();
        }
    }
}
