package com.killer560.hub.gui.tab;

import com.killer560.hub.autocorrect.AutoCorrectConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Chat Auto Correct settings: on/off only - the typo table itself isn't user-editable (yet). */
public class AutoCorrectTab extends BaseTab {

    public AutoCorrectTab() {
        super("Auto Correct");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(enabledText(), btn -> {
                    AutoCorrectConfig cfg = AutoCorrectConfig.getInstance();
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    btn.setMessage(enabledText());
                }).bounds(contentX, y, 220, 20).build());
        y += 30;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("Fixes common typos in chat before it's sent - e.g."),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("\"teh\" -> \"the\", \"definately\" -> \"definitely\"."),
                Minecraft.getInstance().font));

        return widgets;
    }

    private static Component enabledText() {
        return Component.literal("Auto Correct Enabled: " + (AutoCorrectConfig.getInstance().isEnabled() ? "§aON" : "§cOFF"));
    }
}
