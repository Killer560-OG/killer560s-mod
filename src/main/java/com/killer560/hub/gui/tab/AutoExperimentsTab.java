package com.killer560.hub.gui.tab;

import com.killer560.hub.experiments.ExperimentsConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** Auto E-Table - the autonomous Experimentation Table macro: it opens Chronomatron/Ultrasequencer, picks
 *  the highest tier, plays the puzzles, claims the rewards and buys renews/XP bottles. Cheat build only, so
 *  the Helpers folder draws this section's header red ({@link #isCheatOnly()}).
 *  <p>
 *  Split out of {@link ExperimentsTab} on 2026-09-30, per killer560: "Move the auto etable stuff into its
 *  own red header in the same helpers tab but different area. The solver should be its own setting." Every
 *  setting here is the same field under the same JSON key it was before the move; the only changed control
 *  is the old "Mode: Solver Only / Autonomous" button, which is now a plain on/off for the automation
 *  alone - the solver is its own toggle over in {@link ExperimentsTab} and no longer switches off when this
 *  one switches on. */
public class AutoExperimentsTab extends BaseTab implements KeyCaptureTab {

    private static final int GAP = 6;

    private boolean listening = false;

    public AutoExperimentsTab() {
        super("Auto Experimentation Table");
    }

    /** Only added to {@link HelpersTab} behind {@code BuildVariant.CHEAT_FEATURES_ENABLED} - red header. */
    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        int half = (contentWidth - GAP) / 2;
        // The legit build's ExperimentsConfig.isAutonomousMode() can never return true (see its own doc),
        // so nothing here would work - same convention AutoChocolateFactoryTab uses.
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return widgets;
        }
        ExperimentsConfig cfg = ExperimentsConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(autoText(), btn -> {
                    ExperimentsConfig c = ExperimentsConfig.getInstance();
                    c.setAutonomousMode(!c.isAutonomousMode());
                    c.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        // Same "the master toggle hides what it governs instead of showing it inert" rule the rest of the
        // mod's tabs follow - everything below only ever means anything for the auto-clicking and
        // menu-navigation loop.
        if (!cfg.isAutonomousMode()) {
            return widgets;
        }

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
        // killer560, 2026-10-05: two separate skips - Grand bottles only (Titanics still paired), Guardian pets of
        // every rarity.
        widgets.add(SettingsButtonWidget.builder(skipGrandText(), btn -> {
                    ExperimentsConfig c = ExperimentsConfig.getInstance();
                    c.setSkipGrandExpBottles(!c.isSkipGrandExpBottles());
                    c.save();
                    btn.setMessage(skipGrandText());
                }).bounds(contentX, y, half, 20).build());
        widgets.add(SettingsButtonWidget.builder(skipGuardianText(), btn -> {
                    ExperimentsConfig c = ExperimentsConfig.getInstance();
                    c.setSkipGuardianPets(!c.isSkipGuardianPets());
                    c.save();
                    btn.setMessage(skipGuardianText());
                }).bounds(contentX + half + GAP, y, half, 20).build());
        y += 24;

        // Adaptive Timeout extends Superpairs' confirm wait by however long the server stalls (see
        // ExperimentSolver#superpairsConfirmTimeoutMs). Its Timeout Margin slider went on 2026-10-04 -
        // killer560: "that shouldn't have a margin it should sense when the server is lagging".
        widgets.add(SettingsButtonWidget.builder(adaptiveTimeoutText(), btn -> {
                    ExperimentsConfig c = ExperimentsConfig.getInstance();
                    c.setSuperpairsAdaptiveTimeout(!c.isSuperpairsAdaptiveTimeout());
                    c.save();
                    btn.setMessage(adaptiveTimeoutText());
                }).bounds(contentX, y, contentWidth, 20).build());
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
        // emergency-cancel mechanism is the keybind below, which works while actually playing without
        // needing this settings menu open at all. Stop At paired with it here.
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
                }).secondaryPress(btn -> {
                    ExperimentsConfig c = ExperimentsConfig.getInstance();
                    c.setStopStrategy(c.getStopStrategy().previous());
                    c.save();
                    btn.setMessage(stopStrategyText());
                }).bounds(contentX + half + GAP, y, half, 20).build());
        y += 28;

        // 2026-10-04, killer560: "make the timeouts bars and remove the boxes I can type in. Have the bars be
        // in 50ms increments." The three typed boxes and their Set buttons became these sliders; same
        // fields, same JSON keys.
        widgets.add(msSlider(contentX, y, contentWidth, "Click Delay", ExperimentsConfig.MAX_DELAY_MS,
                () -> ExperimentsConfig.getInstance().getDelayMs(),
                ms -> ExperimentsConfig.getInstance().setDelayMs(ms)));
        y += 22;
        widgets.add(msSlider(contentX, y, contentWidth, "First Click Delay", ExperimentsConfig.MAX_FIRST_CLICK_DELAY_MS,
                () -> ExperimentsConfig.getInstance().getFirstClickDelayMs(),
                ms -> ExperimentsConfig.getInstance().setFirstClickDelayMs(ms)));
        y += 22;
        widgets.add(msSlider(contentX, y, contentWidth, "Random Delay (max)", ExperimentsConfig.MAX_DELAY_MS,
                () -> ExperimentsConfig.getInstance().getRandomDelayMaxMs(),
                ms -> ExperimentsConfig.getInstance().setRandomDelayMaxMs(ms)));
        y += 28;

        // Per killer560's request: dragging must SNAP to discrete steps (1-count increments here, 50k
        // coins below) rather than landing anywhere - achieved by writing the snapped fraction back into
        // `this.value` at the end of applyValue(), which AbstractSliderButton also uses to position the
        // handle, so the handle visibly jumps to each step as you drag through it instead of sliding
        // continuously.
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
        int titanicFieldWidth = contentWidth - titanicSliderWidth - GAP;
        // The slider snaps to 50k; the box beside it takes an exact amount with no snapping, per
        // killer560's "still allow for me to manually type in the coin portion for something more
        // precise... but it wont ever snap there." Since 2026-10-04 the box applies as you type (killer560:
        // "remove the set button it isn't needed") - anything that is not a number in range is ignored.
        TitanicSlider titanicSlider = new TitanicSlider(contentX, y, titanicSliderWidth);
        EditBox titanicField = new EditBox(Minecraft.getInstance().font, titanicFieldX, y, titanicFieldWidth, 20,
                Component.literal("Max Titanic Price"));
        titanicField.setMaxLength(10);
        titanicField.setValue(String.format("%.0f", cfg.getTitanicMaxPriceCoins()));
        titanicSlider.field = titanicField;
        titanicField.setResponder(text -> {
            Double parsed = parseDouble(text);
            if (parsed == null) {
                return;
            }
            ExperimentsConfig c = ExperimentsConfig.getInstance();
            if (c.getTitanicMaxPriceCoins() != parsed) {
                c.setTitanicMaxPriceCoins(parsed);
                c.save();
            }
            titanicSlider.sync();
        });
        widgets.add(titanicSlider);
        widgets.add(titanicField);

        return widgets;
    }

    /** A 0..max ms slider that snaps to {@link ExperimentsConfig#DELAY_STEP_MS} and saves as it moves. The
     *  snapped fraction is written back into {@code value}, which also positions the handle, so the handle
     *  jumps between steps instead of sliding continuously. */
    private static ThemedSliderButton msSlider(int x, int y, int width, String label, int maxMs,
            IntSupplier getter, IntConsumer setter) {
        return new ThemedSliderButton(x, y, width, 20, msText(label, getter.getAsInt()),
                getter.getAsInt() / (double) maxMs) {
            @Override
            protected void updateMessage() {
                setMessage(msText(label, getter.getAsInt()));
            }

            @Override
            protected void applyValue() {
                int step = ExperimentsConfig.DELAY_STEP_MS;
                int snapped = (int) Math.round(this.value * maxMs / step) * step;
                setter.accept(Math.max(0, Math.min(maxMs, snapped)));
                ExperimentsConfig.getInstance().save();
                this.value = getter.getAsInt() / (double) maxMs;
            }
        };
    }

    private static Component msText(String label, int ms) {
        return Component.literal(label + ": §b" + ms + "ms");
    }

    /** Max Titanic Price slider, snapping to 50k coins. Dragging it rewrites the box beside it; typing in
     *  the box moves it back via {@link #sync()}. */
    private static final class TitanicSlider extends ThemedSliderButton {
        private static final double SNAP_STEP = 50_000.0;
        private static final double RANGE = ExperimentsConfig.MAX_TITANIC_MAX_PRICE - ExperimentsConfig.MIN_TITANIC_MAX_PRICE;

        private EditBox field;

        TitanicSlider(int x, int y, int width) {
            super(x, y, width, 20, titanicPriceText(), fraction());
        }

        private static double fraction() {
            return (ExperimentsConfig.getInstance().getTitanicMaxPriceCoins() - ExperimentsConfig.MIN_TITANIC_MAX_PRICE) / RANGE;
        }

        /** Re-reads the saved price, for when the box changed it. */
        void sync() {
            this.value = fraction();
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(titanicPriceText());
        }

        @Override
        protected void applyValue() {
            ExperimentsConfig c = ExperimentsConfig.getInstance();
            double raw = ExperimentsConfig.MIN_TITANIC_MAX_PRICE + this.value * RANGE;
            double snapped = Math.round(raw / SNAP_STEP) * SNAP_STEP;
            snapped = Math.max(ExperimentsConfig.MIN_TITANIC_MAX_PRICE,
                    Math.min(ExperimentsConfig.MAX_TITANIC_MAX_PRICE, snapped));
            c.setTitanicMaxPriceCoins(snapped);
            c.save();
            this.value = fraction();
            if (field != null) {
                field.setValue(String.format("%.0f", snapped));
            }
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

    /** Replaces the old two-way "Mode: Solver Only / Autonomous" button - same {@code autonomousMode}
     *  field and JSON key, but it no longer turns the solver off by being on. */
    private static Component autoText() {
        return Component.literal("Auto E-Table: "
                + (ExperimentsConfig.getInstance().isAutonomousMode() ? "§aON" : "§cOFF"));
    }

    private static Component superpairsText() {
        return Component.literal("Auto-Solve Superpairs: "
                + (ExperimentsConfig.getInstance().isSuperpairsEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component skipGrandText() {
        return Component.literal("Skip Grand XP Bottles: "
                + (ExperimentsConfig.getInstance().isSkipGrandExpBottles() ? "§aON" : "§cOFF"));
    }

    private static Component skipGuardianText() {
        return Component.literal("Skip Guardian Pets: "
                + (ExperimentsConfig.getInstance().isSkipGuardianPets() ? "§aON" : "§cOFF"));
    }

    private static Component superpairsFilterText() {
        return Component.literal("Superpairs Pairs: §b"
                + (ExperimentsConfig.getInstance().isSuperpairsValuableOnly() ? "Skip Plain XP" : "Every Pair"));
    }

    private static Component adaptiveTimeoutText() {
        return Component.literal("Adaptive Timeout: "
                + (ExperimentsConfig.getInstance().isSuperpairsAdaptiveTimeout() ? "§aON" : "§cOFF"));
    }

    private static Component stopStrategyText() {
        return Component.literal("Stop At: §b" + ExperimentsConfig.getInstance().getStopStrategy().displayName);
    }

    private static Component blockInputText() {
        ExperimentsConfig cfg = ExperimentsConfig.getInstance();
        return Component.literal("Block Input In Menu: " + (cfg.isBlockInputEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component guardianSwapText() {
        ExperimentsConfig cfg = ExperimentsConfig.getInstance();
        return Component.literal("Auto-Swap to Guardian Pet: " + (cfg.isAutoSwapGuardianPet() ? "§aON" : "§cOFF"));
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
