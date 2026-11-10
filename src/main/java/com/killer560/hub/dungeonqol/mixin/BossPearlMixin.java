package com.killer560.hub.dungeonqol.mixin;

import com.killer560.hub.dungeonqol.BossPearlBlock;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Block Ender Pearls In Boss: cancels vanilla's right click ({@code private void startUseItem()}, same on 26.3 and
 *  26.2) at HEAD, so no use packet and no swing go out. See {@link BossPearlBlock}. */
@Mixin(Minecraft.class)
public abstract class BossPearlMixin {

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void killer560smod$bossPearl(CallbackInfo ci) {
        if (BossPearlBlock.shouldBlock((Minecraft) (Object) this)) {
            ci.cancel();
        }
    }
}
