package com.killer560.hub.supporters.mixin;

import com.killer560.hub.supporters.SupportersFeature;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Nametag half of killer560's item 8.5 ("supporter custom IGNs"). {@code getNameTag(Entity)} is the single
 * chokepoint every entity renderer calls to get the Component painted above its head - verified with javap
 * against the 26.1.2 merged jar: declared once on the {@code EntityRenderer} base
 * ({@code (Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/network/chat/Component;}) and NOT overridden by
 * {@code AvatarRenderer} (26.1.2's player renderer), so one hook here covers every player entity without
 * needing a player-specific mixin. Keyed on the entity's own account UUID (never on the name text it would
 * otherwise show), so a renamed or impersonating account can never pick up someone else's supporter cosmetic.
 * {@code require = 0}: a wrong target here only leaves nametags showing real IGNs, never a crash.
 */
@Mixin(EntityRenderer.class)
public abstract class SupportersNameTagMixin {

    @Inject(method = "getNameTag(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/network/chat/Component;",
            at = @At("RETURN"), cancellable = true, require = 0)
    private void killer560smod$supportersNameTag(Entity entity, CallbackInfoReturnable<Component> cir) {
        if (!(entity instanceof Player)) {
            return;
        }
        Component replaced = SupportersFeature.displayNameFor(entity.getUUID());
        if (replaced != null) {
            cir.setReturnValue(replaced);
        }
    }
}
