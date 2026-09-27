package com.killer560.hub.gui.tab;

import com.killer560.hub.dungeoninfo.DungeonInfoConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Secrets HUD settings - shows secrets found in the room you're standing in over that room's total.
 *  Reorg 2026-09-21 (killer560's secret/score/time HUD split): this tab used to also hold the run timer,
 *  mimic/prince/bat kill alerts and the manual 270/300 messages - the alert/message settings moved to
 *  {@link ScoreCalculatorTab} (killer560: "a score hud that has all the send messages"). The run timer got
 *  its own Time HUD tab in that same split, then was removed outright 2026-09-27 (killer560: "remove the
 *  time hud those are things that should be in the splits section") - see {@code SplitTimersTab} for that.
 *  Secret-collected SOUND settings are NOT here - they already live in the "Secrets" folder
 *  ({@link SecretSoundTab}, moved there 2026-09-21 from {@code DungeonAlertsTab}) - see that tab, not this
 *  one, for that toggle.
 *  <p>
 *  Reworked 2026-09-27 (killer560: "the secret hud should only be in room secrets collected / total secrets
 *  in the room... remove all this extra text outside of the actual settings") - the run-total/percent
 *  display and its "Per-Room Secrets" toggle are gone (per-room is the only thing the HUD shows now), and
 *  the explanatory paragraphs are gone too - the master toggle's own hover tooltip covers that now. */
public class DungeonInfoTab extends BaseTab {

    public DungeonInfoTab() {
        super("Secrets HUD");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        DungeonInfoConfig cfg = DungeonInfoConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(secretsText(), btn -> {
                    cfg.setSecretsHudEnabled(!cfg.isSecretsHudEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 20).build());

        return widgets;
    }

    private static Component secretsText() {
        return Component.literal("Secrets HUD: " + (DungeonInfoConfig.getInstance().isSecretsHudEnabled() ? "§aON" : "§cOFF"));
    }
}
