package com.killer560.hub.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Drop-in replacement for vanilla {@link net.minecraft.client.gui.components.Button}, matching its
 *  builder API shape exactly (same method names/chaining, a {@code Component}+{@code OnPress}
 *  constructor argument pair, {@code .bounds(x,y,w,h).build()}) so every existing
 *  {@code Button.builder(...)} call site across the mod's tab files converts to
 *  {@code SettingsButtonWidget.builder(...)} with just the class name changed - no other code needed to
 *  change, since {@code btn -> { ...; btn.setMessage(...); }} lambdas keep working unchanged (this class
 *  also extends {@link AbstractWidget} and exposes the same inherited {@code getMessage()}/
 *  {@code setMessage(Component)}) and embedded legacy "§" color codes in a label still render correctly
 *  via the {@code Component} text-draw overload, exactly like vanilla {@code Button} already did.
 *  <p>
 *  Built (2026-09-07), part 2 of the black+amber GUI overhaul: vanilla {@code Button} has no way to
 *  recolor its own fixed 9-slice texture, so once the menu chrome (phase 1) was reworked, every actual
 *  settings control still rendered as a plain grey vanilla button, clashing with the new theme - per
 *  killer560's "make those boxes a different color, something more fitting." Deliberately styled with a
 *  DIMMER border than {@link MenuRowWidget}'s accordion headers (full amber, always) so headers still
 *  visually lead over individual controls instead of every box in the menu competing at once.
 *  <p>
 *  Extended (2026-09-27) per killer560's "for the sort buttons or any button that toggles through a
 *  bunch of options, make it so if i right click then it goes back one" - a second, optional
 *  {@code OnPress} set via {@code .secondaryPress(...)} fires on right-click instead of left. Left-click
 *  is untouched: {@code onClick(...)} below is still only ever invoked by vanilla's own left-button-only
 *  {@code mouseClicked}, so every existing call site with no {@code secondaryPress} keeps behaving
 *  exactly as before (right-click on it simply does nothing, same as pre-2026-09-27). Only call sites
 *  that opt in with {@code .secondaryPress(...)} - the enum-cycling buttons - respond to right-click. */
public final class SettingsButtonWidget extends AbstractWidget {

    private static final int BORDER = 0xFF663D1A;
    private static final int BORDER_HOVER = 0xFFCC6600;
    private static final int BG = 0xFF1A1A1A;
    private static final int BG_HOVER = 0xFF262626;
    private static final int TEXT = 0xFFFFFFFF;

    public interface OnPress {
        void onPress(SettingsButtonWidget button);
    }

    private final OnPress onPress;
    private final OnPress onSecondaryPress;

    private SettingsButtonWidget(Component message, OnPress onPress, OnPress onSecondaryPress,
            int x, int y, int width, int height) {
        super(x, y, width, height, message);
        this.onPress = onPress;
        this.onSecondaryPress = onSecondaryPress;
    }

    public static Builder builder(Component message, OnPress onPress) {
        return new Builder(message, onPress);
    }

    public static final class Builder {
        private final Component message;
        private final OnPress onPress;
        private OnPress onSecondaryPress;
        private int x;
        private int y;
        private int width = 150;
        private int height = 20;

        private Builder(Component message, OnPress onPress) {
            this.message = message;
            this.onPress = onPress;
        }

        public Builder bounds(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            return this;
        }

        /** Right-click handler - used by cycling/toggle buttons to step backwards instead of forwards.
         *  Left unset (the default) for ordinary buttons, so right-click on them stays a no-op. */
        public Builder secondaryPress(OnPress onSecondaryPress) {
            this.onSecondaryPress = onSecondaryPress;
            return this;
        }

        public SettingsButtonWidget build() {
            return new SettingsButtonWidget(message, onPress, onSecondaryPress, x, y, width, height);
        }
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int x0 = getX();
        int y0 = getY();
        graphics.fill(x0, y0, x0 + getWidth(), y0 + getHeight(), isHovered ? BG_HOVER : BG);
        graphics.outline(x0, y0, getWidth(), getHeight(), isHovered ? BORDER_HOVER : BORDER);
        graphics.centeredText(Minecraft.getInstance().font, getMessage(), x0 + getWidth() / 2, y0 + (getHeight() - 8) / 2, TEXT);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (onPress != null) {
            onPress.onPress(this);
        }
    }

    /** Vanilla's own {@code mouseClicked} only ever calls {@code onClick} above for the left button
     *  (button 0), so left-click behavior here is completely untouched - this only adds a right-click
     *  (button 1) path, and only when a {@code secondaryPress} was actually set, so a plain button with
     *  no cycling behavior can never have right-click do anything. */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (onSecondaryPress != null && active && visible && event.button() == 1
                && isMouseOver(event.x(), event.y())) {
            onSecondaryPress.onPress(this);
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
    }
}
