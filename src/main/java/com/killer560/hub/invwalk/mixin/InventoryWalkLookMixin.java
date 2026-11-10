package com.killer560.hub.invwalk.mixin;

import com.killer560.hub.compat.McWindow;
import com.killer560.hub.invwalk.InventoryWalkFeature;
import com.mojang.blaze3d.Blaze3D;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Inventory Walk's Look Key (killer560, 2026-11-05; reworked 2026-11-09: "looking in my inventory is kinda laggy and the
 * sense feels off"). In a menu the cursor is NORMAL, so its motion carried the OS pointer acceleration, was not raw and
 * stopped at the screen edge. While the key is held the mouse is grabbed the way the game grabs it (SDL relative mode on
 * 26.3, GLFW_CURSOR_DISABLED with raw motion when the option is on on 26.2, through {@code McWindow.reapplyMouseGrab}), and
 * vanilla's own {@code mouseGrabbed} flag is set so {@code onMove} accumulates exactly as in play; once a frame
 * {@code turnPlayer} turns the player (sensitivity, invert, smoothing, every turn mixin), and the screen sees no motion, no
 * click and no scroll. Let go, screen closed or window unfocused: the cursor is put back where the menu last had it.
 * {@code MouseHandler.grabMouse} is never called (it closes the screen); the real one, if the game asks while looking,
 * finds the flag cleared first.
 */
@Mixin(MouseHandler.class)
public abstract class InventoryWalkLookMixin {

    @Shadow @Final private Minecraft minecraft;
    @Shadow private double xpos;
    @Shadow private double ypos;
    @Shadow private double accumulatedDX;
    @Shadow private double accumulatedDY;
    @Shadow private double lastHandleMovementTime;
    @Shadow private boolean mouseGrabbed;

    @Shadow private void turnPlayer(double movementTime) { }

    @Unique private boolean killer560smod$looking;
    @Unique private double killer560smod$savedX;
    @Unique private double killer560smod$savedY;

    @Inject(method = "handleAccumulatedMovement", at = @At("HEAD"), cancellable = true)
    private void killer560smod$look(CallbackInfo ci) {
        boolean want = InventoryWalkFeature.lookKeyHeld(minecraft);
        if (!want && !killer560smod$looking) {
            return;
        }
        ci.cancel();
        double now = Blaze3D.getTime();
        if (want != killer560smod$looking) {
            killer560smod$looking = want;
            if (want) {
                killer560smod$savedX = xpos;
                killer560smod$savedY = ypos;
            }
            mouseGrabbed = want;
            McWindow.reapplyMouseGrab(minecraft.getWindow(), want, killer560smod$savedX, killer560smod$savedY);
            if (!want) {
                xpos = killer560smod$savedX;
                ypos = killer560smod$savedY;
            }
            accumulatedDX = 0;
            accumulatedDY = 0;
        } else if (accumulatedDX != 0 || accumulatedDY != 0) {
            turnPlayer(now - lastHandleMovementTime);
            accumulatedDX = 0;
            accumulatedDY = 0;
            InventoryWalkFeature.noteLook();
        }
        lastHandleMovementTime = now;
    }

    /** The game grabs for real (a screen closed while looking): its own guard must see the flag clear. */
    @Inject(method = "grabMouse", at = @At("HEAD"))
    private void killer560smod$gameGrab(CallbackInfo ci) {
        if (killer560smod$looking) {
            killer560smod$looking = false;
            mouseGrabbed = false;
        }
    }

    /** The game releases for real (another screen opened while looking): the flag stays set so vanilla releases the grab. */
    @Inject(method = "releaseMouse", at = @At("HEAD"))
    private void killer560smod$gameRelease(CallbackInfo ci) {
        killer560smod$looking = false;
    }

    @Inject(method = {"onButton", "onScroll"}, at = @At("HEAD"), cancellable = true)
    private void killer560smod$noClick(CallbackInfo ci) {
        if (killer560smod$looking) {
            ci.cancel();
        }
    }

    /**
     * The screen's pointer while looking: the menu's last spot (invlook-1110, killer560: "the mouse keeps moving around like
     * i am not looking"). {@code onMove} writes the moving absolute position into xpos/ypos even while grabbed (SDL relative
     * mode on 26.3, a disabled GLFW cursor on 26.2), and the screen's hover, slot highlight and tooltip read it through these.
     * xpos itself must keep moving: 26.2 takes its deltas from it.
     */
    @Inject(method = "getScaledXPos(Lcom/mojang/blaze3d/platform/Window;)D", at = @At("HEAD"), cancellable = true)
    private void killer560smod$frozenX(CallbackInfoReturnable<Double> cir) {
        if (killer560smod$looking) {
            cir.setReturnValue(MouseHandler.getScaledXPos(minecraft.getWindow(), killer560smod$savedX));
        }
    }

    @Inject(method = "getScaledYPos(Lcom/mojang/blaze3d/platform/Window;)D", at = @At("HEAD"), cancellable = true)
    private void killer560smod$frozenY(CallbackInfoReturnable<Double> cir) {
        if (killer560smod$looking) {
            cir.setReturnValue(MouseHandler.getScaledYPos(minecraft.getWindow(), killer560smod$savedY));
        }
    }

    @Inject(method = "xpos", at = @At("HEAD"), cancellable = true)
    private void killer560smod$frozenRawX(CallbackInfoReturnable<Double> cir) {
        if (killer560smod$looking) {
            cir.setReturnValue(killer560smod$savedX);
        }
    }

    @Inject(method = "ypos", at = @At("HEAD"), cancellable = true)
    private void killer560smod$frozenRawY(CallbackInfoReturnable<Double> cir) {
        if (killer560smod$looking) {
            cir.setReturnValue(killer560smod$savedY);
        }
    }
}
