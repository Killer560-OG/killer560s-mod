package com.killer560.hub.perfprobe.mixin;

import com.killer560.hub.perfprobe.PerfProbe;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Wraps each Fabric HUD layer a mod class adds in a {@link PerfProbe} timer (probe on only). javap,
 *  fabric-rendering-v1: the four adders are static and take exactly one {@code HudElement}. {@code replaceElement}
 *  (a Function over the old element) is not wrapped. */
@Mixin(targets = "net.fabricmc.fabric.impl.client.rendering.hud.HudElementRegistryImpl", remap = false)
public abstract class HudLayerProbeMixin {

    @ModifyVariable(method = {"addFirst", "addLast", "attachElementBefore", "attachElementAfter"},
            at = @At("HEAD"), argsOnly = true, remap = false, require = 0)
    private static HudElement killer560$perfProbe(HudElement element) {
        return PerfProbe.wrapListener(HudElement.class, element);
    }
}
