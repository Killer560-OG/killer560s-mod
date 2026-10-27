package com.killer560.hub.gui.tab;

import com.killer560.hub.hud.HudConfig;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.components.AbstractWidget;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.ThemedSliderButton;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Home's Mod & HUD section: Notify Me Of Updates, Skyblock Only, the Edit HUD key, HUD Scale, Auto Scale and the HUD
 *  editor's options. Check for Updates, Join Discord, Edit HUD Positions and Edit Bars left it on 2026-10-13 (killer560:
 *  they live elsewhere - the header's round buttons and Home's quick buttons). */
public class HomeMainTab extends BaseTab implements KeyCaptureTab {

    /** Which key is being captured: 0 none, 1 Edit HUD, 2 Open Menu. */
    private int listening = 0;

    public HomeMainTab() {
        super("Mod & HUD");
    }

    /** Independent settings and no master one: the menu card shows no switch (its first row is Notify Me Of Updates,
     *  which must not become the card's switch). */
    @Override
    public SettingsButtonWidget masterToggle(List<AbstractWidget> built) {
        return null;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int gap = 8;
        int half = (contentWidth - gap) / 2;
        int rightW = Math.max(1, contentWidth - half - gap);
        int y = contentY;

        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Mod", false));
        y += 16;

        widgets.add(SettingsButtonWidget.builder(updateNotifyText(), btn -> {
                    var ucfg = com.killer560.hub.updatecheck.UpdateCheckConfig.getInstance();
                    ucfg.setNotifyOnStart(!ucfg.isNotifyOnStart());
                    ucfg.save();
                    btn.setMessage(updateNotifyText());
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        widgets.add(SettingsButtonWidget.builder(skyblockOnlyText(), btn -> {
                    com.killer560.hub.util.SkyblockGate.setEnabled(!com.killer560.hub.util.SkyblockGate.isEnabled());
                    btn.setMessage(skyblockOnlyText());
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        // Open Menu key (killer560, 2026-10-26): backslash by default; click, then press a key (Esc unbinds).
        widgets.add(SettingsButtonWidget.builder(listening == 2 ? Component.literal("Press any key...") : openMenuText(),
                btn -> {
                    listening = 2;
                    btn.setMessage(Component.literal("Press any key..."));
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        // Sweat Mode is the button at the top of Home only (killer560, 2026-10-18), not a row in Mod or HUD.
        y += 4;

        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "HUD", false));
        y += 16;

        Component keybindLabel = listening == 1
                ? Component.literal("Press any key...")
                : keybindText();
        widgets.add(SettingsButtonWidget.builder(keybindLabel, btn -> {
                    listening = 1;
                    btn.setMessage(Component.literal("Press any key..."));
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        // Global HUD scale (killer560, 2026-10-04, "just like SkyHanni"): 5%..300% in 5% steps, multiplied onto every
        // element's own scale - see HudConfig#globalScale. Saved in killer560smod-hud.json, so profiles carry it.
        float span = HudConfig.MAX_GLOBAL_SCALE - HudConfig.MIN_GLOBAL_SCALE;
        double normalized = (HudConfig.getInstance().getGlobalScale() - HudConfig.MIN_GLOBAL_SCALE) / span;
        widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 20, globalScaleText(), normalized) {
            @Override
            protected void updateMessage() {
                setMessage(globalScaleText());
            }

            @Override
            protected void applyValue() {
                HudConfig cfg = HudConfig.getInstance();
                cfg.setGlobalScale(HudConfig.MIN_GLOBAL_SCALE + (float) this.value * span);
                // Written once on release / key, not on every drag tick (the file holds every HUD position). The shared
                // save-on-release survives this row being rebuilt mid-drag and writes on its own if the menu closes first.
                saveOnRelease(() -> HudConfig.getInstance().save());
            }
        });
        y += 26;

        // Auto Scale (monitor), killer560 2026-10-05: ON by default. Sizes the HUD AND the mod's menus to the window
        // relative to his 2560x1440 / GUI 3 monitor (see hud/AutoScale); the HUD Scale slider above multiplies on
        // top. The factor in use is shown so a "why is it smaller here" has its answer on the button. Toggling it
        // re-lays this very menu out on its next frame (AutoScaleScreenMixin), which rebuilds this widget too.
        widgets.add(SettingsButtonWidget.builder(autoScaleText(), btn -> {
                    HudConfig cfg = HudConfig.getInstance();
                    cfg.setAutoScale(!cfg.isAutoScale());
                    cfg.save();
                    btn.setMessage(autoScaleText());
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        // The HUD editor's own options (2026-10-08, killer560: "the snapping stuff and all that isn't there but instead
        // in the actual mod"). They used to be buttons on the editor screen; the same HudConfig fields, so nothing
        // saved before is lost and the editor reads them on every drag.
        y += 4;
        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "HUD Editor", false));
        y += 16;
        HudConfig hc = HudConfig.getInstance();
        widgets.add(toggle("Snapping", hc::isEditorSnap, hc::setEditorSnap, contentX, y, half, false, requestRebuild));
        widgets.add(toggle("Show Unseen", hc::isEditorShowAll, hc::setEditorShowAll, contentX + half + gap, y,
                rightW, false, null));
        y += 24;
        widgets.add(toggle("Element Edges", hc::isSnapElementEdges, hc::setSnapElementEdges, contentX, y, half, true, null));
        widgets.add(toggle("Element Centers", hc::isSnapElementCentres, hc::setSnapElementCentres,
                contentX + half + gap, y, rightW, true, null));
        y += 24;
        widgets.add(toggle("Screen Edges", hc::isSnapScreenEdges, hc::setSnapScreenEdges, contentX, y, half, true, null));
        widgets.add(toggle("Screen Center", hc::isSnapScreenCentre, hc::setSnapScreenCentre, contentX + half + gap, y,
                rightW, true, null));
        y += 24;
        // Alone on its line, so full width: its switch lines up with the others on the right (at half width it sat in
        // the middle of the row, killer560 2026-10-13).
        widgets.add(toggle("Equal Spacing", hc::isSnapEqualSpacing, hc::setSnapEqualSpacing, contentX, y, contentWidth, true,
                null));
        y += 24;
        // The HUD editor's own Theme (screen sweep, 2026-10-13): Theme Default follows the menu.
        com.killer560.hub.gui.ScreenThemes.append(widgets, com.killer560.hub.hud.HudEditorScreen.THEME_ID, contentX, contentWidth, y);

        return widgets;
    }

    /** An ON/OFF HudConfig switch. A snap target reads gray while Snapping is off: kept, but nothing snaps. */
    private static SettingsButtonWidget toggle(String label, java.util.function.BooleanSupplier get,
                                               java.util.function.Consumer<Boolean> set, int x, int y, int w,
                                               boolean snapTarget, Runnable rebuildOrNull) {
        return SettingsButtonWidget.builder(toggleText(label, get.getAsBoolean(), snapTarget), btn -> {
                    set.accept(!get.getAsBoolean());
                    HudConfig.getInstance().save();
                    if (rebuildOrNull != null) {
                        rebuildOrNull.run(); // the snap targets' gray follows the master switch
                        return;
                    }
                    btn.setMessage(toggleText(label, get.getAsBoolean(), snapTarget));
                }).bounds(x, y, Math.max(1, w), 20).build();
    }

    private static Component toggleText(String label, boolean on, boolean snapTarget) {
        String name = snapTarget && !HudConfig.getInstance().isEditorSnap() ? "§7" + label : label;
        return Component.literal(name + ": " + (on ? "§aON" : "§cOFF"));
    }

    private static Component autoScaleText() {
        if (!HudConfig.getInstance().isAutoScale()) {
            return Component.literal("Auto Scale (monitor): §cOFF");
        }
        float f = com.killer560.hub.hud.AutoScale.current();
        return Component.literal("Auto Scale (monitor): §aON §7(" + Math.round(f * 100) + "% here)");
    }

    private static Component globalScaleText() {
        return Component.literal("HUD Scale: §b" + Math.round(HudConfig.getInstance().getGlobalScale() * 100) + "%");
    }

    private static Component updateNotifyText() {
        return Component.literal("Notify Me Of Updates: "
                + (com.killer560.hub.updatecheck.UpdateCheckConfig.getInstance().isNotifyOnStart() ? "§aON" : "§cOFF"));
    }

    private static Component skyblockOnlyText() {
        return Component.literal("Skyblock Only: " + (com.killer560.hub.util.SkyblockGate.isEnabled() ? "§aON" : "§cOFF"));
    }

    public boolean isListeningForKey() {
        return listening != 0;
    }

    public void onKeyCaptured(int keyCode) {
        int code = keyCode == InputConstants.KEY_ESCAPE ? -1 : keyCode;
        // Escape unbinds (-1), same convention as every other KeyCaptureTab - it used to bind Escape itself.
        if (listening == 2) {
            HudConfig.getInstance().setOpenMenuKeyCode(code);
        } else {
            HudConfig.getInstance().setEditKeyCode(code);
        }
        listening = 0;
        HudConfig.getInstance().save();
    }

    private static Component openMenuText() {
        int code = HudConfig.getInstance().getOpenMenuKeyCode();
        String name = code < 0 ? "Not Set"
                : InputConstants.Type.KEYSYM.getOrCreate(code).getDisplayName().getString();
        return Component.literal("Open Menu Key: §b" + name);
    }

    private static Component keybindText() {
        int code = HudConfig.getInstance().getEditKeyCode();
        String name = code < 0 ? "Not Set"
                : InputConstants.Type.KEYSYM.getOrCreate(code).getDisplayName().getString();
        return Component.literal("Edit HUD Keybind: §b" + name);
    }
}
