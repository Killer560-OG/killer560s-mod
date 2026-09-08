package com.killer560.hub.gui.tab;

import com.killer560.hub.dvd.DvdConfig;
import com.killer560.hub.dvd.DvdContentType;
import com.killer560.hub.dvd.DvdEntry;
import com.killer560.hub.dvd.DvdFeature;
import com.killer560.hub.gifplayer.GifAudioFeature;
import com.killer560.hub.gifplayer.GifPlayerFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Bouncing "DVD screensaver" boxes - a list of configured ones, several can run at once (see
 * {@link DvdFeature} for the bounce/render logic). Adding (or editing) one walks through a short
 * wizard rather than one big page of every field at once: pick content type first, then a page of
 * settings specific to that type, then a final page of settings both types share.
 */
public class DvdTab extends BaseTab {

    private static final int ROW_H = 20;

    private enum Step { CONTENT_TYPE, TEXT_DETAILS, GIF_SELECT, FINAL_SETTINGS }

    /** null = showing the list page; otherwise the id of the DVD currently in the wizard. */
    private String editingId = null;
    private Step step = Step.CONTENT_TYPE;

    public DvdTab() {
        super("DVD");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        if (editingId != null) {
            Optional<DvdEntry> found = DvdConfig.getInstance().entries().stream()
                    .filter(e -> e.id.equals(editingId)).findFirst();
            if (found.isPresent()) {
                return buildStep(found.get(), contentX, contentY, contentWidth, requestRebuild);
            }
            editingId = null;
        }
        return buildList(contentX, contentY, contentWidth, requestRebuild);
    }

    // ---------------------------------------------------------------- list page

    private List<AbstractWidget> buildList(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(Component.literal("+ Add New DVD"), btn -> {
                    DvdEntry created = DvdConfig.getInstance().addNew();
                    DvdFeature.reload();
                    editingId = created.id;
                    step = Step.CONTENT_TYPE;
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 20).build());
        y += 28;

        List<DvdEntry> entries = DvdConfig.getInstance().entries();
        if (entries.isEmpty()) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§7No DVDs yet - add one above."), Minecraft.getInstance().font));
            return widgets;
        }

        int toggleW = 60;
        int editW = 60;
        int deleteW = 70;
        int nameW = contentWidth - toggleW - editW - deleteW - 12;
        for (DvdEntry e : entries) {
            widgets.add(new StringWidget(contentX, y + 4, nameW, 12,
                    Component.literal((e.enabled ? "§a" : "§7") + e.name), Minecraft.getInstance().font));

            int x = contentX + nameW + 4;
            widgets.add(SettingsButtonWidget.builder(Component.literal(e.enabled ? "§aON" : "§cOFF"), btn -> {
                        e.enabled = !e.enabled;
                        DvdConfig.getInstance().save();
                        DvdFeature.reload();
                        requestRebuild.run();
                    }).bounds(x, y, toggleW, ROW_H).build());
            x += toggleW + 4;

            widgets.add(SettingsButtonWidget.builder(Component.literal("Edit"), btn -> {
                        editingId = e.id;
                        step = Step.CONTENT_TYPE;
                        requestRebuild.run();
                    }).bounds(x, y, editW, ROW_H).build());
            x += editW + 4;

            widgets.add(SettingsButtonWidget.builder(Component.literal("Delete"), btn -> {
                        DvdConfig.getInstance().remove(e.id);
                        DvdFeature.reload();
                        requestRebuild.run();
                    }).bounds(x, y, deleteW, ROW_H).build());

            y += ROW_H + 4;
        }

