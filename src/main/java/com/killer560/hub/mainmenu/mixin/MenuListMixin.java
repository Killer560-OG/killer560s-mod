package com.killer560.hub.mainmenu.mixin;

import com.killer560.hub.mainmenu.MainMenuTheme;
import com.killer560.hub.mainmenu.MenuChrome;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Scrolling lists (26.1.2 AbstractSelectionList - base of ObjectSelectionList / ContainerObjectSelectionList,
 * so the server list, world list, options/keybind/language/pack lists and ModMenu's ModListWidget +
 * DescriptionListWidget). extractWidgetRenderState calls, in order: extractListBackground -> scissor ->
 * extractListItems (-> extractItem -> extractSelection for the selected row, then Entry.extractContent) ->
 * extractListSeparators (themed in MenuTextureMixin) -> extractScrollbar (MenuScrollbarMixin).
 * <p>
 * AbstractSelectionList.Entry is a protected nested class, so no handler here names it (javac would refuse
 * and @Inject handler descriptors must match exactly): entries are handled as {@link LayoutElement}s /
 * Objects via public API.
 * <ul>
 * <li>{@code extractListBackground(G)}: menu_list_background / inworld_menu_list_background blit -> a
 * translucent warm-dark panel. Subclasses overriding it with an empty body (StatsScreen lists,
 * SocialInteractionsPlayerList) never reach this.</li>
 * <li>{@code extractListItems(G, int, int, float)} HEAD (no cancel): remembers the mouse position for the
 * selection colours and draws a faint hover wash under a hovered, non-selected row of a list whose entries
 * can be selected (skipping the server list's LAN "Scanning" header and the world list's loading /
 * no-worlds rows). Inside the list scissor, before any entry content.</li>
 * <li>{@code extractSelection(G, E, int)}: vanilla does fill(entry rect, colourArg) then fill(rect inset 1,
 * 0xFF000000). The two colour args are swapped (ordinal 0 -> orange / light-orange border, ordinal 1 ->
 * opaque warm-dark fill), giving a 1px orange outline without re-implementing the geometry.</li>
 * </ul>
 * ModMenu's ModListWidget draws its selection through its own drawSelectionHighlight (MenuModMenuListMixin).
 */
@Mixin(AbstractSelectionList.class)
public abstract class MenuListMixin {

    @Shadow
    @Final
    protected Minecraft minecraft;

    @Shadow
    protected abstract boolean entriesCanBeSelected();

    @Unique
    private int killer560smod$mouseX = Integer.MIN_VALUE;
    @Unique
    private int killer560smod$mouseY = Integer.MIN_VALUE;

    @Inject(method = "extractListBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void killer560smod$themedListBackground(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (!MainMenuTheme.activeOnMenus()) {
            return;
        }
        try {
            AbstractSelectionList<?> self = (AbstractSelectionList<?>) (Object) this;
            MenuChrome.drawListBackground(graphics, self.getX(), self.getY(), self.getRight(), self.getBottom(),
                    minecraft != null && minecraft.level != null);
            ci.cancel();
        } catch (Throwable t) {
            MainMenuTheme.fail("list background", t);
        }
    }

    @Inject(method = "extractListItems(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", at = @At("HEAD"), require = 0)
    private void killer560smod$themedRowHover(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        killer560smod$mouseX = mouseX;
        killer560smod$mouseY = mouseY;
        if (!MainMenuTheme.activeOnMenus()) {
            return;
        }
        try {
            AbstractSelectionList<?> self = (AbstractSelectionList<?>) (Object) this;
            if (!self.isMouseOver(mouseX, mouseY) || !entriesCanBeSelected()) {
                return;
            }
            Object selected = self.getSelected();
            for (Object child : self.children()) {
                if (!(child instanceof LayoutElement row)) {
                    continue;
                }
                int x = row.getX();
                int y = row.getY();
                int w = row.getWidth();
                int h = row.getHeight();
                if (mouseX < x || mouseX >= x + w || mouseY < y || mouseY >= y + h) {
                    continue;
                }
                if (child != selected
                        && !(child instanceof ServerSelectionList.LANHeader)
                        && !(child instanceof WorldSelectionList.LoadingHeader)
                        && !(child instanceof WorldSelectionList.NoWorldsEntry)) {
                    MenuChrome.drawRowHover(graphics, x, y, w, h);
                }
                return;
            }
        } catch (Throwable t) {
            MainMenuTheme.fail("list hover", t);
        }
    }

    @ModifyArg(method = "extractSelection(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/components/AbstractSelectionList$Entry;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V", ordinal = 0),
            index = 4, require = 0)
    private int killer560smod$themedSelectionBorder(int x0, int y0, int x1, int y1, int color) {
        if (!MainMenuTheme.activeOnMenus()) {
            return color;
        }
        try {
            AbstractSelectionList<?> self = (AbstractSelectionList<?>) (Object) this;
            boolean bright = self.isFocused() || killer560smod$hovering(self, x0, y0, x1, y1);
            return bright ? MenuChrome.ACCENT_LIGHT : MenuChrome.ACCENT;
        } catch (Throwable t) {
            MainMenuTheme.fail("list selection border", t);
            return color;
        }
    }

    @ModifyArg(method = "extractSelection(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/components/AbstractSelectionList$Entry;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V", ordinal = 1),
            index = 4, require = 0)
    private int killer560smod$themedSelectionFill(int x0, int y0, int x1, int y1, int color) {
        if (!MainMenuTheme.activeOnMenus()) {
            return color;
        }
        try {
            AbstractSelectionList<?> self = (AbstractSelectionList<?>) (Object) this;
            // Must stay opaque: it is drawn over the full-rect border fill.
            return killer560smod$hovering(self, x0, y0, x1, y1) ? MenuChrome.SELECTION_FILL_HOVER : MenuChrome.SELECTION_FILL;
        } catch (Throwable t) {
            MainMenuTheme.fail("list selection fill", t);
            return color;
        }
    }

    @Unique
    private boolean killer560smod$hovering(AbstractSelectionList<?> self, int x0, int y0, int x1, int y1) {
        int mx = killer560smod$mouseX;
        int my = killer560smod$mouseY;
        return mx >= x0 - 1 && mx < x1 + 1 && my >= y0 - 1 && my < y1 + 1 && self.isMouseOver(mx, my);
    }
}
