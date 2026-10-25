package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.petwheel.PetWheelConfig;
import com.killer560.hub.petwheel.PetWheelScreen;
import com.killer560.hub.util.KeyUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import com.killer560.hub.compat.McCompat;

/**
 * Pet Wheel settings (killer560's item 8.2) - OFF by default, New tab until confirmed live. Not cheat-gated
 * (killer560, 2026-09-21: "it's just a reskin of the /pets menu, not real automation"), so this tab is not
 * marked {@link #isCheatOnly()} and has no red header.
 */
public class PetWheelTab extends BaseTab implements KeyCaptureTab {

    /** Which bind the next key or mouse press is for; {@link #NONE} when no capture is running. */
    private enum Capturing { NONE, WHEEL, PREV_PAGE, NEXT_PAGE }

    private Capturing capturing = Capturing.NONE;

    public PetWheelTab() {
        super("Pet Wheel");
    }

    @Override
    public boolean isListeningForKey() {
        return capturing != Capturing.NONE;
    }

    @Override
    public boolean supportsMouseCapture() {
        return true;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        applyCapture(keyCode == com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE ? KeyUtil.NONE : keyCode);
    }

    @Override
    public void onMouseCaptured(int button) {
        applyCapture(KeyUtil.codeForMouseButton(button));
    }

