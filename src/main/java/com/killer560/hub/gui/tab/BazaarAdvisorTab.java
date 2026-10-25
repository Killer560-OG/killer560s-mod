package com.killer560.hub.gui.tab;

import com.killer560.hub.bazaaradvisor.BazaarAdvisorConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Bazaar Advisor settings - legit, read-only: prints what to buy and how much each time a Bazaar menu opens. The four
 *  answers are set with {@code /bzflip}; this tab shows what they are and switches the advisor on or off. */
public class BazaarAdvisorTab extends BaseTab {

    public BazaarAdvisorTab() {
        super("Bazaar Advisor");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        BazaarAdvisorConfig cfg = BazaarAdvisorConfig.getInstance();

        widgets.add(onOffButton("Bazaar Advisor", cfg::isEnabled, cfg::setEnabled, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        AbstractWidget setup = SettingsButtonWidget.builder(Component.literal("Setup: " + cfg.summary()), btn -> {
                }).bounds(contentX, y, contentWidth, 18).build();
        setup.active = false;
        widgets.add(setup);
        y += 22;
        AbstractWidget change = SettingsButtonWidget.builder(Component.literal("Change the answers with /bzflip"), btn -> {
                }).bounds(contentX, y, contentWidth, 18).build();
        change.active = false;
        widgets.add(change);

        return widgets;
    }
}
