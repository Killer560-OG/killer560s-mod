package com.killer560.hub.gui.tab;

import com.killer560.hub.dungeonqueue.DungeonQueueConfig;
import com.killer560.hub.dungeonqueue.DungeonQueueFeature;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Dungeon Queue settings - see {@link DungeonQueueFeature}. Same widget style and key-capture pattern as
 *  {@link SimonSaysTab}. */
public class DungeonQueueTab extends BaseTab implements KeyCaptureTab {

    private boolean capturingCancelKey = false;
    private boolean capturingRequeueKey = false;

    public DungeonQueueTab() {
        super("Dungeon Queue");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        DungeonQueueConfig cfg = DungeonQueueConfig.getInstance();

        int gap = 8;
        int col2W = (contentWidth - gap) / 2;
        int col2aX = contentX;
        int col2bX = contentX + col2W + gap;

        widgets.add(SettingsButtonWidget.builder(onOff("Dungeon Queue", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 28;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        double delayNorm = cfg.getDelaySeconds() / (double) DungeonQueueConfig.MAX_DELAY_SECONDS;
        widgets.add(new ThemedSliderButton(col2aX, y, col2W, 18,
                Component.literal("Delay: " + cfg.getDelaySeconds() + "s"), delayNorm) {
            @Override
            protected void updateMessage() {
                setMessage(Component.literal("Delay: " + cfg.getDelaySeconds() + "s"));
            }

            @Override
            protected void applyValue() {
                cfg.setDelaySeconds((int) Math.round(this.value * DungeonQueueConfig.MAX_DELAY_SECONDS));
                cfg.save();
            }
        });

        widgets.add(SettingsButtonWidget.builder(onOff("Leader Check", cfg.isLeaderCheck()), btn -> {
                    cfg.setLeaderCheck(!cfg.isLeaderCheck());
                    cfg.save();
                    btn.setMessage(onOff("Leader Check", cfg.isLeaderCheck()));
                }).bounds(col2bX, y, col2W, 18).build());
        y += 22;

        Component cancelLabel = capturingCancelKey ? Component.literal("Press any key...")
                : keyText("Cancel Key", cfg.getCancelKeyCode());
        widgets.add(SettingsButtonWidget.builder(cancelLabel, btn -> {
                    capturingCancelKey = true;
                    capturingRequeueKey = false;
                    btn.setMessage(Component.literal("Press any key..."));
                }).bounds(col2aX, y, col2W, 18).build());

        Component requeueLabel = capturingRequeueKey ? Component.literal("Press any key...")
                : keyText("Requeue Key", cfg.getRequeueKeyCode());
        widgets.add(SettingsButtonWidget.builder(requeueLabel, btn -> {
                    capturingRequeueKey = true;
                    capturingCancelKey = false;
                    btn.setMessage(Component.literal("Press any key..."));
                }).bounds(col2bX, y, col2W, 18).build());
        y += 30;

        widgets.add(SettingsButtonWidget.builder(onOff("Downtime Check", cfg.isDowntimeCheck()), btn -> {
                    cfg.setDowntimeCheck(!cfg.isDowntimeCheck());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        if (!cfg.isDowntimeCheck()) {
            return widgets;
        }

        widgets.add(new StringWidget(col2aX, y + 5, col2W, 10, Component.literal("DT Keyword"),
                Minecraft.getInstance().font));
        EditBox keywordField = new EditBox(Minecraft.getInstance().font, col2bX, y, col2W, 18,
                Component.literal("DT Keyword"));
        keywordField.setMaxLength(32);
        keywordField.setValue(cfg.getDtKeyword());
        keywordField.setHint(Component.literal(DungeonQueueConfig.DEFAULT_DT_KEYWORD));
        keywordField.setResponder(text -> {
            cfg.setDtKeyword(text);
            cfg.save();
        });
        widgets.add(keywordField);
        return widgets;
    }

    private static Component keyText(String label, int keyCode) {
        String name = keyCode < 0 ? "Not Set" : DungeonQueueFeature.keyName(keyCode);
        return Component.literal(label + ": §b" + name);
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    @Override
    public boolean isListeningForKey() {
        return capturingCancelKey || capturingRequeueKey;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        DungeonQueueConfig cfg = DungeonQueueConfig.getInstance();
        int code = keyCode == InputConstants.KEY_ESCAPE ? -1 : keyCode;
        if (capturingRequeueKey) {
            cfg.setRequeueKeyCode(code);
        } else {
            cfg.setCancelKeyCode(code);
        }
        capturingCancelKey = false;
        capturingRequeueKey = false;
        cfg.save();
    }
}
