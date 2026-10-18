package com.killer560.hub.gui.tab;

import com.killer560.hub.dungeonalerts.DungeonAlertsConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Dungeon Alerts pack settings: Shadow Assassin Alert + Room Alerts (see
 *  {@code com.killer560.hub.dungeonalerts}). Secret Sound moved into the new "Secrets" folder and
 *  Terracotta Timer/Spring Boots Overlay/Class Colors each got their own tab 2026-09-21, per killer560's
 *  menu-structure request ("Dungeon Alerts keeps Shadow Assassin + room alerts; Terracotta, Spring Boots
 *  and Class Colors each get their own section") - see {@link SecretSoundTab}, {@link TerracottaTimerTab},
 *  {@link SpringBootsTab}, {@link ClassColorsTab}. All four still read/write the same
 *  {@link DungeonAlertsConfig} instance and JSON keys, unchanged. Every change is saved immediately. */
public class DungeonAlertsTab extends BaseTab {

    private static final int ORANGE = 0xFFCC6600;

    public DungeonAlertsTab() {
        super("Dungeon Alerts");
    }

    /** The card is ON while either alert is, OFF only when both are. */
    @Override
    public List<SettingsButtonWidget> masterToggles(List<AbstractWidget> built) {
        return switchRows(built, "Shadow Assassin Alert", "Room Alerts");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        DungeonAlertsConfig cfg = DungeonAlertsConfig.getInstance();
        int gap = 8;
        int half = (contentWidth - gap) / 2;
        int colB = contentX + half + gap;
        int[] y = {contentY};

        // No switch over the whole tab (killer560, 2026-10-14): the two alerts are independent, each has its own switch,
        // and the card is ON while either is.
        // --- Shadow Assassin Alert ---
        header(w, contentX, y, contentWidth, "Shadow Assassin Alert");
        master(w, contentX, y, contentWidth, "Shadow Assassin Alert", () -> cfg.shadowAssassinEnabled,
                v -> cfg.shadowAssassinEnabled = v, requestRebuild);
        if (cfg.shadowAssassinEnabled) {
            colorButton(w, contentX, y[0], contentWidth, "Alert Color", () -> cfg.shadowAssassinColor,
                    v -> cfg.shadowAssassinColor = v, DungeonAlertsConfig.DEFAULT_SHADOW_ASSASSIN_COLOR);
            y[0] += 24;
        }

        // --- Room Alerts --- killer560 (2026-09-27): only Room Cleared + Secrets Done now - see RoomAlerts.
        header(w, contentX, y, contentWidth, "Room Alerts");
        master(w, contentX, y, contentWidth, "Room Alerts", () -> cfg.roomAlertsEnabled, v -> cfg.roomAlertsEnabled = v, requestRebuild);
        if (cfg.roomAlertsEnabled) {
            toggle(w, contentX, y[0], half, "Room Cleared", () -> cfg.roomAlertsRoomCleared, v -> cfg.roomAlertsRoomCleared = v);
            toggle(w, colB, y[0], half, "Secrets Done", () -> cfg.roomAlertsSecretsDone, v -> cfg.roomAlertsSecretsDone = v);
            y[0] += 22;
            colorButton(w, contentX, y[0], half, "Cleared Color", () -> cfg.roomAlertsClearedColor,
                    v -> cfg.roomAlertsClearedColor = v, DungeonAlertsConfig.DEFAULT_ROOM_ALERT_COLOR);
            colorButton(w, colB, y[0], half, "Secrets Color", () -> cfg.roomAlertsSecretsColor,
                    v -> cfg.roomAlertsSecretsColor = v, DungeonAlertsConfig.DEFAULT_ROOM_ALERT_COLOR);
            y[0] += 22;
            w.add(new ThemedSliderButton(contentX, y[0], contentWidth, 18, displayLabel(cfg), (cfg.roomAlertsDisplaySeconds - 0.5) / 2.5) {
                @Override
                protected void updateMessage() {
                    setMessage(displayLabel(cfg));
                }

                @Override
                protected void applyValue() {
                    cfg.roomAlertsDisplaySeconds = Math.round((0.5 + this.value * 2.5) * 10) / 10f;
                    cfg.save();
                }
            });
            y[0] += 24;
        }

        return w;
    }

    private static void header(List<AbstractWidget> w, int x, int[] y, int width, String title) {
        y[0] += 4;
        w.add(new StringWidget(x, y[0], width, 12, Component.literal(title).withStyle(
                net.minecraft.network.chat.Style.EMPTY.withColor(net.minecraft.network.chat.TextColor.fromRgb(ORANGE & 0xFFFFFF))),
                Minecraft.getInstance().font));
        y[0] += 14;
    }

    private static void master(List<AbstractWidget> w, int x, int[] y, int width, String label, Supplier<Boolean> get,
                               Consumer<Boolean> set, Runnable requestRebuild) {
        w.add(SettingsButtonWidget.builder(onOff(label, get.get()), btn -> {
                    boolean now = !get.get();
                    set.accept(now);
                    DungeonAlertsConfig.getInstance().save();
                    requestRebuild.run();
                }).bounds(x, y[0], width, 20).build());
        y[0] += 24;
    }

    private static void toggle(List<AbstractWidget> w, int x, int y, int width, String label, Supplier<Boolean> get,
                               Consumer<Boolean> set) {
        w.add(SettingsButtonWidget.builder(onOff(label, get.get()), btn -> {
                    boolean now = !get.get();
                    set.accept(now);
                    DungeonAlertsConfig.getInstance().save();
                    btn.setMessage(onOff(label, now));
                }).bounds(x, y, width, 18).build());
    }

    private static void colorButton(List<AbstractWidget> w, int x, int y, int width, String label,
                                    java.util.function.IntSupplier get, java.util.function.IntConsumer set, int defaultRgb) {
        w.add(SettingsButtonWidget.builder(com.killer560.hub.gui.ColorSwatch.label(label, get.getAsInt()), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    com.killer560.hub.compat.McCompat.setScreen(client, new com.killer560.hub.gui.ColorPickerScreen(
                            com.killer560.hub.compat.McCompat.screen(client), label, 0xFF000000 | get.getAsInt(),
                            0xFF000000 | defaultRgb, argb -> {
                        set.accept(argb & 0xFFFFFF);
                        DungeonAlertsConfig.getInstance().save();
                    }));
                }).bounds(x, y, width, 18).build());
    }

    private static Component displayLabel(DungeonAlertsConfig cfg) {
        return Component.literal(String.format(Locale.US, "Display Time: %.1fs", cfg.roomAlertsDisplaySeconds));
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
