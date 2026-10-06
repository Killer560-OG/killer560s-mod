package com.killer560.hub.gui.tab;

import com.killer560.hub.autosecret.AutoSecretConfig;
import com.killer560.hub.autosecret.AutoSecretFeature;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.util.KeyUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;

/**
 * "Auto Clear" - CHEAT BUILD ONLY (killer560, 2026-10-06): Auto Secret's settings, then Auto Clear's.
 *
 * <p>Auto Clear is another feature, built separately; it adds its own rows through {@link #addSection}, so neither
 * feature edits the other's code and this class is the only one that lays the tab out. A section is told where to
 * start and returns where the next one starts, so rows never share a rectangle (testkit 386 walks every tab for
 * overlaps). Until a section is registered the tab says Auto Clear is not in the build.
 */
public class AutoClearTab extends BaseTab implements KeyCaptureTab {

    private static final int GAP = 6;
    private static final int ROW = 24;

    /** One feature's block of rows in this tab. */
    @FunctionalInterface
    public interface Section {
        /**
         * Adds this section's widgets starting at {@code y}.
         * @return the y the next section starts at (below the last row this one added)
         */
        int build(List<AbstractWidget> widgets, int x, int y, int width, Runnable requestRebuild);
    }

    private static final List<Section> SECTIONS = new CopyOnWriteArrayList<>();

    /** For Auto Clear (or anything else that belongs in this tab): its rows go below Auto Secret's. */
    public static void addSection(Section section) {
        if (section != null) {
            SECTIONS.add(section);
        }
    }

    private boolean capturing;
    /** The key being captured is the Autopilot's, not Auto Secret's. */
    private boolean capturingPilot;

    public AutoClearTab() {
        super("Auto Clear");
    }

    @Override
    public boolean isCheatOnly() {
        return true;
    }

    // ---- KeyCaptureTab ----

    @Override
    public boolean isListeningForKey() {
        return capturing;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        if (!capturing) {
            return;
        }
        AutoSecretConfig cfg = AutoSecretConfig.getInstance();
        int key = keyCode == InputConstants.KEY_ESCAPE ? KeyUtil.NONE : KeyUtil.sanitize(keyCode);
        if (capturingPilot) {
            cfg.setAutopilotKey(key);
        } else {
            cfg.setToggleKey(key);
        }
        cfg.save();
        capturing = false;
        capturingPilot = false;
    }

    @Override
    public boolean supportsMouseCapture() {
        return true;
    }

    @Override
    public void onMouseCaptured(int button) {
        if (!capturing) {
            return;
        }
        AutoSecretConfig cfg = AutoSecretConfig.getInstance();
        if (capturingPilot) {
            cfg.setAutopilotKey(KeyUtil.codeForMouseButton(button));
        } else {
            cfg.setToggleKey(KeyUtil.codeForMouseButton(button));
        }
        cfg.save();
        capturing = false;
        capturingPilot = false;
    }

