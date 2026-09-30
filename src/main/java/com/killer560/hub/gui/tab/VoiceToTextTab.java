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

        int gap = 8;
        int col2W = (contentWidth - gap) / 2;
        int col2aX = contentX;
        int col2bX = contentX + col2W + gap;

        widgets.add(SettingsButtonWidget.builder(onOff("Voice To Text", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        // Reformatted 2026-09-27 (killer560: "reformat the menu to look a bit better") to the same two-column
        // row layout the other tabs use, grouped by what each setting is about; the explanatory text this used
        // to need lives in the hover tooltips now (SettingTooltipsData) instead of repeating it on the buttons.
        boolean pushToTalk = cfg.getMode() == VoiceToTextConfig.Mode.PUSH_TO_TALK;
        widgets.add(SettingsButtonWidget.builder(modeText(cfg), btn -> {
                    cfg.setMode(cfg.getMode().next());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(col2aX, y, pushToTalk ? col2W : contentWidth, 18).build());
        if (pushToTalk) {
            Component keyLabel = capturingKey ? Component.literal("Press any key...") : keyText(cfg);
            widgets.add(SettingsButtonWidget.builder(keyLabel, btn -> {
                        capturingKey = true;
                        btn.setMessage(Component.literal("Press any key..."));
                    }).bounds(col2bX, y, col2W, 18).build());
        }
        y += 22;

        widgets.add(SettingsButtonWidget.builder(micText(cfg), btn -> {
                    // Cycles Default -> each device that can record -> Default. Re-listed on every click, so a
                    // headset plugged in with the menu open shows up.
                    java.util.List<String> mics = com.killer560.hub.voicetotext.VoiceToTextFeature.microphones();
                    int i = mics.indexOf(cfg.getMicrophone());
                    cfg.setMicrophone(i + 1 < mics.size() ? mics.get(i + 1) : "");
                    cfg.save();
                    btn.setMessage(micText(cfg));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        // All Chat (and Co-op / Plain) joined Party and Guild here 2026-09-30, per killer560: "make push to
        // talk have an option to go to allchat as well". Cycles ChatDestination - the same enum, in the same
        // order, that the Spotify lyrics feature's own destination button cycles - left-click forward,
        // right-click back, which is how every other cycling button in this menu behaves.
        widgets.add(SettingsButtonWidget.builder(sendToText(cfg), btn -> {
                    cfg.setChatDestination(cfg.getChatDestination().next());
                    cfg.save();
                    btn.setMessage(sendToText(cfg));
                }).secondaryPress(btn -> {
                    cfg.setChatDestination(cfg.getChatDestination().previous());
                    cfg.save();
                    btn.setMessage(sendToText(cfg));
                }).bounds(contentX, y, col2W, 18).build());
        y += 24;

        return widgets;
    }

    private static Component sendToText(VoiceToTextConfig cfg) {
        return Component.literal("Send to: §b" + cfg.getChatDestination().displayName);
    }

    private static Component modeText(VoiceToTextConfig cfg) {
        return Component.literal("Mode: §b" + cfg.getMode().label);
    }

    private static Component micText(VoiceToTextConfig cfg) {
        String m = cfg.getMicrophone();
        return Component.literal("Microphone: §b" + (m.isEmpty() ? "System Default" : m));
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
