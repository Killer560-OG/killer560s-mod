package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.realtime.RealTimeConfig;
import com.killer560.hub.realtime.RealTimeFeature;
import com.killer560.hub.realtime.RealTimeZones;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Real Time clock settings - see {@link RealTimeFeature}. */
public class RealTimeTab extends BaseTab {

    private static final int KEY_TAB = 258;
    private static final int KEY_ENTER = 257;
    private static final int KEY_KP_ENTER = 335;
    private static final int KEY_DOWN = 264;
    private static final int KEY_UP = 265;

    /** Zone search text - transient UI state, survives tab rebuilds but not restarts. */
    private String search = "";

    public RealTimeTab() {
        super("Real Time");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        RealTimeConfig cfg = RealTimeConfig.getInstance();
        int gap = 4;
        int colW = (contentWidth - gap) / 2;
        int col2X = contentX + colW + gap;
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(onOff("Real Time", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(formatLabel(cfg), btn -> {
                    cfg.setUse24Hour(!cfg.isUse24Hour());
                    cfg.save();
                    btn.setMessage(formatLabel(cfg));
                }).bounds(contentX, y, colW, 18).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Show Seconds", cfg.isShowSeconds()), btn -> {
                    cfg.setShowSeconds(!cfg.isShowSeconds());
                    cfg.save();
                    btn.setMessage(onOff("Show Seconds", cfg.isShowSeconds()));
                }).bounds(col2X, y, colW, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Label", cfg.isShowLabel()), btn -> {
                    cfg.setShowLabel(!cfg.isShowLabel());
                    cfg.save();
                    btn.setMessage(onOff("Label", cfg.isShowLabel()));
                }).bounds(contentX, y, colW, 18).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Zone Abbreviation", cfg.isShowZoneAbbreviation()), btn -> {
                    cfg.setShowZoneAbbreviation(!cfg.isShowZoneAbbreviation());
                    cfg.save();
                    btn.setMessage(onOff("Zone Abbreviation", cfg.isShowZoneAbbreviation()));
                }).bounds(col2X, y, colW, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(ColorSwatch.label("Text Color", cfg.getTextColor()), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new ColorPickerScreen(client.screen, "Real Time Color",
                            cfg.getTextColor(), RealTimeConfig.DEFAULT_TEXT_COLOR, argb -> {
                        cfg.setTextColor(argb);
                        cfg.save();
                    }));
                }).bounds(contentX, y, colW, 18).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Text Shadow", cfg.isTextShadow()), btn -> {
                    cfg.setTextShadow(!cfg.isTextShadow());
                    cfg.save();
                    btn.setMessage(onOff("Text Shadow", cfg.isTextShadow()));
                }).bounds(col2X, y, colW, 18).build());
        y += 28;

        boolean custom = cfg.getZoneMode() == RealTimeConfig.ZoneMode.CUSTOM;
        widgets.add(SettingsButtonWidget.builder(zoneModeLabel(cfg), btn -> {
                    if (cfg.getZoneMode() == RealTimeConfig.ZoneMode.CUSTOM) {
                        cfg.setZoneMode(RealTimeConfig.ZoneMode.SYSTEM);
                    } else {
                        cfg.setZoneMode(RealTimeConfig.ZoneMode.CUSTOM);
                        if (cfg.getCustomZone().isBlank()) {
                            // Start the picker on the computer's own zone rather than an arbitrary first entry.
                            cfg.setCustomZone(ZoneId.systemDefault().getId());
                        }
                    }
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        if (!custom) {
            return widgets;
        }

        int arrowW = 20;
        int midW = contentWidth - 2 * (arrowW + gap);
        int midX = contentX + arrowW + gap;
        int rightX = midX + midW + gap;

        widgets.add(SettingsButtonWidget.builder(Component.literal("<"), btn -> {
                    cycleRegion(cfg, -1);
                    requestRebuild.run();
                }).bounds(contentX, y, arrowW, 18).build());
        widgets.add(SettingsButtonWidget.builder(Component.literal("Region: " + cfg.getRegionFilter()), btn -> {
                    cycleRegion(cfg, 1);
                    requestRebuild.run();
                }).bounds(midX, y, midW, 18).build());
        widgets.add(SettingsButtonWidget.builder(Component.literal(">"), btn -> {
                    cycleRegion(cfg, 1);
                    requestRebuild.run();
                }).bounds(rightX, y, arrowW, 18).build());
        y += 22;

        SettingsButtonWidget[] zoneButton = new SettingsButtonWidget[1];
        EditBox searchBox = new EditBox(Minecraft.getInstance().font, contentX, y, contentWidth, 18,
                Component.literal("Search Zones")) {
            @Override
            public boolean keyPressed(KeyEvent event) {
                int key = event.key();
                if (key == KEY_TAB || key == KEY_ENTER || key == KEY_KP_ENTER) {
                    // Accept the highlighted match into the box (autocomplete).
                    if (!getValue().isBlank() && !cfg.getCustomZone().isBlank()
                            && !getValue().equals(cfg.getCustomZone())) {
                        setValue(cfg.getCustomZone());
                        return true;
                    }
                } else if (key == KEY_DOWN || key == KEY_UP) {
                    stepZone(cfg, key == KEY_DOWN ? 1 : -1, zoneButton[0], this);
                    return true;
                }
                return super.keyPressed(event);
            }
        };
        searchBox.setMaxLength(64);
        searchBox.setHint(Component.literal("§8Search Zones"));
        searchBox.setValue(search);
        searchBox.setResponder(text -> {
            search = text;
            List<String> matches = RealTimeZones.filter(cfg.getRegionFilter(), text);
            String current = cfg.getCustomZone();
            // Autocomplete: jump to the best match unless the current zone is still a (prefix) match.
            if (!matches.isEmpty() && !matches.get(0).equals(current)
                    && (!matches.contains(current) || (!text.isBlank() && !startsWithQuery(current, text)))) {
                cfg.setCustomZone(matches.get(0));
                cfg.save();
            }
            updateSuggestion(cfg, searchBox, text);
            if (zoneButton[0] != null) {
                zoneButton[0].setMessage(zoneLabel(cfg));
            }
        });
        widgets.add(searchBox);
        y += 22;

        widgets.add(SettingsButtonWidget.builder(Component.literal("<"), btn ->
                stepZone(cfg, -1, zoneButton[0], searchBox)).bounds(contentX, y, arrowW, 18).build());
        zoneButton[0] = SettingsButtonWidget.builder(zoneLabel(cfg), btn ->
                stepZone(cfg, 1, btn, searchBox)).bounds(midX, y, midW, 18).build();
        widgets.add(zoneButton[0]);
        widgets.add(SettingsButtonWidget.builder(Component.literal(">"), btn ->
                stepZone(cfg, 1, zoneButton[0], searchBox)).bounds(rightX, y, arrowW, 18).build());
        updateSuggestion(cfg, searchBox, search);

        return widgets;
    }

    private void stepZone(RealTimeConfig cfg, int dir, SettingsButtonWidget zoneButton, EditBox box) {
        List<String> matches = RealTimeZones.filter(cfg.getRegionFilter(), search);
        if (matches.isEmpty()) {
            return;
        }
        int idx = matches.indexOf(cfg.getCustomZone());
        int next = idx < 0 ? (dir > 0 ? 0 : matches.size() - 1) : Math.floorMod(idx + dir, matches.size());
        cfg.setCustomZone(matches.get(next));
        cfg.save();
        if (zoneButton != null) {
            zoneButton.setMessage(zoneLabel(cfg));
        }
        updateSuggestion(cfg, box, search);
    }

    private void cycleRegion(RealTimeConfig cfg, int dir) {
        List<String> regions = RealTimeZones.regions();
        int idx = Math.max(0, regions.indexOf(cfg.getRegionFilter()));
        cfg.setRegionFilter(regions.get(Math.floorMod(idx + dir, regions.size())));
        List<String> matches = RealTimeZones.filter(cfg.getRegionFilter(), search);
        if (matches.isEmpty() && !search.isEmpty()) {
            search = "";
            matches = RealTimeZones.filter(cfg.getRegionFilter(), search);
        }
        if (!matches.isEmpty() && !matches.contains(cfg.getCustomZone())) {
            cfg.setCustomZone(matches.get(0));
        }
        cfg.save();
    }

    private static void updateSuggestion(RealTimeConfig cfg, EditBox box, String text) {
        if (box == null) {
            return;
        }
        String zone = cfg.getCustomZone();
        String q = text.replace(' ', '_');
        if (!text.isEmpty() && zone.length() > q.length()
                && zone.toLowerCase(Locale.ROOT).startsWith(q.toLowerCase(Locale.ROOT))) {
            box.setSuggestion(zone.substring(q.length()));
        } else {
            box.setSuggestion(null);
        }
    }

    private static boolean startsWithQuery(String id, String text) {
        String q = text.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        String lower = id.toLowerCase(Locale.ROOT);
        return lower.startsWith(q) || lower.substring(lower.lastIndexOf('/') + 1).startsWith(q);
    }

    private static Component zoneLabel(RealTimeConfig cfg) {
        ZoneId zone = RealTimeZones.resolve(cfg.getCustomZone());
        return Component.literal("Zone: §6" + zone.getId() + " §7(" + RealTimeFeature.abbreviation(zone) + ")");
    }

    private static Component zoneModeLabel(RealTimeConfig cfg) {
        if (cfg.getZoneMode() == RealTimeConfig.ZoneMode.CUSTOM) {
            return Component.literal("Time Zone: §6Custom");
        }
        return Component.literal("Time Zone: §6System §7(" + ZoneId.systemDefault().getId() + ")");
    }

    private static Component formatLabel(RealTimeConfig cfg) {
        return Component.literal("Format: §6" + (cfg.isUse24Hour() ? "24 Hour" : "12 Hour"));
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
