package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.zpew.ZeroPingEtherwarpConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Zero Ping Etherwarp - cheat-only (see {@link com.killer560.hub.zpew.ZeroPingEtherwarp}). Dungeons' cheat block. */
public class ZeroPingEtherwarpTab extends BaseTab {

    private static final int ROW = 18;

    public ZeroPingEtherwarpTab() {
        super("Zero Ping Etherwarp");
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
        var font = Minecraft.getInstance().font;
        ZeroPingEtherwarpConfig cfg = ZeroPingEtherwarpConfig.getInstance();
        int y = contentY;
        w.add(SettingsButtonWidget.builder(onOff("Zero Ping Etherwarp", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, ROW).build());
        y += ROW + 6;
        if (!cfg.isEnabled()) {
            return w;
        }
        w.add(SectionHeaders.row(contentX, y, contentWidth, "Zero Ping Etherwarp", true));
        y += 16;
        String[] help = {
                "§7Your view jumps to where an etherwarp lands the moment you use it,",
                "§7instead of a round trip later. Camera only: your character still moves",
                "§7when the server says so, and nothing extra is sent."
        };
        for (String line : help) {
            w.add(new StringWidget(contentX, y, contentWidth, 10, Component.literal(line), font));
            y += 11;
        }
        y += 6;
        w.add(SettingsButtonWidget.builder(onOff("Chain From Prediction", cfg.isChain()), btn -> {
                    cfg.setChain(!cfg.isChain());
                    cfg.save();
                    btn.setMessage(onOff("Chain From Prediction", cfg.isChain()));
                }).bounds(contentX, y, contentWidth, ROW).build());
        y += ROW + 4;
        w.add(SettingsButtonWidget.builder(onOff("Overlay From Prediction", cfg.isOverlayFromPrediction()), btn -> {
                    cfg.setOverlayFromPrediction(!cfg.isOverlayFromPrediction());
                    cfg.save();
                    btn.setMessage(onOff("Overlay From Prediction", cfg.isOverlayFromPrediction()));
                }).bounds(contentX, y, contentWidth, ROW).build());
        y += ROW + 4;
        double min = ZeroPingEtherwarpConfig.MIN_TIMEOUT_MS;
        double span = ZeroPingEtherwarpConfig.MAX_TIMEOUT_MS - min;
        w.add(new ThemedSliderButton(contentX, y, contentWidth, ROW, timeoutText(cfg),
                Math.max(0.0, Math.min(1.0, (cfg.getTimeoutMs() - min) / span))) {
            @Override
            protected void updateMessage() {
                setMessage(timeoutText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setTimeoutMs((int) (Math.round((min + this.value * span) / 50.0) * 50));
                saveOnRelease(() -> cfg.save());
            }
        });
        return w;
    }

    private static Component timeoutText(ZeroPingEtherwarpConfig cfg) {
        return Component.literal("Give Up After: " + cfg.getTimeoutMs() + " ms");
    }
}
