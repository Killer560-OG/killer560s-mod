package com.killer560.hub.intro;

import com.killer560.hub.gui.MenuStyle;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * A button of the tour, drawn in the speech bubble's own look (killer560, 2026-11-01: the bottom buttons "don't fit the
 * bubble"): a rounded box with the bubble's accent border and card gradient, the text in the bubble's font and color.
 * Kinds: PLAIN, PRIMARY (accent fill: Next, and the suggested answer of a choice) and CHOSEN (the answer picked: accent
 * fill with a brighter border). An inactive one (Next before a choice) is drawn dim and takes no click.
 */
final class IntroButton extends AbstractWidget {

    enum Kind { PLAIN, PRIMARY, CHOSEN }

    private final Runnable action;
    private final Kind kind;

    IntroButton(int x, int y, int w, int h, String label, Kind kind, Runnable action) {
        super(x, y, w, h, Component.literal(label));
        this.kind = kind;
        this.action = action;
    }

    Kind kind() {
        return kind;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (action != null) {
            action.run();
        }
    }

    /** Presses it as a click would (keyboard Enter and the testkit's direct path). */
    void press() {
        if (active && action != null) {
            action.run();
        }
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        boolean hover = active && isHovered();
        int[] fill = SpeechBubble.fill();
        int top = fill[0];
        int bottom = fill[1];
        int border = MenuStyle.OR;
        int text = MenuStyle.TXT;
        boolean accentFill = kind != Kind.PLAIN;
        if (accentFill) {
            top = MenuStyle.OR_LIGHT;
            bottom = MenuStyle.OR;
            text = MenuStyle.luminance(MenuStyle.OR) > 0.55f ? 0xFF160C02 : 0xFFFFFFFF;
            border = kind == Kind.CHOSEN ? 0xFFFFFFFF : MenuStyle.OR_DARK;
        }
        if (hover) {
            top = MenuStyle.mix(top, 0xFFFFFFFF, 0.12f);
            bottom = MenuStyle.mix(bottom, 0xFFFFFFFF, 0.12f);
        }
        if (!active) {
            top = MenuStyle.alpha(top, 0x70);
            bottom = MenuStyle.alpha(bottom, 0x70);
            border = MenuStyle.alpha(border, 0x60);
            text = MenuStyle.DIM;
        }
        float radius = getHeight() / 2f;
        SpeechBubble.roundBox(g, getX(), getY(), getWidth(), getHeight(), radius, top, bottom, border,
                kind == Kind.CHOSEN ? 1.5f : 1.0f, true);
        String label = getMessage().getString();
        var font = MenuStyle.font();
        int tx = getX() + (getWidth() - font.width(label)) / 2;
        int ty = getY() + (getHeight() - 8) / 2 + 1;
        g.text(font, label, tx, ty, text, MenuStyle.TEXT_SHADOW && !accentFill && active);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
    }
}
