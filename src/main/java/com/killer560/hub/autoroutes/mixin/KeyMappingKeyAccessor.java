package com.killer560.hub.autoroutes.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads {@code KeyMapping.key} - the key a mapping is CURRENTLY bound to. The field is {@code protected} and
 * 26.1.2 exposes no public getter for it (only {@code getDefaultKey()}, which is wrong the moment the player
 * rebinds anything), so an accessor is the only honest way to get it.
 * <p>
 * Needed by {@code RouteExecutor}'s no-mixin fallback path: when the input mixin doesn't apply, the executor
 * drives by holding the key mappings, which makes {@code KeyMapping.isDown()} report the bot's own state and
 * hides the player's real keypresses - so "press W to stop the route" silently stops working. Polling the
 * physical key through GLFW needs the bound key code, which is what this hands back.
 */
@Mixin(KeyMapping.class)
public interface KeyMappingKeyAccessor {

    @Accessor("key")
    InputConstants.Key killer560smod$getKey();
}
