package com.killer560.hub.notify.mixin;

import com.killer560.hub.notify.ModOverlayMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Draws {@link ModOverlayMessage}. Per killer560's request (2026-09-07): moved from vanilla's own
 *  action-bar-message spot (just above the hotbar) to the vertical center of the screen, and now
 *  word-wraps instead of running off-screen as one long line - longer messages (like the autonomous
 *  run's "finished: <reason>" text) read a lot better as a few centered lines than one wide one.
 *  <p>
 *  <b>Theme color (2026-09-14):</b> killer560's explicit request - "any mod generated text should
 *  follow the same color pattern", the same {@code 0xFFCC6600} orange used everywhere else in this
 *  mod's own UI (ModScreen's title, every settings widget's hover/accent border, Leap Order's title,
 *  Posmsg/Ability Timer's default marker color). Individual call sites across the mod had accumulated
 *  their own ad-hoc {@code §a}/{@code §c}/{@code §b}/etc. color codes over many separate features built
 *  across sessions - rather than hunt down and edit every single call site, this strips any legacy
 *  formatting codes a caller embedded and forces the theme color here, in the one place that actually
 *  draws the popup, so every feature's text is consistent by construction from now on. */
@Mixin(Gui.class)
public abstract class GuiMixin {

    private static final int THEME_ORANGE = 0xFFCC6600;

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void killer560smod$drawOverlayMessage(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        String message = ModOverlayMessage.current();
        if (message == null) {
            return;
        }
        String plain = ChatFormatting.stripFormatting(message);
        if (plain == null) {
            plain = message;
        }
        Font font = Minecraft.getInstance().font;
        int wrapWidth = graphics.guiWidth() * 2 / 3;
        List<FormattedCharSequence> lines = font.split(Component.literal(plain), wrapWidth);
        int totalHeight = lines.size() * font.lineHeight;
        int centerX = graphics.guiWidth() / 2;
        int startY = (graphics.guiHeight() - totalHeight) / 2;
        for (int i = 0; i < lines.size(); i++) {
            graphics.centeredText(font, lines.get(i), centerX, startY + i * font.lineHeight, THEME_ORANGE);
        }
    }
}
