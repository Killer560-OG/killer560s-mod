package com.killer560.hub.dungeonbreaker.mixin;

import com.killer560.hub.dungeonbreaker.ObsidianDig;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A right click while a held obsidian dig is in progress does nothing, as in vanilla while its own hand dig runs
 * (flaghunt-1110 B: GrimAC experimental PacketOrderI, "place/use while digging", from the player's use_item).
 */
@Mixin(Minecraft.class)
public abstract class ObsidianHoldUseMixin {

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void killer560smod$noUseWhileObsidianDig(CallbackInfo ci) {
        if (ObsidianDig.holdingDig()) {
            ci.cancel();
        }
    }
}
