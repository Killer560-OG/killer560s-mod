package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.CommitEditBox;
import com.killer560.hub.reforge.ReforgeHelperConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Reforge Helper settings - see {@code reforge.ReforgeHelperFeature}. */
public class ReforgeHelperTab extends BaseTab {

    public ReforgeHelperTab() {
        super("Reforge Helper");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        ReforgeHelperConfig cfg = ReforgeHelperConfig.getInstance();
        int y = contentY;
        widgets.add(onOffButton("Reforge Helper", cfg::isEnabledRaw, cfg::setEnabled, cfg::save, requestRebuild,
                contentX, y, contentWidth, 20));
        y += 24;
        if (!cfg.isEnabledRaw() && !isSearchScan()) {
            return widgets;
        }
        CommitEditBox box = new CommitEditBox(Minecraft.getInstance().font, contentX, y, contentWidth, 18,
                Component.literal("Wanted Reforges"));
        box.setMaxLength(ReforgeHelperConfig.MAX_WANTED_LENGTH);
        box.setHint(Component.literal("Wanted Reforges, like Fabled, Withered (or click them in the menu)"));
        box.setValue(cfg.getWanted());
        box.setResponder(v -> {
            cfg.setWanted(v);
            cfg.save();
        });
        widgets.add(box);
        CommitEditBox.withSet(widgets, box);
        y += 24;
        int colW = (contentWidth - 8) / 2;
        widgets.add(onOffButton("Block When Wanted", cfg::isBlockWanted, cfg::setBlockWanted, cfg::save, null,
                contentX, y, colW, 18));
        widgets.add(BazaarOrderHelperTab.colorButton("Wanted Color", cfg.getColor(), ReforgeHelperConfig.DEFAULT_COLOR,
                argb -> {
                    cfg.setColor(argb);
                    cfg.save();
                }, contentX + colW + 8, y, colW));
        return widgets;
    }
}
