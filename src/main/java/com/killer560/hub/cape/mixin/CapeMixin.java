package com.killer560.hub.cape.mixin;

import com.killer560.hub.cape.CapeConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Wears the custom cape on your own player: after the avatar renderer has read the player's skin, its cape texture is replaced
 * by the chosen built-in design. Checked with javap on 26.1.2 and 26.2: {@code extractRenderState(Avatar, AvatarRenderState, float)}
 * is the same descriptor on both, and {@code PlayerSkin} is a record with the same five components.
 */
@Mixin(AvatarRenderer.class)
public abstract class CapeMixin {

    @Inject(method = "extractRenderState", at = @At("RETURN"), require = 0)
    private void killer560smod$customCape(Avatar entity, AvatarRenderState state, float partialTick, CallbackInfo ci) {
        CapeConfig cfg = CapeConfig.getInstance();
        if (!cfg.hasCape()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !entity.getUUID().equals(client.player.getUUID())) {
            return;
        }
        PlayerSkin skin = state.skin;
        String name = cfg.getSelected();
        Identifier texture = cfg.isCustom() ? com.killer560.hub.cape.CustomCapeTexture.ensure(cfg)
                : Identifier.fromNamespaceAndPath("killer560smod", "textures/cape/" + name + ".png");
        Identifier asset = Identifier.fromNamespaceAndPath("killer560smod", "cape/" + name);
        state.skin = new PlayerSkin(skin.body(), new ClientAsset.ResourceTexture(asset, texture), skin.elytra(),
                skin.model(), skin.secure());
        state.showCape = true;
    }
}
