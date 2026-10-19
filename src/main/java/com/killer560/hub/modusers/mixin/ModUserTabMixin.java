package com.killer560.hub.modusers.mixin;

import com.killer560.hub.modusers.ModUserConfig;
import com.killer560.hub.modusers.ModUsers;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mod User Tag in the tab list (killer560, 2026-10-19): the same diamond as the name tag, in front of whatever name the row
 * shows, a custom name included. It is keyed on the account's real name, so a custom name never hides it. Your own row shows
 * it only while you show yourself as a mod user. {@code PlayerTabOverlay.getNameForDisplay(PlayerInfo)} is public on 26.1.2
 * and 26.2 (checked with javap).
 */
@Mixin(PlayerTabOverlay.class)
public abstract class ModUserTabMixin {

    @Inject(method = "getNameForDisplay(Lnet/minecraft/client/multiplayer/PlayerInfo;)Lnet/minecraft/network/chat/Component;",
            at = @At("RETURN"), cancellable = true, require = 0)
    private void killer560smod$modUserTab(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
        if (info == null || info.getProfile() == null || cir.getReturnValue() == null) {
            return;
        }
        ModUserConfig cfg = ModUserConfig.getInstance();
        String name = info.getProfile().name();
        Minecraft client = Minecraft.getInstance();
        String variant;
        if (client.player != null && name.equals(client.player.getGameProfile().name())) {
            if (!cfg.isShare()) {
                return;
            }
            variant = cfg.getShareAs();
        } else {
            if (!cfg.isSeeOthers()) {
                return;
            }
            variant = ModUsers.variantOf(name);
        }
        if (variant == null) {
            return;
        }
        ChatFormatting colour = "cheat".equals(variant) ? ChatFormatting.RED : ChatFormatting.GREEN;
        cir.setReturnValue(Component.literal("\u25C6 ").withStyle(colour).append(cir.getReturnValue()));
    }
}
