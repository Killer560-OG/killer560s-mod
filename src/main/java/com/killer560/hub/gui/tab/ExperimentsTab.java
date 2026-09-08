package com.killer560.hub.gui.tab;

import com.killer560.hub.experiments.ExperimentStopStrategy;
import com.killer560.hub.experiments.ExperimentsConfig;
import com.killer560.hub.notify.ModOverlayMessage;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import com.killer560.hub.gui.ThemedSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Experimentation Table solver settings - see {@link com.killer560.hub.experiments.ExperimentsFeature}. */
public class ExperimentsTab extends BaseTab implements KeyCaptureTab {

    private boolean listening = false;

    public ExperimentsTab() {
        super("Experiments");
    }

    private static final int GAP = 6;

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        int half = (contentWidth - GAP) / 2;
        ExperimentsConfig cfg = ExperimentsConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(enabledText(), btn -> {
                    ExperimentsConfig c = ExperimentsConfig.getInstance();
                    c.setEnabled(!c.isEnabled());
                    c.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        // Per killer560's request: this master toggle hides every other setting below it (not just
        // disables their effect, which cfg.isEnabled() already did via ExperimentsFeature.tick()'s
        // own early-return) - a decluttered tab when the whole feature is off.
        if (!cfg.isEnabled()) {
            return widgets;
        }

        // Per killer560's request (2026-09-07): Mode moved to right below the main toggle, ahead of the
        // Superpairs row. Stop At, Block Input, and Auto-Swap Guardian Pet only ever mean anything for
        // the autonomous auto-clicking loop - Solver Only never auto-clicks at all, so these are hidden
        // entirely in that mode instead of shown-but-disabled.
        boolean autonomous = cfg.isAutonomousMode();
        // Per killer560's "legit variant with only legit stuff" split (2026-09-08): the legit build's
        // ExperimentsConfig.isAutonomousMode() can never return true (see its own doc), so this toggle
        // would otherwise look broken - clickable, but silently reverting to Solver Only every rebuild.
        // Just omit the row entirely when there's no second mode to switch to.
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            widgets.add(SettingsButtonWidget.builder(modeText(), btn -> {
                        ExperimentsConfig c = ExperimentsConfig.getInstance();
                        c.setAutonomousMode(!c.isAutonomousMode());
                        c.save();
                        requestRebuild.run();
                    }).bounds(contentX, y, contentWidth, 20).build());
            y += 24;
        }

        // Per killer560's request (2026-09-07): everything below this point - Superpairs auto-solve/filter,
        // Emergency Cancel Key, Stop At, Block Input, Auto-Swap Guardian, the three delay fields, and
        // Auto-Renew Charges/Max Titanic Price - only ever matters for the autonomous auto-clicking and
        // menu-navigation loop. Solver Only never clicks or navigates menus itself, so all of it is
        // hidden in that mode instead of shown-but-inert.
        if (autonomous) {
            widgets.add(SettingsButtonWidget.builder(superpairsText(), btn -> {
                        ExperimentsConfig c = ExperimentsConfig.getInstance();
                        c.setSuperpairsEnabled(!c.isSuperpairsEnabled());
                        c.save();
                        btn.setMessage(superpairsText());
                    }).bounds(contentX, y, half, 20).build());
            widgets.add(SettingsButtonWidget.builder(superpairsFilterText(), btn -> {
                        ExperimentsConfig c = ExperimentsConfig.getInstance();
                        c.setSuperpairsValuableOnly(!c.isSuperpairsValuableOnly());
                        c.save();
                        btn.setMessage(superpairsFilterText());
                    }).bounds(contentX + half + GAP, y, half, 20).build());
            y += 24;

            // Per killer560's request: Block Input and Auto-Swap Guardian side by side instead of stacked.
            widgets.add(SettingsButtonWidget.builder(blockInputText(), btn -> {
                        ExperimentsConfig c = ExperimentsConfig.getInstance();
                        c.setBlockInputEnabled(!c.isBlockInputEnabled());
                        c.save();
                        btn.setMessage(blockInputText());
                    }).bounds(contentX, y, half, 20).build());
            widgets.add(SettingsButtonWidget.builder(guardianSwapText(), btn -> {
                        ExperimentsConfig c = ExperimentsConfig.getInstance();
                        c.setAutoSwapGuardianPet(!c.isAutoSwapGuardianPet());
                        c.save();
                        btn.setMessage(guardianSwapText());
                    }).bounds(contentX + half + GAP, y, half, 20).build());
            y += 24;

            // Per killer560's request: the "Cancel Now" manual-trigger button removed entirely - the real
            // emergency-cancel mechanism is the keybind below, which works while actually playing
            // without needing this settings menu open at all. Stop At paired with it here.
            Component cancelKeyLabel = listening ? Component.literal("Press any key...") : emergencyCancelKeyText();
            widgets.add(SettingsButtonWidget.builder(cancelKeyLabel, btn -> {
                        listening = true;
                        btn.setMessage(Component.literal("Press any key..."));
                    }).bounds(contentX, y, half, 20).build());
            widgets.add(SettingsButtonWidget.builder(stopStrategyText(), btn -> {
                        ExperimentsConfig c = ExperimentsConfig.getInstance();
                        c.setStopStrategy(c.getStopStrategy().next());
                        c.save();
                        btn.setMessage(stopStrategyText());
                    }).bounds(contentX + half + GAP, y, half, 20).build());
            y += 28;

            EditBox delayField = new EditBox(Minecraft.getInstance().font, contentX, y, 80, 20, Component.literal("Delay (ms)"));
            delayField.setValue(String.valueOf(cfg.getDelayMs()));
            widgets.add(delayField);
            widgets.add(SettingsButtonWidget.builder(Component.literal("Click to set delay to " + cfg.getDelayMs() + "ms"), btn -> {
                        Integer ms = parseInt(delayField.getValue());
                        if (ms != null) {
                            ExperimentsConfig.getInstance().setDelayMs(ms);
                            ExperimentsConfig.getInstance().save();
                        }
                        requestRebuild.run();
                    }).bounds(contentX + 86, y, contentWidth - 86, 20).build());
            y += 22;

            EditBox firstDelayField = new EditBox(Minecraft.getInstance().font, contentX, y, 80, 20, Component.literal("First click delay"));
            firstDelayField.setValue(String.valueOf(cfg.getFirstClickDelayMs()));
            widgets.add(firstDelayField);
            widgets.add(SettingsButtonWidget.builder(Component.literal("Click to set delay to " + cfg.getFirstClickDelayMs() + "ms"), btn -> {
                        Integer ms = parseInt(firstDelayField.getValue());
                        if (ms != null) {
                            ExperimentsConfig.getInstance().setFirstClickDelayMs(ms);
                            ExperimentsConfig.getInstance().save();
                        }
                        requestRebuild.run();
                    }).bounds(contentX + 86, y, contentWidth - 86, 20).build());
            y += 22;

            EditBox randomDelayField = new EditBox(Minecraft.getInstance().font, contentX, y, 80, 20, Component.literal("Random Delay (ms)"));
            randomDelayField.setValue(String.valueOf(cfg.getRandomDelayMaxMs()));
            widgets.add(randomDelayField);
            widgets.add(SettingsButtonWidget.builder(Component.literal("Click to set delay to " + cfg.getRandomDelayMaxMs() + "ms"), btn -> {
                        Integer ms = parseInt(randomDelayField.getValue());
                        if (ms != null) {
                            ExperimentsConfig.getInstance().setRandomDelayMaxMs(ms);
                            ExperimentsConfig.getInstance().save();
                        }
                        requestRebuild.run();
                    }).bounds(contentX + 86, y, contentWidth - 86, 20).build());
            y += 28;

            // Per killer560's request: dragging must SNAP to discrete steps (1-count increments here, 50k
            // coins below) rather than landing anywhere - achieved by writing the snapped fraction back
            // into `this.value` at the end of applyValue(), which AbstractSliderButton also uses to
            // position the handle, so the handle visibly jumps to each step as you drag through it
            // instead of sliding continuously.
            double renewNormalized = cfg.getAutoRenewCount() / (double) ExperimentsConfig.MAX_AUTO_RENEW_COUNT;
            widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 20, renewCountText(), renewNormalized) {
                @Override
                protected void updateMessage() {
                    setMessage(renewCountText());
                }

                @Override
                protected void applyValue() {
                    ExperimentsConfig c = ExperimentsConfig.getInstance();
                    int snapped = (int) Math.round(this.value * ExperimentsConfig.MAX_AUTO_RENEW_COUNT);
                    c.setAutoRenewCount(snapped);
                    c.save();
                    this.value = snapped / (double) ExperimentsConfig.MAX_AUTO_RENEW_COUNT;
                }
            });
            y += 22;

