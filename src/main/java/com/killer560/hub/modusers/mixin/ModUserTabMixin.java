package com.killer560.hub.modusers.mixin;

import com.killer560.hub.modusers.ModUserTabTag;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mod User Tag in the tab list (killer560, 2026-10-19). javap on the mapped 26.1.2 and 26.2 jars:
 * {@code public Component getNameForDisplay(PlayerInfo)}, descriptor
 * {@code (Lnet/minecraft/client/multiplayer/PlayerInfo;)Lnet/minecraft/network/chat/Component;}, called once per row from
 * {@code extractRenderState}, whose result becomes the {@code ScoreDisplayEntry.name} that the row draws. The matching
 * (Hypixel's fake {@code !A-a} rows carry the real name only as text) is in {@link ModUserTabTag}.
 */
@Mixin(PlayerTabOverlay.class)
public abstract class ModUserTabMixin {

    @Inject(method = "getNameForDisplay(Lnet/minecraft/client/multiplayer/PlayerInfo;)Lnet/minecraft/network/chat/Component;",
            at = @At("RETURN"), cancellable = true, require = 0)
    private void killer560smod$modUserTab(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
        Component tagged = ModUserTabTag.apply(info, cir.getReturnValue());
        if (tagged != null) {
            cir.setReturnValue(tagged);
        }
    }
}
