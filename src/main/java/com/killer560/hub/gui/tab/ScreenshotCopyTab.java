package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.screenshotcopy.ScreenshotCopyConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Screenshot Copy settings: on/off only. Ships disabled by default - see
 *  {@link com.killer560.hub.screenshotcopy.ScreenshotCopyConfig}. */
public class ScreenshotCopyTab extends BaseTab {

    public ScreenshotCopyTab() {
        super("Screenshot Copy");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(enabledText(), btn -> {
                    ScreenshotCopyConfig cfg = ScreenshotCopyConfig.getInstance();
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    btn.setMessage(enabledText());
                }).bounds(contentX, y, 220, 20).build());
        y += 30;

        return widgets;
    }

    private static Component enabledText() {
        return Component.literal("Copy Screenshots to Clipboard: "
                + (ScreenshotCopyConfig.getInstance().isEnabled() ? "§aON" : "§cOFF"));
    }
}
