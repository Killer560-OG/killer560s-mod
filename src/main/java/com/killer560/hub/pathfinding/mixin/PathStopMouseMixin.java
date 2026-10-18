package com.killer560.hub.pathfinding.mixin;

import com.killer560.hub.pathfinding.AutoWalker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Auto Fairy Souls stops on any mouse button he presses; moving the mouse only steers the free camera. Same target as
 *  {@code ExperimentsStopMouseMixin} (javap 26.1.2 and 26.2): {@code private void onButton(long, MouseButtonInfo, int)}. */
@Mixin(MouseHandler.class)
public class PathStopMouseMixin {

    @Inject(method = "onButton", at = @At("HEAD"))
    private void killer560smod$fairyStopOnButton(long window, MouseButtonInfo input, int action, CallbackInfo ci) {
        if (action == 1 && window == Minecraft.getInstance().getWindow().handle() && AutoWalker.isSessionActive()) {
            int b = input.button();
            AutoWalker.onPlayerInput(b == 0 ? "left click" : b == 1 ? "right click" : b == 2 ? "middle click"
                    : "mouse button " + (b + 1), Integer.MIN_VALUE);
        }
    }
}
