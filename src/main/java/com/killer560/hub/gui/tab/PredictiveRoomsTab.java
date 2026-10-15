package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.livemap.predict.PredictiveRoomsConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Predictive Rooms settings, both builds - see {@link com.killer560.hub.livemap.predict.RoomPredictor} for how a room
 *  is named from what he has seen, and {@code livemap.SpecialRoomGuess} for the split-colour one-tile guess. */
public class PredictiveRoomsTab extends BaseTab {

    public PredictiveRoomsTab() {
        super("Predictive Rooms");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        PredictiveRoomsConfig cfg = PredictiveRoomsConfig.getInstance();
        int y = contentY;
        widgets.add(SettingsButtonWidget.builder(onOff("Predictive Rooms", cfg.isEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.isEnabledRaw());
                    cfg.save();
                    btn.setMessage(onOff("Predictive Rooms", cfg.isEnabledRaw()));
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;
        widgets.add(SettingsButtonWidget.builder(onOff("Special Room Guess", cfg.isSpecialGuessRaw()), btn -> {
                    cfg.setSpecialGuess(!cfg.isSpecialGuessRaw());
                    cfg.save();
                    btn.setMessage(onOff("Special Room Guess", cfg.isSpecialGuessRaw()));
                }).bounds(contentX, y, contentWidth, 18).build());
        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
