package com.killer560.hub.gui;

import com.killer560.hub.hud.GuiRects;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * A vanilla {@link EditBox} drawn as the menu's recessed well instead of vanilla's grey-bordered sprite, in the active
 * theme's colours (screen sweep, 2026-10-13). Everything else is vanilla's: the box stays BORDERED, so its text sits
 * where vanilla puts it and every click, cursor and selection rule is unchanged (setBordered(false) would move the text
 * to the box's corner - ProxyConfigScreen's note). Only while it draws does {@link #isBordered()} answer false, which is
 * the one test {@code extractWidgetRenderState} makes before blitting the sprite (javap, 26.1.2 and 26.2);
 * {@link #getInnerWidth()} keeps the bordered width so the visible text is cut exactly as before. The hint is painted here
 * in the theme's dim text (vanilla's always has a shadow, which smears on a light theme).
 */
public class WellEditBox extends EditBox {

    private boolean painting;
    /** The hint, painted here: vanilla draws its own hint with a shadow whatever setTextShadow says (LESSONS-GUI). */
    private String hint;

    public WellEditBox(Font font, int x, int y, int width, int height, Component message) {
        super(font, x, y, width, height, message);
    }

    @Override
    public void setHint(Component hint) {
        String plain = hint == null ? null : net.minecraft.ChatFormatting.stripFormatting(hint.getString());
        this.hint = plain == null ? "" : plain;
    }

    @Override
    public boolean isBordered() {
        return !painting && super.isBordered();
    }

    @Override
    public int getInnerWidth() {
        boolean was = painting;
        painting = false;
        try {
            return super.getInnerWidth();
        } finally {
            painting = was;
        }
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        if (!isVisible()) {
            return;
        }
        // MenuStyle already holds the open screen's theme (ToolScreenKit.begin); re-deriving here would reset it.
        if (super.isBordered()) {
            GuiRects r = GuiRects.begin(g);
            MenuStyle.well(r, getX(), getY(), getWidth(), getHeight(), MenuStyle.WELL_DEEP);
            if (isFocused() || isHovered()) {
                r.outline(getX() - 1, getY() - 1, getWidth() + 2, getHeight() + 2,
                        isFocused() ? MenuStyle.OR2 : MenuStyle.KEY_CHIP_EDGE);
            }
            r.submit();
        }
        setTextColor(MenuStyle.TXT);
        setTextShadow(MenuStyle.TEXT_SHADOW);
        painting = true;
        try {
            super.extractWidgetRenderState(g, mouseX, mouseY, partialTick);
        } finally {
            painting = false;
        }
        if (hint != null && !hint.isEmpty() && getValue().isEmpty() && !isFocused()) {
            var font = net.minecraft.client.Minecraft.getInstance().font;
            boolean bordered = super.isBordered();
            int tx = bordered ? getX() + 4 : getX();
            int ty = bordered ? getY() + (getHeight() - 8) / 2 : getY();
            g.text(font, font.plainSubstrByWidth(hint, getInnerWidth()), tx, ty, MenuStyle.DIM2, MenuStyle.TEXT_SHADOW);
        }
    }
}
