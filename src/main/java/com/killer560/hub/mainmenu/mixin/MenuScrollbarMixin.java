package com.killer560.hub.mainmenu.mixin;

import com.killer560.hub.mainmenu.MainMenuTheme;
import com.killer560.hub.mainmenu.MenuChrome;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Scrollbars of every AbstractScrollArea (selection lists, ScrollableLayout containers, multi-line text
 * areas). 26.1.2 {@code AbstractScrollArea.extractScrollbar(G, int mouseX, int mouseY)} (javap) blits
 * scrollbarSettings.backgroundSprite + scrollerSprite (widget/scroller_background, widget/scroller), or the
 * disabled sprite when not scrollable and one is set, then requests a cursor when over the bar. This replaces
 * it 1:1 - same geometry (scrollBarX/scrollBarY/scrollbarWidth/scrollerHeight, all virtual so subclass
 * overrides still apply) and the same cursor requests - with a dark track and an orange thumb (light while
 * hovered / dragged). Only for the default sprites: a scroll area built with custom ScrollbarSettings sprites
 * keeps them.
 */
@Mixin(AbstractScrollArea.class)
public abstract class MenuScrollbarMixin {

    @Shadow
    @Final
    private static Identifier SCROLLER_SPRITE;

    @Shadow
    @Final
    private static Identifier SCROLLER_BACKGROUND_SPRITE;

    @Shadow
    @Final
    private AbstractScrollArea.ScrollbarSettings scrollbarSettings;

    @Shadow
    private boolean scrolling;

    @Shadow
    protected abstract int scrollBarX();

    @Shadow
    protected abstract int scrollerHeight();

    @Shadow
    public abstract int scrollBarY();

    @Shadow
    protected abstract boolean scrollable();

    @Shadow
    public abstract int scrollbarWidth();

    @Shadow
    protected abstract boolean isOverScrollbar(double mouseX, double mouseY);

    @Inject(method = "extractScrollbar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$themedScrollbar(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
        AbstractScrollArea.ScrollbarSettings settings = scrollbarSettings;
        if (settings == null || settings.scrollerSprite() != SCROLLER_SPRITE
                || settings.backgroundSprite() != SCROLLER_BACKGROUND_SPRITE
                || !MainMenuTheme.activeOnMenus()) {
            return;
        }
        try {
            AbstractScrollArea self = (AbstractScrollArea) (Object) this;
            int x = scrollBarX();
            int thumbH = scrollerHeight();
            int thumbY = scrollBarY();
            int w = scrollbarWidth();
            boolean canScroll = scrollable();
            if (!canScroll && settings.disabledScrollerSprite() != null) {
                MenuChrome.drawScrollTrack(graphics, x, self.getY(), w, self.getHeight());
                MenuChrome.drawScrollThumb(graphics, x, self.getY(), w, thumbH, false, true);
                if (isOverScrollbar(mouseX, mouseY)) {
                    graphics.requestCursor(CursorTypes.NOT_ALLOWED);
                }
            }
            if (canScroll) {
                boolean over = isOverScrollbar(mouseX, mouseY);
                MenuChrome.drawScrollTrack(graphics, x, self.getY(), w, self.getHeight());
                MenuChrome.drawScrollThumb(graphics, x, thumbY, w, thumbH, over || scrolling, false);
                if (over) {
                    graphics.requestCursor(scrolling ? CursorTypes.RESIZE_NS : CursorTypes.POINTING_HAND);
                }
            }
            ci.cancel();
        } catch (Throwable t) {
            MainMenuTheme.fail("scrollbar", t);
        }
    }
}