        return widgets;
    }

    // ---------------------------------------------------------------- wizard steps

    private List<AbstractWidget> buildStep(DvdEntry e, int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        return switch (step) {
            case CONTENT_TYPE -> buildContentTypeStep(e, contentX, contentY, contentWidth, requestRebuild);
            case TEXT_DETAILS -> buildTextDetailsStep(e, contentX, contentY, contentWidth, requestRebuild);
            case GIF_SELECT -> buildGifSelectStep(e, contentX, contentY, contentWidth, requestRebuild);
            case FINAL_SETTINGS -> buildFinalSettingsStep(e, contentX, contentY, contentWidth, requestRebuild);
        };
    }

    private List<AbstractWidget> buildContentTypeStep(DvdEntry e, int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("What should " + e.name + " show?"), Minecraft.getInstance().font));
        y += 20;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Text"), btn -> {
                    e.contentType = DvdContentType.TEXT;
                    DvdConfig.getInstance().save();
                    DvdFeature.reload();
                    step = Step.TEXT_DETAILS;
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, ROW_H).build());
        y += ROW_H + 4;

        widgets.add(SettingsButtonWidget.builder(Component.literal("GIF"), btn -> {
                    e.contentType = DvdContentType.GIF;
                    DvdConfig.getInstance().save();
                    DvdFeature.reload();
                    step = Step.GIF_SELECT;
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, ROW_H).build());
        y += ROW_H + 10;

        widgets.add(SettingsButtonWidget.builder(Component.literal("<- Back to list"), btn -> {
                    editingId = null;
                    requestRebuild.run();
                }).bounds(contentX, y, 140, ROW_H).build());

        return widgets;
    }

    private List<AbstractWidget> buildTextDetailsStep(DvdEntry e, int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Box size follows the text automatically - use Scale to resize."),
                Minecraft.getInstance().font));
        y += 14;

        EditBox textField = new EditBox(Minecraft.getInstance().font, contentX, y, 220, ROW_H, Component.literal("Text"));
        textField.setMaxLength(100);
        textField.setValue(e.text);
        widgets.add(textField);
        widgets.add(SettingsButtonWidget.builder(Component.literal("Set"), btn -> {
                    e.text = textField.getValue();
                    DvdConfig.getInstance().save();
                    requestRebuild.run();
                }).bounds(contentX + 226, y, contentWidth - 226, ROW_H).build());
        y += ROW_H + 4;

        EditBox colorField = new EditBox(Minecraft.getInstance().font, contentX, y, 100, ROW_H, Component.literal("Text color hex"));
        colorField.setMaxLength(6);
        colorField.setValue(e.textColorHex);
        widgets.add(colorField);
        widgets.add(SettingsButtonWidget.builder(Component.literal("Set Color (hex, e.g. FF00AA)"), btn -> {
                    String hex = colorField.getValue().replace("#", "").trim();
                    if (hex.matches("(?i)[0-9a-f]{6}")) {
                        e.textColorHex = hex.toUpperCase(Locale.US);
                        DvdConfig.getInstance().save();
                    }
                    requestRebuild.run();
                }).bounds(contentX + 106, y, contentWidth - 106, ROW_H).build());
        y += ROW_H + 4;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Background: " + (e.backgroundEnabled ? "§aON" : "§cOFF")), btn -> {
                    e.backgroundEnabled = !e.backgroundEnabled;
                    DvdConfig.getInstance().save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, ROW_H).build());
        y += ROW_H + 4;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Change color on corner hit: "
                        + (e.changeColorOnCornerHit ? "§aON" : "§cOFF")), btn -> {
                    e.changeColorOnCornerHit = !e.changeColorOnCornerHit;
                    DvdConfig.getInstance().save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, ROW_H).build());
        y += ROW_H + 4;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Change color on any wall hit: "
                        + (e.changeColorOnWallHit ? "§aON" : "§cOFF")), btn -> {
                    e.changeColorOnWallHit = !e.changeColorOnWallHit;
                    DvdConfig.getInstance().save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, ROW_H).build());
        y += ROW_H + 10;

        widgets.add(SettingsButtonWidget.builder(Component.literal("<- Back"), btn -> {
                    step = Step.CONTENT_TYPE;
                    requestRebuild.run();
                }).bounds(contentX, y, 100, ROW_H).build());
        widgets.add(SettingsButtonWidget.builder(Component.literal("Next ->"), btn -> {
                    step = Step.FINAL_SETTINGS;
                    requestRebuild.run();
                }).bounds(contentX + 106, y, contentWidth - 106, ROW_H).build());

        return widgets;
    }

    private List<AbstractWidget> buildGifSelectStep(DvdEntry e, int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        List<String> gifs = GifPlayerFeature.discoverFileNames();
        widgets.add(SettingsButtonWidget.builder(Component.literal("Gif: §b" + (gifs.isEmpty() ? "(none found)"
                        : (e.gifFileName.isBlank() ? "(click to pick)" : e.gifFileName))), btn -> {
                    if (gifs.isEmpty()) {
                        return;
                    }
                    int idx = gifs.indexOf(e.gifFileName);
                    e.gifFileName = gifs.get((idx + 1) % gifs.size());
                    DvdConfig.getInstance().save();
                    DvdFeature.reload();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, ROW_H).build());
        y += ROW_H + 4;

        if (gifs.isEmpty()) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§7Drop a .gif in the GIF Player folder first."), Minecraft.getInstance().font));
            y += 16;
        }
        y += 6;

        widgets.add(SettingsButtonWidget.builder(Component.literal("<- Back"), btn -> {
                    step = Step.CONTENT_TYPE;
                    requestRebuild.run();
                }).bounds(contentX, y, 100, ROW_H).build());
        widgets.add(SettingsButtonWidget.builder(Component.literal("Next ->"), btn -> {
                    step = Step.FINAL_SETTINGS;
                    requestRebuild.run();
                }).bounds(contentX + 106, y, contentWidth - 106, ROW_H).build());

        return widgets;
    }

    private List<AbstractWidget> buildFinalSettingsStep(DvdEntry e, int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        EditBox speedField = new EditBox(Minecraft.getInstance().font, contentX, y, 80, ROW_H, Component.literal("Speed"));
        speedField.setValue(String.format("%.2f", e.speedMultiplier));
        widgets.add(speedField);
        widgets.add(SettingsButtonWidget.builder(Component.literal("Set Speed (current " + String.format("%.2fx", e.speedMultiplier) + ")"), btn -> {
                    Float v = parseFloat(speedField.getValue());
                    if (v != null) {
                        e.speedMultiplier = Math.max(0.05f, v);
                        DvdConfig.getInstance().save();
                    }
                    requestRebuild.run();
                }).bounds(contentX + 86, y, contentWidth - 86, ROW_H).build());
        y += ROW_H + 4;

        if (e.contentType == DvdContentType.GIF) {
            // Width/Height only matter for GIF content - Text sizes itself to the actual text
            // (see DvdFeature), so showing these here would just be dead settings for it.
            EditBox widthField = new EditBox(Minecraft.getInstance().font, contentX, y, 80, ROW_H, Component.literal("Width"));
            widthField.setValue(String.valueOf(e.boxWidth));
            widgets.add(widthField);
            widgets.add(SettingsButtonWidget.builder(Component.literal("Set Width (current " + e.boxWidth + ")"), btn -> {
                        Integer v = parseInt(widthField.getValue());
                        if (v != null) {
                            e.boxWidth = Math.max(4, v);
                            DvdConfig.getInstance().save();
                        }
                        requestRebuild.run();
                    }).bounds(contentX + 86, y, contentWidth - 86, ROW_H).build());
            y += ROW_H + 4;

            EditBox heightField = new EditBox(Minecraft.getInstance().font, contentX, y, 80, ROW_H, Component.literal("Height"));
            heightField.setValue(String.valueOf(e.boxHeight));
            widgets.add(heightField);
            widgets.add(SettingsButtonWidget.builder(Component.literal("Set Height (current " + e.boxHeight + ")"), btn -> {
                        Integer v = parseInt(heightField.getValue());
                        if (v != null) {
                            e.boxHeight = Math.max(4, v);
                            DvdConfig.getInstance().save();
                        }
                        requestRebuild.run();
                    }).bounds(contentX + 86, y, contentWidth - 86, ROW_H).build());
            y += ROW_H + 4;
        }

        EditBox scaleField = new EditBox(Minecraft.getInstance().font, contentX, y, 80, ROW_H, Component.literal("Scale"));
        scaleField.setValue(String.format("%.2f", e.scale));
        widgets.add(scaleField);
        widgets.add(SettingsButtonWidget.builder(Component.literal("Set Scale (current " + String.format("%.2fx", e.scale) + ")"), btn -> {
                    Float v = parseFloat(scaleField.getValue());
                    if (v != null) {
                        e.scale = Math.max(0.05f, v);
                        DvdConfig.getInstance().save();
                    }
                    requestRebuild.run();
                }).bounds(contentX + 86, y, contentWidth - 86, ROW_H).build());
        y += ROW_H + 4;

        List<String> soundOptions = new ArrayList<>();
        soundOptions.add("");
        soundOptions.addAll(GifAudioFeature.discoverFileNames());
        widgets.add(SettingsButtonWidget.builder(Component.literal("Corner Sound: §b"
                        + (e.cornerHitSoundFile.isBlank() ? "None" : e.cornerHitSoundFile)), btn -> {
                    int idx = soundOptions.indexOf(e.cornerHitSoundFile);
                    e.cornerHitSoundFile = soundOptions.get((Math.max(idx, 0) + 1) % soundOptions.size());
                    DvdConfig.getInstance().save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, ROW_H).build());
        y += ROW_H + 4;

        EditBox cornerTextField = new EditBox(Minecraft.getInstance().font, contentX, y, 220, ROW_H,
                Component.literal("Corner hit message"));
        cornerTextField.setMaxLength(150);
        cornerTextField.setValue(e.cornerHitText);
        widgets.add(cornerTextField);
        widgets.add(SettingsButtonWidget.builder(Component.literal("Set"), btn -> {
                    e.cornerHitText = cornerTextField.getValue();
                    DvdConfig.getInstance().save();
                    requestRebuild.run();
                }).bounds(contentX + 226, y, contentWidth - 226, ROW_H).build());
        y += ROW_H + 6;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Corner message supports {name} (the gif's filename, or this"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7DVD's own name for Text content). Shown on screen, not sent to chat."),
                Minecraft.getInstance().font));
        y += 16;

        widgets.add(SettingsButtonWidget.builder(Component.literal("<- Back"), btn -> {
                    step = e.contentType == DvdContentType.TEXT ? Step.TEXT_DETAILS : Step.GIF_SELECT;
                    requestRebuild.run();
                }).bounds(contentX, y, 100, ROW_H).build());
        widgets.add(SettingsButtonWidget.builder(Component.literal("Done"), btn -> {
                    editingId = null;
                    requestRebuild.run();
                }).bounds(contentX + 106, y, contentWidth - 106, ROW_H).build());

        return widgets;
    }

    private static Float parseFloat(String text) {
        try {
            return Float.parseFloat(text.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer parseInt(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
