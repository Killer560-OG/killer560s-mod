package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.proximityvoice.ProximityVoiceConfig;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Proximity Voice settings - see {@link com.killer560.hub.proximityvoice.ProximityVoiceFeature}'s
 *  class doc for the real P2P/STUN architecture and its disclosed tradeoffs (no codec, works on most
 *  but not all home networks, untested with a real second player). */
public class ProximityVoiceTab extends BaseTab implements KeyCaptureTab {

    private boolean capturingKey = false;

    public ProximityVoiceTab() {
        super("Proximity Voice");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        ProximityVoiceConfig cfg = ProximityVoiceConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Proximity Voice", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Push To Talk", cfg.isPushToTalk()), btn -> {
                    cfg.setPushToTalk(!cfg.isPushToTalk());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, 160, 18).build());
        y += 22;

        if (cfg.isPushToTalk()) {
            Component keyLabel = capturingKey ? Component.literal("Press any key...") : keyText(cfg);
            widgets.add(SettingsButtonWidget.builder(keyLabel, btn -> {
                        capturingKey = true;
                        btn.setMessage(Component.literal("Press any key..."));
                    }).bounds(contentX, y, 160, 18).build());
            y += 22;
        } else {
            widgets.add(SettingsButtonWidget.builder(onOff("Muted", cfg.isMutedSelf()), btn -> {
                        cfg.setMutedSelf(!cfg.isMutedSelf());
                        cfg.save();
                        btn.setMessage(onOff("Muted", cfg.isMutedSelf()));
                    }).bounds(contentX, y, 160, 18).build());
            y += 22;
        }

        double rangeNorm = (cfg.getMaxRange() - 8.0) / (128.0 - 8.0);
        widgets.add(new ThemedSliderButton(contentX, y, 220, 18,
                Component.literal(String.format(Locale.US, "Range: %.0f blocks", cfg.getMaxRange())), rangeNorm) {
            @Override
            protected void updateMessage() {
                setMessage(Component.literal(String.format(Locale.US, "Range: %.0f blocks", cfg.getMaxRange())));
            }

            @Override
            protected void applyValue() {
                cfg.setMaxRange(8.0 + this.value * (128.0 - 8.0));
                cfg.save();
            }
        });
        y += 22;

        double volNorm = cfg.getOutputVolume();
        widgets.add(new ThemedSliderButton(contentX, y, 220, 18,
                Component.literal(String.format(Locale.US, "Volume: %.0f%%", cfg.getOutputVolume() * 100)), volNorm) {
            @Override
            protected void updateMessage() {
                setMessage(Component.literal(String.format(Locale.US, "Volume: %.0f%%", cfg.getOutputVolume() * 100)));
            }

            @Override
            protected void applyValue() {
                cfg.setOutputVolume((float) this.value);
                cfg.save();
            }
        });
        y += 26;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§c§lUntested: no microphone or second player to verify real P2P"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§c§laudio with. Works on most home networks, not all (no relay"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§c§lserver exists to fall back on) - test with a real friend first."),
                Minecraft.getInstance().font));

        return widgets;
    }

    private static Component keyText(ProximityVoiceConfig cfg) {
        String name = cfg.getPushToTalkKeyCode() < 0 ? "Not Set"
                : InputConstants.Type.KEYSYM.getOrCreate(cfg.getPushToTalkKeyCode()).getDisplayName().getString();
        return Component.literal("Push-to-Talk Key: §b" + name);
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    @Override
    public boolean isListeningForKey() {
        return capturingKey;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        capturingKey = false;
        ProximityVoiceConfig cfg = ProximityVoiceConfig.getInstance();
        cfg.setPushToTalkKeyCode(keyCode == InputConstants.KEY_ESCAPE ? -1 : keyCode);
        cfg.save();
    }
}
