package com.killer560.hub.gui;

import com.killer560.hub.hud.GuiRects;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * A text box in the menu theme for {@link MenuThemedScreen}s: a recessed well (accent ring when focused or hovered) with
 * the text in the theme's color. Vanilla's bordered box draws a fixed dark sprite and a shadowed hint, unreadable on
 * the Light theme (docs/LESSONS-GUI.md, the Profiles name box), so this is an unbordered {@link EditBox} placed inside
 * the outer box it was asked for, painting the well and its own hint, and taking clicks anywhere on the outer box
 * ({@code AbstractWidget.mouseClicked} asks {@link #isMouseOver}, javap 26.1.2). Its getters still report the inner
 * text line; {@link #outer()} is the box as drawn.
 */
public class ThemedEditBox extends EditBox {

    private int ox;
    private int oy;
    private final int ow;
    private final int oh;
    private String themedHint = "";
    private boolean brightHint;
    /** Alpha of the well's fill, 0 to 255; 255 (the default) draws the theme's well exactly as before. */
    private int wellAlpha = 255;

    public ThemedEditBox(Font font, int x, int y, int w, int h, Component narration) {
        super(font, x + 5, y + (h - 8) / 2, Math.max(8, w - 10), 9, narration);
        this.ox = x;
        this.oy = y;
        this.ow = w;
        this.oh = h;
        setBordered(false);
    }

    /** The hint shown while the box is empty and not focused (drawn flat, in the theme's dim color). */
    public ThemedEditBox hint(String hint) {
        this.themedHint = hint == null ? "" : hint;
        return this;
    }

    /** Draws the hint in the text color instead of the dim one (killer560's proxy fields: "make it all white"). */
    public ThemedEditBox brightHint() {
        this.brightHint = true;
        return this;
    }

    /** The well's background alpha (0 to 255), for a screen that lets the box be see-through. The text stays opaque. */
    public ThemedEditBox wellAlpha(int alpha) {
        this.wellAlpha = Math.max(0, Math.min(255, alpha));
        return this;
    }

    /** {x, y, w, h} of the box as drawn. */
    public int[] outer() {
        return new int[]{ox, oy, ow, oh};
    }

    public String themedHint() {
        return themedHint;
    }

    /** Moves the drawn box (and the text line inside it) so its left edge is at {@code x} (screen sweep C: the
     *  scoreboard editor lays its rows out after building them). */
    @Override
    public void setX(int x) {
        this.ox = x;
        super.setX(x + 5);
    }

    /** Moves the drawn box (and the text line inside it) so its top is at {@code y}. */
    @Override
    public void setY(int y) {
        this.oy = y;
        super.setY(y + (oh - 8) / 2);
    }

    @Override
    public boolean isMouseOver(double mx, double my) {
        return isVisible() && mx >= ox && mx < ox + ow && my >= oy && my < oy + oh;
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        if (!isVisible()) {
            return;
        }
        MenuStyle.use();
        setTextColor(MenuStyle.TXT);
        setTextShadow(MenuStyle.TEXT_SHADOW);
        boolean hover = mouseX >= ox && mouseX < ox + ow && mouseY >= oy && mouseY < oy + oh;
        GuiRects r = GuiRects.begin(g);
        int fill = wellAlpha >= 255 ? MenuStyle.WELL_DEEP : (MenuStyle.WELL_DEEP & 0x00FFFFFF) | (wellAlpha << 24);
        MenuStyle.well(r, ox, oy, ow, oh, fill);
        if (isFocused() || hover) {
            r.outline(ox - 1, oy - 1, ow + 2, oh + 2, isFocused() ? MenuStyle.OR2 : MenuStyle.KEY_CHIP_EDGE);
        }
        r.submit();
        if (getValue().isEmpty() && !isFocused() && !themedHint.isEmpty()) {
            MenuStyle.flat(g, MenuStyle.ellipsize(themedHint, ow - 10), getX(), getY(),
                    brightHint ? MenuStyle.TXT_SOFT : MenuStyle.DIM);
        }
        super.extractWidgetRenderState(g, mouseX, mouseY, partialTick);
    }
}
