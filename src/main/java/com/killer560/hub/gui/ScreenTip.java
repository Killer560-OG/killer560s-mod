package com.killer560.hub.gui;

import com.killer560.hub.hud.GuiRects;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/** The menu-styled hover box the mod's own screens draw for a button description (the color picker, the screenshot editor). */
public final class ScreenTip {

    private ScreenTip() {
    }

    /** Draws {@code text} wrapped to 200 px next to the mouse, kept on screen; call after the widgets so it is on top. */
    public static void draw(GuiGraphicsExtractor graphics, Font font, String text, int mouseX, int mouseY, int screenW, int screenH) {
        List<FormattedCharSequence> lines = font.split(Component.literal(text), 200);
        int w = 0;
        for (var line : lines) {
            w = Math.max(w, font.width(line));
        }
        int h = lines.size() * 10 - 2;
        int x = mouseX + 12;
        int y = mouseY + 12;
        if (x + w + 8 > screenW) {
            x = Math.max(4, mouseX - w - 16);
        }
        if (y + h + 8 > screenH) {
            y = Math.max(4, mouseY - h - 16);
        }
        GuiRects r = GuiRects.begin(graphics);
        r.gradient(x - 4, y - 4, x + w + 4, y + h + 4, MenuStyle.TOOLTIP_TOP, MenuStyle.TOOLTIP_BOTTOM);
        r.outline(x - 4, y - 4, w + 8, h + 8, MenuStyle.OR);
        r.submit();
        int ly = y;
        for (var line : lines) {
            MenuStyle.text(graphics, line, x, ly, MenuStyle.TXT);
            ly += 10;
        }
    }
}
