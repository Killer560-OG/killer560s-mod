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
        return w;
    }

    private static Component echoesText() {
        int n = SimBreakerState.secretCharges();
        return Component.literal("Echoes of the Lost: " + (n == 0 ? "Off" : "Level " + n + " (+" + n + ")"));
    }
}
