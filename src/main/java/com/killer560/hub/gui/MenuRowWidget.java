package com.killer560.hub.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** A fully custom-rendered clickable row, used for both the main category sidebar (flat text, amber
 *  underline when selected - matching Skyblocker's own reference menu) and {@code FolderTab}'s
 *  accordion section headers (a bordered amber/black box instead of vanilla's grey button texture,
 *  since vanilla {@link net.minecraft.client.gui.components.Button} doesn't expose a way to recolor
 *  its own 9-slice texture). Part of the black+amber GUI overhaul (2026-09-07), per killer560's "focusing
 *  more on black with orange accents." */
public final class MenuRowWidget extends AbstractWidget {

    static final int ACCENT = 0xFFCC6600;
    private static final int ACCENT_DIM = 0xFF553311;
    private static final int TEXT_DIM = 0xFFAAAAAA;
    private static final int TEXT_HOVER = 0xFFDDDDDD;
    private static final int BOX_BG = 0xFF1A1108;
    private static final int BOX_BG_HOVER = 0xFF2A1B0D;

    /** Idle (not selected/hovered) text colour for a cheat-only row - a dimmer red, mirroring TEXT_DIM vs ACCENT. */
    private static final int CHEAT_TEXT_DIM = 0xFFCC4444;

    private final String label;
    private final boolean selected;
    private final boolean boxed;
    private final boolean cheatOnly;
    private final Runnable onClick;

    public MenuRowWidget(int x, int y, int width, int height, String label, boolean selected, boolean boxed, Runnable onClick) {
        this(x, y, width, height, label, selected, boxed, false, onClick);
    }

    /** @param cheatOnly draw the label as a bold red title (see {@link SectionHeaders}) - for tabs that only exist in
     *  the cheat jar. The widget's message stays the plain label, so search/tooltips/narration are unaffected. */
    public MenuRowWidget(int x, int y, int width, int height, String label, boolean selected, boolean boxed, boolean cheatOnly,
                         Runnable onClick) {
        super(x, y, width, height, Component.literal(label));
        this.label = label;
        this.selected = selected;
        this.boxed = boxed;
        this.cheatOnly = cheatOnly;
        this.onClick = onClick;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int x0 = getX();
        int y0 = getY();
        int textX = x0;
        if (boxed) {
            graphics.fill(x0, y0, x0 + getWidth(), y0 + getHeight(), isHovered ? BOX_BG_HOVER : BOX_BG);
            graphics.outline(x0, y0, getWidth(), getHeight(), selected ? ACCENT : ACCENT_DIM);
            textX = x0 + 6;
        } else if (selected) {
            graphics.fill(x0, y0 + getHeight() - 1, x0 + getWidth(), y0 + getHeight(), ACCENT);
        }
        int textY = y0 + (getHeight() - 8) / 2;
        if (cheatOnly) {
            int cheatColor = (selected || isHovered) ? SectionHeaders.color(true) : CHEAT_TEXT_DIM;
            graphics.text(Minecraft.getInstance().font, Component.literal(label).withStyle(ChatFormatting.BOLD),
                    textX, textY, cheatColor, false);
            return;
        }
        int textColor = selected ? ACCENT : (isHovered ? TEXT_HOVER : TEXT_DIM);
        graphics.text(Minecraft.getInstance().font, label, textX, textY, textColor, false);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        onClick.run();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
    }
}
