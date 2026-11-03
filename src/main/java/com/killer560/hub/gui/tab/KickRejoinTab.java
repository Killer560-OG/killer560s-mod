package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.CommitEditBox;
import com.killer560.hub.kickrejoin.KickRejoinConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Kick Rejoin Timer ({@link com.killer560.hub.kickrejoin.KickRejoinFeature}): the switch, the title and sound when
 *  Hypixel's 60 s rejoin wait is over, and the optional party message with its text. */
public class KickRejoinTab extends BaseTab {

    private static final int ROW = 18;

    public KickRejoinTab() {
        super("Kick Rejoin Timer");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        KickRejoinConfig cfg = KickRejoinConfig.getInstance();
        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int colBX = contentX + colW + gap;
        int y = contentY;

        widgets.add(onOffButton("Kick Rejoin Timer", cfg::isEnabled, cfg::setEnabled, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += 24;
        if (!cfg.isEnabled()) {
            return widgets;
        }
        widgets.add(onOffButton("Rejoin Title", cfg::isReadyTitle, cfg::setReadyTitle, cfg::save,
                null, contentX, y, colW, ROW));
        widgets.add(onOffButton("Rejoin Sound", cfg::isReadySound, cfg::setReadySound, cfg::save,
                null, colBX, y, colW, ROW));
        y += 20;
        widgets.add(onOffButton("Kick Party Message", cfg::isPartyMessage, cfg::setPartyMessage, cfg::save,
                requestRebuild, contentX, y, contentWidth, ROW));
        y += 20;
        if (cfg.isPartyMessage()) {
            CommitEditBox text = new CommitEditBox(Minecraft.getInstance().font, contentX, y, contentWidth, ROW,
                    Component.literal("Kick Party Message Text"));
            text.setMaxLength(KickRejoinConfig.MAX_PARTY_TEXT);
            text.setHint(Component.literal("\u00a78" + KickRejoinConfig.DEFAULT_PARTY_TEXT));
            text.setValue(cfg.getPartyText());
            text.setResponder(value -> {
                cfg.setPartyText(value);
                cfg.save();
            });
            widgets.add(text);
            CommitEditBox.withSet(widgets, text);
        }
        return widgets;
    }
}
