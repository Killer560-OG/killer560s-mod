package com.killer560.hub.gui.tab;

import com.killer560.hub.cape.CapeConfig;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.CapeDesignerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/** Custom Cape: pick one of the built-in designs to wear (your own player only), or Make Your Own: a pattern and two colours.
 *  Click to step; the first click on None starts at the first design, and Make Your Own comes after the last design. */
public class CapeTab extends BaseTab {

    public CapeTab() {
        super("Cape");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        CapeConfig cfg = CapeConfig.getInstance();
        widgets.add(SettingsButtonWidget.cycling(capeText(cfg), (btn, dir) -> {
                    cfg.cycle(dir);
                    cfg.save();
                    btn.setMessage(capeText(cfg));
                    requestRebuild.run();
                }).bounds(contentX, contentY, contentWidth, 18).build());
        // Make Your Own's own designer: each pixel of the back panel is placed by hand (killer560, 2026-10-19).
        widgets.add(SettingsButtonWidget.builder(Component.literal("Design Make Your Own..."), btn ->
                        McCompat.setScreen(Minecraft.getInstance(), new CapeDesignerScreen(McCompat.screen(Minecraft.getInstance()))))
                .bounds(contentX, contentY + 22, contentWidth, 18).build());
        if (cfg.isCustom()) {
            int y = contentY + 44;
            widgets.add(SettingsButtonWidget.cycling(patternText(cfg), (btn, dir) -> {
                        CapeConfig.Pattern[] all = CapeConfig.Pattern.values();
                        cfg.setCustomPattern(all[SettingsButtonWidget.step(cfg.getCustomPattern().ordinal(), dir, all.length)]);
                        cfg.save();
                        btn.setMessage(patternText(cfg));
                    }).bounds(contentX, y, contentWidth, 18).build());
            y += 22;
            widgets.add(colourRow("Colour A", cfg.getCustomColourA(), 0x2D6CDF, cfg::setCustomColourA, cfg, contentX, y, contentWidth));
            y += 22;
            widgets.add(colourRow("Colour B", cfg.getCustomColourB(), 0xF2B134, cfg::setCustomColourB, cfg, contentX, y, contentWidth));
        }
        return widgets;
    }

    private static AbstractWidget colourRow(String label, int rgb, int defaultRgb, IntConsumer set, CapeConfig cfg,
                                            int x, int y, int w) {
        return SettingsButtonWidget.builder(ColorSwatch.label(label, 0xFF000000 | rgb), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), label,
                            0xFF000000 | rgb, 0xFF000000 | defaultRgb, argb -> {
                        set.accept(argb & 0xFFFFFF);
                        cfg.save();
                    }));
                }).bounds(x, y, w, 18).build();
    }

    private static Component patternText(CapeConfig cfg) {
        return Component.literal("Pattern: " + cfg.getCustomPattern().label);
    }

    private static Component capeText(CapeConfig cfg) {
        String name = cfg.isCustom() ? "Make Your Own" : cfg.hasCape() ? titleCase(cfg.getSelected()) : "None";
        return Component.literal("Cape: " + name);
    }

    private static String titleCase(String id) {
        return id.isEmpty() ? id : Character.toUpperCase(id.charAt(0)) + id.substring(1);
    }
}
