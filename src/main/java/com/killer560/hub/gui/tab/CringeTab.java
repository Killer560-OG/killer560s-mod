package com.killer560.hub.gui.tab;

import com.killer560.hub.util.ExternalOpen;
import com.killer560.hub.cringe.CringeLines;
import com.killer560.hub.notify.ModOverlayMessage;
import net.minecraft.client.gui.components.AbstractWidget;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.network.chat.Component;

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
        com.killer560.hub.cringe.CringeConfig cfg = com.killer560.hub.cringe.CringeConfig.getInstance();

        // Cringe as a whole (killer560, 2026-10-13): first, so it is the card's switch. Off, /cringe and Leap Message's
        // cringe line send nothing (CringeFeature#sendRandom) and the buttons below are hidden.
        widgets.add(SettingsButtonWidget.builder(Component.literal("Cringe: " + (cfg.isEnabled() ? "§aON" : "§cOFF")), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;
        if (!cfg.isEnabled()) {
            return widgets;
        }

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
            ExternalOpen.path(CringeLines.file().getParent()); // AWT is headless in MC
        } catch (Exception e) {
            ModOverlayMessage.show("§c[Killer560's Mod] Couldn't open cringe folder: " + e.getMessage(), 4000);
        }
    }
}
