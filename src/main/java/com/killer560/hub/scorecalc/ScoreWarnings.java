package com.killer560.hub.scorecalc;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.translate.TranslateFeature;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.List;

/**
 * Score Warning (2026-10-25): two once-a-run reminders read off the Score Calculator's own numbers, so they can never
 * disagree with its HUD.
 * <ul>
 * <li><b>Pre-Boss Warning</b> (Dungeons Guide "Score Warning on Watcher portal"): when the Watcher says "You may pass"
 * and blood's clear has counted (or 3 s after his line), it lists what is still missing - crypts under 5, rooms not
 * cleared, secrets short of S+ - before you take the portal. Silent when nothing is missing.</li>
 * <li><b>Crypt Reminder</b> (Skyblocker's crypts message, on a timer instead of a score): once the run's clock passes
 * the set time with fewer than 5 crypts, outside the boss, a title and a chat line; optionally the count to party.</li>
 * </ul>
 */
public final class ScoreWarnings {

    /** Fallback when blood's "Cleared" change never comes: warn this long after the Watcher's line. */
    private static final int WATCHER_FALLBACK_TICKS = 60;

    private static int watcherDoneTick = -1;
    private static boolean preBossWarned = false;
    private static boolean cryptReminded = false;
    private static int warningsShown = 0;
    private static String lastWarning = null;

    private ScoreWarnings() {
    }

    /** Either reminder is on: the calculator's tracking must run for them. */
    static boolean wanted() {
        ScoreCalculatorConfig cfg = ScoreCalculatorConfig.getInstance();
        return cfg.isPreBossWarning() || cfg.isCryptReminder();
    }

    static void reset() {
        watcherDoneTick = -1;
        preBossWarned = false;
        cryptReminded = false;
    }

    static void onWatcherDone(int runTicks) {
        if (watcherDoneTick < 0) {
            watcherDoneTick = runTicks;
        }
    }

    /** After every recalculation with real tab data. */
    static void check(ScoreCalculatorConfig cfg, ScoreCalculator.Inputs in, ScoreCalculator.Result result, int runTicks) {
        if (in == null || result == null || in.inBoss()) {
            return;
        }
        if (cfg.isPreBossWarning() && !preBossWarned && watcherDoneTick >= 0
                && (in.bloodDone() || runTicks - watcherDoneTick >= WATCHER_FALLBACK_TICKS)) {
            preBossWarned = true;
            List<String> missing = new ArrayList<>();
            if (in.crypts() < 5) {
                missing.add("Crypts " + Math.max(0, in.crypts()) + "/5");
            }
            if (in.clearedPercent() < 100) {
                missing.add("Cleared " + Math.max(0, in.clearedPercent()) + "%");
            }
            if (result.secretsRemaining() > 0) {
                missing.add(result.secretsRemaining() + " secrets for S+");
            }
            if (!missing.isEmpty()) {
                String detail = String.join(", ", missing);
                show("§cBefore the boss!", "§e" + detail, cfg.isScoreWarningSound());
                ModChat.send("Score Warning", ModChat.text("Before the boss: "), ModChat.value(detail),
                        ModChat.dim(" || score "), ModChat.value(String.valueOf(result.total())));
            }
        }
        if (cfg.isCryptReminder() && !cryptReminded && in.secondsElapsed() >= cfg.getCryptReminderSeconds()) {
            cryptReminded = true;
            if (in.crypts() < 5) {
                String count = "Crypts: " + Math.max(0, in.crypts()) + "/5";
                show("§c" + count, "", cfg.isScoreWarningSound());
                ModChat.send("Score Warning", ModChat.text("Crypt reminder: "), ModChat.value(count));
                if (cfg.isCryptReminderParty()) {
                    TranslateFeature.sendGenerated(count, "pc");
                }
            }
        }
    }

    private static void show(String title, String subtitle, boolean sound) {
        Minecraft client = Minecraft.getInstance();
        warningsShown++;
        lastWarning = title + (subtitle.isEmpty() ? "" : " | " + subtitle);
        McCompat.setTimes(client, 5, 50, 10);
        McCompat.setTitle(client, Component.literal(title));
        McCompat.setSubtitle(client, Component.literal(subtitle));
        if (sound) {
            client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 0.8f, 1.0f));
        }
    }

    /** For the testkit: warnings shown since the client started. */
    public static int warningsShown() {
        return warningsShown;
    }

    /** For the testkit: the last warning's title and subtitle. */
    public static String lastWarning() {
        return lastWarning;
    }
}
