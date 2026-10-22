package com.killer560.hub.gui.tab;

import com.killer560.hub.autosell.AutoSellConfig;
import com.killer560.hub.autosell.AutoSellListScreen;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.util.KeyUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Auto Sell settings. The sell list / never-sell list / screen title pattern are managed through
 *  {@code /autosell} (see {@code AutoSellCommands}) rather than a GUI list editor, same reasoning as
 *  {@link InventorySorterTab}; this tab is the master switch, the safety-relevant counts and a status line. */
public class AutoSellTab extends BaseTab implements KeyCaptureTab {

    /** True while the toggle key is waiting for a press (killer560, 2026-10-18). */
    private boolean capturingToggleKey = false;

    public AutoSellTab() {
        super("Auto Sell");
    }

    @Override
    public boolean isListeningForKey() {
        return capturingToggleKey;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        AutoSellConfig cfg = AutoSellConfig.getInstance();
        cfg.setToggleKeyCode(keyCode == com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE ? KeyUtil.NONE : keyCode);
        cfg.save();
        capturingToggleKey = false;
    }

    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        AutoSellConfig cfg = AutoSellConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Auto Sell", cfg.isEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.isEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabledRaw()) {
            return widgets;
        }

        // Random wait between two sell clicks, 0..10 ticks, one slider for each end (killer560, 2026-10-18).
        int tickRange = AutoSellConfig.MAX_TICKS;
        widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 20, minTicksText(cfg),
                cfg.getMinTicks() / (double) tickRange) {
            @Override
            protected void updateMessage() {
                setMessage(minTicksText(AutoSellConfig.getInstance()));
            }

            @Override
            protected void applyValue() {
                AutoSellConfig c = AutoSellConfig.getInstance();
                c.setMinTicks((int) Math.round(this.value * tickRange));
                saveOnRelease(() -> c.save());
                this.value = c.getMinTicks() / (double) tickRange;
            }
        });
        y += 24;
        widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 20, maxTicksText(cfg),
                cfg.getMaxTicks() / (double) tickRange) {
            @Override
            protected void updateMessage() {
                setMessage(maxTicksText(AutoSellConfig.getInstance()));
            }

            @Override
            protected void applyValue() {
                AutoSellConfig c = AutoSellConfig.getInstance();
                c.setMaxTicks((int) Math.round(this.value * tickRange));
                saveOnRelease(() -> c.save());
                this.value = c.getMaxTicks() / (double) tickRange;
            }
        });
        y += 26;

        // Hover toggle key: press it over an item in any container to add or remove that item from the sell list.
        widgets.add(SettingsButtonWidget.builder(
                capturingToggleKey ? Component.literal("Press any key...")
                        : Component.literal("Toggle Item Key: " + KeyUtil.bindDisplayName(cfg.getToggleKeyCode())),
                btn -> {
                    capturingToggleKey = true;
                    btn.setMessage(Component.literal("Press any key..."));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;
        widgets.add(SettingsButtonWidget.builder(Component.literal("Open Sell List"), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    McCompat.setScreen(client, new AutoSellListScreen(McCompat.screen(client)));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 24;

        return widgets;
    }

    private static Component minTicksText(AutoSellConfig cfg) {
        return Component.literal("Min Wait Between Sells: " + cfg.getMinTicks() + " ticks");
    }

    private static Component maxTicksText(AutoSellConfig cfg) {
        return Component.literal("Max Wait Between Sells: " + cfg.getMaxTicks() + " ticks");
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
