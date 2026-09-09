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

    // Mutator added (2026-09-09) for TerminalSolverFeature's Melody recentering - both fields are real
    // (non-final, per javap) protected ints, so writing back through the same accessor keeps rendering
    // AND real click hit-testing in sync automatically, since both already read from this one field.
    @Accessor("topPos")
    void killer560smod$setTopPos(int topPos);
}
