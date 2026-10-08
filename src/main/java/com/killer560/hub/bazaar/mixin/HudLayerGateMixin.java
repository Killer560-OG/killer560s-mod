package com.killer560.hub.bazaar.mixin;

import com.killer560.hub.bazaar.BazaarHud;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.impl.client.rendering.hud.HudElementRegistryImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * The one HUD gate for the Bazaar ({@link BazaarHud}): every layer added through Fabric's HUD registry is wrapped as it
 * is registered, so no HUD layer draws while the Bazaar is open. Targets (javap, fabric-rendering-v1 23.3.1 for 26.1.2
 * and 25.3.3 for 26.2, identical): {@code HudElementRegistryImpl.addFirst/addLast(Identifier, HudElement)} and
 * {@code attachElementBefore/attachElementAfter(Identifier, Identifier, HudElement)}, all static; each has exactly one
 * {@code HudElement} argument. Fabric API is not a Minecraft class, so nothing is remapped.
 */
@Mixin(value = HudElementRegistryImpl.class, remap = false)
public abstract class HudLayerGateMixin {

    @ModifyVariable(method = {"addFirst", "addLast", "attachElementBefore", "attachElementAfter"}, at = @At("HEAD"),
            argsOnly = true, require = 0)
    private static HudElement killer560smod$gateForBazaar(HudElement element) {
        return BazaarHud.gate(element);
    }
}
