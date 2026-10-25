package com.killer560.hub.leftclickew.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Vanilla's right click ({@code private void startUseItem()}, javap 26.1.2 and 26.2), for Left Click Etherwarp. */
@Mixin(Minecraft.class)
public interface LeftClickUseInvoker {

    @Invoker("startUseItem")
    void killer560smod$startUseItem();
}
