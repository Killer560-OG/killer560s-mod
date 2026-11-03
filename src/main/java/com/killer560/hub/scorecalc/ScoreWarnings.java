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
 * <li><b>Pre-Boss Warning</b> (Dungeons Guide "Score Warning on Watcher portal"): the moment the Watcher says "You may
 * pass" (the Portal Entry split's start), it lists what is still missing - crypts under 5, rooms not cleared, secrets
 * short of S+ - before you take the portal. Silent when nothing is missing. Optionally Mage only, and Permanent: a title
 * held up until the boss, "DO NOT ENTER PORTAL" (with or without the list) or a green "ENTER THE PORTAL".</li>
 * <li><b>Crypt Reminder</b> (Skyblocker's crypts message, on a timer instead of a score): once the run's clock passes
 * the set time with fewer than 5 crypts, outside the boss, a title and a chat line; optionally the count to party.</li>
 * </ul>
 */
public final class ScoreWarnings {

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
        portalColor = 0;
        portalBlocks = java.util.List.of();
    }

    // ---- Color Portal (killer560, 2026-11-03: "make it red if you shouldnt enter, yellow if it is 270 and green if it is
    // 300"). From the Watcher's line until the boss, the nether portal blocks near him are tinted by the calculator's score.
    static final int PORTAL_RED = 0xFFFF3030;
    static final int PORTAL_YELLOW = 0xFFFFD700;
    static final int PORTAL_GREEN = 0xFF30FF30;
    private static volatile int portalColor = 0;
    private static volatile java.util.List<net.minecraft.core.BlockPos> portalBlocks = java.util.List.of();
    private static final com.killer560.hub.util.BoxBatch PORTAL_FILL = new com.killer560.hub.util.BoxBatch();
    private static int portalFrames = 0;

    public static int portalColorFor(int score) {
        return score >= 300 ? PORTAL_GREEN : score >= 270 ? PORTAL_YELLOW : PORTAL_RED;
    }

    private static void updatePortal(ScoreCalculatorConfig cfg, ScoreCalculator.Result result) {
        if (!cfg.isPreBossWarning() || !cfg.isPreBossPortalColor() || watcherDoneTick < 0) {
            portalColor = 0;
            return;
        }
        portalScore = result.total();
        portalColor = portalColorFor(portalScore);
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        net.minecraft.core.BlockPos at = mc.player.blockPosition();
        java.util.List<net.minecraft.core.BlockPos> found = new ArrayList<>();
        net.minecraft.core.BlockPos.MutableBlockPos p = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int dx = -24; dx <= 24 && found.size() < 200; dx++) {
            for (int dz = -24; dz <= 24 && found.size() < 200; dz++) {
                for (int dy = -8; dy <= 12; dy++) {
                    p.set(at.getX() + dx, at.getY() + dy, at.getZ() + dz);
                    if (mc.level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL)) {
                        found.add(p.immutable());
                    }
                }
            }
        }
        portalBlocks = java.util.List.copyOf(found);
    }

    static void renderPortal(net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext context) {
        int color = portalColor;
        java.util.List<net.minecraft.core.BlockPos> blocks = portalBlocks;
        if (color == 0 || blocks.isEmpty()) {
            return;
        }
        float[] k = com.killer560.hub.util.WorldRenderUtils.argbToFloats(color);
        PORTAL_FILL.clear();
        double e = 0.002;
        for (net.minecraft.core.BlockPos b : blocks) {
            PORTAL_FILL.add(b.getX() - e, b.getY() - e, b.getZ() - e, b.getX() + 1 + e, b.getY() + 1 + e, b.getZ() + 1 + e,
                    k[0], k[1], k[2], 0.55f);
        }
        PORTAL_FILL.drawFilled(context, com.killer560.hub.util.EspTypes.FILLED);
        portalFrames++;
    }

    /** Testkit: the portal tint now (0 = none) and how many portal blocks it covers. */
    public static int portalColor() {
        return portalColor;
    }

    private static volatile int portalScore = -1;

    /** Testkit: the score the portal color was picked from. */
    public static int portalScore() {
        return portalScore;
    }

    public static int portalBlockCount() {
        return portalBlocks.size();
    }

    public static int portalFrames() {
        return portalFrames;
    }

    static void onWatcherDone(int runTicks) {
        if (watcherDoneTick < 0) {
            watcherDoneTick = runTicks;
        }
    }

    /** After every recalculation with real tab data (every half second, and at once on the Watcher's line). */
    static void check(ScoreCalculatorConfig cfg, ScoreCalculator.Inputs in, ScoreCalculator.Result result, int runTicks) {
        if (in == null || result == null || in.inBoss()) {
            portalColor = 0;
            return;
        }
        updatePortal(cfg, result);
        // From the Watcher's "You have proven yourself" line - the second the Portal Entry split starts (killer560,
        // 2026-11-01) - no longer after blood's clear has counted: missing() counts blood as cleared until it has.
        if (cfg.isPreBossWarning() && watcherDoneTick >= 0 && (!cfg.isPreBossMageOnly() || playingMage())) {
            List<String> missing = missing(in, result);
            String detail = String.join(", ", missing);
            if (cfg.isPreBossPermanent()) {
                // Stays up until the boss and follows the score: red while anything is missing, green once nothing is.
                String title = missing.isEmpty() ? "§a§lENTER THE PORTAL" : "§c§lDO NOT ENTER PORTAL";
                String subtitle = missing.isEmpty() || !cfg.isPreBossListMissing() ? "" : "§e" + detail;
                boolean first = !preBossWarned;
                preBossWarned = true;
                hold(title, subtitle, first && cfg.isScoreWarningSound());
                if (first && !missing.isEmpty()) {
                    ModChat.send("Score Warning", ModChat.text("Before the boss: "), ModChat.value(detail),
                            ModChat.dim(" || score "), ModChat.value(String.valueOf(result.total())));
                }
            } else if (!preBossWarned) {
                preBossWarned = true;
                if (!missing.isEmpty()) {
                    show("§cBefore the boss!", "§e" + detail, cfg.isScoreWarningSound());
                    ModChat.send("Score Warning", ModChat.text("Before the boss: "), ModChat.value(detail),
                            ModChat.dim(" || score "), ModChat.value(String.valueOf(result.total())));
                }
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

    /**
     * What still keeps the run from its best score before the boss: crypts under 5, rooms not cleared, secrets short of
     * S+. Rooms count as the calculator does (Odin): blood is cleared until Hypixel's "Cleared" line has counted it, and
     * the boss room once entered - so right on the Watcher's line, before blood's clear reaches the sidebar, a fully
     * cleared run reads 100%.
     */
    static List<String> missing(ScoreCalculator.Inputs in, ScoreCalculator.Result result) {
        List<String> missing = new ArrayList<>();
        if (in.crypts() < 5) {
            missing.add("Crypts " + Math.max(0, in.crypts()) + "/5");
        }
        int cleared = Math.max(0, in.clearedPercent());
        if (result.totalRooms() > 0) {
            int done = in.completedRooms() + (in.bloodDone() ? 0 : 1) + 1;
            cleared = Math.max(cleared, Math.min(100, (int) Math.floor(done * 100.0 / result.totalRooms())));
        }
        if (cleared < 100) {
            missing.add("Cleared " + cleared + "%");
        }
        if (result.secretsRemaining() > 0) {
            missing.add(result.secretsRemaining() + " secrets for S+");
        }
        return missing;
    }

    private static boolean playingMage() {
        return com.killer560.hub.leapmenu.PartyTracker.selfClass() == com.killer560.hub.dungeonclass.DungeonClass.MAGE;
    }

    /** A title that stays up while it is refreshed (every check, half a second apart): one second each time, no fade
     *  in, so a refresh never blinks. Counted as a new warning only when its text changes. */
    private static void hold(String title, String subtitle, boolean sound) {
        Minecraft client = Minecraft.getInstance();
        String text = title + (subtitle.isEmpty() ? "" : " | " + subtitle);
        if (!text.equals(lastWarning)) {
            warningsShown++;
            lastWarning = text;
        }
        McCompat.setTimes(client, 0, 25, 5);
        McCompat.setTitle(client, Component.literal(title));
        McCompat.setSubtitle(client, Component.literal(subtitle));
        if (sound) {
            client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 0.8f, 1.0f));
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
