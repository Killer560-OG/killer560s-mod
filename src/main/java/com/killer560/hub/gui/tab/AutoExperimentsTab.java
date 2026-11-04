package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.CommitEditBox;
import com.killer560.hub.experiments.ExperimentsConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
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
public class AutoExperimentsTab extends BaseTab {

    private static final int GAP = 6;


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

        // An ON/OFF switch, not a cycling setting: a click flips it either way.
        widgets.add(flag("Auto E-Table", ExperimentsConfig::isAutonomousMode, ExperimentsConfig::setAutonomousMode, requestRebuild, contentX, y, contentWidth));
        y += 24;

        // Same "the master toggle hides what it governs instead of showing it inert" rule the rest of the
        // mod's tabs follow - everything below only ever means anything for the auto-clicking and
        // menu-navigation loop.
        if (!cfg.isAutonomousMode() && !isSearchScan()) {
            return widgets;
        }

        widgets.add(flag("Auto-Solve Superpairs", ExperimentsConfig::isSuperpairsEnabled, ExperimentsConfig::setSuperpairsEnabled, null, contentX, y, half));
        widgets.add(SettingsButtonWidget.cycling(superpairsFilterText(), (btn, dir) -> {
                    ExperimentsConfig c = ExperimentsConfig.getInstance();
                    c.setSuperpairsValuableOnly(!c.isSuperpairsValuableOnly());
                    c.save();
                    btn.setMessage(superpairsFilterText());
                }).bounds(contentX + half + GAP, y, half, 20).build());
        y += 24;
        // killer560, 2026-10-05: two separate skips - Grand bottles only (Titanics still paired), Guardian pets of
        // every rarity.
        widgets.add(flag("Skip Grand XP Bottles", ExperimentsConfig::isSkipGrandExpBottles, ExperimentsConfig::setSkipGrandExpBottles, null, contentX, y, half));
        widgets.add(flag("Skip Guardian Pets", ExperimentsConfig::isSkipGuardianPets, ExperimentsConfig::setSkipGuardianPets, null, contentX + half + GAP, y, half));
        y += 24;
        widgets.add(flag("Skip Metaphysical Serums", ExperimentsConfig::isSkipMetaphysicalSerums, ExperimentsConfig::setSkipMetaphysicalSerums, null, contentX, y, half));
        // killer560, 2026-10-19: a T7 book or the Nadeshiko Dye comes before anything else.
        widgets.add(flag("T7 Drops First", ExperimentsConfig::isSuperpairsT7First, ExperimentsConfig::setSuperpairsT7First, null, contentX + half + GAP, y, half));
        y += 24;
        // killer560, 2026-10-19: hold back rewards worth up to the slider's value until the board is known.
        widgets.add(flag("Explore Before Claiming", ExperimentsConfig::isSuperpairsExploreFirst, ExperimentsConfig::setSuperpairsExploreFirst, null, contentX, y, half));
        double skipRange = ExperimentsConfig.MAX_SUPERPAIRS_SKIP_VALUE - ExperimentsConfig.MIN_SUPERPAIRS_SKIP_VALUE;
        widgets.add(new ThemedSliderButton(contentX + half + GAP, y, half, 20, skipValueText(),
                (cfg.getSuperpairsSkipValueCoins() - ExperimentsConfig.MIN_SUPERPAIRS_SKIP_VALUE) / skipRange) {
            @Override
            protected void updateMessage() {
                setMessage(skipValueText());
            }

            @Override
            protected void applyValue() {
                ExperimentsConfig c = ExperimentsConfig.getInstance();
                c.setSuperpairsSkipValueCoins(ExperimentsConfig.MIN_SUPERPAIRS_SKIP_VALUE + this.value * skipRange);
                saveOnRelease(() -> c.save());
                this.value = (c.getSuperpairsSkipValueCoins() - ExperimentsConfig.MIN_SUPERPAIRS_SKIP_VALUE) / skipRange;
            }
        });
        y += 24;

        // Adaptive Timeout extends Superpairs' confirm wait by however long the server stalls (see
        // ExperimentSolver#superpairsConfirmTimeoutMs). Its Timeout Margin slider went on 2026-10-04 -
        // killer560: "that shouldn't have a margin it should sense when the server is lagging".
        widgets.add(flag("Adaptive Timeout", ExperimentsConfig::isSuperpairsAdaptiveTimeout, ExperimentsConfig::setSuperpairsAdaptiveTimeout, null, contentX, y, contentWidth));
        y += 24;

        // Per killer560's request: Block Input and Auto-Swap Guardian side by side instead of stacked.
        widgets.add(flag("Block Input In Menu", ExperimentsConfig::isBlockInputEnabled, ExperimentsConfig::setBlockInputEnabled, null, contentX, y, half));
        widgets.add(flag("Auto-Swap to Guardian Pet", ExperimentsConfig::isAutoSwapGuardianPet, ExperimentsConfig::setAutoSwapGuardianPet, null, contentX + half + GAP, y, half));
        y += 24;

