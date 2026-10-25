package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.invwalk.InventoryWalkConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Inventory Walk - cheat-only (see {@link com.killer560.hub.invwalk.InventoryWalkFeature}). Misc's cheat block. */
public class InventoryWalkTab extends BaseTab {

    private static final int ROW = 18;

    public InventoryWalkTab() {
        super("Inventory Walk");
    }

    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return w;
        }
        var font = Minecraft.getInstance().font;
        InventoryWalkConfig cfg = InventoryWalkConfig.getInstance();
        int y = contentY;
        w.add(SettingsButtonWidget.builder(onOff("Inventory Walk", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, ROW).build());
        y += ROW + 6;
        if (!cfg.isEnabled()) {
            return w;
        }
        w.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Inventory Walk", true), font));
        y += 16;
        String[] warning = {
                "§cKeeps your movement keys working while a menu is open. The server sees",
                "§cyou move with a menu open, which a normal client cannot do. Ban risk.",
                "§7Tested against GrimAC only - that says nothing about Watchdog."
        };
        for (String line : warning) {
            w.add(new StringWidget(contentX, y, contentWidth, 10, Component.literal(line), font));
            y += 11;
        }
        y += 6;
        w.add(SettingsButtonWidget.cycling(screensText(cfg), (btn, dir) -> {
                    cfg.setScreens(SettingsButtonWidget.step(cfg.getScreens(), dir));
                    cfg.save();
                    btn.setMessage(screensText(cfg));
                }).bounds(contentX, y, contentWidth, ROW).build());
        y += ROW + 4;
        w.add(SettingsButtonWidget.builder(onOff("Jump", cfg.isJump()), btn -> {
                    cfg.setJump(!cfg.isJump());
                    cfg.save();
                    btn.setMessage(onOff("Jump", cfg.isJump()));
                }).bounds(contentX, y, contentWidth, ROW).build());
        y += ROW + 4;
        w.add(SettingsButtonWidget.builder(onOff("Sprint", cfg.isSprint()), btn -> {
                    cfg.setSprint(!cfg.isSprint());
                    cfg.save();
                    btn.setMessage(onOff("Sprint", cfg.isSprint()));
                }).bounds(contentX, y, contentWidth, ROW).build());
        y += ROW + 4;
        double span = InventoryWalkConfig.MAX_PAUSE_TICKS;
        w.add(new ThemedSliderButton(contentX, y, contentWidth, ROW, pauseText(cfg),
                Math.max(0.0, Math.min(1.0, cfg.getPauseAfterClickTicks() / span))) {
            @Override
            protected void updateMessage() {
                setMessage(pauseText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setPauseAfterClickTicks((int) Math.round(this.value * span));
                saveOnRelease(() -> cfg.save());
            }
        });
        return w;
    }

    private static Component onOff(String label, boolean on) {
        return Component.literal(label + ": " + (on ? "§aON" : "§cOFF"));
    }

    private static Component screensText(InventoryWalkConfig cfg) {
        return Component.literal("Screens: " + cfg.getScreens().label);
    }

    private static Component pauseText(InventoryWalkConfig cfg) {
        int t = cfg.getPauseAfterClickTicks();
        return Component.literal("Pause After Click: " + (t == 0 ? "Off" : t + " tick(s)"));
    }
}
