package com.killer560.hub.gui.tab;

import com.killer560.hub.dungeonalerts.DungeonAlertsConfig;
import com.killer560.hub.dungeonalerts.SecretSound;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Dungeon Alerts pack settings - one section per feature (see {@code com.killer560.hub.dungeonalerts}). Every
 *  change is saved immediately. */
public class DungeonAlertsTab extends BaseTab {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-dungeonalerts");
    private static final int ORANGE = 0xFFCC6600;

    public DungeonAlertsTab() {
        super("Dungeon Alerts");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        DungeonAlertsConfig cfg = DungeonAlertsConfig.getInstance();
        int gap = 8;
        int half = (contentWidth - gap) / 2;
        int colB = contentX + half + gap;
        int[] y = {contentY};

        // --- Shadow Assassin Alert ---
        header(w, contentX, y, contentWidth, "Shadow Assassin Alert");
        master(w, contentX, y, contentWidth, "Shadow Assassin Alert", () -> cfg.shadowAssassinEnabled,
                v -> cfg.shadowAssassinEnabled = v, requestRebuild);
        if (cfg.shadowAssassinEnabled) {
            toggle(w, contentX, y[0], contentWidth, "Party Chat Alert", () -> cfg.shadowAssassinPartyChat, v -> cfg.shadowAssassinPartyChat = v);
            y[0] += 22;
        }

        // --- Secret Sound ---
        header(w, contentX, y, contentWidth, "Secret Sound");
        master(w, contentX, y, contentWidth, "Secret Sound", () -> cfg.secretSoundEnabled, v -> cfg.secretSoundEnabled = v, requestRebuild);
        if (cfg.secretSoundEnabled) {
            w.add(SettingsButtonWidget.builder(soundLabel(cfg), btn -> {
                        SecretSound.SoundChoice[] all = SecretSound.SoundChoice.values();
                        SecretSound.SoundChoice next = all[(SecretSound.SoundChoice.byName(cfg.secretSoundId).ordinal() + 1) % all.length];
                        cfg.secretSoundId = next.name();
                        cfg.save();
                        LOGGER.info("[DungeonAlerts] Secret Sound -> {}", next.name());
                        btn.setMessage(soundLabel(cfg));
                    }).bounds(contentX, y[0], half, 18).build());
            w.add(SettingsButtonWidget.builder(Component.literal("Play Sound"), btn -> SecretSound.play())
                    .bounds(colB, y[0], half, 18).build());
            y[0] += 22;
            w.add(new ThemedSliderButton(contentX, y[0], half, 18, volumeLabel(cfg), cfg.secretSoundVolume) {
                @Override
                protected void updateMessage() {
                    setMessage(volumeLabel(cfg));
                }

                @Override
                protected void applyValue() {
                    cfg.secretSoundVolume = Math.round(this.value * 10) / 10f;
                    cfg.save();
                }
            });
            w.add(new ThemedSliderButton(colB, y[0], half, 18, pitchLabel(cfg), cfg.secretSoundPitch / 2.0) {
                @Override
                protected void updateMessage() {
                    setMessage(pitchLabel(cfg));
                }

                @Override
                protected void applyValue() {
                    cfg.secretSoundPitch = Math.round(this.value * 20) / 10f;
                    cfg.save();
                }
            });
            y[0] += 22;
        }

        // --- Terracotta Timer ---
        header(w, contentX, y, contentWidth, "Terracotta Timer (F6/M6 boss)");
        master(w, contentX, y, contentWidth, "Terracotta Timer", () -> cfg.terracottaEnabled, v -> cfg.terracottaEnabled = v, requestRebuild);

        // --- Spring Boots Overlay ---
        header(w, contentX, y, contentWidth, "Spring Boots Overlay");
        master(w, contentX, y, contentWidth, "Spring Boots Overlay", () -> cfg.springBootsEnabled, v -> cfg.springBootsEnabled = v, requestRebuild);
        if (cfg.springBootsEnabled) {
            toggle(w, contentX, y[0], contentWidth, "Height Box", () -> cfg.springBootsBox, v -> cfg.springBootsBox = v);
            y[0] += 22;
        }

        // --- Class Colors ---
        header(w, contentX, y, contentWidth, "Class Colors");
        master(w, contentX, y, contentWidth, "Class Colors", () -> cfg.classColorsEnabled, v -> cfg.classColorsEnabled = v, requestRebuild);
        if (cfg.classColorsEnabled) {
            toggle(w, contentX, y[0], half, "Tab List", () -> cfg.classColorsTab, v -> cfg.classColorsTab = v);
            toggle(w, colB, y[0], half, "Nametags", () -> cfg.classColorsNametags, v -> cfg.classColorsNametags = v);
            y[0] += 22;
        }

        // --- Room Alerts ---
        header(w, contentX, y, contentWidth, "Room Alerts");
        master(w, contentX, y, contentWidth, "Room Alerts", () -> cfg.roomAlertsEnabled, v -> cfg.roomAlertsEnabled = v, requestRebuild);
        if (cfg.roomAlertsEnabled) {
            toggle(w, contentX, y[0], half, "All Puzzle Rooms", () -> cfg.roomAlertsPuzzles, v -> cfg.roomAlertsPuzzles = v);
            toggle(w, colB, y[0], half, "Chat Message", () -> cfg.roomAlertsChat, v -> cfg.roomAlertsChat = v);
            y[0] += 22;
            toggle(w, contentX, y[0], half, "On-screen Text", () -> cfg.roomAlertsTitle, v -> cfg.roomAlertsTitle = v);
            w.add(new ThemedSliderButton(colB, y[0], half, 18, displayLabel(cfg), (cfg.roomAlertsDisplaySeconds - 0.5) / 2.5) {
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
            w.add(new StringWidget(contentX, y[0], contentWidth, 12,
                    Component.literal("§7Room names (comma separated, e.g. Water Board, Trinity):"), Minecraft.getInstance().font));
            y[0] += 14;
            EditBox names = new EditBox(Minecraft.getInstance().font, contentX, y[0], contentWidth, 18, Component.literal("Room names"));
            names.setMaxLength(500);
            names.setValue(cfg.roomAlertsNames);
            names.setResponder(text -> {
                cfg.roomAlertsNames = text;
                cfg.save();
            });
            w.add(names);
            y[0] += 24;
        }

        w.add(new StringWidget(contentX, y[0], contentWidth, 12,
                Component.literal("§7HUD parts (Spring Boots, Room Alerts) move in the HUD editor. Ragnarock moved to the Rag Axe tab."),
                Minecraft.getInstance().font));
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
                    LOGGER.info("[DungeonAlerts] {} -> {}", label, now ? "ON" : "OFF");
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
                    LOGGER.info("[DungeonAlerts] {} -> {}", label, now ? "ON" : "OFF");
                    btn.setMessage(onOff(label, now));
                }).bounds(x, y, width, 18).build());
    }

    private static Component soundLabel(DungeonAlertsConfig cfg) {
        return Component.literal("Sound: " + SecretSound.SoundChoice.byName(cfg.secretSoundId).label);
    }

    private static Component volumeLabel(DungeonAlertsConfig cfg) {
        return Component.literal(String.format(Locale.US, "Volume: %.1f", cfg.secretSoundVolume));
    }

    private static Component pitchLabel(DungeonAlertsConfig cfg) {
        return Component.literal(String.format(Locale.US, "Pitch: %.1f", cfg.secretSoundPitch));
    }

    private static Component displayLabel(DungeonAlertsConfig cfg) {
        return Component.literal(String.format(Locale.US, "Display Time: %.1fs", cfg.roomAlertsDisplaySeconds));
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
