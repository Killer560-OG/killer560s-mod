package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
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

        widgets.add(SettingsButtonWidget.builder(onOff("Item Protection", cfg.isEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.isEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabledRaw()) {
            return widgets;
        }

        int half = (contentWidth - 8) / 2;
        int col2 = contentX + half + 8;

        // ---------------- Protect Item ----------------
        widgets.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Protect Item", false), mc.font));
        y += 16;

        widgets.add(SettingsButtonWidget.builder(onOff("Protect Item", cfg.isProtectItemEnabledRaw()), btn -> {
                    cfg.setProtectItemEnabled(!cfg.isProtectItemEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        if (cfg.isProtectItemEnabledRaw()) {
            widgets.add(SettingsButtonWidget.builder(keyLabel("Protect Key", cfg.getProtectKey(), capturing == Capturing.PROTECT), btn -> {
                        capturing = Capturing.PROTECT;
                        btn.setMessage(Component.literal("Press any key..."));
                    }).bounds(contentX, y, half, 18).build());
            widgets.add(SettingsButtonWidget.builder(onOff("Lock In Place", cfg.isLockInPlace()), btn -> {
                        cfg.setLockInPlace(!cfg.isLockInPlace());
                        cfg.save();
                        btn.setMessage(onOff("Lock In Place", cfg.isLockInPlace()));
                    }).bounds(col2, y, half, 18).build());
            y += 22;

            widgets.add(SettingsButtonWidget.builder(onOff("Item ID Fallback", cfg.isUseItemIdFallback()), btn -> {
                        cfg.setUseItemIdFallback(!cfg.isUseItemIdFallback());
                        cfg.save();
                        btn.setMessage(onOff("Item ID Fallback", cfg.isUseItemIdFallback()));
                    }).bounds(contentX, y, half, 18).build());
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
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Auto-Protect Starred Items", false), mc.font));
        y += 16;

        widgets.add(SettingsButtonWidget.builder(onOff("Auto-Protect Starred", cfg.isProtectStarredEnabledRaw()), btn -> {
                    cfg.setProtectStarredEnabled(!cfg.isProtectStarredEnabledRaw());
                    cfg.save();
                    btn.setMessage(onOff("Auto-Protect Starred", cfg.isProtectStarredEnabledRaw()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;


        // ---------------- Hotbar drops ----------------
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Prevent Hotbar Drops", false), mc.font));
        y += 16;

        widgets.add(SettingsButtonWidget.builder(onOff("Prevent Hotbar Drops", cfg.isPreventHotbarDropEnabledRaw()), btn -> {
                    cfg.setPreventHotbarDropEnabled(!cfg.isPreventHotbarDropEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        if (cfg.isPreventHotbarDropEnabledRaw()) {
            widgets.add(SettingsButtonWidget.builder(onOff("Confirm To Force", cfg.isConfirmToForce()), btn -> {
                        cfg.setConfirmToForce(!cfg.isConfirmToForce());
                        cfg.save();
                        btn.setMessage(onOff("Confirm To Force", cfg.isConfirmToForce()));
                    }).bounds(contentX, y, half, 18).build());
            widgets.add(SettingsButtonWidget.builder(onOff("Block Every Drop", cfg.isBlockEveryHotbarDrop()), btn -> {
                        cfg.setBlockEveryHotbarDrop(!cfg.isBlockEveryHotbarDrop());
                        cfg.save();
                        btn.setMessage(onOff("Block Every Drop", cfg.isBlockEveryHotbarDrop()));
                    }).bounds(col2, y, half, 18).build());
            y += 22;

        }

        // ---------------- Feedback ----------------
        widgets.add(SettingsButtonWidget.builder(onOff("Block Sound", cfg.isBlockSound()), btn -> {
                    cfg.setBlockSound(!cfg.isBlockSound());
                    cfg.save();
                    btn.setMessage(onOff("Block Sound", cfg.isBlockSound()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    private static Component keyLabel(String label, int key, boolean listening) {
        if (listening) {
            return Component.literal("Press any key...");
        }
        return Component.literal(label + ": " + CommandKeybindsTab.bindName(key));
    }
}