    // ---- layout ----

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return w;
        }
        AutoSecretConfig cfg = AutoSecretConfig.getInstance();
        int half = (contentWidth - GAP) / 2;
        int right = Math.max(1, contentWidth - half - GAP);
        int[] y = {contentY};

        // Dungeon Autopilot: Auto Secret, Auto Clear and Auto Puzzles under one planner (its rows first - it drives the rest).
        header(w, contentX, y, contentWidth, "Dungeon Autopilot");
        w.add(SettingsButtonWidget.builder(pilotRunText(), btn -> {
            AutoSecretFeature.toggleAutopilot();
            requestRebuild.run();
        }).bounds(contentX, y[0], half, 20).build());
        w.add(SettingsButtonWidget.builder(pilotKeyText(cfg), btn -> {
            capturing = true;
            capturingPilot = true;
            btn.setMessage(Component.literal("Autopilot Key: §ePress any key..."));
        }).bounds(contentX + half + GAP, y[0], right, 20).build());
        y[0] += ROW;
        w.add(SettingsButtonWidget.builder(runModeText(cfg), btn -> {
            AutoSecretConfig.RunMode[] all = AutoSecretConfig.RunMode.values();
            cfg.setRunMode(all[(cfg.getRunMode().ordinal() + 1) % all.length]);
            cfg.save();
            btn.setMessage(runModeText(cfg));
        }).bounds(contentX, y[0], half, 20).build());
        toggle(w, contentX + half + GAP, y[0], right, "Blood First", cfg::isBloodFirst, cfg::setBloodFirst);
        y[0] += ROW;
        toggle(w, contentX, y[0], half, "Do Puzzles", cfg::isDoPuzzles, cfg::setDoPuzzles);
        toggle(w, contentX + half + GAP, y[0], right, "Autopilot HUD", cfg::isAutopilotHud, cfg::setAutopilotHud);
        y[0] += ROW;
        double kSpan = AutoSecretConfig.MAX_KEY_BASE - AutoSecretConfig.MIN_KEY_BASE;
        slider(w, contentX, y[0], contentWidth, () -> String.format(java.util.Locale.US,
                        "Key Base Range: %.1f (-> %.1f)", cfg.getKeyBaseRange(), cfg.keyPickupRange()),
                (cfg.getKeyBaseRange() - AutoSecretConfig.MIN_KEY_BASE) / kSpan,
                v -> cfg.setKeyBaseRange(Math.round((AutoSecretConfig.MIN_KEY_BASE + v * kSpan) * 10) / 10.0));
        y[0] += ROW;

        header(w, contentX, y, contentWidth, "Auto Secret");
        w.add(SettingsButtonWidget.builder(runText(), btn -> {
            AutoSecretFeature.toggle();
            requestRebuild.run();
        }).bounds(contentX, y[0], half, 20).build());
        w.add(SettingsButtonWidget.builder(keyText(cfg), btn -> {
            capturing = true;
            capturingPilot = false;
            btn.setMessage(Component.literal("Auto Secret Key: §ePress any key..."));
        }).bounds(contentX + half + GAP, y[0], right, 20).build());
        y[0] += ROW;

        toggle(w, contentX, y[0], half, "Insta Clear First", cfg::isInstaClear, cfg::setInstaClear);
        toggle(w, contentX + half + GAP, y[0], right, "Ice Fill First", cfg::isIceFillFirst, cfg::setIceFillFirst);
        y[0] += ROW;
        toggle(w, contentX, y[0], half, "Auto Clear Rooms", cfg::isAutoClearRooms, cfg::setAutoClearRooms);
        toggle(w, contentX + half + GAP, y[0], right, "Chat Feedback", cfg::isChatFeedback, cfg::setChatFeedback);
        y[0] += ROW;
        int pSpan = AutoSecretConfig.MAX_PUZZLE_WAIT_S - AutoSecretConfig.MIN_PUZZLE_WAIT_S;
        slider(w, contentX, y[0], half, () -> "Puzzle Wait: " + cfg.getPuzzleWaitSeconds() + "s",
                (cfg.getPuzzleWaitSeconds() - AutoSecretConfig.MIN_PUZZLE_WAIT_S) / (double) pSpan,
                v -> cfg.setPuzzleWaitSeconds((int) Math.round(AutoSecretConfig.MIN_PUZZLE_WAIT_S + v * pSpan)));
        int sSpan = AutoSecretConfig.MAX_STALL_S - AutoSecretConfig.MIN_STALL_S;
        slider(w, contentX + half + GAP, y[0], right, () -> "Route Stall: " + cfg.getRouteStallSeconds() + "s",
                (cfg.getRouteStallSeconds() - AutoSecretConfig.MIN_STALL_S) / (double) sSpan,
                v -> cfg.setRouteStallSeconds((int) Math.round(AutoSecretConfig.MIN_STALL_S + v * sSpan)));
        y[0] += ROW;
        w.add(new StringWidget(contentX, y[0], contentWidth, 12, Component.literal("§7" + AutoSecretFeature.statusText()),
                Minecraft.getInstance().font));
        y[0] += 16;

        if (SECTIONS.isEmpty()) {
            header(w, contentX, y, contentWidth, "Auto Clear");
            w.add(new StringWidget(contentX, y[0], contentWidth, 12, Component.literal("§7Auto Clear is not in this build yet."),
                    Minecraft.getInstance().font));
            y[0] += 16;
        } else {
            for (Section s : SECTIONS) {
                y[0] = s.build(w, contentX, y[0] + GAP, contentWidth, requestRebuild);
            }
        }
        return w;
    }

    // ---- helpers ----

    private static Component pilotRunText() {
        return Component.literal("Autopilot: " + (AutoSecretFeature.isAutopilot() ? "§aRunning" : "§cStopped"));
    }

    private Component pilotKeyText(AutoSecretConfig cfg) {
        if (capturing && capturingPilot) {
            return Component.literal("Autopilot Key: §ePress any key...");
        }
        int key = cfg.getAutopilotKey();
        return Component.literal("Autopilot Key: " + (key == KeyUtil.NONE ? "§7Not Set" : "§e" + KeyUtil.bindDisplayName(key)));
    }

    private static Component runModeText(AutoSecretConfig cfg) {
        return Component.literal("Run Mode: §e" + cfg.getRunMode().label());
    }

    private static Component runText() {
        return Component.literal("Auto Secret: " + (AutoSecretFeature.isRunning() ? "§aRunning" : "§cStopped"));
    }

    private Component keyText(AutoSecretConfig cfg) {
        if (capturing && !capturingPilot) {
            return Component.literal("Auto Secret Key: §ePress any key...");
        }
        int key = cfg.getToggleKey();
        return Component.literal("Auto Secret Key: " + (key == KeyUtil.NONE ? "§7Not Set" : "§e" + KeyUtil.bindDisplayName(key)));
    }

    private static void header(List<AbstractWidget> w, int x, int[] y, int width, String text) {
        y[0] += 6;
        w.add(new StringWidget(x, y[0], width, 12, SectionHeaders.header(text, true), Minecraft.getInstance().font));
        y[0] += 16;
    }

    private static void toggle(List<AbstractWidget> w, int x, int y, int width, String name, Supplier<Boolean> getter,
                               Consumer<Boolean> setter) {
        w.add(SettingsButtonWidget.builder(onOff(name, getter.get()), btn -> {
            setter.accept(!getter.get());
            AutoSecretConfig.getInstance().save();
            btn.setMessage(onOff(name, getter.get()));
        }).bounds(x, y, Math.max(1, width), 20).build());
    }

    private static Component onOff(String name, boolean on) {
        return Component.literal(name + ": " + (on ? "§aON" : "§cOFF"));
    }

    private static void slider(List<AbstractWidget> w, int x, int y, int width, Supplier<String> text, double normalized,
                               DoubleConsumer apply) {
        w.add(new ThemedSliderButton(x, y, Math.max(1, width), 20, Component.literal(text.get()),
                Math.max(0.0, Math.min(1.0, normalized))) {
            @Override
            protected void updateMessage() {
                setMessage(Component.literal(text.get()));
            }

            @Override
            protected void applyValue() {
                apply.accept(this.value);
                AutoSecretConfig.getInstance().save();
            }
        });
    }
}
