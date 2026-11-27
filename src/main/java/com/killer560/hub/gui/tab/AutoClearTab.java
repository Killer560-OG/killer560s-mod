package com.killer560.hub.gui.tab;

import com.killer560.hub.autoclear.AutoClearConfig;
import com.killer560.hub.autosecret.AutoSecretConfig;
import com.killer560.hub.autosecret.DungeonsAutopilot;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;

/**
 * "Dungeons Autopilot" - CHEAT BUILD ONLY (apmenu-1121, killer560 2026-11-21: "one button with a couple of different
 * modes ... it should all just be under one keybind"): the one card over Auto Clear, Auto Secret, the Dungeon Autopilot
 * planner and the old Auto Blood Rush. The first row is {@link DungeonsAutopilot}'s switch (never saved, so a restart
 * never starts a run); its Toggle Keybind is the drawer's ModuleKeybinds row (id "AutoClearTab", kept so old binds and
 * profiles carry over). Every other row is a saved setting of AutoSecretConfig or AutoClearConfig.
 */
public class AutoClearTab extends BaseTab {

    private static final int GAP = 6;
    private static final int ROW = 24;

    public AutoClearTab() {
        super("Dungeons Autopilot");
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
        AutoSecretConfig cfg = AutoSecretConfig.getInstance();
        AutoClearConfig clear = AutoClearConfig.getInstance();
        Runnable save = cfg::save;
        Runnable saveClear = clear::save;
        int half = (contentWidth - GAP) / 2;
        int colB = contentX + half + GAP;
        int right = Math.max(1, contentWidth - half - GAP);
        int[] y = {contentY};

        w.add(onOffButton("Dungeons Autopilot", DungeonsAutopilot::isOn, DungeonsAutopilot::setOn, () -> { },
                requestRebuild, contentX, y[0], contentWidth, 20));
        y[0] += ROW;
        header(w, contentX, y, contentWidth, "Mode");
        w.add(SettingsButtonWidget.cycleEnum(() -> value("Mode", cfg.getMode().label()), cfg::getMode, cfg::setMode, save)
                .bounds(contentX, y[0], contentWidth, 20).build());
        y[0] += ROW;
        w.add(SettingsButtonWidget.cycleEnum(() -> value("Blood Rush", cfg.getBloodRush().label()), cfg::getBloodRush,
                cfg::setBloodRush, save).bounds(contentX, y[0], half, 20).build());
        toggle(w, colB, y[0], right, "Disable After Blood Rush", cfg::isDisableAfterBloodRush, cfg::setDisableAfterBloodRush, save);
        y[0] += ROW;

        header(w, contentX, y, contentWidth, "Order");
        // autopilot-menu-1126 (killer560 2026-11-26): this order; Clear First is gone (Blood First off is clear first).
        toggle(w, contentX, y[0], half, "Blood First", cfg::isBloodFirst, cfg::setBloodFirst, save);
        toggle(w, colB, y[0], right, "Clear Room Before Secreting", cfg::isClearBeforeSecret, cfg::setClearBeforeSecret, save);
        y[0] += ROW;
        toggle(w, contentX, y[0], half, "Allow Insta Clearing", cfg::isInstaClear, cfg::setInstaClear, save);
        toggle(w, colB, y[0], right, "Do Puzzles", cfg::isDoPuzzles, cfg::setDoPuzzles, save);
        y[0] += ROW;
        toggle(w, contentX, y[0], half, "Ice Fill First", cfg::isIceFillFirst, cfg::setIceFillFirst, save);
        toggle(w, colB, y[0], right, "Quiz First", cfg::isQuizFirst, cfg::setQuizFirst, save);
        y[0] += ROW;
        toggle(w, contentX, y[0], half, "Skip Unnecessary Secrets", cfg::isSkipUnneededSecrets, cfg::setSkipUnneededSecrets, save);
        toggle(w, colB, y[0], right, "Face Door on Arrival", cfg::isFaceDoorOnArrival, cfg::setFaceDoorOnArrival, save);
        y[0] += ROW;
        w.add(SettingsButtonWidget.cycleEnum(() -> value("Secret Room Before Blood", cfg.getRoomBeforeBlood().label()),
                cfg::getRoomBeforeBlood, cfg::setRoomBeforeBlood, save).bounds(contentX, y[0], contentWidth, 20).build());
        y[0] += ROW;
        w.add(SettingsButtonWidget.cycleEnum(() -> value("Room Order", cfg.getSecretOrder().label()), cfg::getSecretOrder,
                cfg::setSecretOrder, save).bounds(contentX, y[0], contentWidth, 20).build());
        y[0] += ROW;

        header(w, contentX, y, contentWidth, "Timing");
        int pSpan = AutoSecretConfig.MAX_PUZZLE_WAIT_S - AutoSecretConfig.MIN_PUZZLE_WAIT_S;
        slider(w, contentX, y[0], half, () -> "Puzzle Stall Timeout: " + cfg.getPuzzleWaitSeconds() + " s",
                (cfg.getPuzzleWaitSeconds() - AutoSecretConfig.MIN_PUZZLE_WAIT_S) / (double) pSpan,
                v -> cfg.setPuzzleWaitSeconds((int) Math.round(AutoSecretConfig.MIN_PUZZLE_WAIT_S + v * pSpan)), save);
        int sSpan = AutoSecretConfig.MAX_STALL_S - AutoSecretConfig.MIN_STALL_S;
        slider(w, colB, y[0], right, () -> "Route Stall Timeout: " + cfg.getRouteStallSeconds() + " s",
                (cfg.getRouteStallSeconds() - AutoSecretConfig.MIN_STALL_S) / (double) sSpan,
                v -> cfg.setRouteStallSeconds((int) Math.round(AutoSecretConfig.MIN_STALL_S + v * sSpan)), save);
        y[0] += ROW;

        header(w, contentX, y, contentWidth, "Clearing");
        // One-Tap Warp is about Spirit Sceptre bats: shown only with that weapon (its value is kept meanwhile; with the
        // Hyperion no bat flies, so it does nothing). The weapon cycler rebuilds the tab so the row comes and goes.
        boolean sceptre = clear.getWeapon() == AutoClearConfig.Weapon.SPIRIT_SCEPTRE;
        w.add(SettingsButtonWidget.cycleEnum(() -> value("Clear Weapon", clear.getWeapon().label()), clear::getWeapon,
                clear::setWeapon, () -> {
                    clear.save();
                    requestRebuild.run();
                }).bounds(contentX, y[0], sceptre || isSearchScan() ? half : contentWidth, 20).build());
        if (sceptre || isSearchScan()) {
            w.add(SettingsButtonWidget.cycleEnum(() -> value("One-Tap Warp", clear.getOneTap().label()), clear::getOneTap,
                    clear::setOneTap, saveClear).bounds(colB, y[0], right, 20).build());
        }
        y[0] += ROW;
        toggle(w, contentX, y[0], half, "Hyperion Hops", clear::isHyperionHops, clear::setHyperionHops, saveClear);
        toggle(w, colB, y[0], right, "Pick Up Wither Keys", clear::isPickUpKeys, clear::setPickUpKeys, saveClear);
        y[0] += ROW;
        toggle(w, contentX, y[0], half, "Walk When Faster", clear::isWalkWhenFaster, clear::setWalkWhenFaster, saveClear);
        toggle(w, colB, y[0], right, "Predictive Bats", clear::isPredictiveBats, clear::setPredictiveBats, saveClear);
        y[0] += ROW;
        toggle(w, contentX, y[0], half, "Learned Room Costs", clear::isLearnedRoomCosts, clear::setLearnedRoomCosts, saveClear);
        toggle(w, colB, y[0], right, "Warp to Door Before Start", cfg::isGreenRoomWarp, cfg::setGreenRoomWarp, save);
        y[0] += ROW;
        int mSpan = AutoClearConfig.MAX_MOB_TIMEOUT_TICKS - AutoClearConfig.MIN_MOB_TIMEOUT_TICKS;
        slider(w, contentX, y[0], contentWidth, () -> String.format(Locale.US, "Mob Timeout: %.1fs", clear.getMobTimeoutTicks() / 20.0),
                (clear.getMobTimeoutTicks() - AutoClearConfig.MIN_MOB_TIMEOUT_TICKS) / (double) mSpan,
                v -> clear.setMobTimeoutTicks((int) Math.round(AutoClearConfig.MIN_MOB_TIMEOUT_TICKS + v * mSpan)), saveClear);
        y[0] += ROW;

        header(w, contentX, y, contentWidth, "Display");
        toggle(w, contentX, y[0], half, "Status HUD", cfg::isStatusHud, cfg::setStatusHud, save);
        toggle(w, colB, y[0], right, "Chat Feedback", cfg::isChatFeedback, cfg::setChatFeedback, save);
        y[0] += ROW;
        return w;
    }

    private static Component value(String label, String value) {
        return Component.literal(label + ": §e" + value);
    }

    private static void header(List<AbstractWidget> w, int x, int[] y, int width, String text) {
        y[0] += 6;
        w.add(SectionHeaders.row(x, y[0], width, text, true));
        y[0] += 16;
    }

    private static void toggle(List<AbstractWidget> w, int x, int y, int width, String name, BooleanSupplier getter,
                               Consumer<Boolean> setter, Runnable save) {
        w.add(onOffButton(name, getter, setter, save, null, x, y, Math.max(1, width), 20));
    }

    private static void slider(List<AbstractWidget> w, int x, int y, int width, Supplier<String> text, double normalized,
                               DoubleConsumer apply, Runnable save) {
        w.add(normSlider(x, y, Math.max(1, width), 20, text, normalized, apply, save));
    }
}
