package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.posmsg.PosmsgConfig;
import com.killer560.hub.posmsg.PosmsgEntry;
import com.killer560.hub.posmsg.PosmsgFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Posmsg (position message) waypoints - preloaded room presets (Simon Says/EE2/EE3/Outpour/Recor/
 *  Necron's Platform/P5, per killer560's list) plus any custom ones added via
 *  {@code /posmsg add <message> <x> <y> <z> <radius>}. Each waypoint gets every toggle killer560
 *  asked for: enabled ("each individual circle"), show radius, show display, show-only-inside-radius,
 *  and once-vs-multiple-per-run. Coordinates are set with "Set to my position" (stand on the real
 *  spot, click it) rather than a raw numeric field, since that's both simpler in a scrolling list of
 *  7+ entries and harder to fat-finger than typing floats. */
public class PosmsgTab extends BaseTab {

    public PosmsgTab() {
        super("Posmsg");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(masterText(), btn -> {
                    PosmsgConfig cfg = PosmsgConfig.getInstance();
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    btn.setMessage(masterText());
                }).bounds(contentX, y, 220, 20).build());
        y += 24;

        widgets.add(SettingsButtonWidget.builder(Component.literal("+ Add Custom Waypoint"), btn -> {
                    PosmsgConfig cfg = PosmsgConfig.getInstance();
                    cfg.addNew();
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("Not configured yet? Stand on the real spot and hit \"Set to my position\"."),
                Minecraft.getInstance().font));
        y += 18;

        for (PosmsgEntry e : new ArrayList<>(PosmsgConfig.getInstance().entries())) {
            y = buildEntryRows(widgets, e, contentX, y, contentWidth, requestRebuild);
            y += 10;
        }

        return widgets;
    }

    private int buildEntryRows(List<AbstractWidget> widgets, PosmsgEntry e, int contentX, int y,
                                int contentWidth, Runnable requestRebuild) {
        String status = e.configured
                ? String.format(Locale.US, "%.1f, %.1f, %.1f  r=%.1f", e.x, e.y, e.z, e.radius)
                : "§7(not configured)";
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal((e.builtin ? "§6" : "§b") + e.name + " §7- " + status),
                Minecraft.getInstance().font));
        y += 14;

        int col1 = contentX;
        int col2 = contentX + 108;
        int col3 = contentX + 216;

        widgets.add(SettingsButtonWidget.builder(onOff("Enabled", e.enabled), btn -> {
                    e.enabled = !e.enabled;
                    PosmsgConfig.getInstance().save();
                    btn.setMessage(onOff("Enabled", e.enabled));
                }).bounds(col1, y, 100, 18).build());

        widgets.add(SettingsButtonWidget.builder(Component.literal("Send"), btn ->
                    PosmsgFeature.send(e)
                ).bounds(col2, y, 100, 18).build());

        widgets.add(SettingsButtonWidget.builder(Component.literal("Set to my position"), btn -> {
                    var player = Minecraft.getInstance().player;
                    if (player != null) {
                        e.x = player.getX();
                        e.y = player.getY();
                        e.z = player.getZ();
                        e.configured = true;
                        PosmsgConfig.getInstance().save();
                        requestRebuild.run();
                    }
                }).bounds(col3, y, 108, 18).build());
        y += 20;

        widgets.add(SettingsButtonWidget.builder(onOff("Show Radius", e.showRadius), btn -> {
                    e.showRadius = !e.showRadius;
                    PosmsgConfig.getInstance().save();
                    btn.setMessage(onOff("Show Radius", e.showRadius));
                }).bounds(col1, y, 100, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Show Display", e.showDisplay), btn -> {
                    e.showDisplay = !e.showDisplay;
                    PosmsgConfig.getInstance().save();
                    btn.setMessage(onOff("Show Display", e.showDisplay));
                }).bounds(col2, y, 100, 18).build());

        widgets.add(SettingsButtonWidget.builder(Component.literal(String.format(Locale.US, "Radius: %.1f", e.radius)), btn -> {
                    e.radius = e.radius >= 10 ? 1.0 : e.radius + 0.5;
                    PosmsgConfig.getInstance().save();
                    btn.setMessage(Component.literal(String.format(Locale.US, "Radius: %.1f", e.radius)));
                }).bounds(col3, y, 108, 18).build());
        y += 20;

        widgets.add(SettingsButtonWidget.builder(onOff("Only If Inside", e.showOnlyInsideRadius), btn -> {
                    e.showOnlyInsideRadius = !e.showOnlyInsideRadius;
                    PosmsgConfig.getInstance().save();
                    btn.setMessage(onOff("Only If Inside", e.showOnlyInsideRadius));
                }).bounds(col1, y, 100, 18).build());

        widgets.add(SettingsButtonWidget.builder(Component.literal(e.onceOnlyPerRun ? "Once Per Run" : "Every Run"), btn -> {
                    e.onceOnlyPerRun = !e.onceOnlyPerRun;
                    PosmsgConfig.getInstance().save();
                    btn.setMessage(Component.literal(e.onceOnlyPerRun ? "Once Per Run" : "Every Run"));
                }).bounds(col2, y, 100, 18).build());

        if (!e.builtin) {
            widgets.add(SettingsButtonWidget.builder(Component.literal("§cDelete"), btn -> {
                        PosmsgConfig.getInstance().remove(e.id);
                        requestRebuild.run();
                    }).bounds(col3, y, 108, 18).build());
        }
        y += 20;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Color: ■"), btn -> {
                    e.colorHex = nextColor(e.colorHex);
                    PosmsgConfig.getInstance().save();
                    btn.setMessage(Component.literal("Color: ■"));
                    requestRebuild.run();
                }).bounds(col1, y, 100, 18).build());
        y += 20;

        return y;
    }

    // Cycles through the same 5 hues used for the dungeon classes, plus the default amber - a full
    // RGB picker would need a new widget type this codebase doesn't have yet; this keeps every waypoint
    // visually distinct enough for "color of the display toggle" without one.
    private static final String[] COLOR_CYCLE = {
            "CC6600", "3B82F6", "22C55E", "A855F7", "EF4444", "F97316"
    };

    private static String nextColor(String currentHex) {
        for (int i = 0; i < COLOR_CYCLE.length; i++) {
            if (COLOR_CYCLE[i].equalsIgnoreCase(currentHex)) {
                return COLOR_CYCLE[(i + 1) % COLOR_CYCLE.length];
            }
        }
        return COLOR_CYCLE[0];
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    private static Component masterText() {
        return Component.literal("Posmsg: " + (PosmsgConfig.getInstance().isEnabled() ? "§aON" : "§cOFF"));
    }
}
