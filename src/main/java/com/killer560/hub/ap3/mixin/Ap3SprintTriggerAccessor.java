package com.killer560.hub.ap3.mixin;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** LocalPlayer's W double-tap sprint window: Fast Align's tick-exact replica (ap3/Ap3TickAlign) starts from it. */
@Mixin(LocalPlayer.class)
public interface Ap3SprintTriggerAccessor {

    @Accessor("sprintTriggerTime")
    int killer560$sprintTriggerTime();
}
