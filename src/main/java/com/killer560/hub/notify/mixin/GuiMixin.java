package com.killer560.hub.notify.mixin;

import com.killer560.hub.notify.ModOverlayMessage;
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
 *  run's "finished: <reason>" text) read a lot better as a few centered lines than one wide one. */
@Mixin(Gui.class)
public abstract class GuiMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void killer560smod$drawOverlayMessage(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        String message = ModOverlayMessage.current();
        if (message == null) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        int wrapWidth = graphics.guiWidth() * 2 / 3;
        List<FormattedCharSequence> lines = font.split(Component.literal(message), wrapWidth);
        int totalHeight = lines.size() * font.lineHeight;
        int centerX = graphics.guiWidth() / 2;
        int startY = (graphics.guiHeight() - totalHeight) / 2;
        for (int i = 0; i < lines.size(); i++) {
            graphics.centeredText(font, lines.get(i), centerX, startY + i * font.lineHeight, 0xFFFFFFFF);
        }
    }
}
