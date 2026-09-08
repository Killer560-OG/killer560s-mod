package com.killer560.hub.experiments.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the container GUI's own top-left screen position (verified via javap: both fields are
 *  {@code protected int}, not otherwise reachable) - needed to convert a {@code Slot}'s own
 *  container-relative x/y (public fields) into real screen coordinates for drawing the Solver-Only
 *  highlight overlay over the correct slot. */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {

    @Accessor("leftPos")
    int killer560smod$getLeftPos();

    @Accessor("topPos")
    int killer560smod$getTopPos();
}
