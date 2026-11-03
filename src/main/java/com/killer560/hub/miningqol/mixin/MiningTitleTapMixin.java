package com.killer560.hub.miningqol.mixin;

import com.killer560.hub.miningqol.AttackHoldProbe;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * READ-ONLY: titles and subtitles for {@link AttackHoldProbe}'s ring of recent server lines. Targets
 * {@code public void setTitleText(ClientboundSetTitleTextPacket)} and {@code setSubtitleText(ClientboundSetSubtitleTextPacket)}
 * on ClientPacketListener (javap, 26.3 and 26.2; both packets have {@code text()}). At RETURN, so only on the client
 * thread after the handler's thread check. A throw out of a packet hook disconnects him, so everything is caught.
 */
@Mixin(ClientPacketListener.class)
public abstract class MiningTitleTapMixin {

    @Inject(method = "setTitleText", at = @At("RETURN"), require = 0)
    private void killer560smod$miningTitle(ClientboundSetTitleTextPacket packet, CallbackInfo ci) {
        try {
            AttackHoldProbe.heard("title", packet.text());
        } catch (Throwable ignored) {
            // diagnostics only
        }
    }

    @Inject(method = "setSubtitleText", at = @At("RETURN"), require = 0)
    private void killer560smod$miningSubtitle(ClientboundSetSubtitleTextPacket packet, CallbackInfo ci) {
        try {
            AttackHoldProbe.heard("subtitle", packet.text());
        } catch (Throwable ignored) {
            // diagnostics only
        }
    }
}
