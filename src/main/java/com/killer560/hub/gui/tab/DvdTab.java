package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.CommitEditBox;
import com.killer560.hub.dvd.DvdConfig;
import com.killer560.hub.dvd.DvdContentType;
import com.killer560.hub.dvd.DvdEntry;
import com.killer560.hub.dvd.DvdFeature;
import com.killer560.hub.gifplayer.GifAudioFeature;
import com.killer560.hub.gifplayer.GifPlayerFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import com.killer560.hub.gui.SettingsButtonWidget;
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
        // DVD as a whole (killer560, 2026-10-13): first on every page, so it is the card's switch. Off, no box draws
        // (DvdFeature#renderOverlay) and the list and wizard are hidden.
        DvdConfig dc = DvdConfig.getInstance();
        List<AbstractWidget> all = new ArrayList<>();
        all.add(onOffButton("DVD", dc::isDvdEnabled, dc::setDvdEnabled, dc::save, requestRebuild,
                contentX, contentY, contentWidth, ROW_H));
        if (!dc.isDvdEnabled()) {
            return all;
        }
        all.addAll(buildPage(contentX, contentY + ROW_H + 8, contentWidth, requestRebuild));
        return all;
    }

    private List<AbstractWidget> buildPage(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
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

        // "Box size follows the text automatically" moved into the "Scale" tooltip (mod-wide in-panel-paragraph cleanup, 2026-09-21).
        CommitEditBox textField = new CommitEditBox(Minecraft.getInstance().font, contentX, y, 220, ROW_H, Component.literal("Text"));
        textField.setMaxLength(100);
        textField.setValue(e.text);
        widgets.add(textField);
        SettingsButtonWidget textFieldSet = SettingsButtonWidget.builder(Component.literal("Set"), btn -> {
                    e.text = textField.getValue();
                    DvdConfig.getInstance().save();
                    requestRebuild.run();
                }).bounds(contentX + 226, y, contentWidth - 226, ROW_H).build();
        widgets.add(textFieldSet);
        textField.pressOnEnter(textFieldSet);
        y += ROW_H + 4;

        // The color picker (hex box, Copy / Paste, Recent) instead of a bare 6-digit hex field (2026-10-13).
        widgets.add(SettingsButtonWidget.builder(com.killer560.hub.gui.ColorSwatch.label("Text Color", e.textColor()), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    com.killer560.hub.compat.McCompat.setScreen(client, new com.killer560.hub.gui.ColorPickerScreen(
                            com.killer560.hub.compat.McCompat.screen(client), "DVD Text Color", e.textColor(), 0xFFFFFFFF,
                            argb -> {
                                e.textColorHex = String.format(Locale.US, "%06X", argb & 0xFFFFFF);
                                DvdConfig.getInstance().save();
                            }));
                }).bounds(contentX, y, contentWidth, ROW_H).build());
        y += ROW_H + 4;

        widgets.add(onOffButton("Background", () -> e.backgroundEnabled, v -> e.backgroundEnabled = v,
                () -> DvdConfig.getInstance().save(), requestRebuild, contentX, y, contentWidth, ROW_H));
        y += ROW_H + 4;

        widgets.add(onOffButton("Change color on corner hit", () -> e.changeColorOnCornerHit,
                v -> e.changeColorOnCornerHit = v, () -> DvdConfig.getInstance().save(), requestRebuild,
                contentX, y, contentWidth, ROW_H));
        y += ROW_H + 4;

        widgets.add(onOffButton("Change color on any wall hit", () -> e.changeColorOnWallHit,
                v -> e.changeColorOnWallHit = v, () -> DvdConfig.getInstance().save(), requestRebuild,
                contentX, y, contentWidth, ROW_H));
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

        List<String> gifs = GifPlayerFeature.listedFileNames();
        widgets.add(SettingsButtonWidget.cycling(Component.literal("Gif: §b" + (gifs.isEmpty() ? "(none found)"
                        : (e.gifFileName.isBlank() ? "(click to pick)" : GifPlayerFeature.displayName(e.gifFileName)))), (btn, dir) -> {
                    if (gifs.isEmpty()) {
                        return;
                    }
                    int idx = gifs.indexOf(e.gifFileName);
                    e.gifFileName = gifs.get(idx < 0 ? (dir > 0 ? 0 : gifs.size() - 1)
                            : SettingsButtonWidget.step(idx, dir, gifs.size()));
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

        CommitEditBox speedField = new CommitEditBox(Minecraft.getInstance().font, contentX, y, 80, ROW_H, Component.literal("Speed"));
        speedField.setValue(String.format(java.util.Locale.US, "%.2f", e.speedMultiplier));
        widgets.add(speedField);
        SettingsButtonWidget speedFieldSet = SettingsButtonWidget.builder(Component.literal("Set Speed (current " + String.format("%.2fx", e.speedMultiplier) + ")"), btn -> {
                    Float v = parseFloat(speedField.getValue());
                    if (v != null) {
                        e.speedMultiplier = Math.max(0.05f, v);
                        DvdConfig.getInstance().save();
                    }
                    requestRebuild.run();
                }).bounds(contentX + 86, y, contentWidth - 86, ROW_H).build();
        widgets.add(speedFieldSet);
        speedField.pressOnEnter(speedFieldSet);
        y += ROW_H + 4;

        if (e.contentType == DvdContentType.GIF) {
            // Width/Height only matter for GIF content - Text sizes itself to the actual text
            // (see DvdFeature), so showing these here would just be dead settings for it.
            CommitEditBox widthField = new CommitEditBox(Minecraft.getInstance().font, contentX, y, 80, ROW_H, Component.literal("Width"));
            widthField.setValue(String.valueOf(e.boxWidth));
            widgets.add(widthField);
            SettingsButtonWidget widthFieldSet = SettingsButtonWidget.builder(Component.literal("Set Width (current " + e.boxWidth + ")"), btn -> {
                        Integer v = parseInt(widthField.getValue());
                        if (v != null) {
                            e.boxWidth = Math.max(4, v);
                            DvdConfig.getInstance().save();
                        }
                        requestRebuild.run();
                    }).bounds(contentX + 86, y, contentWidth - 86, ROW_H).build();
            widgets.add(widthFieldSet);
            widthField.pressOnEnter(widthFieldSet);
            y += ROW_H + 4;

            CommitEditBox heightField = new CommitEditBox(Minecraft.getInstance().font, contentX, y, 80, ROW_H, Component.literal("Height"));
            heightField.setValue(String.valueOf(e.boxHeight));
            widgets.add(heightField);
            SettingsButtonWidget heightFieldSet = SettingsButtonWidget.builder(Component.literal("Set Height (current " + e.boxHeight + ")"), btn -> {
                        Integer v = parseInt(heightField.getValue());
                        if (v != null) {
                            e.boxHeight = Math.max(4, v);
                            DvdConfig.getInstance().save();
                        }
                        requestRebuild.run();
                    }).bounds(contentX + 86, y, contentWidth - 86, ROW_H).build();
            widgets.add(heightFieldSet);
            heightField.pressOnEnter(heightFieldSet);
            y += ROW_H + 4;
        }

        CommitEditBox scaleField = new CommitEditBox(Minecraft.getInstance().font, contentX, y, 80, ROW_H, Component.literal("Scale"));
        scaleField.setValue(String.format(java.util.Locale.US, "%.2f", e.scale));
        widgets.add(scaleField);
        SettingsButtonWidget scaleFieldSet = SettingsButtonWidget.builder(Component.literal("Set Scale (current " + String.format("%.2fx", e.scale) + ")"), btn -> {
                    Float v = parseFloat(scaleField.getValue());
                    if (v != null) {
                        e.scale = Math.max(0.05f, v);
                        DvdConfig.getInstance().save();
                    }
                    requestRebuild.run();
                }).bounds(contentX + 86, y, contentWidth - 86, ROW_H).build();
        widgets.add(scaleFieldSet);
        scaleField.pressOnEnter(scaleFieldSet);
        y += ROW_H + 4;

        // Game sounds first (killer560, 2026-11-01: "add default corner sounds there arent any"), then his own files.
        List<String> soundOptions = new ArrayList<>();
        soundOptions.add("");
        for (com.killer560.hub.dungeonalerts.SecretSound.SoundChoice c
                : com.killer560.hub.dungeonalerts.SecretSound.SoundChoice.values()) {
            soundOptions.add(DvdFeature.BUILTIN_PREFIX + c.name());
        }
        soundOptions.addAll(GifAudioFeature.discoverFileNames());
        widgets.add(SettingsButtonWidget.cycling(Component.literal("Corner Sound: §b"
                        + DvdFeature.soundLabel(e.cornerHitSoundFile)), (btn, dir) -> {
                    int idx = soundOptions.indexOf(e.cornerHitSoundFile);
                    e.cornerHitSoundFile = soundOptions.get(SettingsButtonWidget.step(Math.max(idx, 0), dir, soundOptions.size()));
                    DvdConfig.getInstance().save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, ROW_H).build());
        y += ROW_H + 4;

        // Corner Volume, 0-500% (killer560, 2026-11-01).
        widgets.add(steppedSlider(contentX, y, contentWidth, ROW_H, cornerVolumeText(e), 0, DvdFeature.MAX_CORNER_VOLUME,
                e.cornerHitVolume, 0.05, v -> e.cornerHitVolume = (float) v, () -> cornerVolumeText(e),
                () -> DvdConfig.getInstance().save()));
        y += ROW_H + 4;

        // The text box's own heading (killer560, 2026-11-01: "put a line that says on screen corner text above").
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("On Screen Corner Text"), Minecraft.getInstance().font));
        y += 13;
        CommitEditBox cornerTextField = new CommitEditBox(Minecraft.getInstance().font, contentX, y, 220, ROW_H,
                Component.literal("Corner hit message"));
        cornerTextField.setMaxLength(150);
        cornerTextField.setValue(e.cornerHitText);
        widgets.add(cornerTextField);
        SettingsButtonWidget cornerTextFieldSet = SettingsButtonWidget.builder(Component.literal("Set"), btn -> {
                    e.cornerHitText = cornerTextField.getValue();
                    DvdConfig.getInstance().save();
                    requestRebuild.run();
                }).bounds(contentX + 226, y, contentWidth - 226, ROW_H).build();
        widgets.add(cornerTextFieldSet);
        cornerTextField.pressOnEnter(cornerTextFieldSet);
        y += ROW_H + 6;

        // Already covered by the "Corner Hit Message" tooltip.
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

    private static Component cornerVolumeText(DvdEntry e) {
        return Component.literal("Corner Volume: " + Math.round(e.cornerHitVolume * 100) + "%");
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
