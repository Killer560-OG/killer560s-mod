package com.killer560.hub.gui.tab;

import com.killer560.hub.bazaarflip.BazaarFlipConfig;
import com.killer560.hub.bazaarflip.BazaarFlipFeature;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.util.KeyUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Bazaar-to-NPC Flipper settings. Cheat build only - see
 * {@link com.killer560.hub.bazaarflip.BazaarFlipFeature}.
 */
public class BazaarFlipTab extends BaseTab implements KeyCaptureTab {

    /** Non-zero while the start key is being captured - same pattern as {@link BreakerAuraTab}. */
    private int capturing = 0;

    public BazaarFlipTab() {
        super("Bazaar Flipper");
    }

    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public boolean isListeningForKey() {
        return capturing != 0;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        applyCapture(keyCode == com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE ? KeyUtil.NONE : keyCode);
    }

    @Override
    public boolean supportsMouseCapture() {
        return true;
    }

    @Override
    public void onMouseCaptured(int button) {
        applyCapture(KeyUtil.codeForMouseButton(button));
    }

    private void applyCapture(int code) {
        BazaarFlipConfig cfg = BazaarFlipConfig.getInstance();
        if (capturing == 1) {
            cfg.setStartKeyCode(code);
        }
        capturing = 0;
        cfg.save();
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return widgets;
        }
        BazaarFlipConfig cfg = BazaarFlipConfig.getInstance();
        int gap = 8;
        int col2W = (contentWidth - gap) / 2;
        int col2bX = contentX + col2W + gap;
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(onOff("Bazaar Flipper", cfg.isEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.isEnabledRaw());
                    cfg.save();
                    if (!cfg.isEnabledRaw()) {
                        BazaarFlipFeature.requestStop();
                    }
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        widgets.add(warningLabel(contentX, y, contentWidth,
                "§7Needs a Booster Cookie (remote /bz, selling from /trades)."));
        y += 22;

        if (!cfg.isEnabledRaw()) {
            return widgets;
        }

        // The start key is offered whatever the state - he binds it before he uses it. The running/not-running row and
        // the "Any key stops it" line beside and under it were removed on 2026-10-16 (killer560: "remove the not
        // running thing", "remove the any key stops it"); the start key's tooltip says how it stops.
        widgets.add(SettingsButtonWidget.builder(
                capturing == 1 ? Component.literal("Press any key...")
                        : Component.literal("Start Key: " + KeyUtil.bindDisplayName(cfg.getStartKeyCode())),
                btn -> {
                    capturing = 1;
                    btn.setMessage(Component.literal("Press any key..."));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.cycling(
                Component.literal("Rank By: §6" + cfg.getRankingMode().display), (btn, dir) -> {
                    cfg.cycleRankingMode(dir);
                    cfg.save();
                    btn.setMessage(Component.literal("Rank By: §6" + cfg.getRankingMode().display));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        // Min Profit Per Run was removed on 2026-10-16 (killer560: "I want it to always choose the best"): the best
        // flip by Rank By is taken whenever its profit after fees is above 0.

        // Target Purse is typed (killer560, 2026-10-16: "have Target Purse be a box they type in"): digits with or
        // without commas, or 750k / 5m / 2.5m / 1b (BazaarFlipConfig.parseCoins). It applies as he types, like the
        // Titanic price box in Auto Experiments; text that is not a number is ignored and the label says so. Empty or 0
        // is no target. The box opens showing the saved value with commas.
        SettingsButtonWidget purseLabel = SettingsButtonWidget.builder(targetPurseText(cfg.getTargetPurse()), btn -> {
        }).bounds(contentX, y, col2W, 18).build();
        purseLabel.active = false;
        widgets.add(purseLabel);
        EditBox purseBox = new EditBox(Minecraft.getInstance().font, col2bX, y, col2W, 18,
                Component.literal("Target Purse"));
        purseBox.setMaxLength(24);
        purseBox.setValue(cfg.getTargetPurse() > 0 ? String.format(Locale.US, "%,d", cfg.getTargetPurse()) : "");
        purseBox.setResponder(text -> {
            Long coins = BazaarFlipConfig.parseCoins(text);
            if (coins == null) {
                purseLabel.setMessage(Component.literal("Target Purse: §cnot a number"));
                return;
            }
            if (cfg.getTargetPurse() != coins) {
                cfg.setTargetPurse(coins);
                cfg.save();
            }
            purseLabel.setMessage(targetPurseText(cfg.getTargetPurse()));
        });
        widgets.add(purseBox);
        y += 22;

        widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18, speedText(cfg),
                (cfg.getSpeed() - BazaarFlipConfig.MIN_SPEED)
                        / (double) (BazaarFlipConfig.MAX_SPEED - BazaarFlipConfig.MIN_SPEED)) {
            @Override
            protected void updateMessage() {
                setMessage(speedText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setSpeed((int) Math.round(BazaarFlipConfig.MIN_SPEED
                        + this.value * (BazaarFlipConfig.MAX_SPEED - BazaarFlipConfig.MIN_SPEED)));
                cfg.save();
            }
        });
        y += 22;

        widgets.add(inertLabel(contentX, y, contentWidth, "/bazaarflip scan - price the market now, no clicks"));
        // The "Last scan" / "Best" lines under this were removed on 2026-10-16 (killer560: "remove those lines at the
        // bottom about last s... and best"); /bazaarflip scan still prints its result in chat, and /profit bazaarflip
        // has every finished cycle.

        return widgets;
    }

    private static Component targetPurseText(long coins) {
        return Component.literal(coins <= 0
                ? "Target Purse: §6Off"
                : String.format(Locale.US, "Target Purse: §6%,d", coins));
    }

    private static Component speedText(BazaarFlipConfig cfg) {
        return Component.literal(String.format(Locale.US, "Speed: §6%d §7(%d-%d ms between actions)",
                cfg.getSpeed(), cfg.getActionDelayMinMs(), cfg.getActionDelayMaxMs()));
    }

    private static AbstractWidget inertLabel(int x, int y, int width, String text) {
        SettingsButtonWidget w = SettingsButtonWidget.builder(Component.literal("§7" + text), btn -> {
        }).bounds(x, y, width, 16).build();
        w.active = false;
        return w;
    }

    private static AbstractWidget warningLabel(int x, int y, int width, String text) {
        SettingsButtonWidget w = SettingsButtonWidget.builder(Component.literal(text), btn -> {
        }).bounds(x, y, width, 16).build();
        w.active = false;
        return w;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}
