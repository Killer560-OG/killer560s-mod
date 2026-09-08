package com.killer560.hub.gui.tab;

import com.killer560.hub.translate.TranslateConfig;
import com.killer560.hub.translate.TranslateLanguages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Chat Translate settings: on/off, and a searchable language picker (acts as the "dropdown" - see
 * {@link TranslateLanguages}) reused both here and by the bare {@code /language} command, which
 * sets {@link #openPickerOnNextBuild} before opening the mod menu so it lands straight on it.
 */
public class TranslateTab extends BaseTab {

    private static final int MAX_SHOWN = 9;

    /** Set by the bare {@code /language} command right before it opens the mod menu. */
    public static volatile boolean openPickerOnNextBuild = false;

    private boolean onPickerPage = false;
    private String searchQuery = "";
    private EditBox searchField;

    public TranslateTab() {
        super("Translate");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        if (openPickerOnNextBuild) {
            openPickerOnNextBuild = false;
            onPickerPage = true;
        }
        return onPickerPage
                ? buildPickerPage(contentX, contentY, contentWidth, requestRebuild)
                : buildMainPage(contentX, contentY, contentWidth, requestRebuild);
    }

    private List<AbstractWidget> buildMainPage(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(enabledText(), btn -> {
                    TranslateConfig cfg = TranslateConfig.getInstance();
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    btn.setMessage(enabledText());
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        widgets.add(SettingsButtonWidget.builder(languageText(), btn -> {
                    openPicker(requestRebuild);
                }).bounds(contentX, y, 260, 20).build());
        y += 30;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("When on, chat you type is translated into the selected"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("language before it's sent."), Minecraft.getInstance().font));
        y += 20;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7/translate <language> - sets the language and turns this on"),
                Minecraft.getInstance().font));
        y += 14;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7/language - opens this picker directly"), Minecraft.getInstance().font));

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
                        TranslateConfig cfg = TranslateConfig.getInstance();
                        cfg.setTargetLanguageCode(lang.code());
                        cfg.setEnabled(true);
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
        return Component.literal("Chat Translate Enabled: " + (TranslateConfig.getInstance().isEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component languageText() {
        return Component.literal("Language: §b" + currentLanguageName() + " §7(click to change)");
    }

    private static String currentLanguageName() {
        String code = TranslateConfig.getInstance().getTargetLanguageCode();
        return code.isBlank() ? "Not Set" : TranslateLanguages.nameForCode(code);
    }
}
