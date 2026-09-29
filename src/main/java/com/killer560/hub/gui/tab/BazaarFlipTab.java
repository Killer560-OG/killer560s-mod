package com.killer560.hub.gui.tab;

import com.killer560.hub.bazaarflip.BazaarFlipCandidate;
import com.killer560.hub.bazaarflip.BazaarFlipConfig;
import com.killer560.hub.bazaarflip.BazaarFlipFeature;
import com.killer560.hub.bazaarflip.BazaarFlipScanner;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.util.KeyUtil;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Bazaar-to-NPC Flipper settings. Cheat build only - see
 * {@link com.killer560.hub.bazaarflip.BazaarFlipFeature}, and in particular its
 * {@code MENU_CLICKS_VERIFIED} constant, which is why this tab says out loud that the buy and sell clicks
 * are not verified rather than letting him find out by pointing it at half a billion coins.
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

        // Stated on the tab, not just in a log file: the two clicking steps have never been verified against
        // the real menus, so the bot walks the flow and stops rather than spending coins on a guess.
        widgets.add(warningLabel(contentX, y, contentWidth,
                "§cBuy/sell clicks NOT verified - it opens each menu, prints it, then stops."));
        y += 18;
        widgets.add(warningLabel(contentX, y, contentWidth,
                "§7Run it once and send the printed menu contents to have the clicks written."));
        y += 22;

        if (!cfg.isEnabledRaw()) {
            return widgets;
        }

        // The start key is offered whatever the state - he binds it before he uses it.
        widgets.add(SettingsButtonWidget.builder(
                capturing == 1 ? Component.literal("Press any key...")
                        : Component.literal("Start Key: " + KeyUtil.bindDisplayName(cfg.getStartKeyCode())),
                btn -> {
                    capturing = 1;
                    btn.setMessage(Component.literal("Press any key..."));
                }).bounds(contentX, y, col2W, 18).build());
        boolean running = BazaarFlipFeature.isRunning();
        widgets.add(SettingsButtonWidget.builder(
                Component.literal(running ? "§cRunning - click to stop" : "§7Not running - press the start key"),
                btn -> {
                    if (BazaarFlipFeature.isRunning()) {
                        BazaarFlipFeature.requestStop();
                    }
                    requestRebuild.run();
                }).bounds(col2bX, y, col2W, 18).build());
        y += 22;

        widgets.add(inertLabel(contentX, y, contentWidth, "Any key stops it while it runs."));
        y += 20;

        widgets.add(SettingsButtonWidget.builder(
                Component.literal("Rank By: §6" + cfg.getRankingMode().display), btn -> {
                    cfg.cycleRankingMode();
                    cfg.save();
                    btn.setMessage(Component.literal("Rank By: §6" + cfg.getRankingMode().display));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        // 0 .. 5,000,000 coins. The floor is what makes percentage ranking usable: NETHERRACK-class items sit
        // at the top of the percentage list and net about two thousand coins for a whole inventory cycle.
        final double profitMax = 5_000_000.0;
        widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18, minProfitText(cfg),
                Math.min(1.0, cfg.getMinProfitPerRun() / profitMax)) {
            @Override
            protected void updateMessage() {
                setMessage(minProfitText(cfg));
            }

            @Override
            protected void applyValue() {
                // Rounded to 10,000 so dragging lands on readable numbers.
                cfg.setMinProfitPerRun(Math.round(this.value * profitMax / 10_000.0) * 10_000L);
                cfg.save();
            }
        });
        y += 20;

        // 0 .. 1,000,000,000 coins, 0 meaning "no target".
        final double purseMax = 1_000_000_000.0;
        widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18, targetPurseText(cfg),
                Math.min(1.0, cfg.getTargetPurse() / purseMax)) {
            @Override
            protected void updateMessage() {
                setMessage(targetPurseText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setTargetPurse(Math.round(this.value * purseMax / 1_000_000.0) * 1_000_000L);
                cfg.save();
            }
        });
        y += 20;

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
        y += 20;

        BazaarFlipCandidate target = BazaarFlipFeature.getTarget();
        BazaarFlipScanner.ScanResult scan = BazaarFlipScanner.getLast();
        if (target != null) {
            widgets.add(inertLabel(contentX, y, contentWidth, "Target: " + target.summary()));
            y += 18;
        } else if (scan != null && scan.ok()) {
            widgets.add(inertLabel(contentX, y, contentWidth,
                    "Last scan: " + scan.profitableCount() + " profitable of " + scan.productsWithNpcPrice()
                            + " with an NPC price, " + scan.ranked().size() + " over the floor"));
            y += 18;
            BazaarFlipCandidate best = scan.best();
            if (best != null) {
                widgets.add(inertLabel(contentX, y, contentWidth, "Best: " + best.summary()));
            }
        } else if (scan != null) {
            widgets.add(inertLabel(contentX, y, contentWidth, "Last scan failed: " + scan.error()));
        }

        return widgets;
    }

    private static Component minProfitText(BazaarFlipConfig cfg) {
        return Component.literal(String.format(Locale.US, "Min Profit Per Run: §6%,d coins", cfg.getMinProfitPerRun()));
    }

    private static Component targetPurseText(BazaarFlipConfig cfg) {
        return Component.literal(cfg.getTargetPurse() <= 0
                ? "Target Purse: §6Off"
                : String.format(Locale.US, "Target Purse: §6%,d coins", cfg.getTargetPurse()));
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
