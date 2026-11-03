package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.roomsim.SimBreakerState;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Dungeon Sim settings. For now one: how many Dungeon Breaker charges each secret gives back, which is the Wither
 * Essence Shop perk "Echoes of the Lost" (levels 1 to 5 give 1 to 5 charges; 0 here is the perk not bought).
 * killer560 (2026-10-06): "the regaining charges should be an optional slider or setting somewhere."
 */
public class SimSettingsTab extends BaseTab {

    public SimSettingsTab() {
        super("Sim Settings");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        w.add(new ThemedSliderButton(contentX, contentY, contentWidth, 18, echoesText(),
                SimBreakerState.secretCharges() / (double) SimBreakerState.MAX_SECRET_CHARGES) {
            @Override
            protected void updateMessage() {
                setMessage(echoesText());
            }

            @Override
            protected void applyValue() {
                SimBreakerState.setSecretCharges((int) Math.round(this.value * SimBreakerState.MAX_SECRET_CHARGES));
            }
        });
        // Spawn Starred Mobs (killer560, 2026-11-03: "Someone should be able to choose whether the mobs spawn or not for the
        // sim"): the Map Designer's Mobs choice, the same saved setting - a floor with starred mobs (on their learned
        // spawn spots where Blood Rush Helper knows them), Fels, wither doors and keys. Off by default, as it was.
        w.add(onOffButton("Spawn Starred Mobs", () -> com.killer560.hub.roomsim.SimMenuPrefs.load().mobs, on -> {
            com.killer560.hub.roomsim.SimMenuPrefs prefs = com.killer560.hub.roomsim.SimMenuPrefs.load();
            prefs.mobs = on;
            prefs.save();
            com.killer560.hub.roomsim.SimMobFloor.setEnabled(on);
        }, () -> { }, null, contentX, contentY + 22, contentWidth, 18));
        com.killer560.hub.gui.ScreenThemes.append(w, com.killer560.hub.roomsim.SimMenuScreen.THEME_ID, contentX,
                contentWidth, contentY + 22);
        return w;
    }

    private static Component echoesText() {
        int n = SimBreakerState.secretCharges();
        return Component.literal("Echoes of the Lost: " + (n == 0 ? "Off" : "Level " + n + " (+" + n + ")"));
    }
}