    private void applyCapture(int code) {
        PetWheelConfig cfg = PetWheelConfig.getInstance();
        switch (capturing) {
            case WHEEL -> cfg.setKeyCode(code);
            case PREV_PAGE -> cfg.setPrevPageKeyCode(code);
            case NEXT_PAGE -> cfg.setNextPageKeyCode(code);
            case NONE -> {
                return;
            }
        }
        capturing = Capturing.NONE;
        cfg.save();
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        PetWheelConfig cfg = PetWheelConfig.getInstance();
        var font = Minecraft.getInstance().font;
        int y = contentY;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Pet Wheel", false), font));
        y += 16;

        widgets.add(SettingsButtonWidget.builder(onOff("Pet Wheel", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        int gap = 8;
        int col2W = (contentWidth - gap) / 2;
        int col2bX = contentX + col2W + gap;

        // Theme sits directly under the Pet Wheel switch (moved up from the bottom of the tab, killer560, 2026-10-18).
        widgets.add(com.killer560.hub.gui.ScreenThemes.button(com.killer560.hub.petwheel.PetWheelScreen.THEME_ID, contentX, y, contentWidth, 18));
        y += 24;

        widgets.add(SettingsButtonWidget.builder(keyText(cfg), btn -> {
                    capturing = Capturing.WHEEL;
                    btn.setMessage(Component.literal("Press any key/button..."));
                }).bounds(contentX, y, col2W, 18).build());

        widgets.add(SettingsButtonWidget.cycling(modeText(cfg), (btn, dir) -> {
                    cfg.cycleMode(dir);
                    cfg.save();
                    requestRebuild.run();
                }).bounds(col2bX, y, col2W, 18).build());
        y += 22;

        int minScale = PetWheelConfig.MIN_SCALE_PCT;
        int maxScale = PetWheelConfig.MAX_SCALE_PCT;
        double scaleNorm = (cfg.getScalePercent() - minScale) / (double) (maxScale - minScale);
        widgets.add(new ThemedSliderButton(contentX, y, col2W, 18, scaleText(cfg), scaleNorm) {
            @Override
            protected void updateMessage() {
                setMessage(scaleText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setScalePercent((int) Math.round(minScale + this.value * (maxScale - minScale)));
                saveOnRelease(() -> cfg.save());
            }
        });

        // killer560, 2026-09-27: "make a way to have the pictures alot bigger as well" - a second, independent
        // multiplier that only grows the pet picture itself (see PetWheelConfig#MAX_ICON_SCALE_PCT's doc).
        int minIcon = PetWheelConfig.MIN_ICON_SCALE_PCT;
        int maxIcon = PetWheelConfig.MAX_ICON_SCALE_PCT;
        double iconNorm = (cfg.getIconScalePercent() - minIcon) / (double) (maxIcon - minIcon);
        widgets.add(new ThemedSliderButton(col2bX, y, col2W, 18, iconSizeText(cfg), iconNorm) {
            @Override
            protected void updateMessage() {
                setMessage(iconSizeText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setIconScalePercent((int) Math.round(minIcon + this.value * (maxIcon - minIcon)));
                saveOnRelease(() -> cfg.save());
            }
        });

        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Hide Level", cfg.isHideLevel()), btn -> {
                    cfg.setHideLevel(!cfg.isHideLevel());
                    cfg.save();
                    btn.setMessage(onOff("Hide Level", cfg.isHideLevel()));
                }).bounds(contentX, y, col2W, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Hide Name", cfg.isHideName()), btn -> {
                    cfg.setHideName(!cfg.isHideName());
                    cfg.save();
                    btn.setMessage(onOff("Hide Name", cfg.isHideName()));
                }).bounds(col2bX, y, col2W, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Hide Pets Menu", cfg.isHideMenuWhileSummoning()), btn -> {
                    cfg.setHideMenuWhileSummoning(!cfg.isHideMenuWhileSummoning());
                    cfg.save();
                    btn.setMessage(onOff("Hide Pets Menu", cfg.isHideMenuWhileSummoning()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 24;

        // Pages (killer560, 2026-10-18): the wheel's presets. The page shown here is the one Edit Pets edits and the
        // wheel opens on; on the wheel itself, scroll or Left/Right changes it.
        widgets.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Pages", false), font));
        y += 16;
        // One row as a group: Previous, "Page N of M", Next, then the page's name (the name is saved as it is typed).
        int sideW = contentWidth / 5;
        int labelW = contentWidth * 27 / 100;
        int rowGap = 4;
        int nameW = contentWidth - 2 * sideW - labelW - 3 * rowGap;
        int labelX = contentX + sideW + rowGap;
        int nextX = labelX + labelW + rowGap;
        int nameX = nextX + sideW + rowGap;
        widgets.add(SettingsButtonWidget.builder(Component.literal("Previous"), btn -> {
                    cfg.stepPage(-1);
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, sideW, 18).build());
        widgets.add(new StringWidget(labelX, y + 3, labelW, 12, pageText(cfg), font));
        widgets.add(SettingsButtonWidget.builder(Component.literal("Next"), btn -> {
                    cfg.stepPage(1);
                    cfg.save();
                    requestRebuild.run();
                }).bounds(nextX, y, sideW, 18).build());
        net.minecraft.client.gui.components.EditBox pageName = new net.minecraft.client.gui.components.EditBox(font,
                nameX, y, nameW, 18, Component.literal("Page Name"));
        pageName.setMaxLength(PetWheelConfig.MAX_PAGE_NAME);
        pageName.setValue(cfg.getCurrentPageName());
        pageName.setHint(Component.literal("§8Page name"));
        // Responder set AFTER setValue (see PosmsgTab): the search builds this tab speculatively and must not save.
        pageName.setResponder(text -> {
            cfg.setPageName(cfg.getPageIndex(), text);
            cfg.save();
        });
        widgets.add(pageName);
        y += 22;
        widgets.add(SettingsButtonWidget.builder(Component.literal("Add Page"), btn -> {
                    if (cfg.addPage()) {
                        cfg.save();
                    }
                    requestRebuild.run();
                }).bounds(contentX, y, col2W, 18).build());
        widgets.add(SettingsButtonWidget.builder(Component.literal("Delete Page"), btn -> {
                    if (cfg.removeCurrentPage()) {
                        cfg.save();
                    }
                    requestRebuild.run();
                }).bounds(col2bX, y, col2W, 18).build());
        y += 22;
        // killer560, 2026-10-18: custom page keys. Left/Right and scroll always work on the wheel as well.
        widgets.add(SettingsButtonWidget.builder(pageKeyText("Previous Page", cfg.getPrevPageKeyCode(),
                        capturing == Capturing.PREV_PAGE), btn -> {
                    capturing = Capturing.PREV_PAGE;
                    btn.setMessage(Component.literal("Press any key/button..."));
                }).bounds(contentX, y, col2W, 18).build());
        widgets.add(SettingsButtonWidget.builder(pageKeyText("Next Page", cfg.getNextPageKeyCode(),
                        capturing == Capturing.NEXT_PAGE), btn -> {
                    capturing = Capturing.NEXT_PAGE;
                    btn.setMessage(Component.literal("Press any key/button..."));
                }).bounds(col2bX, y, col2W, 18).build());
        y += 24;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Edit Pets"), btn ->
                        McCompat.setScreen(Minecraft.getInstance(), PetWheelScreen.forEdit(McCompat.screen(Minecraft.getInstance()))))
                .bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        return widgets;
    }

    private static Component pageText(PetWheelConfig cfg) {
        return Component.literal("Page " + (cfg.getPageIndex() + 1) + " of " + cfg.getPageCount());
    }

    private Component keyText(PetWheelConfig cfg) {
        if (capturing == Capturing.WHEEL) {
            return Component.literal("Press any key/button...");
        }
        return Component.literal("Wheel Key: " + KeyUtil.bindDisplayName(cfg.getKeyCode()));
    }

    private static Component pageKeyText(String label, int code, boolean capturingNow) {
        if (capturingNow) {
            return Component.literal("Press any key/button...");
        }
        return Component.literal(label + ": " + KeyUtil.bindDisplayName(code));
    }

    private static Component modeText(PetWheelConfig cfg) {
        String label = cfg.getMode() == PetWheelConfig.InteractionMode.HOLD_RELEASE
                ? "Hold & Release" : "Press then Click";
        return Component.literal("Mode: " + label);
    }

    private static Component scaleText(PetWheelConfig cfg) {
        return Component.literal("Scale: " + cfg.getScalePercent() + "%");
    }

    private static Component iconSizeText(PetWheelConfig cfg) {
        return Component.literal("Icon Size: " + cfg.getIconScalePercent() + "%");
    }
}
