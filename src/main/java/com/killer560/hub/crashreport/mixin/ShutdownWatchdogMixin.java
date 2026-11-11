package com.killer560.hub.crashreport.mixin;

import com.killer560.hub.crashreport.ShutdownGuard;
import com.mojang.blaze3d.platform.ClientShutdownWatchdog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.main.GameConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla starts its shutdown watchdog from the end of Main.main ("post-main"); the guard starts beside it (crashguard-1110). */
@Mixin(ClientShutdownWatchdog.class)
public abstract class ShutdownWatchdogMixin {

    @Inject(method = "startShutdownWatchdog", at = @At("HEAD"))
    private static void killer560smod$guard(String reason, boolean exit, Minecraft mc, GameConfig config, long mainId, CallbackInfo ci) {
        ShutdownGuard.arm(reason, mainId);
    }
}
