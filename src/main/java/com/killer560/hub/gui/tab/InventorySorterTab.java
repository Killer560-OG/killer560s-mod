package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.invsort.InventoryLayoutStore;
import com.killer560.hub.invsort.InventorySorterConfig;
import com.killer560.hub.invsort.InventorySorterScreen;
import com.killer560.hub.util.KeyUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Auto Inventory Sorter settings (rebuilt 2026-10-08 to killer560's list: "There should be the toggle, a button to open
 * a menu that has the inventories you saved shown ... You should be able to set keybinds to swap to different
 * inventories ... it should have a slider for ticks between sorting ... Have a random slider"). Layouts are made,
 * renamed and deleted in {@link InventorySorterScreen} ({@code /invsort}); this tab is the switch, the menu button,
 * the two pacing sliders and one key per saved layout.
 */
public class InventorySorterTab extends BaseTab implements KeyCaptureTab {

    /** The layout whose key is being captured, or null. */
    private String capturingFor;

    public InventorySorterTab() {
        super("Inventory Sorter");
    }

    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public boolean isListeningForKey() {
        return capturingFor != null;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        if (capturingFor == null) {
            return;
        }
        InventorySorterConfig cfg = InventorySorterConfig.getInstance();
        cfg.setLayoutKey(capturingFor, keyCode == InputConstants.KEY_ESCAPE ? KeyUtil.NONE : keyCode);
        cfg.save();
        capturingFor = null;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        InventorySorterConfig cfg = InventorySorterConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Auto Inventory Sorter", cfg.isEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.isEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabledRaw()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(Component.literal("Open Layouts"), btn -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.player != null) {
                        mc.setScreenAndShow(new InventorySorterScreen(com.killer560.hub.compat.McCompat.screen(mc)));
                    }
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        widgets.add(tickSlider(contentX, y, contentWidth, "Ticks Between Moves", cfg.getTicksBetweenMoves(),
                InventorySorterConfig.MIN_TICKS, InventorySorterConfig.MAX_TICKS, v -> {
                    cfg.setTicksBetweenMoves(v);
                    cfg.save();
                }));
        y += 22;
        widgets.add(tickSlider(contentX, y, contentWidth, "Random Extra Ticks", cfg.getRandomExtraTicks(),
                0, InventorySorterConfig.MAX_RANDOM_TICKS, v -> {
                    cfg.setRandomExtraTicks(v);
                    cfg.save();
                }));
        y += 26;

        // One key per saved layout.
        for (String name : InventoryLayoutStore.getInstance().listNames()) {
            boolean listening = name.equals(capturingFor);
            String label = listening ? "Press any key..."
                    : "Layout Key: " + name + " - " + CommandKeybindsTab.bindName(cfg.getLayoutKey(name));
            widgets.add(SettingsButtonWidget.builder(Component.literal(label), btn -> {
                        capturingFor = name;
                        btn.setMessage(Component.literal("Press any key..."));
                    }).bounds(contentX, y, contentWidth, 18).build());
            y += 20;
        }
        return widgets;
    }

    private static ThemedSliderButton tickSlider(int x, int y, int width, String label, int current, int min, int max,
                                                 IntConsumer setter) {
        double norm = max == min ? 0.0 : (current - min) / (double) (max - min);
        return new ThemedSliderButton(x, y, width, 18, tickLabel(label, current), Math.max(0.0, Math.min(1.0, norm))) {
            private int value() {
                return min + (int) Math.round(this.value * (max - min));
            }

            @Override
            protected void updateMessage() {
                setMessage(tickLabel(label, value()));
            }

            @Override
            protected void applyValue() {
                setter.accept(value());
            }
        };
    }

    private static Component tickLabel(String label, int ticks) {
        return Component.literal(label + ": " + ticks + (ticks == 1 ? " tick" : " ticks"));
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
