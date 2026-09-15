package com.killer560.hub.dungeonextras.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.prediction.PredictiveAction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Breaker Aura: {@code private void startPrediction(ClientLevel, PredictiveAction)} (javap-verified, 26.1.2) - the
 *  same sequenced send vanilla uses for START_DESTROY_BLOCK. Callers must instanceof-check (config is optional). */
@Mixin(MultiPlayerGameMode.class)
public interface MultiPlayerGameModeInvoker {

    @Invoker("startPrediction")
    void killer560smod$invokeStartPrediction(ClientLevel level, PredictiveAction action);
}
