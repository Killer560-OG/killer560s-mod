package com.killer560.hub.autoclosechest.mixin;

import com.killer560.hub.autoclosechest.AutoCloseChestFeature;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Intercepts the real screen-opening packet at the HEAD of the one real vanilla method that handles it,
 *  before the game ever sets the screen - see {@link AutoCloseChestFeature}'s own class doc for why this
 *  has to happen here (a flicker-free auto-close) rather than opening the screen and closing it a tick
 *  later. */
@Mixin(ClientPacketListener.class)
public abstract class AutoCloseChestMixin {

    @Inject(method = "handleOpenScreen", at = @At("HEAD"), cancellable = true)
    private void killer560smod$autoCloseSecretChest(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        try {
            if (AutoCloseChestFeature.shouldAutoClose(packet)) {
                AutoCloseChestFeature.autoClose(packet);
                ci.cancel();
            }
        } catch (RuntimeException e) {
            // An exception out of a clientbound packet hook does not just log: ClientCommonPacketListenerImpl
            // .onPacketError disconnects the client with disconnect.packetError ("Network Protocol Error").
            // Fail closed to a log line and let the screen open normally.
            LoggerFactory.getLogger("killer560smod-autoclosechest")
                    .error("[AutoCloseChest] openScreen packet hook threw", e);
        }
    }
}
