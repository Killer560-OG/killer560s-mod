package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.voicetotext.VoiceToTextConfig;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Voice To Text settings - see {@link com.killer560.hub.voicetotext.VoiceToTextFeature}'s class doc
 *  for the real, disclosed risk this feature carries that nothing else in this mod does (an
 *  unverified native speech-recognition library) - test it specifically before trusting it live. */
public class VoiceToTextTab extends BaseTab implements KeyCaptureTab {

    private boolean capturingKey = false;

    public VoiceToTextTab() {
        super("Voice To Text");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        VoiceToTextConfig cfg = VoiceToTextConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Voice To Text", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        Component keyLabel = capturingKey ? Component.literal("Press any key...") : keyText(cfg);
        widgets.add(SettingsButtonWidget.builder(keyLabel, btn -> {
                    capturingKey = true;
                    btn.setMessage(Component.literal("Press any key..."));
                }).bounds(contentX, y, 160, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(
                    Component.literal("Send to: " + (cfg.isSendToPartyChat() ? "Party" : "Guild")), btn -> {
                        cfg.setSendToPartyChat(!cfg.isSendToPartyChat());
                        cfg.save();
                        btn.setMessage(Component.literal("Send to: " + (cfg.isSendToPartyChat() ? "Party" : "Guild")));
                    }).bounds(contentX, y, 160, 18).build());
        y += 24;

        return widgets;
    }

    private static Component keyText(VoiceToTextConfig cfg) {
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
        VoiceToTextConfig cfg = VoiceToTextConfig.getInstance();
        cfg.setPushToTalkKeyCode(keyCode == InputConstants.KEY_ESCAPE ? -1 : keyCode);
        cfg.save();
    }
}
