package com.killer560.hub.terminals.mixin;

import com.killer560.hub.terminals.TerminalSolverFeature;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Counts container clicks that reach the vanilla send path, so Auto Terminals can tell a click another mod's terminal
 *  solver swallowed at slotClicked from one that went to the server (zeeks-simterm-1120). */
@Mixin(MultiPlayerGameMode.class)
public abstract class TerminalClickSentMixin {

    @Inject(method = "handleContainerInput", at = @At("HEAD"))
    private void killer560smod$clickSent(int containerId, int slot, int button, ContainerInput input, Player player, CallbackInfo ci) {
        TerminalSolverFeature.containerInputs++;
    }
}
