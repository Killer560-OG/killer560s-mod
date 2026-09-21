package com.killer560.hub.gui.tab;

import com.killer560.hub.armourdye.ArmourDye;
import com.killer560.hub.armourdye.ArmourDyeConfig;
import com.killer560.hub.armourdye.ArmourDyeEntry;
import com.killer560.hub.armourdye.ArmourDyeFeature;
import com.killer560.hub.armourdye.ArmourTrims;
import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.util.KeyUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Armour Recolour settings - see {@link ArmourDye} for what the overrides do, which surfaces they cover and what
 * this borrows from Skyblocker. Everything defaults OFF and nothing here ever touches the real item.
 * <p>
 * Entries are keyed on the Skyblock item id (killer560: "every copy of that piece you own looks the same"), and the
 * two ways to add one both avoid typing that id: the four "Add Worn" buttons take what you have on right now, and
 * the capture key adds whatever armour piece you are hovering in any inventory screen. Every mutation calls
 * {@code save()} straight away - the mod's standing "every setting must persist" rule.
 */
public class ArmourDyeTab extends BaseTab implements KeyCaptureTab {

    private boolean capturingKey = false;

    public ArmourDyeTab() {
        super("Armour Recolour");
    }

    @Override
    public boolean isListeningForKey() {
        return capturingKey;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        if (!capturingKey) {
            return;
        }
        ArmourDyeConfig cfg = ArmourDyeConfig.getInstance();
        cfg.setCaptureKey(keyCode == InputConstants.KEY_ESCAPE ? KeyUtil.NONE : keyCode);
        capturingKey = false;
        cfg.save();
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        ArmourDyeConfig cfg = ArmourDyeConfig.getInstance();
        Minecraft mc = Minecraft.getInstance();
        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int col2X = contentX + colW + gap;
        int y = contentY;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Armour Recolour", false), mc.font));
        y += 16;
        widgets.add(SettingsButtonWidget.builder(onOff("Armour Recolour", cfg.isEnabledRaw()), btn -> {
            cfg.setEnabled(!cfg.isEnabledRaw());
            if (cfg.isEnabledRaw()) {
                // Turning it back on clears the session kill-switch, so a one-off render failure isn't permanent.
                ArmourDye.resetFailures();
            }
            cfg.save();
            requestRebuild.run();
        }).bounds(contentX, y, colW, 18).build());
        widgets.add(toggle(col2X, y, colW, "Skin Inventory Icons", cfg::isSkinInventoryIcons, cfg::setSkinInventoryIcons));
        y += 22;
        widgets.add(SettingsButtonWidget.builder(keyLabel("Capture Key", cfg.getCaptureKey(), capturingKey), btn -> {
            capturingKey = true;
            requestRebuild.run();
        }).bounds(contentX, y, colW, 18).build());
        y += 22;
        // "Client-side only" reassurance moved into the "Armour Recolour" tooltip (mod-wide in-panel-paragraph cleanup, 2026-09-21).
        // The mixin config is required:false so a cosmetic feature can never stop the game booting - but that
        // also means it could quietly do nothing, which is exactly how Item Protect and Object Hider stayed
        // dead for days. Say so rather than let him wonder why his armour never changed (2026-09-16).
        if (cfg.isEnabledRaw() && !ArmourDye.renderHookSeen()) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12, Component.literal(
                    "§8Render hook hasn't fired yet - look at some armour. If this stays, the mixin didn't apply."), mc.font));
            y += 14;
        }
        if (ArmourDye.hasFailed()) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12, Component.literal(
                    "§cDisabled after repeated render errors - toggle it off and on to retry."), mc.font));
            y += 14;
        }
        y += 4;

        // --- add ---
        widgets.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Add A Piece", false), mc.font));
        y += 16;
        int quarterW = (contentWidth - gap * 3) / 4;
        List<ItemStack> worn = ArmourDye.wornArmour(mc.player);
        String[] slotNames = {"Helmet", "Chest", "Legs", "Boots"};
        for (int i = 0; i < 4; i++) {
            ItemStack stack = i < worn.size() ? worn.get(i) : ItemStack.EMPTY;
            int x = contentX + (quarterW + gap) * i;
            boolean usable = ArmourDye.isArmour(stack);
            widgets.add(SettingsButtonWidget.builder(
                    Component.literal((usable ? "§f" : "§8") + "Add " + slotNames[i]), btn -> {
                        // capture() handles the chat feedback and the save for both add paths.
                        ArmourDyeFeature.capture(stack);
                        requestRebuild.run();
                    }).bounds(x, y, quarterW, 18).build());
        }
        y += 22;
        // "Or bind the capture key..." already covered by the "Add A Piece" section's tooltip.

        // --- entries ---
        List<ArmourDyeEntry> entries = cfg.getEntries();
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Pieces (" + entries.size() + ")", false), mc.font));
        y += 16;
        if (entries.isEmpty()) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§7No pieces yet - add one above."), mc.font));
            return widgets;
        }

        List<String> materials = ArmourTrims.materials();
        List<String> patterns = ArmourTrims.patterns();

        for (ArmourDyeEntry entry : entries) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12, Component.literal(
                    (entry.enabled ? "§6" : "§8") + entry.label + " §8(" + entry.itemId + ")"), mc.font));
            y += 14;

            // Every button label is stable regardless of state: SettingTooltips keys on the label with formatting
            // stripped and cut at the first ':', so a label that changes shape would lose its hover description.
            widgets.add(colorButton(contentX, y, colW, entry));
            widgets.add(SettingsButtonWidget.builder(onOff("Use Colour", entry.colorEnabled), btn -> {
                entry.colorEnabled = !entry.colorEnabled;
                cfg.save();
                requestRebuild.run();
            }).bounds(col2X, y, colW, 18).build());
            y += 22;

            widgets.add(SettingsButtonWidget.builder(
                    Component.literal("Skin: " + entry.skin.label), btn -> {
                        entry.skin = entry.skin.next();
                        cfg.save();
                        requestRebuild.run();
                    }).bounds(contentX, y, colW, 18).build());
            widgets.add(SettingsButtonWidget.builder(
                    Component.literal("Trim: " + shortId(entry.trimMaterial)), btn -> {
                        entry.trimMaterial = cycle(materials, entry.trimMaterial);
                        cfg.save();
                        requestRebuild.run();
                    }).bounds(col2X, y, colW, 18).build());
            y += 22;

            widgets.add(SettingsButtonWidget.builder(
                    Component.literal("Pattern: " + shortId(entry.trimPattern)), btn -> {
                        entry.trimPattern = cycle(patterns, entry.trimPattern);
                        cfg.save();
                        requestRebuild.run();
                    }).bounds(contentX, y, colW, 18).build());
            widgets.add(SettingsButtonWidget.builder(onOff("Enabled", entry.enabled), btn -> {
                entry.enabled = !entry.enabled;
                cfg.save();
                requestRebuild.run();
            }).bounds(col2X, y, colW, 18).build());
            y += 22;

            widgets.add(SettingsButtonWidget.builder(Component.literal("§cRemove"), btn -> {
                cfg.remove(entry.itemId);
                cfg.save();
                requestRebuild.run();
            }).bounds(contentX, y, contentWidth, 18).build());
            y += 26;
        }

        if (materials.isEmpty()) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12, Component.literal(
                    "§7Trim lists load once you're in a world."), mc.font));
            y += 14;
        }
        // "Custom ID" skin explanation already covered by the "Skin" tooltip.

        return widgets;
    }

    /** "" -> first entry -> ... -> last entry -> "" (off), so the button can always get back to no trim. */
    private static String cycle(List<String> options, String current) {
        if (options.isEmpty()) {
            return "";
        }
        int idx = options.indexOf(current);
        if (idx < 0) {
            return options.get(0);
        }
        return idx + 1 >= options.size() ? "" : options.get(idx + 1);
    }

    private static String shortId(String id) {
        if (id == null || id.isBlank()) {
            return "§8Off";
        }
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }

    private static AbstractWidget colorButton(int x, int y, int width, ArmourDyeEntry entry) {
        ArmourDyeConfig cfg = ArmourDyeConfig.getInstance();
        return SettingsButtonWidget.builder(ColorSwatch.label("Colour", entry.color), btn -> {
            Minecraft client = Minecraft.getInstance();
            client.setScreen(new ColorPickerScreen(client.screen, entry.label + " Colour", entry.color,
                    0xFFFFFFFF, argb -> {
                // Picking a colour also arms it - killer560 shouldn't have to hit a second toggle after choosing
                // one. "Use Colour" is still there to park a colour without losing it.
                entry.color = argb;
                entry.colorEnabled = true;
                // Live preview as you drag, same as every other colour setting in this mod. save() also refreshes
                // the render-side snapshot, so the armour on your body recolours while the picker is still open.
                cfg.save();
            }));
        }).bounds(x, y, width, 18).build();
    }

    private static AbstractWidget toggle(int x, int y, int width, String label, BooleanSupplier getter, Consumer<Boolean> setter) {
        return SettingsButtonWidget.builder(onOff(label, getter.getAsBoolean()), btn -> {
            setter.accept(!getter.getAsBoolean());
            ArmourDyeConfig.getInstance().save();
            btn.setMessage(onOff(label, getter.getAsBoolean()));
        }).bounds(x, y, width, 18).build();
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    private static Component keyLabel(String label, int key, boolean listening) {
        if (listening) {
            return Component.literal("Press any key...");
        }
        String name = key == KeyUtil.NONE
                ? "Not Set"
                : InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName().getString();
        return Component.literal(label + ": " + name);
    }
}
