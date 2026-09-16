package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.posmsg.PosmsgConfig;
import com.killer560.hub.posmsg.PosmsgEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Posmsg (position message) waypoints - a ring drawn on the ground that types its own line into party
 *  chat the moment you walk into it. Preloaded room presets (Simon Says/EE2/EE3/Outpour/Recor/Necron's
 *  Platform/P5, per killer560's list) plus any custom ones added here or via
 *  {@code /posmsg add <message> <x> <y> <z> <radius>}.
 *  <p>
 *  Controls per waypoint are exactly the seven killer560 asked for (2026-09-16): the message field with
 *  its Set button, Enabled, Set To My Position, Show Radius, Radius, Only Send Once Per Run, and the
 *  colour. The old Send button, "Show Display" and "Only If Inside" toggles are gone along with the
 *  top-left HUD list they belonged to - the ring in the world is the display now. */
public class PosmsgTab extends BaseTab {

    private static final int ROW = 18;
    private static final int GAP = 6;
    private static final int SET_W = 40;

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
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        widgets.add(SettingsButtonWidget.builder(Component.literal("+ Add Custom Waypoint"), btn -> {
                    PosmsgConfig.getInstance().addNew();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Stand where the message should send from and hit \"Set To My Position\"."),
                Minecraft.getInstance().font));
        y += 18;

        for (PosmsgEntry e : new ArrayList<>(PosmsgConfig.getInstance().entries())) {
            y = buildEntryRows(widgets, e, contentX, y, contentWidth, requestRebuild);
            y += 12;
        }

        return widgets;
    }

    private int buildEntryRows(List<AbstractWidget> widgets, PosmsgEntry e, int contentX, int y,
                                int contentWidth, Runnable requestRebuild) {
        String status = e.configured
                ? String.format(Locale.US, "§7%.0f, %.0f, %.0f", e.x, e.y, e.z)
                : "§c(no position set)";
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal((e.builtin ? "§6" : "§b") + e.name + " §8- " + status),
                Minecraft.getInstance().font));
        y += 14;

        // The message line first - it's the whole point of the waypoint, so it gets the widest row.
        int msgW = Math.max(1, contentWidth - SET_W - GAP);
        EditBox messageField = new EditBox(Minecraft.getInstance().font, contentX, y, msgW, ROW,
                Component.literal("Message"));
        messageField.setMaxLength(100);
        messageField.setValue(e.message == null ? "" : e.message);
        messageField.setHint(Component.literal("§8Sent in party chat, e.g. at hee2"));
        // Responder set AFTER setValue: setValue fires the responder, and BaseTab#widgetsMatchSearch
        // builds this whole tab speculatively just to read widget labels - a responder wired first would
        // then rewrite the config on every search keystroke.
        messageField.setResponder(text -> {
            e.message = text;
            PosmsgConfig.getInstance().save();
        });
        widgets.add(messageField);
        widgets.add(SettingsButtonWidget.builder(Component.literal("Set"), btn -> {
                    e.message = messageField.getValue();
                    PosmsgConfig.getInstance().save();
                    requestRebuild.run();
                }).bounds(contentX + msgW + GAP, y, SET_W, ROW).build());
        y += ROW + GAP;

        int colW = (contentWidth - GAP * 2) / 3;
        int col1 = contentX;
        int col2 = contentX + colW + GAP;
        int col3 = contentX + (colW + GAP) * 2;
        int col3W = Math.max(1, contentWidth - (colW + GAP) * 2);

        widgets.add(SettingsButtonWidget.builder(onOff("Enabled", e.enabled), btn -> {
                    e.enabled = !e.enabled;
                    PosmsgConfig.getInstance().save();
                    btn.setMessage(onOff("Enabled", e.enabled));
                }).bounds(col1, y, colW, ROW).build());

        widgets.add(SettingsButtonWidget.builder(Component.literal("Set To My Position"), btn -> {
                    var player = Minecraft.getInstance().player;
                    if (player != null) {
                        e.x = player.getX();
                        e.y = player.getY();
                        e.z = player.getZ();
                        e.configured = true;
                        PosmsgConfig.getInstance().save();
                        requestRebuild.run();
                    }
                }).bounds(col2, y, colW, ROW).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Show Radius", e.showRadius), btn -> {
                    e.showRadius = !e.showRadius;
                    PosmsgConfig.getInstance().save();
                    btn.setMessage(onOff("Show Radius", e.showRadius));
                }).bounds(col3, y, col3W, ROW).build());
        y += ROW + GAP;

        widgets.add(SettingsButtonWidget.builder(radiusText(e), btn -> {
                    e.radius = e.radius >= 10 ? 1.0 : e.radius + 0.5;
                    PosmsgConfig.getInstance().save();
                    btn.setMessage(radiusText(e));
                }).bounds(col1, y, colW, ROW).build());

        // Plain on/off, default off = fires every time you walk in (killer560, 2026-09-16: "by default
        // it will do it an infinate amount of times... It will be an on or off button though").
        widgets.add(SettingsButtonWidget.builder(onOff("Only Send Once Per Run", e.onceOnlyPerRun), btn -> {
                    e.onceOnlyPerRun = !e.onceOnlyPerRun;
                    PosmsgConfig.getInstance().save();
                    btn.setMessage(onOff("Only Send Once Per Run", e.onceOnlyPerRun));
                }).bounds(col2, y, colW, ROW).build());

        widgets.add(SettingsButtonWidget.builder(ColorSwatch.label("Color", e.color()), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new ColorPickerScreen(client.screen, "Waypoint Color", e.color(),
                            0xFFCC6600, argb -> {
                                e.colorHex = String.format("%06X", argb & 0xFFFFFF);
                                PosmsgConfig.getInstance().save();
                            }));
                }).bounds(col3, y, col3W, ROW).build());
        y += ROW + GAP;

        if (!e.builtin) {
            widgets.add(SettingsButtonWidget.builder(Component.literal("§cDelete"), btn -> {
                        PosmsgConfig.getInstance().remove(e.id);
                        requestRebuild.run();
                    }).bounds(col1, y, colW, ROW).build());
            y += ROW + GAP;
        }

        return y;
    }

    private static Component radiusText(PosmsgEntry e) {
        return Component.literal(String.format(Locale.US, "Radius: %.1f", e.radius));
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    private static Component masterText() {
        return Component.literal("Posmsg: " + (PosmsgConfig.getInstance().isEnabled() ? "§aON" : "§cOFF"));
    }
}
