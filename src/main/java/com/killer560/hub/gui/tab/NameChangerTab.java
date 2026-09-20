package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.namechanger.NameChangerConfig;
import com.killer560.hub.namechanger.NameChangerFeature;
import com.killer560.hub.namechanger.NameColor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.ArrayList;
import java.util.List;

/** Name Changer / nick hider settings - see {@link NameChangerFeature}'s class doc. Own display name, an editable
 *  "real name -> display name" list (same add/edit/delete row pattern as {@link AbilityTimersTab}/{@link PosmsgTab}),
 *  and the randomize-everyone-else mode. Purely visual: nothing typed or sent to the server changes. */
public class NameChangerTab extends BaseTab {

    public NameChangerTab() {
        super("Name Changer");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        NameChangerConfig cfg = NameChangerConfig.getInstance();
        var font = Minecraft.getInstance().font;
        int y = contentY;
        int gap = 8;
        int halfW = (contentWidth - gap) / 2;

        widgets.add(SettingsButtonWidget.builder(onOff("Name Changer", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        // --- own name
        widgets.add(SettingsButtonWidget.builder(onOff("Change My Name", cfg.isOwnNameEnabled()), btn -> {
                    cfg.setOwnNameEnabled(!cfg.isOwnNameEnabled());
                    cfg.save();
                    btn.setMessage(onOff("Change My Name", cfg.isOwnNameEnabled()));
                }).bounds(contentX, y, halfW, 18).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Randomize Others", cfg.isRandomizeOthers()), btn -> {
                    cfg.setRandomizeOthers(!cfg.isRandomizeOthers());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX + halfW + gap, y, halfW, 18).build());
        y += 24;

        int colorW = 80;
        EditBox ownField = new EditBox(font, contentX, y, contentWidth - colorW - gap, 18,
                Component.literal("My display name"));
        ownField.setMaxLength(64);
        ownField.setValue(cfg.getOwnDisplayName());
        ownField.setHint(Component.literal("§8My display name"));
        ownField.setResponder(text -> {
            cfg.setOwnDisplayName(text);
            cfg.save();
        });
        widgets.add(ownField);
        widgets.add(colorButton(contentX + contentWidth - colorW, y, colorW, "My Name Color", cfg.getOwnColor(),
                argb -> {
                    cfg.setOwnColor(argb);
                    cfg.save();
                }));
        y += 24;

        if (cfg.isRandomizeOthers()) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§7Every other player gets a stable fake name for this session ("
                            + NameChangerFeature.seenPlayerCount() + " seen so far)."), font));
            y += 16;
        }

        // --- manual mappings
        widgets.add(SettingsButtonWidget.builder(onOff("Custom Renames", cfg.isMappingsEnabled()), btn -> {
                    cfg.setMappingsEnabled(!cfg.isMappingsEnabled());
                    cfg.save();
                    btn.setMessage(onOff("Custom Renames", cfg.isMappingsEnabled()));
                }).bounds(contentX, y, halfW, 18).build());
        widgets.add(SettingsButtonWidget.builder(Component.literal("+ Add Rename"), btn -> {
                    cfg.addMapping();
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX + halfW + gap, y, halfW, 18).build());
        y += 24;

        int deleteW = 50;
        int swatchW = 20;
        int fieldW = (contentWidth - deleteW - swatchW - gap * 3 - 14) / 2;
        for (NameChangerConfig.Mapping m : new ArrayList<>(cfg.mappings())) {
            int x = contentX;
            EditBox realField = new EditBox(font, x, y, fieldW, 18, Component.literal("Real IGN"));
            realField.setMaxLength(16);
            realField.setValue(m.real);
            realField.setHint(Component.literal("§8Real IGN"));
            realField.setResponder(text -> {
                m.real = text;
                cfg.save();
            });
            widgets.add(realField);
            x += fieldW + gap / 2;

            widgets.add(new StringWidget(x, y + 3, 14, 12, Component.literal("§7->"), font));
            x += 14 + gap / 2;

            EditBox displayField = new EditBox(font, x, y, fieldW, 18, Component.literal("Shown as"));
            displayField.setMaxLength(64);
            displayField.setValue(m.display);
            displayField.setHint(Component.literal("§8Shown as"));
            displayField.setResponder(text -> {
                m.display = text;
                cfg.save();
            });
            widgets.add(displayField);
            x += fieldW + gap;

            widgets.add(colorButton(x, y, swatchW, "Rename Color", m.color, argb -> {
                m.color = argb;
                cfg.save();
            }));
            x += swatchW + gap;

            widgets.add(SettingsButtonWidget.builder(Component.literal("§cDelete"), btn -> {
                        cfg.removeMapping(m);
                        cfg.save();
                        requestRebuild.run();
                    }).bounds(x, y, deleteW, 18).build());
            y += 22;
        }
        if (cfg.mappings().isEmpty()) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§7No renames yet - hit \"+ Add Rename\"."), font));
            y += 14;
        }

        return widgets;
    }

    /** Colour button for a name. killer560 (2026-09-20): "instead of using color codes I select a color for
     *  it". The picker is free-form ARGB, but a replaced name travels as a legacy-coded string, so the pick
     *  is snapped to the nearest of Minecraft's 16 chat colours (see {@link NameColor}). */
    private static AbstractWidget colorButton(int x, int y, int width, String title, int argb,
                                              java.util.function.IntConsumer setter) {
        return SettingsButtonWidget.builder(swatch(argb, width), btn -> {
            Minecraft client = Minecraft.getInstance();
            client.setScreen(new ColorPickerScreen(client.screen, title,
                    argb == NameColor.NONE ? 0xFFFFFFFF : argb, 0xFFFFFFFF, picked -> {
                setter.accept(picked);
                btn.setMessage(swatch(picked, width));
            }));
        }).bounds(x, y, width, 18).build();
    }

    private static Component swatch(int argb, int width) {
        int shown = argb == NameColor.NONE ? 0xFFFFFFFF : argb;
        Component block = Component.literal("■").withStyle(
                Style.EMPTY.withColor(TextColor.fromRgb(shown & 0xFFFFFF)));
        return width >= 40
                ? Component.literal("Color: ").append(block)
                : Component.empty().append(block);
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
