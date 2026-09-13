package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.posmsg.PosmsgConfig;
import com.killer560.hub.posmsg.PosmsgEntry;
import com.killer560.hub.posmsg.PosmsgFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** One-click quick-travel buttons, one per configured Posmsg waypoint (including the built-in "Mel"
 *  preset) - press it, it sends that waypoint exactly like the Posmsg tab's own Send button does.
 *  Modeled on QUOY's fast-leap panel per killer560's reference, with the explicit addition he asked
 *  for: leaping by Posmsg instead of only fixed built-in destinations, since every button here is
 *  backed by the same editable/addable {@link PosmsgConfig} entries the Posmsg tab manages - add a
 *  new custom waypoint there (or via {@code /posmsg add}) and it shows up here too. */
public class FastLeapTab extends BaseTab {

    public FastLeapTab() {
        super("Fast Leap");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("One click sends that waypoint to Party Chat for everyone's Posmsg HUD."),
                Minecraft.getInstance().font));
        y += 18;

        List<PosmsgEntry> ready = new ArrayList<>();
        List<PosmsgEntry> notReady = new ArrayList<>();
        for (PosmsgEntry e : PosmsgConfig.getInstance().entries()) {
            (e.configured ? ready : notReady).add(e);
        }

        for (PosmsgEntry e : ready) {
            widgets.add(SettingsButtonWidget.builder(Component.literal("Leap: " + e.name), btn ->
                        PosmsgFeature.send(e)
                    ).bounds(contentX, y, 220, 20).build());
            y += 24;
        }

        if (!notReady.isEmpty()) {
            y += 6;
            StringBuilder names = new StringBuilder();
            for (PosmsgEntry e : notReady) {
                if (!names.isEmpty()) {
                    names.append(", ");
                }
                names.append(e.name);
            }
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§7Not set up yet (see Posmsg tab): " + names), Minecraft.getInstance().font));
        }

        return widgets;
    }
}
