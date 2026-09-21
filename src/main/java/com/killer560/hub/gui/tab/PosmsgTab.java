package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
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
 *  chat the moment you walk into it, only during the F7/M7 boss fight. Preloaded room presets (Simon
 *  Says/EE2/EE3/Outcore/Recore/Necron's Platform/P5, per killer560's list, shipped with his real
 *  coordinates) plus any custom ones added here or via {@code /posmsg add <message> <x> <y> <z> <radius>}.
 *  <p>
 *  Controls per waypoint are the ones killer560 asked for (2026-09-16): the message field with its Set
 *  button, Enabled, Set To My Position, Show Radius, Radius, Border Thickness, Only Send Once Per Run,
 *  the colour, and (his test-round additions) Text Scale and Text Height for the floating label. Radius
 *  is a slider rather than a cycling button at his request. Enabled sits directly under the waypoint's
 *  title and collapses everything below it when off, so an unused waypoint costs one line in a list
 *  that is already 7 entries long. Every waypoint starts OFF - flipping the master switch lights up
 *  nothing until you opt each spot in ("if you turn it on by default every individual one is off"). */
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
                    // Raw, not the Skyblock-gated isEnabled(): off-Skyblock that reads OFF regardless of
                    // the stored value, so toggling would always write ON.
                    cfg.setEnabled(!cfg.isEnabledRaw());
                    cfg.save();
                    btn.setMessage(masterText());
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        widgets.add(SettingsButtonWidget.builder(Component.literal("+ Add Custom Waypoint"), btn -> {
                    PosmsgConfig.getInstance().addNew();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

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

        // Enabled sits directly under the title and collapses the rest of the waypoint when it's off
        // (killer560, 2026-09-16) - with 7+ waypoints in one scrolling list, the ones you aren't using
        // should take one line, not six.
        widgets.add(SettingsButtonWidget.builder(onOff("Enabled", e.enabled), btn -> {
                    e.enabled = !e.enabled;
                    PosmsgConfig.getInstance().save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, ROW).build());
        y += ROW + GAP;

        if (!e.enabled) {
            // Delete still has to be reachable on a disabled custom waypoint, or it could never be removed.
            if (!e.builtin) {
                widgets.add(SettingsButtonWidget.builder(Component.literal("§cDelete"), btn -> {
                            PosmsgConfig.getInstance().remove(e.id);
                            requestRebuild.run();
                        }).bounds(contentX, y, (contentWidth - GAP * 2) / 3, ROW).build());
                y += ROW + GAP;
            }
            return y;
        }

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
                }).bounds(col1, y, colW, ROW).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Show Radius", e.showRadius), btn -> {
                    e.showRadius = !e.showRadius;
                    PosmsgConfig.getInstance().save();
                    btn.setMessage(onOff("Show Radius", e.showRadius));
                }).bounds(col2, y, colW, ROW).build());

        // A slider, not a cycling button (killer560's test round, 2026-09-16). 1.0-10.0 in half-block
        // steps; a radius typed wider than that via "/posmsg add" still loads and fires - the handle just
        // pins to the end until you drag it.
        widgets.add(slider(col3, y, col3W, radiusText(e),
                PosmsgEntry.MIN_RADIUS, PosmsgEntry.MAX_RADIUS, e.radius, 0.5, v -> e.radius = v, () -> radiusText(e)));
        y += ROW + GAP;

        widgets.add(SettingsButtonWidget.builder(thicknessText(e), btn -> {
                    e.thickness = e.thickness >= 6.0 ? 1.0 : e.thickness + 0.5;
                    PosmsgConfig.getInstance().save();
                    btn.setMessage(thicknessText(e));
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

        // Label controls (killer560's test round, 2026-09-16): size of the floating message text, and
        // how far above the waypoint it floats. 0 height = sitting on the waypoint, exactly how it
        // looked before the option existed.
        widgets.add(slider(col1, y, colW, textScaleText(e),
                PosmsgEntry.MIN_TEXT_SCALE, PosmsgEntry.MAX_TEXT_SCALE, e.textScale, 0.05,
                v -> e.textScale = v, () -> textScaleText(e)));
        widgets.add(slider(col2, y, colW, textHeightText(e),
                PosmsgEntry.MIN_TEXT_HEIGHT, PosmsgEntry.MAX_TEXT_HEIGHT, e.textHeightOffset, 0.25,
                v -> e.textHeightOffset = v, () -> textHeightText(e)));
        if (!e.builtin) {
            widgets.add(SettingsButtonWidget.builder(Component.literal("§cDelete"), btn -> {
                        PosmsgConfig.getInstance().remove(e.id);
                        requestRebuild.run();
                    }).bounds(col3, y, col3W, ROW).build());
        }
        y += ROW + GAP;

        return y;
    }

    /** One themed slider over {@code [min, max]} snapped to {@code step}, saving on every change - same
     *  shape as the sliders in {@code LeapMenuTab}/{@code TerminalSolverTab}, just factored out because
     *  this tab draws three of them per waypoint. */
    private static ThemedSliderButton slider(int x, int y, int w, Component label, double min, double max,
                                             double current, double step, java.util.function.DoubleConsumer apply,
                                             java.util.function.Supplier<Component> text) {
        double span = max - min;
        double normalized = span <= 0 ? 0 : (current - min) / span;
        return new ThemedSliderButton(x, y, w, ROW, label, Math.max(0.0, Math.min(1.0, normalized))) {
            @Override
            protected void updateMessage() {
                setMessage(text.get());
            }

            @Override
            protected void applyValue() {
                double raw = min + this.value * span;
                double snapped = Math.round(raw / step) * step;
                apply.accept(Math.max(min, Math.min(max, snapped)));
                PosmsgConfig.getInstance().save();
            }
        };
    }

    private static Component thicknessText(PosmsgEntry e) {
        return Component.literal(String.format(Locale.US, "Border Thickness: %.1f", e.thickness));
    }

    private static Component radiusText(PosmsgEntry e) {
        return Component.literal(String.format(Locale.US, "Radius: %.1f", e.radius));
    }

    private static Component textScaleText(PosmsgEntry e) {
        return Component.literal(String.format(Locale.US, "Text Scale: %.0f%%", e.textScale * 100));
    }

    private static Component textHeightText(PosmsgEntry e) {
        return Component.literal(String.format(Locale.US, "Text Height: %.2f", e.textHeightOffset));
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    private static Component masterText() {
        return Component.literal("Posmsg: " + (PosmsgConfig.getInstance().isEnabledRaw() ? "§aON" : "§cOFF"));
    }
}
