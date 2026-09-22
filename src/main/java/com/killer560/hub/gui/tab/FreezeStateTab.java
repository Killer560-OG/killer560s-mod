package com.killer560.hub.gui.tab;

import com.killer560.hub.ap3.Ap3Commands.Action;
import com.killer560.hub.ap3.Ap3Config;
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
import java.util.Locale;

/**
 * Freeze State - its own cheat-only tab (killer560, 2026-09-21: "freezestate should be its own tab. Make it in
 * cheats only"). The ban warning, the Rewind Memory slider and the three keybinds ({@code /freezestate}, one tick
 * back, one tick forward). The settings still live in {@link Ap3Config}; the engine is {@code Ap3FreezeState}.
 * Only added to {@link NewTab} behind {@code BuildVariant.CHEAT_FEATURES_ENABLED}.
 */
public class FreezeStateTab extends BaseTab implements KeyCaptureTab {

    private static final int ROW = 18;
    static final Action[] ACTIONS = {Action.FREEZE_STATE, Action.REWIND_TICK, Action.FORWARD_TICK};

    private Action capturing;

    public FreezeStateTab() {
        super("Freeze State");
    }

    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public boolean isListeningForKey() {
        return capturing != null;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        if (capturing == null) {
            return;
        }
        Ap3Config cfg = Ap3Config.getInstance();
        int key = keyCode == InputConstants.KEY_ESCAPE ? KeyUtil.NONE : KeyUtil.sanitize(keyCode);
        cfg.setKeybind(capturing.id, key);
        cfg.save();
        capturing = null;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return w;
        }
        var font = Minecraft.getInstance().font;
        Ap3Config cfg = Ap3Config.getInstance();
        int y = contentY;

        w.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Freeze State", true), font));
        y += 16;
        String[] warning = {
                "§4§l!! NEVER USE THIS ON HYPIXEL - INSTANT BAN !!",
                "§cRewinding teleports your character, and a frozen player in the air",
                "§clooks like hovering. Singleplayer or p3sim only. On Hypixel the keys",
                "§crefuse; only the typed /rewind works there, behind a warning."
        };
        for (String line : warning) {
            w.add(new StringWidget(contentX, y, contentWidth, 10, Component.literal(line), font));
            y += 11;
        }
        y += 6;
        String[] help = {
                "§7/freezestate - freeze / resume. While frozen the camera is free and",
                "§7/ap3 add uses where the camera looks, not your character's facing.",
                "§7/rewind [ticks] goes back from where you are; /rewind forward [ticks] predicts ahead.",
                "§7AP3 keeps going while frozen: each forward step runs one AP3 tick. Your own keys",
                "§7and mouse never move the character while frozen - only AP3 nodes do."
        };
        for (String line : help) {
            w.add(new StringWidget(contentX, y, contentWidth, 10, Component.literal(line), font));
            y += 11;
        }
        y += 8;

        double min = Ap3Config.MIN_REWIND_TICKS;
        double span = Ap3Config.MAX_REWIND_TICKS - min;
        w.add(new ThemedSliderButton(contentX, y, contentWidth, ROW, rewindText(cfg),
                Math.max(0.0, Math.min(1.0, (cfg.getRewindTicks() - min) / span))) {
            @Override
            protected void updateMessage() {
                setMessage(rewindText(cfg));
            }

            @Override
            protected void applyValue() {
                double raw = min + this.value * span;
                cfg.setRewindTicks((int) (Math.round(raw / 20.0) * 20));
                cfg.save();
            }
        });
        y += ROW + 8;

        for (Action action : ACTIONS) {
            w.add(SettingsButtonWidget.builder(keyText(action, cfg.getKeybind(action.id)), btn -> {
                        capturing = action;
                        btn.setMessage(Component.literal(action.label + " Key: §ePress any key..."));
                    }).bounds(contentX, y, contentWidth, ROW).build());
            y += ROW + 4;
        }
        return w;
    }

    private Component keyText(Action action, int key) {
        if (capturing == action) {
            return Component.literal(action.label + " Key: §ePress any key...");
        }
        String name = key == KeyUtil.NONE ? "§7Not Set"
                : "§e" + InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName().getString();
        return Component.literal(action.label + " Key: " + name + " §8" + action.command);
    }

    private static Component rewindText(Ap3Config cfg) {
        return Component.literal(String.format(Locale.US, "Rewind Memory: %d ticks (%.1fs)", cfg.getRewindTicks(),
                cfg.getRewindTicks() / 20.0));
    }
}
