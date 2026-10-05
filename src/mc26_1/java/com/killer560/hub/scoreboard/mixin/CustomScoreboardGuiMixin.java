package com.killer560.hub.scoreboard.mixin;

import com.killer560.hub.playerstats.PlayerStatsFeature;
import com.killer560.hub.scoreboard.CustomScoreboardFeature;
import com.killer560.hub.scoreboard.ScoreboardData;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Custom Scoreboard hooks on {@link Gui} (26.1.2 names/descriptors verified with javap):
 * <ul>
 * <li>{@code displayScoreboardSidebar(GuiGraphicsExtractor, Objective)V} - cancelled while the Custom Scoreboard
 * replaces the vanilla sidebar (SkyHanni's {@code isHideVanillaScoreboardEnabled});</li>
 * <li>{@code setOverlayMessage(Component, boolean)V} - the action bar text, read by the Dungeons event.</li>
 * </ul>
 * {@code require = 0} so a mapping change only disables the hook instead of crashing the game.
 *
 * <p><b>This mixin has one copy per Minecraft version</b>, in {@code src/mc26_1/java} and
 * {@code src/mc26_2/java}, because the class it targets is not the same class on both - and a
 * {@code @Mixin} target is an annotation constant, so it cannot come from the {@code compat} facade the
 * rest of the port uses. Only one is ever compiled. <b>A change to one belongs in the other</b>, exactly as
 * for {@code compat/McCompat}: the mixin configs use {@code defaultRequire: 0}, so a copy left behind fails
 * SILENTLY and the feature simply stops running with nothing in the log.
 */
@Mixin(Gui.class)
public abstract class CustomScoreboardGuiMixin {

    @Inject(method = "displayScoreboardSidebar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/scores/Objective;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$hideVanillaSidebar(GuiGraphicsExtractor graphics, Objective objective, CallbackInfo ci) {
        if (CustomScoreboardFeature.shouldHideVanilla()) {
            ci.cancel();
        }
    }

    /** Reads the action bar for the scoreboard, then lets Stat Bars take Hypixel's stat segments out of it.
     *  The strip lives here rather than in Stat Bars' MODIFY_GAME listener because Fabric hands a modified
     *  line on to every later reader (see PlayerStatsFeature#onModifyGameMessage). A stripped line is sent
     *  again through this same method with PlayerStatsFeature's re-entry guard up, so the vanilla overlay
     *  timer and colour animation behave as for any other line, and the scoreboard does not read it twice. */
    @Inject(method = "setOverlayMessage(Lnet/minecraft/network/chat/Component;Z)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$captureActionBar(Component message, boolean animateColor, CallbackInfo ci) {
        if (PlayerStatsFeature.isResending()) {
            return;
        }
        if (CustomScoreboardFeature.isActive()) {
            ScoreboardData.onActionBar(message);
        }
        Component replacement = PlayerStatsFeature.actionBarReplacement(message);
        if (replacement == message) {
            return;
        }
        ci.cancel();
        if (replacement != null) {
            PlayerStatsFeature.resend(() -> ((Gui) (Object) this).setOverlayMessage(replacement, animateColor));
        }
    }
}
