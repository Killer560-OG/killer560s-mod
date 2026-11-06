package com.killer560.hub.dungeonextras.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.prediction.PredictiveAction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Breaker Aura: {@code private void startPrediction(ClientLevel, PredictiveAction)} (javap-verified, 26.1.2) - the
 *  same sequenced send vanilla uses for START_DESTROY_BLOCK. Callers must instanceof-check (config is optional). */
@Mixin(MultiPlayerGameMode.class)
public interface MultiPlayerGameModeInvoker {

    @Invoker("startPrediction")
    void killer560smod$invokeStartPrediction(ClientLevel level, PredictiveAction action);

    /** Auto Routes: {@code private void ensureHasSentCarriedItem()} (javap-verified on 26.1.2 and 26.2) - sends the
     *  held-item packet for the client's selected slot if the server has not been told yet, and records it in
     *  {@code carriedIndex} so {@code MultiPlayerGameMode.tick()} does not send the same slot a second time. */
    @Invoker("ensureHasSentCarriedItem")
    void killer560smod$invokeEnsureHasSentCarriedItem();

    /** DigWindow.handDig (grimgapB-1105): vanilla continueDestroyBlock's state, to tell whether his held left click digs
     *  this tick (fields javap-verified on 26.3). */
    @Accessor("destroyDelay")
    int killer560smod$getDestroyDelay();

    @Accessor("destroyProgress")
    float killer560smod$getDestroyProgress();

    @Accessor("destroyBlockPos")
    net.minecraft.core.BlockPos killer560smod$getDestroyBlockPos();

    @Accessor("destroyingItem")
    net.minecraft.world.item.ItemStack killer560smod$getDestroyingItem();
}
