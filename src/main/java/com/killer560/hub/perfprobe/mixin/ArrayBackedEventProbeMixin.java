package com.killer560.hub.perfprobe.mixin;

import com.killer560.hub.perfprobe.PerfProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Wraps each listener a mod class registers on any Fabric event in a {@link PerfProbe} timer. Applied only when the
 *  probe is on ({@code PerfProbeMixinPlugin}). The listener type is the component type of the event's handler array
 *  (Fabric keeps no other copy of it). javap, fabric-api-base 2.0.3 and 2.0.4: {@code register(Identifier, T)} is the
 *  one registration path ({@code register(T)} delegates to it). */
@Mixin(targets = "net.fabricmc.fabric.impl.base.event.ArrayBackedEvent", remap = false)
public abstract class ArrayBackedEventProbeMixin {

    @Shadow(remap = false)
    private Object[] handlers;

    @ModifyVariable(method = "register(Lnet/minecraft/resources/Identifier;Ljava/lang/Object;)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, remap = false, require = 0)
    private Object killer560$perfProbe(Object listener) {
        return PerfProbe.wrapListener(handlers.getClass().getComponentType(), listener);
    }
}
