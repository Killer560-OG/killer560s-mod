package com.killer560.hub.supporters.mixin;

import com.killer560.hub.supporters.SupportersFeature;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tab-list half of item 8.5. {@code getNameForDisplay(PlayerInfo)} builds the name column shown for one
 * tab-list row - verified with javap:
 * {@code (Lnet/minecraft/client/multiplayer/PlayerInfo;)Lnet/minecraft/network/chat/Component;}.
 * {@code PlayerInfo.getProfile().id()} is the real account UUID from Hypixel's own login handshake, so this
 * matches the exact same way the nametag hook does - never by the text the tab list would otherwise show.
 * {@code require = 0}: a wrong target here only leaves the tab list showing real IGNs, never a crash.
 */
@Mixin(PlayerTabOverlay.class)
public abstract class SupportersTabListMixin {

    @Inject(method = "getNameForDisplay(Lnet/minecraft/client/multiplayer/PlayerInfo;)"
            + "Lnet/minecraft/network/chat/Component;", at = @At("RETURN"), cancellable = true, require = 0)
    private void killer560smod$supportersTabName(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
        if (info == null || info.getProfile() == null) {
            return;
        }
        Component replaced = SupportersFeature.displayNameFor(info.getProfile().id());
        if (replaced != null) {
            cir.setReturnValue(replaced);
        }
    }
}
