package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.CommitEditBox;
import com.killer560.hub.instaclearwarning.InstaClearWarningConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Insta Clear Warning ({@link com.killer560.hub.instaclearwarning.InstaClearWarningFeature}): one switch, then what it
 *  does when a blood rush room is insta cleared - a HUD popup, a party message you write, a requeue of the same floor. */
public class InstaClearWarningTab extends BaseTab {

    public InstaClearWarningTab() {
        super("Insta Clear Warning");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        InstaClearWarningConfig cfg = InstaClearWarningConfig.getInstance();
        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int colBX = contentX + colW + gap;
        int y = contentY;

        if (masterClosed(widgets, "Insta Clear Warning", cfg::isEnabledRaw, cfg::setEnabled, cfg::save, requestRebuild,
                contentX, y, contentWidth)) {
            return widgets;
        }
        y += 24;
        widgets.add(onOffButton("HUD Popup", cfg::isHudPopup, cfg::setHudPopup, cfg::save,
                null, contentX, y, colW, 18));
        widgets.add(onOffButton("Requeue Same Floor", cfg::isRequeue, cfg::setRequeue, cfg::save,
                null, colBX, y, colW, 18));
        y += 20;
        widgets.add(onOffButton("Party Message", cfg::isPartyMessage, cfg::setPartyMessage, cfg::save,
                requestRebuild, contentX, y, contentWidth, 18));
        y += 20;
        if (cfg.isPartyMessage()) {
            CommitEditBox message = new CommitEditBox(Minecraft.getInstance().font, contentX, y, contentWidth, 18,
                    Component.literal("Party Message Text"));
            message.setMaxLength(InstaClearWarningConfig.MAX_MESSAGE);
            message.setHint(Component.literal("§8" + InstaClearWarningConfig.DEFAULT_MESSAGE));
            message.setValue(cfg.getMessage());
            message.setResponder(text -> {
                cfg.setMessage(text);
                cfg.save();
            });
            widgets.add(message);
            CommitEditBox.withSet(widgets, message);
        }
        return widgets;
    }
}
