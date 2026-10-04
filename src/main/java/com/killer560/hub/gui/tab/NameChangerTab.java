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
import com.killer560.hub.compat.McCompat;

/** Name Changer settings - see {@link NameChangerFeature}'s class doc. Own display name (colour, fade, a colour per
 *  letter, and a reset), and an editable "real name -> display name" list (same add/edit/delete row pattern as
 *  {@link AbilityTimersTab}/{@link PosmsgTab}). Randomize Others moved to its own {@link NickhiderTab} (killer560,
 *  2026-10-04). Purely visual: nothing typed or sent to the server changes. */
public class NameChangerTab extends BaseTab {

    private boolean letterColoursOpen = false;

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
        widgets.add(SettingsButtonWidget.builder(Component.literal("Reset Name to Default"), btn -> {
                    cfg.resetOwnNameCosmetics();
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

        // "Fade Color" (killer560, cosmetics tab: "also allow them to fade the color") - a second colour
        // endpoint that turns the flat single-colour name into a per-letter gradient (NameColor#buildFade).
        // Only the local render sees the gradient - SupportersAutoShare only ever shares the FROM colour
        // (My Name Color above), since the relay's own contract has no true-colour field to carry it.
        int fadeToggleW = cfg.isOwnColorFadeEnabled() ? Math.min(100, (contentWidth - gap) / 2) : contentWidth;
        widgets.add(SettingsButtonWidget.builder(onOff("Fade Color", cfg.isOwnColorFadeEnabled()), btn -> {
                    cfg.setOwnColorFadeEnabled(!cfg.isOwnColorFadeEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, fadeToggleW, 18).build());
        if (cfg.isOwnColorFadeEnabled()) {
            widgets.add(colorButton(contentX + fadeToggleW + gap, y, contentWidth - fadeToggleW - gap,
                    "Fade To Color", cfg.getOwnColorFadeTo(), argb -> {
                        cfg.setOwnColorFadeTo(argb);
                        cfg.save();
                    }));
        }
        y += 24;

        y = buildLetterColours(widgets, cfg, contentX, y, contentWidth, font, requestRebuild);

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

    /**
     * "Letter Colours" (killer560, 2026-10-04: "add an option to change each character's colour for your own
     * name"): one swatch per letter of your display name, each drawn in that letter's current colour. Click one
     * to pick its colour; "Clear Letter Colours" drops them all so the name's normal colour/fade shows again.
     * It is a dropdown so opening it always lays the row out for the name as currently typed - rebuilding the
     * tab on every keystroke would throw the focus out of the name box.
     */
    private int buildLetterColours(List<AbstractWidget> widgets, NameChangerConfig cfg, int contentX, int y,
                                   int contentWidth, net.minecraft.client.gui.Font font, Runnable requestRebuild) {
        y = CollapsibleSection.header(widgets, contentX, y - 4, contentWidth, "Letter Colours", false,
                letterColoursOpen, () -> {
                    letterColoursOpen = !letterColoursOpen;
                    requestRebuild.run();
                });
        if (!letterColoursOpen) {
            return y;
        }
        // Indexed exactly as NameChangerFeature.ownStyled colours it: &-codes become §-codes, which are not letters.
        String text = NameChangerFeature.colorize(cfg.getOwnDisplayName());
        int letters = Math.min(NameColor.visibleLetters(text), NameChangerConfig.MAX_CHAR_COLORS);
        if (letters == 0) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§7Type a display name above first."), font));
            return y + 16;
        }
        int clearW = 130;
        widgets.add(new StringWidget(contentX, y + 3, contentWidth - clearW - 8, 12,
                Component.literal("§7Click a letter to colour it"), font));
        widgets.add(SettingsButtonWidget.builder(Component.literal("Clear Letter Colours"), btn -> {
                    cfg.clearOwnCharColors();
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX + contentWidth - clearW, y, clearW, 18).build());
        y += 22;
        int cell = 18;
        int step = cell + 2;
        int perRow = Math.max(1, (contentWidth + 2) / step);
        List<Integer> codePoints = new ArrayList<>();
        for (int i = 0; i < text.length(); ) {
            if (text.charAt(i) == '§' && i + 1 < text.length()) {
                i += 2;
                continue;
            }
            int cp = text.codePointAt(i);
            codePoints.add(cp);
            i += Character.charCount(cp);
        }
        int idx = 0;
        for (int cp : codePoints) {
            if (idx >= letters) {
                break;
            }
            final int letter = idx;
            String ch = new String(Character.toChars(cp));
            int x = contentX + (letter % perRow) * step;
            int rowY = y + (letter / perRow) * step;
            int own = cfg.getOwnCharColor(letter);
            int shown = own != NameColor.NONE ? own : (cfg.getOwnColor() == NameColor.NONE ? 0xFFFFFFFF : cfg.getOwnColor());
            widgets.add(SettingsButtonWidget.builder(letterLabel(ch, shown), btn -> {
                Minecraft client = Minecraft.getInstance();
                McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), "Letter " + (letter + 1),
                        shown, 0xFFFFFFFF, picked -> {
                    cfg.setOwnCharColor(letter, picked);
                    cfg.save();
                    btn.setMessage(letterLabel(ch, picked));
                }));
            }).bounds(x, rowY, cell, cell).build());
            idx++;
        }
        int rows = (letters + perRow - 1) / perRow;
        return y + rows * step + 4;
    }

    private static Component letterLabel(String ch, int argb) {
        return Component.literal(ch).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(argb & 0xFFFFFF)));
    }

    /** Colour button for a name. killer560 (2026-09-20): "instead of using color codes I select a color for
     *  it". The picker is free-form ARGB, but a replaced name travels as a legacy-coded string, so the pick
     *  is snapped to the nearest of Minecraft's 16 chat colours (see {@link NameColor}). */
    private static AbstractWidget colorButton(int x, int y, int width, String title, int argb,
                                              java.util.function.IntConsumer setter) {
        return SettingsButtonWidget.builder(swatch(argb, width), btn -> {
            Minecraft client = Minecraft.getInstance();
            McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), title,
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
