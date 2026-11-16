package com.killer560.hub.privacy.mixin;

import com.killer560.hub.privacy.ModPresence;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.server.packs.resources.Resource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * Drops this mod's own pack from every language file stack (see {@link ModPresence}). {@code loadFrom} reads
 * each namespace's {@code lang/<code>.json} through its one {@code getResourceStack} call (javap 26.3 and 26.2).
 */
@Mixin(ClientLanguage.class)
public abstract class ClientLanguageMixin {

    @ModifyExpressionValue(method = "loadFrom", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/packs/resources/ResourceManager;getResourceStack(Lnet/minecraft/resources/Identifier;)Ljava/util/List;"))
    private static List<Resource> killer560smod$dropOwnLang(List<Resource> stack) {
        for (Resource r : stack) {
            if (ModPresence.ownPack(r.sourcePackId())) {
                return stack.stream().filter(x -> !ModPresence.ownPack(x.sourcePackId())).toList();
            }
        }
        return stack;
    }
}
