package com.killer560.hub.gui.tab;

import com.killer560.hub.util.ExternalOpen;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.invsort.InventoryLayoutStore;
import com.killer560.hub.invsort.InventorySorterConfig;
import com.killer560.hub.invsort.InventorySorterExecutor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Auto Inventory Sorter settings. Layouts themselves are saved/applied/listed/deleted through
 *  {@code /invsort} (see {@code InventorySorterCommands}), same as AP3's chains and Auto Routes' routes are
 *  chat-command-driven rather than edited from a GUI list; this tab is the master switch, the status line and
 *  a shortcut to the layout folder. */
public class InventorySorterTab extends BaseTab {

    public InventorySorterTab() {
        super("Inventory Sorter");
    }

    @Override
    public boolean isCheatOnly() {
        return true;
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

        boolean running = InventorySorterExecutor.isRunning();
        widgets.add(SettingsButtonWidget.builder(
                        Component.literal(running ? "§cSorting... /invsort stop" : "§7Not sorting"),
                        btn -> {
                            if (InventorySorterExecutor.isRunning()) {
                                InventorySorterExecutor.requestStop();
                                requestRebuild.run();
                            }
                        }).bounds(contentX, y, contentWidth, 18).build());
        y += 24;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Open Layouts Folder"), btn ->
                        ExternalOpen.path(InventoryLayoutStore.directory()))
                .bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        widgets.add(inertLabel(contentX, y, contentWidth,
                InventoryLayoutStore.getInstance().listNames().size() + " saved layout(s)"));
        y += 24;

        widgets.add(inertLabel(contentX, y, contentWidth, "/invsort save <name>"));
        y += 18;
        widgets.add(inertLabel(contentX, y, contentWidth, "/invsort load <name>"));
        y += 18;
        widgets.add(inertLabel(contentX, y, contentWidth, "/invsort list  /invsort delete <name>"));

        return widgets;
    }

    /** A non-clickable row, purely to print a hint under the buttons above (layouts themselves are managed
     *  entirely through {@code /invsort} - see the class doc). */
    private static AbstractWidget inertLabel(int x, int y, int width, String text) {
        SettingsButtonWidget w = SettingsButtonWidget.builder(Component.literal(text), btn -> {
                }).bounds(x, y, width, 16).build();
        w.active = false;
        return w;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
