package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.tooltipscroll.TooltipScrollConfig;
import com.killer560.hub.tooltipscroll.TooltipScrollFeature;
import com.killer560.hub.util.KeyUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Scrollable Tooltips settings - see {@link com.killer560.hub.tooltipscroll.TooltipScrollFeature}.
 *  Everything ships OFF. */
public class TooltipScrollTab extends BaseTab implements KeyCaptureTab {

    private boolean capturingModifier = false;

    public TooltipScrollTab() {
        super("Scrollable Tooltips");
    }

    @Override
    public boolean isListeningForKey() {
        return capturingModifier;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        if (!capturingModifier) {
            return;
        }
        TooltipScrollConfig cfg = TooltipScrollConfig.getInstance();
        cfg.setModifierKey(keyCode == InputConstants.KEY_ESCAPE ? KeyUtil.NONE : keyCode);
        capturingModifier = false;
        cfg.save();
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        TooltipScrollConfig cfg = TooltipScrollConfig.getInstance();
        Minecraft mc = Minecraft.getInstance();
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(onOff("Scrollable Tooltips", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        // The mixin configs are required:false so a signature change can never stop the game booting - but
        // that also means they could quietly do nothing, which is exactly how killer560 found this feature
        // completely dead in-game (2026-09-20: "scrollable tooltips does not work") even with no other mods
        // installed. Two separate flags, not one merged one - the render hook fires on ANY tooltip anywhere
        // and is easy to trigger, so a single flag could say "alive" while the scroll half is still dead.
        boolean renderSeen = TooltipScrollFeature.renderHookSeen();
        boolean scrollSeen = TooltipScrollFeature.scrollHookSeen();
        if (!renderSeen || !scrollSeen) {
            String warning;
            if (!renderSeen) {
                warning = "§8Render hook hasn't fired - hover any tooltip. If this stays, the mixin didn't apply.";
            } else {
                warning = "§8Render hook is alive; scroll hook hasn't fired - scroll while hovering an item.";
            }
            widgets.add(new StringWidget(contentX, y, contentWidth, 12, Component.literal(warning), mc.font));
            y += 14;
        }

        int half = (contentWidth - 8) / 2;
        int col2 = contentX + half + 8;

        widgets.add(SettingsButtonWidget.builder(onOff("Hold a Key to Scroll", cfg.isRequireModifier()), btn -> {
                    cfg.setRequireModifier(!cfg.isRequireModifier());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, half, 18).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Invert Scroll", cfg.isInvert()), btn -> {
                    cfg.setInvert(!cfg.isInvert());
                    cfg.save();
                    btn.setMessage(onOff("Invert Scroll", cfg.isInvert()));
                }).bounds(col2, y, half, 18).build());
        y += 22;

        if (cfg.isRequireModifier()) {
            widgets.add(SettingsButtonWidget.builder(keyLabel("Scroll Key", cfg.getModifierKey(), capturingModifier), btn -> {
                        capturingModifier = true;
                        btn.setMessage(Component.literal("Press any key..."));
                    }).bounds(contentX, y, contentWidth, 18).build());
            y += 22;
        }

        int min = TooltipScrollConfig.MIN_LINES_PER_SCROLL;
        int max = TooltipScrollConfig.MAX_LINES_PER_SCROLL;
        double norm = (cfg.getLinesPerScroll() - min) / (double) (max - min);
        widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18,
                Component.literal("Lines Per Scroll: " + cfg.getLinesPerScroll()), norm) {
            @Override
            protected void updateMessage() {
                setMessage(Component.literal("Lines Per Scroll: " + cfg.getLinesPerScroll()));
            }

            @Override
            protected void applyValue() {
                cfg.setLinesPerScroll(min + (int) Math.round(this.value * (max - min)));
                cfg.save();
            }
        });
        y += 26;

        return widgets;
    }

    private static Component keyLabel(String label, int key, boolean listening) {
        if (listening) {
            return Component.literal("Press any key...");
        }
        String name = key == KeyUtil.NONE
                ? "Not Set"
                : InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName().getString();
        return Component.literal(label + ": " + name);
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
