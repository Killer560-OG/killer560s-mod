package com.killer560.hub.miningqol.mixin;

import com.killer560.hub.miningqol.DarkStone;
import com.mojang.blaze3d.vertex.BufferBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Dark Stone: every vertex color BufferBuilder writes (setColor(int) and addVertex's fast path both end in the private
 * static putRgba, javap 26.3 and 26.2) is multiplied while the section compiler is drawing a stone-type block (see
 * {@link DarkStone#begin}); outside that, {@link DarkStone#tint} returns the color as is. A ModifyVariable on the argument,
 * so another mod's hook on the same method still chains.
 */
@Mixin(BufferBuilder.class)
public abstract class DarkBufferMixin {

    @ModifyVariable(method = "putRgba(JI)V", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private static int killer560smod$darkStoneColor(int color) {
        return DarkStone.tint(color);
    }
}
