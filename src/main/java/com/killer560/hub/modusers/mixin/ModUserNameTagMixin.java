package com.killer560.hub.modusers.mixin;

import com.killer560.hub.modusers.ModUserConfig;
import com.killer560.hub.modusers.ModUsers;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mod User Tag (killer560, 2026-10-19): a coloured diamond before the name of a player who shows as a mod user, when you chose
 * to see them. Legit is green, cheat is red. Only the name tag changes; nothing else is drawn or sent.
 * {@code EntityRenderer.getNameTag(Entity)} is the same hook the supporter names use.
 */
@Mixin(EntityRenderer.class)
public abstract class ModUserNameTagMixin {

    @Inject(method = "getNameTag(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/network/chat/Component;",
            at = @At("RETURN"), cancellable = true, require = 0)
    private void killer560smod$modUserTag(Entity entity, CallbackInfoReturnable<Component> cir) {
        if (!(entity instanceof Player) || !ModUserConfig.getInstance().isSeeOthers()) {
            return;
        }
        String name = entity.getName().getString();
        Minecraft client = Minecraft.getInstance();
        if (client.player != null && name.equals(client.player.getGameProfile().name())) {
            return;
        }
        String variant = ModUsers.variantOf(name);
        if (variant == null) {
            return;
        }
        ChatFormatting colour = "cheat".equals(variant) ? ChatFormatting.RED : ChatFormatting.GREEN;
        Component original = cir.getReturnValue() != null ? cir.getReturnValue() : entity.getDisplayName();
        cir.setReturnValue(Component.literal("◆ ").withStyle(colour).append(original));
    }
}
