package com.killer560.hub.gui.tab;

import com.killer560.hub.dungeoninfo.DungeonInfoConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Secrets HUD settings - per-run and per-room secrets-found count. Reorg 2026-09-21 (killer560's
 *  secret/score/time HUD split): this tab used to also hold the run timer, mimic/prince/bat kill alerts and
 *  the manual 270/300 messages - the timer moved to {@link TimeHudTab}, and the alert/message settings moved
 *  to {@link ScoreCalculatorTab} (killer560: "a score hud that has all the send messages"). Secret-collected
 *  SOUND settings are NOT here - they already live in the "Secrets" folder ({@link SecretSoundTab}, moved
 *  there 2026-09-21 from {@code DungeonAlertsTab}) - see that tab, not this one, for that toggle. */
public class DungeonInfoTab extends BaseTab {

    public DungeonInfoTab() {
        super("Secrets HUD");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        DungeonInfoConfig cfg = DungeonInfoConfig.getInstance();

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Secrets count is read from the tab list. Move it with the HUD editor."),
                Minecraft.getInstance().font));
        y += 20;

        widgets.add(SettingsButtonWidget.builder(secretsText(), btn -> {
                    cfg.setSecretsHudEnabled(!cfg.isSecretsHudEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 20).build());
        y += 24;

        if (!cfg.isSecretsHudEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Per-Room Secrets", cfg.isShowPerRoomSecrets()), btn -> {
                    cfg.setShowPerRoomSecrets(!cfg.isShowPerRoomSecrets());
                    cfg.save();
                    btn.setMessage(onOff("Per-Room Secrets", cfg.isShowPerRoomSecrets()));
                }).bounds(contentX, y, 220, 18).build());
        y += 22;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Per-Room: secrets found since you entered the room you're currently in"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7(needs Live Map to have identified the room - see the Live Map tab)."),
                Minecraft.getInstance().font));

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    private static Component secretsText() {
        return Component.literal("Secrets HUD: " + (DungeonInfoConfig.getInstance().isSecretsHudEnabled() ? "§aON" : "§cOFF"));
    }
}