        // Escape or the mod-wide Emergency Cancel Key (Home, macrostop/MacroStop) stops a run, nothing else (killer560,
        // 2026-11-01; from 2026-10-13 to then any key, click or scroll did). Stop At has the row to itself.
        widgets.add(SettingsButtonWidget.cycling(stopStrategyText(), (btn, dir) -> {
                    ExperimentsConfig c = ExperimentsConfig.getInstance();
                    c.setStopStrategy(dir > 0 ? c.getStopStrategy().next() : c.getStopStrategy().previous());
                    c.save();
                    btn.setMessage(stopStrategyText());
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 28;

        // 2026-10-04, killer560: "make the timeouts bars and remove the boxes I can type in. Have the bars be
        // in 50ms increments." The three typed boxes and their Set buttons became these sliders; same
        // fields, same JSON keys.
        // Click Delay and First Click Delay start at MIN_CLICK_DELAY_MS (150) since 2026-10-15: at 0 the bot clicked
        // every tick.
        widgets.add(msSlider(contentX, y, contentWidth, "Click Delay",
                ExperimentsConfig.MIN_CLICK_DELAY_MS, ExperimentsConfig.MAX_DELAY_MS,
                () -> ExperimentsConfig.getInstance().getDelayMs(),
                ms -> ExperimentsConfig.getInstance().setDelayMs(ms)));
        y += 22;
        widgets.add(msSlider(contentX, y, contentWidth, "First Click Delay",
                ExperimentsConfig.MIN_CLICK_DELAY_MS, ExperimentsConfig.MAX_FIRST_CLICK_DELAY_MS,
                () -> ExperimentsConfig.getInstance().getFirstClickDelayMs(),
                ms -> ExperimentsConfig.getInstance().setFirstClickDelayMs(ms)));
        y += 22;
        widgets.add(msSlider(contentX, y, contentWidth, "Random Delay (max)",
                ExperimentsConfig.MIN_DELAY_MS, ExperimentsConfig.MAX_DELAY_MS,
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
                saveOnRelease(() -> c.save());
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
        // "remove the set button it isn't needed") - anything that is not a number in range is ignored. The Set button
        // came back on 2026-11-01 with every other text box ("If it doesnt have a set button add it as well"); it and
        // Enter apply the same as typing does.
        TitanicSlider titanicSlider = new TitanicSlider(contentX, y, titanicSliderWidth);
        CommitEditBox titanicField = new CommitEditBox(Minecraft.getInstance().font, titanicFieldX, y, titanicFieldWidth, 20,
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
        CommitEditBox.withSet(widgets, titanicField);

        return widgets;
    }

    /** A min..max ms slider that snaps to {@link ExperimentsConfig#DELAY_STEP_MS} and saves as it moves. The
     *  snapped fraction is written back into {@code value}, which also positions the handle, so the handle
     *  jumps between steps instead of sliding continuously. {@code minMs} must be a multiple of the step. */
    private static ThemedSliderButton msSlider(int x, int y, int width, String label, int minMs, int maxMs,
            IntSupplier getter, IntConsumer setter) {
        double range = maxMs - minMs;
        return new ThemedSliderButton(x, y, width, 20, msText(label, getter.getAsInt()),
                (getter.getAsInt() - minMs) / range) {
            @Override
            protected void updateMessage() {
                setMessage(msText(label, getter.getAsInt()));
            }

            @Override
            protected void applyValue() {
                int step = ExperimentsConfig.DELAY_STEP_MS;
                int snapped = minMs + (int) Math.round(this.value * range / step) * step;
                setter.accept(Math.max(minMs, Math.min(maxMs, snapped)));
                saveOnRelease(() -> ExperimentsConfig.getInstance().save());
                this.value = (getter.getAsInt() - minMs) / range;
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
            saveOnRelease(() -> c.save());
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

    /** An ON/OFF switch over one {@link ExperimentsConfig} flag (the config is read fresh at each click). */
    private static SettingsButtonWidget flag(String label, java.util.function.Function<ExperimentsConfig, Boolean> get,
            java.util.function.BiConsumer<ExperimentsConfig, Boolean> set, Runnable rebuild, int x, int y, int w) {
        return onOffButton(label, () -> get.apply(ExperimentsConfig.getInstance()),
                v -> set.accept(ExperimentsConfig.getInstance(), v), () -> ExperimentsConfig.getInstance().save(), rebuild,
                x, y, w, 20);
    }

    private static Component skipValueText() {
        return Component.literal(String.format("Hold Back Up To: §b%,.0f coins",
                ExperimentsConfig.getInstance().getSuperpairsSkipValueCoins()));
    }

    private static Component superpairsFilterText() {
        return Component.literal("Superpairs Pairs: §b"
                + (ExperimentsConfig.getInstance().isSuperpairsValuableOnly() ? "Skip Plain XP" : "Every Pair"));
    }

    private static Component stopStrategyText() {
        return Component.literal("Stop At: §b" + ExperimentsConfig.getInstance().getStopStrategy().displayName);
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
}
