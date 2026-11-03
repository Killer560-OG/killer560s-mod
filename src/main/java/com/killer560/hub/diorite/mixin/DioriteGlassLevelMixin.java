package com.killer560.hub.diorite.mixin;

import com.killer560.hub.diorite.DioriteGlassFeature;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * I Hate Diorite: a block the server sets inside one of Storm's pillars becomes glass in the same call, so a pillar that
 * is lowered or rebuilt never shows diorite for a tick or waits for the 0.5 s rescan (killer560, 2026-11-03). Render
 * side only: the server's own state is never sent anything. {@code ClientLevel.setServerVerifiedBlockState(BlockPos,
 * BlockState, int)} is public void with that shape on 26.1.2 and 26.2 (javap).
 */
@Mixin(ClientLevel.class)
public abstract class DioriteGlassLevelMixin {

    @ModifyVariable(method = "setServerVerifiedBlockState", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private BlockState killer560smod$dioriteGlass(BlockState state, BlockPos pos) {
        try {
            BlockState glass = DioriteGlassFeature.glassFor(pos, state);
            return glass != null ? glass : state;
        } catch (RuntimeException e) {
            return state; // a throw on the packet path disconnects him
        }
    }
}
