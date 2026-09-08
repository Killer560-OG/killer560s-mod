package com.killer560.hub.gui.tab;

import com.killer560.hub.cringe.CringeLines;
import com.killer560.hub.notify.ModOverlayMessage;
import net.minecraft.client.gui.components.AbstractWidget;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.network.chat.Component;

import java.awt.Desktop;
import java.util.ArrayList;
import java.util.List;

/** Points at the plain-text file {@code /cringe} draws its lines from, so anyone can add, remove,
 *  or edit lines themselves without touching any code. See {@link com.killer560.hub.cringe.CringeFeature}. */
public class CringeTab extends BaseTab {

    public CringeTab() {
        super("Cringe");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Open Cringe Lines Folder"), btn -> openFolder())
                .bounds(contentX, y, 220, 20).build());
        y += 24;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Reload Lines"), btn -> {
                    CringeLines.load();
                    ModOverlayMessage.show("§a[Killer560's Mod] Reloaded " + CringeLines.all().size() + " cringe lines", 3000);
                }).bounds(contentX, y, 220, 20).build());

        return widgets;
    }

    private static void openFolder() {
        try {
            Desktop.getDesktop().open(CringeLines.file().getParent().toFile());
        } catch (Exception e) {
            ModOverlayMessage.show("§c[Killer560's Mod] Couldn't open cringe folder: " + e.getMessage(), 4000);
        }
    }
}
