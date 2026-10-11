package com.killer560.hub.gui.tab;

import com.killer560.hub.experiments.ExperimentsConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Experimentation Table SOLVER settings - see {@link com.killer560.hub.experiments.ExperimentsFeature}.
 *  <p>
 *  2026-09-30 (killer560: "Move the auto etable stuff into its own red header in the same helpers tab but
 *  different area. The solver should be its own setting."): the autonomous auto-clicking half moved out to
 *  {@link AutoExperimentsTab}, which the Helpers folder draws with a red header because it is cheat-only.
 *  What is left here is the legit half, and the old two-way "Mode: Solver Only / Autonomous" button is gone
 *  with it - the solver is now just its own toggle, so it can be run with no automation at all (what Solver
 *  Only meant), or the automation can be run with the highlights off, or both together.
 *  <p>
 *  No persisted key changed: this tab still drives {@code "enabled"} exactly as before, and the automation
 *  tab still drives {@code "autonomousMode"}. See {@link ExperimentsConfig}'s own doc for the one-time
 *  fix-up that keeps a pre-split "enabled: false" file from waking the automation up. */
public class ExperimentsTab extends BaseTab {

    public ExperimentsTab() {
        super("Experiments");
    }

    private static final int GAP = 6;

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        ExperimentsConfig cfg = ExperimentsConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(enabledText(), btn -> {
                    ExperimentsConfig c = ExperimentsConfig.getInstance();
                    c.setEnabled(!c.isEnabled());
                    c.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        // Per killer560's request the master toggle HIDES what it governs rather than showing it inert -
        // click protection is a solver-only mechanism, so it goes with the solver.
        if (cfg.isEnabled()) {
            widgets.add(SettingsButtonWidget.builder(clickProtectionText(), btn -> {
                        ExperimentsConfig c = ExperimentsConfig.getInstance();
                        c.setClickProtectionEnabled(!c.isClickProtectionEnabled());
                        c.save();
                        btn.setMessage(clickProtectionText());
                    }).bounds(contentX, y, contentWidth, 20).build());
            y += 24;
        }

        // Per killer560's request (2026-09-08): a client-side chat message (not the action-bar popup)
        // announcing when the lore-based max-clicks threshold is reached - see
        // ExperimentsFeature#maybeNotifyMaxClicksReached. Fires with the solver on, with the automation
        // on, or with both, so it is shown unconditionally rather than under either one of them.
        widgets.add(SettingsButtonWidget.builder(notifyMaxClicksText(), btn -> {
                    ExperimentsConfig c = ExperimentsConfig.getInstance();
                    c.setNotifyMaxClicksReached(!c.isNotifyMaxClicksReached());
                    c.save();
                    btn.setMessage(notifyMaxClicksText());
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 28;

        // The profit tracker is independent of the solver (it only reads claimed rewards), so it stays
        // reachable with the solver itself switched off.
        addProfitTrackerSection(widgets, contentX, y, contentWidth, requestRebuild);
        return widgets;
    }

    /** Experimentation Table profit tracker: the on/off toggle and, beside it, the button that opens
     *  {@code /profit}. killer560, 2026-10-11: "remove the reset profit totals button. Remove the sessions and
     *  reward valy and all that random text, and move the open /profit to where the reste profit totals is right
     *  now" - the totals and the (scoped, red-confirm) reset live only on the /profit screen now. */
    private static int addProfitTrackerSection(List<AbstractWidget> widgets, int contentX, int y, int contentWidth,
            Runnable requestRebuild) {
        int half = (contentWidth - GAP) / 2;
        widgets.add(SettingsButtonWidget.builder(profitTrackerText(), btn -> {
                    ExperimentsConfig c = ExperimentsConfig.getInstance();
                    c.setProfitTrackerEnabled(!c.isProfitTrackerEnabled());
                    c.save();
                    requestRebuild.run();
                }).bounds(contentX, y, half, 20).build());
        widgets.add(SettingsButtonWidget.builder(Component.literal("Open /profit"), btn ->
                com.killer560.hub.compat.McCompat.setScreen(Minecraft.getInstance(),
                        new com.killer560.hub.gui.profit.ProfitHubScreen(
                                com.killer560.hub.compat.McCompat.screen(Minecraft.getInstance()))))
                .bounds(contentX + half + GAP, y, half, 20).build());
        y += 24;
        return y;
    }

    private static Component profitTrackerText() {
        return Component.literal("Profit Tracker: "
                + (ExperimentsConfig.getInstance().isProfitTrackerEnabled() ? "§aON" : "§cOFF"));
    }

    /** Label deliberately unchanged across the 2026-09-30 split - it already said "solver", it now means
     *  only the solver, and its hover description in {@code SettingTooltipsData} is keyed off this text. */
    private static Component enabledText() {
        return Component.literal("Experiment Solver Enabled: "
                + (ExperimentsConfig.getInstance().isEnabled() ? "§aON" : "§cOFF"));
    }

    /** Solver-side only: it has no effect while the automation is running, which never routes real clicks
     *  through the misclick-protection check (see {@code ExperimentsFeature#shouldBlockManualMisclick}). */
    private static Component clickProtectionText() {
        ExperimentsConfig cfg = ExperimentsConfig.getInstance();
        return Component.literal("Click Protection: " + (cfg.isClickProtectionEnabled() ? "§aON" : "§cOFF"));
    }

    /** Fires for the solver and for the automation alike since 2026-09-15. */
    private static Component notifyMaxClicksText() {
        ExperimentsConfig cfg = ExperimentsConfig.getInstance();
        return Component.literal("Notify Max Clicks Reached: " + (cfg.isNotifyMaxClicksReached() ? "§aON" : "§cOFF"));
    }
}
