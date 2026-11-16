package com.killer560.hub.privacy.mixin;

import com.killer560.hub.privacy.ModPresence;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keybind components never resolve to a mapping this mod created (see {@link ModPresence}). Every constructor
 * chains into the five-argument one, and {@code createNameSupplier} is the keybind resolver's only lookup
 * ({@code ALL.get(key)}; javap 26.3 and 26.2).
 */
@Mixin(KeyMapping.class)
public abstract class KeyMappingPresenceMixin {

    @Inject(method = "<init>(Ljava/lang/String;Lcom/mojang/blaze3d/platform/InputConstants$Type;ILnet/minecraft/client/KeyMapping$Category;I)V",
            at = @At("RETURN"))
    private void killer560smod$note(String name, com.mojang.blaze3d.platform.InputConstants.Type type, int value,
                                    KeyMapping.Category category, int order, CallbackInfo ci) {
        ModPresence.noteMapping(name);
    }

    @ModifyExpressionValue(method = "createNameSupplier", at = @At(value = "INVOKE",
            target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"))
    private static Object killer560smod$hide(Object mapping) {
        return ModPresence.hideMapping(mapping);
    }
}
