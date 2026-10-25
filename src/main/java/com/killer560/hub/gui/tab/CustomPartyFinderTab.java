package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.lfg.LfgConfig;
import com.killer560.hub.lfg.LfgScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import com.killer560.hub.compat.McCompat;

/** Custom Party Finder ({@link com.killer560.hub.lfg.LfgFeature}): the switch, the menu (/pf or /partyfinder) and its four options. */
public class CustomPartyFinderTab extends BaseTab {

    public CustomPartyFinderTab() {
        super("Custom Party Finder");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        LfgConfig cfg = LfgConfig.getInstance();
        int y = contentY;
        widgets.add(onOffButton("Custom Party Finder", cfg::isEnabledRaw, cfg::setEnabled, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += 26;
        if (!cfg.isEnabledRaw()) {
            return widgets;
        }
        widgets.add(SettingsButtonWidget.builder(Component.literal("Open Party Finder"), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    McCompat.setScreen(client, new LfgScreen(McCompat.screen(client)));
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;
        widgets.add(toggle("Dual-List Hypixel Queues", cfg::isDualList, cfg::setDualList, cfg, contentX, y, contentWidth));
        y += 22;
        widgets.add(toggle("Auto Accept Requests", cfg::isAutoAcceptRequests, cfg::setAutoAcceptRequests, cfg, contentX, y, contentWidth));
        y += 22;
        widgets.add(toggle("Auto Accept Invite", cfg::isAutoAcceptInvite, cfg::setAutoAcceptInvite, cfg, contentX, y, contentWidth));
        y += 22;
        widgets.add(toggle("Check Requirements", cfg::isCheckRequirements, cfg::setCheckRequirements, cfg, contentX, y, contentWidth));
        return widgets;
    }

    private static AbstractWidget toggle(String label, BooleanSupplier get, Consumer<Boolean> set, LfgConfig cfg,
                                         int x, int y, int w) {
        return onOffButton(label, get, set, cfg::save, null, x, y, w, 18);
    }
}
