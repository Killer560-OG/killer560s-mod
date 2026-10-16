package com.killer560.hub.mainmenu;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * What the Title Screen GUI instance's "Main Menu Looks" button opens (killer560, 2026-10-16: "add a button to replace the
 * multiplayer button that opens a menu to swap gui's"): every look in {@link TitleScreenLooks#looks()}, one row per theme,
 * the one on screen lit. A click shows that look at once and stays here, so the background behind the panel (on the
 * right, left uncovered) changes with each pick; Done or Escape goes back to the title screen in that look.
 */
public final class TitleScreenLooksScreen extends Screen {

    private static final int CHIP_H = 18;
    private static final int GAP = 4;
    private static final int PAD = 10;

    private final Screen parent;
    /** {x, y, label} of each theme's row heading, set by init. */
    private final List<Object[]> headings = new ArrayList<>();
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;

    public TitleScreenLooksScreen(Screen parent) {
        super(Component.literal("Main Menu Looks"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        headings.clear();
        panelX = 8;
        panelY = 8;
        panelW = Math.min(this.width - 16, Math.max(250, (int) (this.width * 0.5)));
        int labelW = 0;
        for (TitleScreenLooks.Look l : TitleScreenLooks.looks()) {
            labelW = Math.max(labelW, this.font.width(l.group()));
        }
        labelW += 10;
        int x0 = panelX + PAD + labelW;
        int x1 = panelX + panelW - PAD;
        int y = panelY + ScreenChrome.HEADER_H + 12;
        int on = TitleScreenLooks.currentIndex();
        String group = null;
        int x = x0;
        List<TitleScreenLooks.Look> looks = TitleScreenLooks.looks();
        for (int i = 0; i < looks.size(); i++) {
            TitleScreenLooks.Look l = looks.get(i);
            String name = l.name().isEmpty() ? l.group() : l.name();
            int w = this.font.width(name) + 14;
            if (!l.group().equals(group)) {
                if (group != null) {
                    y += CHIP_H + GAP + 2;
                }
                group = l.group();
                x = x0;
                headings.add(new Object[]{panelX + PAD, y + (CHIP_H - 8) / 2, group});
            } else if (x + w > x1) {
                x = x0;
                y += CHIP_H + GAP;
            }
            SettingsButtonWidget chip = SettingsButtonWidget.builder(Component.literal(name), b -> pick(l))
                    .toggle(i == on).bounds(x, y, w, CHIP_H).build();
            chip.setTooltip(Tooltip.create(Component.literal(l.label() + "\n" + l.note())));
            addRenderableWidget(chip);
            x += w + GAP;
        }
        y += CHIP_H + 14;
        int bw = 80;
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Done"), b -> onClose()).primary()
                .bounds(panelX + panelW - PAD - bw, y, bw, 20).build());
        SettingsButtonWidget saved = SettingsButtonWidget.builder(Component.literal("Saved Look"), b -> {
            TitleScreenLooks.clear();
            dirty = true;
        }).bounds(panelX + panelW - PAD - bw * 2 - GAP, y, bw, 20).build();
        saved.setTooltip(Tooltip.create(Component.literal("Back to the look this instance's settings give.")));
        saved.active = TitleScreenLooks.current() != null;
        addRenderableWidget(saved);
        panelH = y + 20 + PAD - panelY;
    }

    private void pick(TitleScreenLooks.Look look) {
        TitleScreenLooks.apply(look);
        // The lit chip follows; rebuilt after this click returns, never inside it.
        dirty = true;
    }

    private boolean dirty;

    @Override
    public void tick() {
        super.tick();
        if (dirty) {
            dirty = false;
            rebuildWidgets();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        ScreenChrome.frame(g, panelX, panelY, panelW, panelH);
        ScreenChrome.header(g, panelX, panelY, panelW, "Main Menu Looks");
        int i = TitleScreenLooks.currentIndex();
        String now = i < 0 ? "Saved" : TitleScreenLooks.looks().get(i).label();
        ScreenChrome.headerRight(g, panelX, panelY, panelW, now, MenuStyle.DIM);
        for (Object[] h : headings) {
            ScreenChrome.text(g, (String) h[2], (Integer) h[0], (Integer) h[1], MenuStyle.OR);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        McCompat.setScreen(this.minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
