package com.killer560.hub.gui.tab;

import com.killer560.hub.clicktranslate.ClickTranslateConfig;
import com.killer560.hub.translate.TranslateLanguages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Click Translate settings: on/off, and a searchable language picker (same pattern as
 *  {@link TranslateTab}) for which language a clicked message gets translated into - defaults to
 *  English for anyone who's never changed it. See
 *  {@link com.killer560.hub.clicktranslate.ClickTranslateFeature}. */
public class ClickTranslateTab extends BaseTab {

    private static final int MAX_SHOWN = 9;

    private boolean onPickerPage = false;
    private String searchQuery = "";
    private EditBox searchField;

    public ClickTranslateTab() {
        super("Click Translate");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        return onPickerPage
                ? buildPickerPage(contentX, contentY, contentWidth, requestRebuild)
                : buildMainPage(contentX, contentY, contentWidth, requestRebuild);
    }

    private List<AbstractWidget> buildMainPage(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(enabledText(), btn -> {
                    ClickTranslateConfig cfg = ClickTranslateConfig.getInstance();
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    btn.setMessage(enabledText());
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        widgets.add(SettingsButtonWidget.builder(languageText(), btn -> openPicker(requestRebuild))
                .bounds(contentX, y, 260, 20).build());

        return widgets;
    }

    private List<AbstractWidget> buildPickerPage(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("Currently: §b" + currentLanguageName()), Minecraft.getInstance().font));
        y += 16;

        if (searchField == null) {
            searchField = new EditBox(Minecraft.getInstance().font, contentX, y, 260, 20, Component.literal("Search"));
            searchField.setMaxLength(32);
            searchField.setHint(Component.literal("Search languages..."));
            searchField.setValue(searchQuery);
            searchField.setResponder(text -> {
                searchQuery = text;
                requestRebuild.run();
            });
        }
        widgets.add(searchField);
        y += 26;

        List<TranslateLanguages.Lang> matches = TranslateLanguages.search(searchQuery);
        int shown = Math.min(MAX_SHOWN, matches.size());
        for (TranslateLanguages.Lang lang : matches.subList(0, shown)) {
            widgets.add(SettingsButtonWidget.builder(Component.literal(lang.name()), btn -> {
                        ClickTranslateConfig cfg = ClickTranslateConfig.getInstance();
                        cfg.setTargetLanguageCode(lang.code());
                        cfg.save();
                        closePicker(requestRebuild);
                    }).bounds(contentX, y, 220, 20).build());
            y += 22;
        }

        if (matches.isEmpty()) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§7No languages match \"" + searchQuery + "\""), Minecraft.getInstance().font));
            y += 16;
        } else if (matches.size() > shown) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§7+" + (matches.size() - shown) + " more - keep typing to narrow it down"),
                    Minecraft.getInstance().font));
            y += 16;
        }
        y += 6;

        widgets.add(SettingsButtonWidget.builder(Component.literal("<- Back"), btn -> closePicker(requestRebuild))
                .bounds(contentX, y, 220, 20).build());

        return widgets;
    }

    private void openPicker(Runnable requestRebuild) {
        onPickerPage = true;
        requestRebuild.run();
    }

    private void closePicker(Runnable requestRebuild) {
        onPickerPage = false;
        searchQuery = "";
        searchField = null;
        requestRebuild.run();
    }

    private static Component enabledText() {
        return Component.literal("Click Translate Enabled: "
                + (ClickTranslateConfig.getInstance().isEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component languageText() {
        return Component.literal("Translate To: §b" + currentLanguageName() + " §7(click to change)");
    }

    private static String currentLanguageName() {
        return TranslateLanguages.nameForCode(ClickTranslateConfig.getInstance().getTargetLanguageCode());
    }
}
