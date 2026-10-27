package com.killer560.hub.zpew.mixin;

import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Aimed Warps (cheat build): {@code private int clickCount} on {@code KeyMapping} (javap, 26.1.2 and 26.2) - how many
 * presses of the use key are waiting for this tick's {@code handleKeybinds}. Read to give each its own look, and zeroed
 * when Zero Ping Etherwarp sends them itself.
 */
@Mixin(KeyMapping.class)
public interface ZpewKeyMappingAccessor {

    @Accessor("clickCount")
    int killer560smod$getClickCount();

    @Accessor("clickCount")
    void killer560smod$setClickCount(int count);
}
