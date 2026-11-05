package com.killer560.hub.helditem;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemeAccent;
import com.killer560.hub.gui.tab.HeldItemTab;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.HumanoidArm;

import java.util.ArrayList;
import java.util.List;

/**
 * Held Item Transform's Edit menu (killer560, 2026-11-01: "The edit button should open the edit menu with all the
 * sliders but on the opposite side that the player is holding the item on so they can see it as they edit it").
 *
 * <p>The same rows as the tab ({@link HeldItemTab#buildSettings}) in one column against the screen edge away from the
 * main hand: the left edge for a right-handed player, the right edge for a left-handed one. The world is not blurred
 * or dimmed and the game is not paused, so the first-person item stays in view and every change shows at once. The
 * mouse wheel scrolls the column when it is taller than the screen.
 */
public class HeldItemEditScreen extends Screen implements MenuThemedScreen {

    private static final int PANEL_W = 200;
    private static final int PAD = 6;

    private final Screen parent;
    private int scroll;
    private int contentHeight;
    private int panelX;
    /** Which side the column went to on the last layout, for the testkit: true = left edge. */
    private boolean panelLeft;

    public HeldItemEditScreen(Screen parent) {
        super(Component.literal("Edit Held Item"));
        this.parent = parent;
    }

    /** True when the sliders sit on the left edge (the item is held in the right hand). */
    public boolean isPanelLeft() {
        return panelLeft;
    }

    /** The column goes against the side away from the main hand. */
    static boolean panelOnLeft(HumanoidArm mainArm) {
        return mainArm != HumanoidArm.LEFT;
    }

    @Override
    protected void init() {
        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        HumanoidArm arm = this.minecraft != null && this.minecraft.player != null
                ? this.minecraft.player.getMainArm() : HumanoidArm.RIGHT;
        panelLeft = panelOnLeft(arm);
        int w = Math.min(PANEL_W, Math.max(80, this.width / 2 - 2 * PAD));
        panelX = panelLeft ? PAD : this.width - w - PAD;
        int x = panelX + PAD;
        int innerW = w - 2 * PAD;
        int top = PAD + 4 - scroll;
        List<AbstractWidget> widgets = new ArrayList<>();
        HeldItemConfig cfg = HeldItemConfig.getInstance();
        int y = top;
        widgets.add(SettingsButtonWidget.builder(onOffText(cfg), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    rebuild();
                }).bounds(x, y, innerW, 20).build());
        y += 26;
        if (cfg.isEnabled()) {
            y = HeldItemTab.buildSettings(widgets, x, y, innerW, this::rebuild);
        }
        widgets.add(SettingsButtonWidget.builder(Component.literal("Done"), btn -> onClose())
                .bounds(x, y + 2, innerW, 20).build());
        y += 26;
        contentHeight = y - top + PAD;
        int maxScroll = Math.max(0, contentHeight - this.height + PAD);
        if (scroll > maxScroll) {
            scroll = maxScroll;
            rebuild();
            return;
        }
        for (AbstractWidget wd : widgets) {
            addRenderableWidget(wd);
        }
    }

    private static Component onOffText(HeldItemConfig cfg) {
        return Component.literal("Held Item Transform: " + (cfg.isEnabled() ? "§aON" : "§cOFF"));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // Nothing: no blur, no dim, so the held item is seen exactly as in game.
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int w = Math.min(PANEL_W, Math.max(80, this.width / 2 - 2 * PAD));
        graphics.fill(panelX, 0, panelX + w, this.height, 0xB0101010);
        graphics.fill(panelLeft ? panelX + w - 1 : panelX, 0, panelLeft ? panelX + w : panelX + 1, this.height, ThemeAccent.accent(0xFFCC6600));
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        int maxScroll = Math.max(0, contentHeight - this.height + PAD);
        int next = Math.max(0, Math.min(maxScroll, scroll - (int) Math.round(scrollY * 20)));
        if (next != scroll) {
            scroll = next;
            rebuild();
            return true;
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        HeldItemConfig.getInstance().save();
        McCompat.setScreen(this.minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public int[] themedPanelBounds() {
        int w = Math.min(PANEL_W, Math.max(80, this.width / 2 - 2 * PAD));
        return new int[]{panelX, 0, w, this.height};
    }
}
