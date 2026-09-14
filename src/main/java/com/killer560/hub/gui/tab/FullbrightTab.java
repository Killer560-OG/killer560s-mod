package com.killer560.hub.gui.tab;

import com.killer560.hub.fullbright.FullbrightConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Standalone mirror of the Fullbright toggle that already lives in {@link DisplayTab} - exists only
 *  so the real fix made to it this session (see {@code FullbrightMixin}'s class doc: round 1 fixed a
 *  real FPS drop, round 2 fixed the brightening itself after killer560's live test showed it barely
 *  did anything) is visible in the New tab for testing, without touching Display's existing layout.
 *  Both toggles read/write the same {@link FullbrightConfig} singleton, so flipping either one here or
 *  in Display affects both. Remove this mirror once Fullbright is confirmed working for real. */
public class FullbrightTab extends BaseTab {

    public FullbrightTab() {
        super("Fullbright");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        FullbrightConfig cfg = FullbrightConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff(cfg), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    btn.setMessage(onOff(cfg));
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Round 2 fix (2026-09-13): now also forces a white ambient light"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7floor so true darkness actually brightens, not just already-lit"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7areas. Test in a genuinely dark spot, not just a quick glance."),
                Minecraft.getInstance().font));

        return widgets;
    }

    private static Component onOff(FullbrightConfig cfg) {
        return Component.literal("Fullbright: " + (cfg.isEnabled() ? "§aON" : "§cOFF"));
    }
}
