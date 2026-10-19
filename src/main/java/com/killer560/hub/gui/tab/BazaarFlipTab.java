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
                    } else {
                        // Every time it turns on, the advisor's answers are asked again, with Use last at the bottom
                        // (killer560, 2026-10-19).
                        com.killer560.hub.compat.McCompat.setScreen(Minecraft.getInstance(),
                                new com.killer560.hub.bazaaradvisor.BazaarAdvisorSetupScreen());
                        return;
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

        // The autopilot is always on (killer560, 2026-10-19: "remove auto pilot as it should always be in autopilot mode").
        // Its answers come from /bzflip: this button opens the same setup screen the bare command opens, so it runs the
        // command for him, and he can still type /bzflip himself.
        widgets.add(SettingsButtonWidget.builder(Component.literal("Configure /bzflip"), btn ->
                        com.killer560.hub.compat.McCompat.setScreen(Minecraft.getInstance(),
                                new com.killer560.hub.bazaaradvisor.BazaarAdvisorSetupScreen()))
                .bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        // Reserve: a slider from 0 to 100, the share of the purse the autopilot keeps back (default 10).
        widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18, reserveText(cfg),
                cfg.getAutopilotReservePercent() / 100.0) {
            @Override
            protected void updateMessage() {
                setMessage(reserveText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setAutopilotReservePercent((int) Math.round(this.value * 100));
                cfg.save();
            }
        });
        y += 22;

        // Max New Orders Per Session: a toggle; when on, the cap is the number beneath it.
        widgets.add(SettingsButtonWidget.builder(onOff("Max New Orders Per Session", cfg.isAutopilotMaxOrdersOn()), btn -> {
                    cfg.setAutopilotMaxOrdersOn(!cfg.isAutopilotMaxOrdersOn());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;
        if (cfg.isAutopilotMaxOrdersOn()) {
            widgets.add(SettingsButtonWidget.cycling(orderCapText(cfg), (btn, dir) -> {
                cfg.stepAutopilotMaxOrders(dir);
                cfg.save();
                btn.setMessage(orderCapText(cfg));
            }).bounds(contentX, y, contentWidth, 18).build());
            y += 22;
        }

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

        widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18, sellSpeedText(cfg),
                (cfg.getSellSpeed() - BazaarFlipConfig.MIN_SPEED)
                        / (double) (BazaarFlipConfig.MAX_SPEED - BazaarFlipConfig.MIN_SPEED)) {
            @Override
            protected void updateMessage() {
                setMessage(sellSpeedText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setSellSpeed((int) Math.round(BazaarFlipConfig.MIN_SPEED
                        + this.value * (BazaarFlipConfig.MAX_SPEED - BazaarFlipConfig.MIN_SPEED)));
                cfg.save();
            }
        });
        y += 22;

        widgets.add(inertLabel(contentX, y, contentWidth, "/bzscan - price the market now, no clicks"));
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

    private static Component reserveText(BazaarFlipConfig cfg) {
        return Component.literal("Reserve: §6" + cfg.getAutopilotReservePercent() + "% of purse");
    }

    private static Component orderCapText(BazaarFlipConfig cfg) {
        return Component.literal("Max New Orders Per Session: §6" + cfg.getAutopilotMaxOrders());
    }

    private static Component speedText(BazaarFlipConfig cfg) {
        return Component.literal(String.format(Locale.US, "Menu Speed: §6%d §7(%d-%d ms between actions)",
                cfg.getSpeed(), cfg.getActionDelayMinMs(), cfg.getActionDelayMaxMs()));
    }

    private static Component sellSpeedText(BazaarFlipConfig cfg) {
        return Component.literal(String.format(Locale.US, "Sell Speed: §6%d §7(%d-%d ms between sells)",
                cfg.getSellSpeed(), cfg.getSellDelayMinMs(), cfg.getSellDelayMaxMs()));
    }

    /** The advisor's answers in as few characters as possible, so the row is never cut off. */
    private static String shortAdvisor() {
        com.killer560.hub.bazaaradvisor.BazaarAdvisorConfig a = com.killer560.hub.bazaaradvisor.BazaarAdvisorConfig.getInstance();
        String mode = a.getMode() == com.killer560.hub.bazaaradvisor.BazaarAdvisorConfig.Mode.NPC ? "NPC" : "Bazaar";
        String goal = a.getGoal() == com.killer560.hub.bazaaradvisor.BazaarAdvisorConfig.Goal.TOTAL_PROFIT ? "total" : "%";
        String buy = a.getBuyMethod() == com.killer560.hub.bazaaradvisor.BazaarAdvisorConfig.BuyMethod.INSTA_BUY ? "insta buy" : "buy order";
        String sell = a.getMode() == com.killer560.hub.bazaaradvisor.BazaarAdvisorConfig.Mode.NPC ? "NPC sell"
                : a.getSellMethod() == com.killer560.hub.bazaaradvisor.BazaarAdvisorConfig.SellMethod.INSTA_SELL ? "insta sell" : "sell offer";
        return mode + " / " + goal + " / " + buy + " / " + sell;
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
