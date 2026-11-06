package com.killer560.hub.invwalk.mixin;

import com.killer560.hub.invwalk.InventoryWalkFeature;
import com.mojang.blaze3d.Blaze3D;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import com.killer560.hub.compat.McWindow;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Inventory Walk's Look Key (killer560, 2026-11-05: "is there a way to change my facing angle while in my inventory?",
 * key + mouse). While it is held in a menu Inventory Walk walks in, a cursor move turns the player through vanilla's own
 * {@code turnPlayer} (sensitivity, invert, smooth camera, every {@code turn} mixin) and is then undone: the cursor is put
 * back where the menu last saw it ({@code xpos/ypos} never change), so no hover, drag or click comes of it.
 * {@code onMove} is {@code (JDDDD)V} on 26.3 and {@code (JDD)V} on 26.2 (javap, 2026-11-05), so the handler takes the
 * position as the first two double arguments through {@code @Local(argsOnly)}, which both versions share.
 */
@Mixin(MouseHandler.class)
public abstract class InventoryWalkLookMixin {

    @Shadow @Final private Minecraft minecraft;
    @Shadow private double xpos;
    @Shadow private double ypos;
    @Shadow private double accumulatedDX;
    @Shadow private double accumulatedDY;
    @Shadow private double lastHandleMovementTime;

    @Shadow private void turnPlayer(double movementTime) { }

    @Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
    private void killer560smod$lookKey(CallbackInfo ci, @Local(argsOnly = true, ordinal = 0) double x,
            @Local(argsOnly = true, ordinal = 1) double y) {
        if (!InventoryWalkFeature.lookKeyHeld(minecraft)) {
            return;
        }
        double dx = x - xpos;
        double dy = y - ypos;
        ci.cancel();
        if (dx == 0 && dy == 0) {
            return;
        }
        double now = Blaze3D.getTime();
        accumulatedDX = dx;
        accumulatedDY = dy;
        turnPlayer(now - lastHandleMovementTime);
        lastHandleMovementTime = now;
        accumulatedDX = 0;
        accumulatedDY = 0;
        McWindow.warpCursor(minecraft.getWindow(), xpos, ypos);
        InventoryWalkFeature.noteLook();
    }
}
