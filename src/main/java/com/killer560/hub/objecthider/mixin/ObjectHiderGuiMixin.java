package com.killer560.hub.objecthider.mixin;

import com.killer560.hub.objecthider.ObjectHiderConfig;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * QUOI {@code PlayerDisplay.kt} "Hide" dropdown (Health / Absorption / Mount health / Regeneration bounce /
 * Armour / Hunger), all six OFF by default - {@link ObjectHiderConfig}. QUOI's own hooks target an older
 * {@code Gui.renderHearts}/{@code renderArmor}/{@code renderFood}/{@code renderVehicleHealth} render pass
 * (mixins/GuiMixin.java:29-99); javap-verified against THIS 26.1.2 merged jar, {@code Gui} no longer renders
 * directly - it only EXTRACTS a render state ({@code extractHearts}/{@code extractArmor}/{@code extractFood}/
 * {@code extractVehicleHealth}, all still present, all still {@code private}), which a separate submission
 * pass turns into draw calls later. Cancelling the extract call is equivalent to QUOI's render-cancel: nothing
 * is added to the {@link GuiGraphicsExtractor} for that bar, so nothing is submitted or drawn - purely visual,
 * this never touches health/armor/food values or anything sent to the server.
 * <p>
 * Absorption and the regen "bounce" have no dedicated extract method to cancel (they're read inline inside
 * {@code extractPlayerHealth}, which also computes normal hearts, air and the heart-blink timer - cancelling
 * it outright would be far too broad). javap-verified {@code extractPlayerHealth} calls
 * {@code Player.getAbsorptionAmount()} once and {@code Player.hasEffect(MobEffects.REGENERATION)} once, so
 * both are redirected instead - same technique QUOI itself uses for Absorption
 * ({@code @Redirect Player.getAbsorptionAmount() -> 0.0F}, {@code mixins/GuiMixin.java:29-41}); the
 * Regeneration-bounce redirect is this port's equivalent of QUOI's {@code @ModifyExpressionValue} (this repo
 * has no MixinExtras dependency, so plain {@code @Redirect} on the single {@code hasEffect} call site does
 * the same job).
 */
@Mixin(Gui.class)
public abstract class ObjectHiderGuiMixin {

    @Inject(method = "extractHearts", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$objectHider$extractHearts(CallbackInfo ci) {
        if (ObjectHiderConfig.getInstance().isHideHealthBar()) {
            ci.cancel();
        }
    }

    @Inject(method = "extractArmor", at = @At("HEAD"), cancellable = true, require = 0)
    private static void killer560smod$objectHider$extractArmor(CallbackInfo ci) {
        if (ObjectHiderConfig.getInstance().isHideArmorBar()) {
            ci.cancel();
        }
    }

    @Inject(method = "extractFood", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$objectHider$extractFood(CallbackInfo ci) {
        if (ObjectHiderConfig.getInstance().isHideHungerBar()) {
            ci.cancel();
        }
    }

    @Inject(method = "extractVehicleHealth", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$objectHider$extractVehicleHealth(CallbackInfo ci) {
        if (ObjectHiderConfig.getInstance().isHideMountHealthBar()) {
            ci.cancel();
        }
    }

    @Redirect(method = "extractPlayerHealth",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;getAbsorptionAmount()F"),
            require = 0)
    private float killer560smod$objectHider$absorption(Player player) {
        return ObjectHiderConfig.getInstance().isHideAbsorptionHearts() ? 0.0F : player.getAbsorptionAmount();
    }

    @Redirect(method = "extractPlayerHealth",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;hasEffect(Lnet/minecraft/core/Holder;)Z"),
            require = 0)
    private boolean killer560smod$objectHider$regenBounce(Player player, Holder<MobEffect> effect) {
        if (ObjectHiderConfig.getInstance().isHideRegenBounce()) {
            return false;
        }
        return player.hasEffect(effect);
    }
}
