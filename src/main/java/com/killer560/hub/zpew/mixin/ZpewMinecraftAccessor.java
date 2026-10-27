package com.killer560.hub.zpew.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Aimed Warps (cheat build): {@code private int rightClickDelay} on {@code Minecraft} (javap, 26.1.2 and 26.2). A burst
 * sends his presses itself, so it sets the delay to 4 as vanilla's {@code startUseItem} would - otherwise a held right
 * button would fire one more use this same tick.
 */
@Mixin(Minecraft.class)
public interface ZpewMinecraftAccessor {

    @Accessor("rightClickDelay")
    void killer560smod$setRightClickDelay(int ticks);
}
