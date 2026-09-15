package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.routes.Route;
import com.killer560.hub.routes.RouteStore;
import com.killer560.hub.routes.SkyblockArea;
import com.killer560.hub.routes.WaypointRoutesConfig;
import com.killer560.hub.routes.WaypointRoutesFeature;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Waypoint Routes - see {@link WaypointRoutesFeature}. Route list (select/delete), selected-route settings,
 *  clipboard import/export, display options and keybinds. */
public class WaypointRoutesTab extends BaseTab implements KeyCaptureTab {

    private int capturingKey = -1;
    private String pendingDeleteId = null;
    private boolean pendingClear = false;

    public WaypointRoutesTab() {
        super("Waypoint Routes");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        WaypointRoutesConfig cfg = WaypointRoutesConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        int y = contentY;
        int gap = 8;
        int col2W = (contentWidth - gap) / 2;
        int col2bX = contentX + col2W + gap;
        int col3W = (contentWidth - gap * 2) / 3;
        int col3bX = contentX + col3W + gap;
        int col3cX = contentX + (col3W + gap) * 2;

        widgets.add(SettingsButtonWidget.builder(onOff("Waypoint Routes", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 28;
        if (!cfg.isEnabled()) {
            return widgets;
        }

        String areaKey = SkyblockArea.key();
        Route active = WaypointRoutesFeature.activeRoute();
        widgets.add(new StringWidget(contentX, y, contentWidth, 12, Component.literal("Area: §6"
                + SkyblockArea.label(areaKey) + "§r   Active: §6" + (active == null ? "None" : active.name)),
                client.font));
        y += 16;

        // --- routes ---
        widgets.add(SettingsButtonWidget.builder(Component.literal("New Route"), btn -> {
                    Route route = RouteStore.create("Route");
                    RouteStore.save();
                    cfg.setSelectedRouteId(route.id);
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, col3W, 18).build());
        widgets.add(SettingsButtonWidget.builder(Component.literal("Import Clipboard"), btn -> {
                    WaypointRoutesFeature.importFromClipboard();
                    requestRebuild.run();
                }).bounds(col3bX, y, col3W, 18).build());
        widgets.add(SettingsButtonWidget.builder(Component.literal("Export Selected"), btn ->
                        WaypointRoutesFeature.exportToClipboard(RouteStore.byId(cfg.getSelectedRouteId())))
                .bounds(col3cX, y, col3W, 18).build());
        y += 24;

        int deleteW = 50;
        for (Route route : RouteStore.all()) {
            boolean selected = route.id.equals(cfg.getSelectedRouteId());
            boolean activeHere = active != null && active.id.equals(route.id);
            String text = (selected ? "§6> " : "") + route.name + " §7(" + route.points.size() + ")"
                    + (activeHere ? " §aActive" : "");
            widgets.add(SettingsButtonWidget.builder(Component.literal(text), btn -> {
                        cfg.setSelectedRouteId(route.id);
                        cfg.save();
                        pendingDeleteId = null;
                        pendingClear = false;
                        requestRebuild.run();
                    }).bounds(contentX, y, contentWidth - deleteW - 4, 18).build());
            boolean confirming = route.id.equals(pendingDeleteId);
            widgets.add(SettingsButtonWidget.builder(Component.literal(confirming ? "§cSure?" : "§cDelete"), btn -> {
                        if (!route.id.equals(pendingDeleteId)) {
                            pendingDeleteId = route.id;
                        } else {
                            RouteStore.delete(route.id);
                            RouteStore.save();
                            cfg.forgetRoute(route.id);
                            cfg.save();
                            WaypointRoutesFeature.forget(route.id);
                            pendingDeleteId = null;
                        }
                        requestRebuild.run();
                    }).bounds(contentX + contentWidth - deleteW, y, deleteW, 18).build());
            y += 20;
        }
        y += 6;

        Route sel = RouteStore.byId(cfg.getSelectedRouteId());
        if (sel != null) {
            EditBox nameField = new EditBox(client.font, contentX, y, contentWidth, 18, Component.literal("Route name"));
            nameField.setMaxLength(48);
            nameField.setValue(sel.name);
            nameField.setResponder(text -> {
                if (!text.isBlank()) {
                    sel.name = text.trim();
                    RouteStore.save();
                }
            });
            widgets.add(nameField);
            y += 22;

            widgets.add(SettingsButtonWidget.builder(ColorSwatch.label("Color", sel.color), btn ->
                            client.setScreen(new ColorPickerScreen(client.screen, "Route Color", sel.color, Route.DEFAULT_COLOR,
                                    argb -> {
                                        sel.color = argb;
                                        RouteStore.save();
                                    })))
                    .bounds(contentX, y, col2W, 18).build());
            widgets.add(SettingsButtonWidget.builder(onOff("Loop", sel.loop), btn -> {
                        sel.loop = !sel.loop;
                        RouteStore.save();
                        btn.setMessage(onOff("Loop", sel.loop));
                    }).bounds(col2bX, y, col2W, 18).build());
            y += 20;

            double radiusNorm = (sel.getRadius() - Route.MIN_RADIUS) / (Route.MAX_RADIUS - Route.MIN_RADIUS);
            widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18, radiusText(sel), radiusNorm) {
                @Override
                protected void updateMessage() {
                    setMessage(radiusText(sel));
                }

                @Override
                protected void applyValue() {
                    double raw = Route.MIN_RADIUS + this.value * (Route.MAX_RADIUS - Route.MIN_RADIUS);
                    sel.setRadius(Math.round(raw * 2.0) / 2.0);
                    RouteStore.save();
                }
            });
            y += 20;

            boolean activeHere = active != null && active.id.equals(sel.id)
                    && sel.id.equals(cfg.getActiveRouteId(areaKey));
            widgets.add(SettingsButtonWidget.builder(Component.literal(activeHere ? "Deactivate Here" : "Activate Here"), btn -> {
                        cfg.setActiveRouteId(areaKey, activeHere ? null : sel.id);
                        cfg.save();
                        requestRebuild.run();
                    }).bounds(contentX, y, col3W, 18).build());
            widgets.add(SettingsButtonWidget.builder(Component.literal("Restart"), btn ->
                            WaypointRoutesFeature.restart(sel))
                    .bounds(col3bX, y, col3W, 18).build());
            widgets.add(SettingsButtonWidget.builder(Component.literal("Nearest Point"), btn ->
                            WaypointRoutesFeature.startAtNearest(sel))
                    .bounds(col3cX, y, col3W, 18).build());
            y += 20;

            widgets.add(SettingsButtonWidget.builder(Component.literal("Remove Last Point"), btn -> {
                        if (!sel.points.isEmpty()) {
                            sel.points.remove(sel.points.size() - 1);
                            RouteStore.save();
                            WaypointRoutesFeature.setProgress(sel, WaypointRoutesFeature.getProgress(sel));
                        }
                        requestRebuild.run();
                    }).bounds(contentX, y, col2W, 18).build());
            widgets.add(SettingsButtonWidget.builder(Component.literal(pendingClear ? "§cSure? Clear" : "§cClear Points"), btn -> {
                        if (!pendingClear) {
                            pendingClear = true;
                        } else {
                            sel.points.clear();
                            RouteStore.save();
                            WaypointRoutesFeature.restart(sel);
                            pendingClear = false;
                        }
                        requestRebuild.run();
                    }).bounds(col2bX, y, col2W, 18).build());
            y += 28;
        }

        // --- display ---
        widgets.add(SettingsButtonWidget.builder(onOff("Line To Next", cfg.isLineToNext()), btn -> {
                    cfg.setLineToNext(!cfg.isLineToNext());
                    cfg.save();
                    btn.setMessage(onOff("Line To Next", cfg.isLineToNext()));
                }).bounds(contentX, y, col2W, 18).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Route Lines", cfg.isRouteLines()), btn -> {
                    cfg.setRouteLines(!cfg.isRouteLines());
                    cfg.save();
                    btn.setMessage(onOff("Route Lines", cfg.isRouteLines()));
                }).bounds(col2bX, y, col2W, 18).build());
        y += 20;
        widgets.add(SettingsButtonWidget.builder(onOff("Numbers", cfg.isShowNumbers()), btn -> {
                    cfg.setShowNumbers(!cfg.isShowNumbers());
                    cfg.save();
                    btn.setMessage(onOff("Numbers", cfg.isShowNumbers()));
                }).bounds(contentX, y, col2W, 18).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Distance", cfg.isShowDistance()), btn -> {
                    cfg.setShowDistance(!cfg.isShowDistance());
                    cfg.save();
                    btn.setMessage(onOff("Distance", cfg.isShowDistance()));
                }).bounds(col2bX, y, col2W, 18).build());
        y += 20;
        widgets.add(SettingsButtonWidget.builder(onOff("Start At Nearest", cfg.isStartAtNearest()), btn -> {
                    cfg.setStartAtNearest(!cfg.isStartAtNearest());
                    cfg.save();
                    btn.setMessage(onOff("Start At Nearest", cfg.isStartAtNearest()));
                }).bounds(contentX, y, col2W, 18).build());
        widgets.add(SettingsButtonWidget.builder(ColorSwatch.label("Next Point", cfg.getTargetColor()), btn ->
                        client.setScreen(new ColorPickerScreen(client.screen, "Next Point Color", cfg.getTargetColor(),
                                WaypointRoutesConfig.DEFAULT_TARGET_COLOR, argb -> {
                                    cfg.setTargetColor(argb);
                                    cfg.save();
                                })))
                .bounds(col2bX, y, col2W, 18).build());
        y += 20;

        double scaleNorm = (cfg.getTextScale() - 0.5) / 2.5;
        widgets.add(new ThemedSliderButton(contentX, y, col2W, 18, scaleText(cfg), scaleNorm) {
            @Override
            protected void updateMessage() {
                setMessage(scaleText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setTextScale((float) (0.5 + this.value * 2.5));
                cfg.save();
            }
        });
        double thickNorm = (cfg.getLineThickness() - 1.0) / 5.0;
        widgets.add(new ThemedSliderButton(col2bX, y, col2W, 18, thicknessText(cfg), thickNorm) {
            @Override
            protected void updateMessage() {
                setMessage(thicknessText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setLineThickness((float) (1.0 + Math.round(this.value * 10) / 2.0));
                cfg.save();
            }
        });
        y += 28;

        // --- keybinds ---
        for (int i = 0; i < WaypointRoutesConfig.KEY_NAMES.length; i++) {
            final int which = i;
            int x = i % 2 == 0 ? contentX : col2bX;
            Component label = capturingKey == which ? Component.literal("Press any key...") : keyText(cfg, which);
            widgets.add(SettingsButtonWidget.builder(label, btn -> {
                        capturingKey = which;
                        btn.setMessage(Component.literal("Press any key..."));
                    }).bounds(x, y, col2W, 18).build());
            if (i % 2 == 1 || i == WaypointRoutesConfig.KEY_NAMES.length - 1) {
                y += 20;
            }
        }
        return widgets;
    }

    private static Component radiusText(Route route) {
        return Component.literal(String.format(Locale.US, "Radius: §6%.1f", route.getRadius()));
    }

    private static Component scaleText(WaypointRoutesConfig cfg) {
        return Component.literal(String.format(Locale.US, "Text Scale: §6%.2fx", cfg.getTextScale()));
    }

    private static Component thicknessText(WaypointRoutesConfig cfg) {
        return Component.literal(String.format(Locale.US, "Line Width: §6%.1f", cfg.getLineThickness()));
    }

    private static Component keyText(WaypointRoutesConfig cfg, int which) {
        int code = cfg.getKeyCode(which);
        String name = code < 0 ? "Not Set" : InputConstants.Type.KEYSYM.getOrCreate(code).getDisplayName().getString();
        return Component.literal(WaypointRoutesConfig.KEY_NAMES[which] + ": §6" + name);
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    @Override
    public boolean isListeningForKey() {
        return capturingKey >= 0;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        if (capturingKey < 0) {
            return;
        }
        WaypointRoutesConfig cfg = WaypointRoutesConfig.getInstance();
        cfg.setKeyCode(capturingKey, keyCode == InputConstants.KEY_ESCAPE ? -1 : keyCode);
        cfg.save();
        capturingKey = -1;
    }
}
