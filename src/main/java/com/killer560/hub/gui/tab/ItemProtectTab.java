package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.itemprotect.ItemProtectConfig;
import com.killer560.hub.util.KeyUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import com.killer560.hub.compat.McCompat;

/** Item Protection settings - see {@link com.killer560.hub.itemprotect.ItemProtectFeature}'s class doc for
 *  exactly what each guard blocks and where it hooks in. Everything ships OFF. Slot Lock was removed 2026-10-08
 *  (Protect Item's Lock In Place does its job per item). */
public class ItemProtectTab extends BaseTab implements KeyCaptureTab {

    private enum Capturing {
        NONE, PROTECT
    }

    private Capturing capturing = Capturing.NONE;

    public ItemProtectTab() {
        super("Item Protection");
    }

    @Override
    public boolean isListeningForKey() {
        return capturing != Capturing.NONE;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        applyCapture(keyCode == InputConstants.KEY_ESCAPE ? KeyUtil.NONE : keyCode);
    }

    /** Lets {@code ModScreen} route the next mouse press here instead of to the widget under the cursor.
     *  Not yet an interface method - the {@code KeyCaptureTab}/{@code ModScreen} patch is in this wave's
     *  staging notes; it becomes an override the moment that lands. */
    public boolean supportsMouseCapture() {
        return true;
    }

    /** Mouse half of the capture (killer560: "make all of the keybind things compatible with mouse buttons
     *  and middle mouse buttons"). Not yet an interface method - {@code ModScreen}'s routing patch is in this
     *  wave's staging notes; until it lands this simply never gets called. */
    public void onMouseCaptured(int button) {
        applyCapture(ItemProtectConfig.codeForMouseButton(button));
    }

    private void applyCapture(int code) {
        ItemProtectConfig cfg = ItemProtectConfig.getInstance();
        switch (capturing) {
            case PROTECT -> cfg.setProtectKey(code);
            default -> {
                return;
            }
        }
        capturing = Capturing.NONE;
        cfg.save();
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        ItemProtectConfig cfg = ItemProtectConfig.getInstance();
        Minecraft mc = Minecraft.getInstance();
        int y = contentY;

        widgets.add(onOffButton("Item Protection", cfg::isEnabledRaw, cfg::setEnabled, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += 26;

        if (!cfg.isEnabledRaw()) {
            return widgets;
        }

        int half = (contentWidth - 8) / 2;
        int col2 = contentX + half + 8;

        // ---------------- Protect Item ----------------
        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Protect Item", false));
        y += 16;

        widgets.add(onOffButton("Protect Item", cfg::isProtectItemEnabledRaw, cfg::setProtectItemEnabled, cfg::save,
                requestRebuild, contentX, y, contentWidth, 18));
        y += 22;

        if (cfg.isProtectItemEnabledRaw()) {
            widgets.add(SettingsButtonWidget.builder(keyLabel("Protect Key", cfg.getProtectKey(), capturing == Capturing.PROTECT), btn -> {
                        capturing = Capturing.PROTECT;
                        btn.setMessage(Component.literal("Press any key..."));
                    }).bounds(contentX, y, half, 18).build());
            widgets.add(onOffButton("Lock In Place", cfg::isLockInPlace, cfg::setLockInPlace, cfg::save,
                    null, col2, y, half, 18));
            y += 22;

            widgets.add(onOffButton("Item ID Fallback", cfg::isUseItemIdFallback, cfg::setUseItemIdFallback, cfg::save,
                    null, contentX, y, half, 18));
            widgets.add(SettingsButtonWidget.builder(ColorSwatch.label("Star Color", cfg.getProtectedColor()), btn -> {
                        Minecraft client = Minecraft.getInstance();
                        McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), "Protected Item Star Color",
                                cfg.getProtectedColor(), ItemProtectConfig.DEFAULT_STAR_COLOR, argb -> {
                            cfg.setProtectedColor(argb);
                            cfg.save();
                        }));
                    }).bounds(col2, y, half, 18).build());
            y += 22;

            for (String name : new ArrayList<>(cfg.getProtectedNames())) {
                widgets.add(new StringWidget(contentX, y, half, 12, Component.literal("§f" + name), mc.font));
                widgets.add(SettingsButtonWidget.builder(Component.literal("§cRemove"), btn -> {
                            cfg.removeProtectedName(name);
                            cfg.save();
                            requestRebuild.run();
                        }).bounds(col2, y - 2, half, 16).build());
                y += 18;
            }

            int savedItems = cfg.getProtectedKeys().size();
            widgets.add(new StringWidget(contentX, y, half, 12,
                    Component.literal("§7Items saved by ID: §f" + savedItems), mc.font));
            y += 22;
        }

        // ---------------- Starred ----------------
        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Auto-Protect Starred Items", false));
        y += 16;

        widgets.add(onOffButton("Auto-Protect Starred", cfg::isProtectStarredEnabledRaw, cfg::setProtectStarredEnabled,
                cfg::save, null, contentX, y, contentWidth, 18));
        y += 22;


        // ---------------- Hotbar drops ----------------
        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Prevent Hotbar Drops", false));
        y += 16;

        widgets.add(onOffButton("Prevent Hotbar Drops", cfg::isPreventHotbarDropEnabledRaw,
                cfg::setPreventHotbarDropEnabled, cfg::save, requestRebuild, contentX, y, contentWidth, 18));
        y += 22;

        if (cfg.isPreventHotbarDropEnabledRaw()) {
            widgets.add(onOffButton("Confirm To Force", cfg::isConfirmToForce, cfg::setConfirmToForce, cfg::save,
                    requestRebuild, contentX, y, half, 18));
            widgets.add(onOffButton("Block Every Drop", cfg::isBlockEveryHotbarDrop, cfg::setBlockEveryHotbarDrop,
                    cfg::save, null, col2, y, half, 18));
            y += 22;
            if (cfg.isConfirmToForce()) {
                int minD = ItemProtectConfig.MIN_FORCE_DROPS;
                int maxD = ItemProtectConfig.MAX_FORCE_DROPS;
                widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18, forceDropsText(cfg),
                        (cfg.getForceDrops() - minD) / (double) (maxD - minD)) {
                    @Override
                    protected void updateMessage() {
                        setMessage(forceDropsText(ItemProtectConfig.getInstance()));
                    }

                    @Override
                    protected void applyValue() {
                        ItemProtectConfig c = ItemProtectConfig.getInstance();
                        int snapped = minD + (int) Math.round(this.value * (maxD - minD));
                        c.setForceDrops(snapped);
                        saveOnRelease(() -> c.save());
                        this.value = (snapped - minD) / (double) (maxD - minD);
                    }
                });
                y += 22;
            }
        }

        // ---------------- Feedback ----------------
        widgets.add(onOffButton("Block Sound", cfg::isBlockSound, cfg::setBlockSound, cfg::save,
                null, contentX, y, contentWidth, 18));
        y += 22;

        return widgets;
    }

    private static Component forceDropsText(ItemProtectConfig cfg) {
        return Component.literal("Drops To Force: " + cfg.getForceDrops());
    }

    private static Component keyLabel(String label, int key, boolean listening) {
        if (listening) {
            return Component.literal("Press any key...");
        }
        return Component.literal(label + ": " + CommandKeybindsTab.bindName(key));
    }
}
