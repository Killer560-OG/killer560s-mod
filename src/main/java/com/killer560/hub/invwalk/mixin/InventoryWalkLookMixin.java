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
}
