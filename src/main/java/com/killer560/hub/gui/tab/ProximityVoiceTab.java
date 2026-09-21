package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.proximityvoice.MicTester;
import com.killer560.hub.proximityvoice.MicrophoneDevices;
import com.killer560.hub.proximityvoice.ProximityVoiceConfig;
import com.killer560.hub.proximityvoice.ProximityVoiceConfig.VoiceScope;
import com.killer560.hub.proximityvoice.ProximityVoiceFeature;
import com.killer560.hub.util.KeyUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Proximity Voice settings - see {@link com.killer560.hub.proximityvoice.ProximityVoiceFeature}'s
 *  class doc for the real P2P/STUN architecture, the Talk/Listen scope model, and their disclosed
 *  tradeoffs (no codec, works on most but not all home networks, untested with a real second player). */
public class ProximityVoiceTab extends BaseTab implements KeyCaptureTab {

    private boolean capturingKey = false;
    /** Transient UI-only state (not persisted) - whether the live mic level meter is showing. Reopening
     *  the tab keeps whatever this was left at, same as {@link #capturingKey}. */
    private boolean testingMic = false;

    public ProximityVoiceTab() {
        super("Proximity Voice");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        ProximityVoiceConfig cfg = ProximityVoiceConfig.getInstance();
        // Cheap even every rebuild - only actually re-scans off-thread if nothing is already in flight,
        // and the GUI reads the cache regardless (see MicrophoneDevices' doc on why this must never block
        // the render thread).
        MicrophoneDevices.refreshAsync();

        widgets.add(SettingsButtonWidget.builder(onOff("Proximity Voice", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(transmitModeText(cfg), btn -> {
                    cfg.setPushToTalk(!cfg.isPushToTalk());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 18).build());
        y += 22;

        if (cfg.isPushToTalk()) {
            Component keyLabel = capturingKey ? Component.literal("Press any key or mouse button...") : keyText(cfg);
            widgets.add(SettingsButtonWidget.builder(keyLabel, btn -> {
                        capturingKey = true;
                        btn.setMessage(Component.literal("Press any key or mouse button..."));
                    }).bounds(contentX, y, 220, 18).build());
            y += 22;
        } else {
            widgets.add(SettingsButtonWidget.builder(onOff("Muted", cfg.isMutedSelf()), btn -> {
                        cfg.setMutedSelf(!cfg.isMutedSelf());
                        cfg.save();
                        btn.setMessage(onOff("Muted", cfg.isMutedSelf()));
                    }).bounds(contentX, y, 160, 18).build());
            y += 22;
        }

        String deviceName = cfg.getMicrophoneDeviceName();
        widgets.add(SettingsButtonWidget.builder(microphoneText(deviceName), btn -> {
                    String next = nextDevice(deviceName);
                    cfg.setMicrophoneDeviceName(next);
                    cfg.save();
                    ProximityVoiceFeature.onMicrophoneDeviceChanged();
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 18).build());
        y += 22;

        String inUse = ProximityVoiceFeature.currentDeviceInUse();
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7In use: §b" + (inUse == null ? "not open right now" : inUse)),
                Minecraft.getInstance().font));
        y += 14;

        widgets.add(SettingsButtonWidget.builder(onOff("Test Mic", testingMic), btn -> {
                    testingMic = !testingMic;
                    requestRebuild.run();
                }).bounds(contentX, y, 160, 18).build());
        y += 22;
        if (testingMic) {
            widgets.add(new MicLevelMeter(contentX, y, 220, 14, deviceName));
            y += 18;
        }

        widgets.add(SettingsButtonWidget.builder(scopeText("Talk Reaches", cfg.getTalkScope()), btn -> {
                    cfg.setTalkScope(flip(cfg.getTalkScope()));
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(scopeText("You Hear", cfg.getListenScope()), btn -> {
                    cfg.setListenScope(flip(cfg.getListenScope()));
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 18).build());
        y += 22;

        boolean anyLobby = cfg.getTalkScope() == VoiceScope.LOBBY || cfg.getListenScope() == VoiceScope.LOBBY;
        if (anyLobby) {
            widgets.add(SettingsButtonWidget.builder(onOff("Lobby Falloff", cfg.isLobbyFalloffEnabled()), btn -> {
                        cfg.setLobbyFalloffEnabled(!cfg.isLobbyFalloffEnabled());
                        cfg.save();
                        btn.setMessage(onOff("Lobby Falloff", cfg.isLobbyFalloffEnabled()));
                    }).bounds(contentX, y, 200, 18).build());
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

        widgets.add(SettingsButtonWidget.builder(onOff("Party Voice HUD", cfg.isShowPartyVoiceHud()), btn -> {
                    cfg.setShowPartyVoiceHud(!cfg.isShowPartyVoiceHud());
                    cfg.save();
                    btn.setMessage(onOff("Party Voice HUD", cfg.isShowPartyVoiceHud()));
                }).bounds(contentX, y, 200, 18).build());
        y += 22;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Talk reaches: " + scopeDescription(cfg.getTalkScope())),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7You hear: " + scopeDescription(cfg.getListenScope())),
                Minecraft.getInstance().font));

        return widgets;
    }

    private static Component transmitModeText(ProximityVoiceConfig cfg) {
        return Component.literal("Transmit Mode: " + (cfg.isPushToTalk() ? "§bPush To Talk" : "§aOpen Mic"));
    }

    private static Component microphoneText(String deviceName) {
        String shown = deviceName == null || deviceName.isBlank() ? MicrophoneDevices.DEFAULT_LABEL : deviceName;
        return Component.literal("Microphone: §b" + shown);
    }

    /** Cycles System Default -> each cached device name -> back to System Default. A device that vanished
     *  since it was picked (unplugged) simply isn't in the list any more - clicking through just skips it,
     *  same as {@link MicrophoneDevices#openLine} falling back for it at capture time. */
    private static String nextDevice(String current) {
        List<String> options = new ArrayList<>();
        options.add("");
        options.addAll(MicrophoneDevices.cachedDeviceNames());
        int idx = options.indexOf(current == null ? "" : current);
        return options.get((idx + 1) % options.size());
    }

    private static Component scopeText(String label, VoiceScope scope) {
        return Component.literal(label + ": " + (scope == VoiceScope.LOBBY ? "§bLobby" : "§aParty"));
    }

    private static VoiceScope flip(VoiceScope scope) {
        return scope == VoiceScope.LOBBY ? VoiceScope.PARTY : VoiceScope.LOBBY;
    }

    private static String scopeDescription(VoiceScope scope) {
        return scope == VoiceScope.LOBBY ? "§beveryone in your lobby" : "§ayour real party only";
    }

    private static Component keyText(ProximityVoiceConfig cfg) {
        return Component.literal("Push-to-Talk Key: §b" + KeyUtil.bindDisplayName(cfg.getPushToTalkKeyCode()));
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    @Override
    public boolean isListeningForKey() {
        return capturingKey;
    }

    @Override
    public boolean supportsMouseCapture() {
        return true;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        capturingKey = false;
        ProximityVoiceConfig cfg = ProximityVoiceConfig.getInstance();
        cfg.setPushToTalkKeyCode(keyCode == InputConstants.KEY_ESCAPE ? KeyUtil.NONE : keyCode);
        cfg.save();
    }

    @Override
    public void onMouseCaptured(int button) {
        capturingKey = false;
        ProximityVoiceConfig cfg = ProximityVoiceConfig.getInstance();
        cfg.setPushToTalkKeyCode(KeyUtil.codeForMouseButton(button));
        cfg.save();
    }

    /** Live mic input-level bar - killer560's "Test Mic" ask. {@link #extractWidgetRenderState} runs every
     *  frame this widget is on screen, which doubles as {@link MicTester}'s heartbeat (see its class doc
     *  for why that matters: there is no "tab closed" callback to hook here, so the render calls
     *  themselves are what lets the test line close itself once this widget stops appearing). */
    private static final class MicLevelMeter extends AbstractWidget {
        private static final int BG = 0xFF1A1A1A;
        private static final int BORDER = 0xFF663D1A;
        private static final int FILL = 0xFFCC6600;

        private final String deviceName;

        MicLevelMeter(int x, int y, int width, int height, String deviceName) {
            super(x, y, width, height, Component.literal("Mic Level"));
            this.deviceName = deviceName;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            MicTester.touch(deviceName);
            float level = Math.max(0f, Math.min(1f, MicTester.level()));
            int x0 = getX();
            int y0 = getY();
            int w = getWidth();
            int h = getHeight();
            graphics.fill(x0, y0, x0 + w, y0 + h, BG);
            graphics.outline(x0, y0, w, h, BORDER);
            int filled = Math.round(level * (w - 2));
            if (filled > 0) {
                graphics.fill(x0 + 1, y0 + 1, x0 + 1 + filled, y0 + h - 1, FILL);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, getMessage());
        }
    }
}
