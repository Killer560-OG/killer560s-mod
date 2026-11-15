package com.killer560.hub.zpew.mixin;

import com.killer560.hub.zpew.ZeroPingEtherwarp;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Warps Per Tick (cheat build): when {@code public InteractionResult useItem(Player, InteractionHand)} returns (javap,
 * 26.2 and 26.3: same signature), its use packet has gone with its own sequence number, so the extra warps it owes go
 * now - after it, with higher sequence numbers (GrimAC BadPacketsH wants them rising), still before this tick's movement
 * packet. A use inside his right click ({@code startUseItem}) leaves them to {@link ZpewStartUseMixin}, at the click's end.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class ZpewUseItemMixin {

    @Inject(method = "useItem", at = @At("RETURN"))
    private void killer560smod$zpewExtraWarps(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && !ZeroPingEtherwarp.inClick) ZeroPingEtherwarp.flushExtraWarps();
    }
}
