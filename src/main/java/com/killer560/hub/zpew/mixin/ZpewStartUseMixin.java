package com.killer560.hub.zpew.mixin;

import com.killer560.hub.zpew.ZeroPingEtherwarp;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Warps Per Tick (cheat build; zpewunify-1111): the extra warps go when the whole right click is done, not when its main
 * hand use returns. Vanilla's {@code startUseItem} goes on to the OFF hand after a main hand use that passes, and sends a
 * {@code use_item_on} for it; with the extras sent before that, the tick read use_item x N then use_item_on, which
 * experimental GrimAC PacketOrderN flagged at Warps Per Tick 20 (testkit 1027-zpewmt, 1111-zpew-wpt20-same-*).
 */
@Mixin(Minecraft.class)
public abstract class ZpewStartUseMixin {

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void killer560smod$zpewClickStart(CallbackInfo ci) {
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && ZeroPingEtherwarp.beforeClick()) {
            ci.cancel(); // replayed next tick (PacketOrderN)
            return;
        }
        ZeroPingEtherwarp.inClick = true;
    }

    @Inject(method = "startUseItem", at = @At("RETURN"))
    private void killer560smod$zpewClickDone(CallbackInfo ci) {
        ZeroPingEtherwarp.inClick = false;
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) ZeroPingEtherwarp.flushExtraWarps();
    }
}
