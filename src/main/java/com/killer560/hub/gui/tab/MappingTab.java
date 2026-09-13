package com.killer560.hub.gui.tab;

import com.killer560.hub.mapping.MappingConfig;
import com.killer560.hub.mapping.MappingFeature;
import com.killer560.hub.notify.ModOverlayMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Dungeon map settings - see {@link MappingConfig}'s class doc for why "funny map"/extra info/mimic
 *  highlight/class recolor are placeholders (persisted, but don't draw anything yet) while "Dump Held
 *  Map Now" is a real, working data-gathering tool for building those features later. */
public class MappingTab extends BaseTab {

    public MappingTab() {
        super("Mapping");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        MappingConfig cfg = MappingConfig.getInstance();

        widgets.add(SettingsButtonWidgetHelper.toggle(contentX, y, "Diagnostic logging", cfg.isEnabled(),
                v -> { cfg.setEnabled(v); cfg.save(); }, requestRebuild));
        y += 26;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("Saves the real pixel data of whatever map you're holding to a file -"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("dump the same room in different states and diff the files to find"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("real pixel meanings (mimic room, class icons, etc)."),
                Minecraft.getInstance().font));
        y += 18;

        widgets.add(com.killer560.hub.gui.SettingsButtonWidget.builder(
                Component.literal("Dump Held Map Now"), btn -> {
                    String result = MappingFeature.dumpHeldMap();
                    ModOverlayMessage.show(result, 4000);
                }).bounds(contentX, y, 220, 20).build());
        y += 30;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7The 4 toggles below are placeholders - saved, but don't draw"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7anything yet. They need real pixel data (see above) first."),
                Minecraft.getInstance().font));
        y += 18;

        widgets.add(SettingsButtonWidgetHelper.toggle(contentX, y, "Funny map", cfg.isFunnyMapEnabled(),
                v -> { cfg.setFunnyMapEnabled(v); cfg.save(); }, requestRebuild));
        y += 24;
        widgets.add(SettingsButtonWidgetHelper.toggle(contentX, y, "Extra info overlay", cfg.isExtraInfoEnabled(),
                v -> { cfg.setExtraInfoEnabled(v); cfg.save(); }, requestRebuild));
        y += 24;
        widgets.add(SettingsButtonWidgetHelper.toggle(contentX, y, "Mimic room show/hide", cfg.isMimicRoomHighlightEnabled(),
                v -> { cfg.setMimicRoomHighlightEnabled(v); cfg.save(); }, requestRebuild));
        y += 24;
        widgets.add(SettingsButtonWidgetHelper.toggle(contentX, y, "Player-head class recolor", cfg.isClassRecolorEnabled(),
                v -> { cfg.setClassRecolorEnabled(v); cfg.save(); }, requestRebuild));

        return widgets;
    }

    /** Tiny local helper so each of the 5 on/off rows above doesn't repeat the same builder boilerplate. */
    private static final class SettingsButtonWidgetHelper {
        static AbstractWidget toggle(int x, int y, String label, boolean value,
                                      java.util.function.Consumer<Boolean> setter, Runnable requestRebuild) {
            return com.killer560.hub.gui.SettingsButtonWidget.builder(
                    Component.literal(label + ": " + (value ? "§aON" : "§cOFF")), btn -> {
                        setter.accept(!value);
                        requestRebuild.run();
                    }).bounds(x, y, 220, 20).build();
        }
    }
}
