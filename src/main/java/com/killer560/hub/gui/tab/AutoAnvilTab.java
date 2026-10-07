package com.killer560.hub.gui.tab;

import com.killer560.hub.autoanvil.AutoAnvilConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Auto Anvil (see {@code com.killer560.hub.autoanvil.AutoAnvilFeature}). Cheat build only, beside Auto Sell. */
public class AutoAnvilTab extends BaseTab {

    private static final int BTN_W = 220;

    public AutoAnvilTab() {
        super("Auto Anvil");
    }

    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return w;
        }
        AutoAnvilConfig cfg = AutoAnvilConfig.getInstance();
        int half = (contentWidth - 8) / 2;
        int[] y = {contentY};

        toggle(w, contentX, y, "Auto Anvil", cfg::isEnabledRaw, cfg::setEnabled, requestRebuild);
        if (!cfg.isEnabledRaw()) {
            return w;
        }
        toggle(w, contentX, y, "Combine Results Again", cfg::isCascade, cfg::setCascade, null);
        toggle(w, contentX, y, "Close When Done", cfg::isCloseWhenDone, cfg::setCloseWhenDone, null);
        slider(w, contentX, y[0], half, () -> "Min Delay: " + cfg.getMinDelayMs() + "ms",
                norm(cfg.getMinDelayMs()), v -> cfg.setMinDelayMs(denorm(v)));
        slider(w, contentX + half + 8, y[0], half, () -> "Max Delay: " + cfg.getMaxDelayMs() + "ms",
                norm(cfg.getMaxDelayMs()), v -> cfg.setMaxDelayMs(denorm(v)));
        y[0] += 24;
        return w;
    }

    private static double norm(int value) {
        return (value - AutoAnvilConfig.MIN_DELAY_MS) / (double) (AutoAnvilConfig.MAX_DELAY_MS - AutoAnvilConfig.MIN_DELAY_MS);
    }

    private static int denorm(double v) {
        return (int) Math.round(AutoAnvilConfig.MIN_DELAY_MS + v * (AutoAnvilConfig.MAX_DELAY_MS - AutoAnvilConfig.MIN_DELAY_MS));
    }

    private static void toggle(List<AbstractWidget> w, int x, int[] y, String name, Supplier<Boolean> getter,
                               Consumer<Boolean> setter, Runnable rebuild) {
        w.add(SettingsButtonWidget.builder(onOff(name, getter.get()), btn -> {
            setter.accept(!getter.get());
            AutoAnvilConfig.getInstance().save();
            if (rebuild != null) {
                rebuild.run();
            } else {
                btn.setMessage(onOff(name, getter.get()));
            }
        }).bounds(x, y[0], BTN_W, 20).build());
        y[0] += 24;
    }

    private static Component onOff(String name, boolean on) {
        return Component.literal(name + ": " + (on ? "§aON" : "§cOFF"));
    }

    private static void slider(List<AbstractWidget> w, int x, int y, int width, Supplier<String> text, double normalized,
                               java.util.function.DoubleConsumer apply) {
        w.add(new ThemedSliderButton(x, y, width, 20, Component.literal(text.get()), Math.max(0.0, Math.min(1.0, normalized))) {
            @Override
            protected void updateMessage() {
                setMessage(Component.literal(text.get()));
            }

            @Override
            protected void applyValue() {
                apply.accept(this.value);
                AutoAnvilConfig.getInstance().save();
            }
        });
    }
}