            int titanicSliderWidth = half;
            int titanicFieldX = contentX + titanicSliderWidth + GAP;
            int titanicFieldWidth = 70;
            // Declared before the slider so its applyValue() can keep this field's text in sync while
            // dragging - manually typing into this field and pressing "Set" still applies the EXACT
            // typed value with no snapping at all, per killer560's "still allow for me to manually type in
            // the coin portion for something more precise... but it wont ever snap there."
            EditBox titanicField = new EditBox(Minecraft.getInstance().font, titanicFieldX, y, titanicFieldWidth, 20,
                    Component.literal("Max Titanic Price"));
            titanicField.setMaxLength(10);
            titanicField.setValue(String.format("%.0f", cfg.getTitanicMaxPriceCoins()));

            double titanicNormalized = (cfg.getTitanicMaxPriceCoins() - ExperimentsConfig.MIN_TITANIC_MAX_PRICE)
                    / (ExperimentsConfig.MAX_TITANIC_MAX_PRICE - ExperimentsConfig.MIN_TITANIC_MAX_PRICE);
            widgets.add(new ThemedSliderButton(contentX, y, titanicSliderWidth, 20, titanicPriceText(), titanicNormalized) {
                private static final double SNAP_STEP = 50_000.0;

                @Override
                protected void updateMessage() {
                    setMessage(titanicPriceText());
                }

                @Override
                protected void applyValue() {
                    ExperimentsConfig c = ExperimentsConfig.getInstance();
                    double raw = ExperimentsConfig.MIN_TITANIC_MAX_PRICE
                            + this.value * (ExperimentsConfig.MAX_TITANIC_MAX_PRICE - ExperimentsConfig.MIN_TITANIC_MAX_PRICE);
                    double snapped = Math.round(raw / SNAP_STEP) * SNAP_STEP;
                    snapped = Math.max(ExperimentsConfig.MIN_TITANIC_MAX_PRICE,
                            Math.min(ExperimentsConfig.MAX_TITANIC_MAX_PRICE, snapped));
                    c.setTitanicMaxPriceCoins(snapped);
                    c.save();
                    this.value = (snapped - ExperimentsConfig.MIN_TITANIC_MAX_PRICE)
                            / (ExperimentsConfig.MAX_TITANIC_MAX_PRICE - ExperimentsConfig.MIN_TITANIC_MAX_PRICE);
                    titanicField.setValue(String.format("%.0f", snapped));
                }
            });
            widgets.add(titanicField);
            widgets.add(SettingsButtonWidget.builder(Component.literal("Set"), btn -> {
                        Double parsed = parseDouble(titanicField.getValue());
                        if (parsed == null) {
                            ModOverlayMessage.show("§c[Killer560's Mod] Invalid price - enter a number 0-3000000", 3000);
                            return;
                        }
                        ExperimentsConfig c = ExperimentsConfig.getInstance();
                        c.setTitanicMaxPriceCoins(parsed);
                        c.save();
                        requestRebuild.run();
                    }).bounds(titanicFieldX + titanicFieldWidth + GAP, y, contentWidth - titanicSliderWidth - titanicFieldWidth - 2 * GAP, 20).build());
        } else {
            // Per killer560's request (2026-09-08): expose the Chronomatron/Ultrasequencer misclick
            // protection as a real setting instead of always-on with no way to disable it. Only shown
            // in Solver Only mode - it has no effect at all in Autonomous (see
            // ExperimentsFeature#shouldBlockManualMisclick), which never routes real clicks through it.
            widgets.add(SettingsButtonWidget.builder(clickProtectionText(), btn -> {
                        ExperimentsConfig c = ExperimentsConfig.getInstance();
                        c.setClickProtectionEnabled(!c.isClickProtectionEnabled());
                        c.save();
                        btn.setMessage(clickProtectionText());
                    }).bounds(contentX, y, contentWidth, 20).build());
            y += 24;
        }

        return widgets;
    }

    private static Integer parseInt(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static Double parseDouble(String text) {
        try {
            double value = Double.parseDouble(text.trim());
            if (value < ExperimentsConfig.MIN_TITANIC_MAX_PRICE || value > ExperimentsConfig.MAX_TITANIC_MAX_PRICE) {
                return null;
            }
            return value;
        } catch (Exception e) {
            return null;
        }
    }

    private static Component renewCountText() {
        return Component.literal("Auto-Renew Charges: §b" + ExperimentsConfig.getInstance().getAutoRenewCount() + "/day");
    }

    private static Component titanicPriceText() {
        double price = ExperimentsConfig.getInstance().getTitanicMaxPriceCoins();
        return Component.literal(price <= 0
                ? "Max Titanic Price: §7Disabled"
                : String.format("Max Titanic Price: §b%,.0f coins", price));
    }

    private static Component enabledText() {
        return Component.literal("Experiment Solver Enabled: "
                + (ExperimentsConfig.getInstance().isEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component superpairsText() {
        return Component.literal("Auto-Solve Superpairs: "
                + (ExperimentsConfig.getInstance().isSuperpairsEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component superpairsFilterText() {
        return Component.literal("Superpairs Pairs: §b"
                + (ExperimentsConfig.getInstance().isSuperpairsValuableOnly() ? "Skip Plain XP" : "Every Pair"));
    }

    private static Component modeText() {
        return Component.literal("Mode: §b"
                + (ExperimentsConfig.getInstance().isAutonomousMode() ? "Autonomous" : "Solver Only"));
    }

    private static Component stopStrategyText() {
        return Component.literal("Stop At: §b" + ExperimentsConfig.getInstance().getStopStrategy().displayName);
    }

    /** Only ever built while {@code autonomous} is true (see {@link #buildWidgets}) - Solver Only
     *  hides this row entirely instead of showing it disabled. */
    private static Component blockInputText() {
        ExperimentsConfig cfg = ExperimentsConfig.getInstance();
        return Component.literal("Block Input In Menu: " + (cfg.isBlockInputEnabled() ? "§aON" : "§cOFF"));
    }

    /** Only ever built while {@code autonomous} is true (see {@link #buildWidgets}) - Solver Only
     *  hides this row entirely instead of showing it disabled. */
    private static Component guardianSwapText() {
        ExperimentsConfig cfg = ExperimentsConfig.getInstance();
        return Component.literal("Auto-Swap to Guardian Pet: " + (cfg.isAutoSwapGuardianPet() ? "§aON" : "§cOFF"));
    }

    /** Only ever built while {@code autonomous} is false (see {@link #buildWidgets}) - it has no effect
     *  in Autonomous mode, which never routes real clicks through the misclick-protection check. */
    private static Component clickProtectionText() {
        ExperimentsConfig cfg = ExperimentsConfig.getInstance();
        return Component.literal("Click Protection: " + (cfg.isClickProtectionEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component emergencyCancelKeyText() {
        int code = ExperimentsConfig.getInstance().getEmergencyCancelKeyCode();
        String name = code < 0 ? "Not Set"
                : InputConstants.Type.KEYSYM.getOrCreate(code).getDisplayName().getString();
        return Component.literal("Emergency Cancel Key: §b" + name);
    }

    @Override
    public boolean isListeningForKey() {
        return listening;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        listening = false;
        ExperimentsConfig cfg = ExperimentsConfig.getInstance();
        // Per killer560's explicit request (2026-09-06): pressing Escape while capturing this keybind
        // unbinds it entirely (same "Escape cancels/clears" convention vanilla Minecraft's own keybind
        // settings use), rather than literally binding the emergency-cancel key to Escape itself -
        // verified via javap that InputConstants.KEY_ESCAPE is the real constant matching the raw GLFW
        // key code keyPressed hands to onKeyCaptured.
        cfg.setEmergencyCancelKeyCode(keyCode == InputConstants.KEY_ESCAPE ? -1 : keyCode);
        cfg.save();
    }
}
