package com.killer560.hub.dungeoninfo;

import com.killer560.hub.hud.HudElement;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.translate.TranslateFeature;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Secrets-found display, run-time tracking, and keyword-triggered mimic/prince/bat chat alerts.
 * <p>
 * <b>Honesty note (2026-09-13):</b> this session had no way to confirm Hypixel's exact real sidebar
 * text for a live secrets-found count, or the exact real chat lines for a mimic/prince/party-bat
 * encounter, against an actual game - this codebase's own standing rule elsewhere is "confirmed via a
 * real log/decompile, not guessed." So:
 * <ul>
 * <li>Secrets: matched with a loose "Secrets Found: N" pattern (case-insensitive, tolerant of Hypixel's
 * mid-word color codes the same way {@link DungeonState} already has to strip) - the FIRST dungeon
 * entry each session also logs the full raw sidebar text at {@code INFO}, so a mismatch is easy to spot
 * and fix from the log rather than silently never working.
 * <li>Mimic/Prince/Bat: plain, user-editable keyword substring matches (default guesses: "mimic",
 * "prince", "bat") rather than exact hardcoded chat lines - safe to fire on a real event even if the
 * exact wording is off, and trivially correctable in the tab if a keyword turns out too broad/narrow.
 * </ul>
 * Run-time tracking reuses {@link DungeonState#isInDungeon()}'s already-integrated transition (the
 * same signal {@code PosmsgFeature} already trusts for its own "new run" reset) rather than guessing a
 * new "dungeon completed" chat regex.
 */
public final class DungeonInfoFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-dungeoninfo");
    private static final Pattern SECRETS_PATTERN = Pattern.compile("(?i)Secrets\\s*Found:\\s*(\\d+)");
    private static final long ALERT_COOLDOWN_MS = 2000;

    private static boolean wasInDungeon = false;
    private static long runStartAtMs = 0;
    private static long runEndedAtMs = -1;
    private static boolean loggedSidebarThisRun = false;
    private static int lastSecretsCount = -1;
    private static long lastMimicAlertAtMs = 0;
    private static long lastPrinceAlertAtMs = 0;
    private static long lastBatAlertAtMs = 0;

    private DungeonInfoFeature() {
    }

    public static void register() {
        ClientReceiveMessageEvents.CHAT.register(
                (message, signedMessage, sender, params, receptionTimestamp) -> onChatMessage(message.getString()));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onChatMessage(message.getString()));
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    private static void tick() {
        boolean inDungeonNow = DungeonState.isInDungeon();
        if (inDungeonNow && !wasInDungeon) {
            runStartAtMs = System.currentTimeMillis();
            runEndedAtMs = -1;
            loggedSidebarThisRun = false;
            lastSecretsCount = -1;
        } else if (!inDungeonNow && wasInDungeon) {
            runEndedAtMs = System.currentTimeMillis();
        }
        wasInDungeon = inDungeonNow;

        if (inDungeonNow && DungeonInfoConfig.getInstance().isSecretsHudEnabled()) {
            updateSecretsCount();
        }
    }

    private static void updateSecretsCount() {
        String sidebar = readSidebarText();
        if (!loggedSidebarThisRun && !sidebar.isBlank()) {
            loggedSidebarThisRun = true;
            LOGGER.info("[DungeonInfo] Raw sidebar on first read this run:\n{}", sidebar);
        }
        String plain = ChatFormatting.stripFormatting(sidebar);
        if (plain == null) {
            return;
        }
        Matcher m = SECRETS_PATTERN.matcher(plain);
        if (m.find()) {
            int count = Integer.parseInt(m.group(1));
            if (count != lastSecretsCount) {
                lastSecretsCount = count;
            }
        }
    }

    private static void onChatMessage(String text) {
        DungeonInfoConfig cfg = DungeonInfoConfig.getInstance();
        String lower = text.toLowerCase(Locale.US);
        long now = System.currentTimeMillis();

        if (cfg.isMimicMessageEnabled() && !cfg.getMimicKeyword().isBlank()
                && lower.contains(cfg.getMimicKeyword().toLowerCase(Locale.US)) && now - lastMimicAlertAtMs > ALERT_COOLDOWN_MS) {
            lastMimicAlertAtMs = now;
            TranslateFeature.sendGenerated(cfg.getMimicMessage(), "pc");
        }
        if (cfg.isPrinceMessageEnabled() && !cfg.getPrinceKeyword().isBlank()
                && lower.contains(cfg.getPrinceKeyword().toLowerCase(Locale.US)) && now - lastPrinceAlertAtMs > ALERT_COOLDOWN_MS) {
            lastPrinceAlertAtMs = now;
            TranslateFeature.sendGenerated(cfg.getPrinceMessage(), "pc");
        }
        if (cfg.isBatMessageEnabled() && !cfg.getBatKeyword().isBlank()
                && lower.contains(cfg.getBatKeyword().toLowerCase(Locale.US)) && now - lastBatAlertAtMs > ALERT_COOLDOWN_MS) {
            lastBatAlertAtMs = now;
            TranslateFeature.sendGenerated(cfg.getBatMessage(), "pc");
        }
    }

    public static void sendScore270() {
        DungeonInfoConfig cfg = DungeonInfoConfig.getInstance();
        TranslateFeature.sendGenerated(cfg.getScore270Message(), "pc");
    }

    public static void sendScore300() {
        DungeonInfoConfig cfg = DungeonInfoConfig.getInstance();
        TranslateFeature.sendGenerated(cfg.getScore300Message(), "pc");
    }

    /** Client-side elapsed time for the current (or most recently finished) run, "with lag" simply
     *  being real wall-clock elapsed time - killer560's "with and without lag" ask needs a real
     *  server-tick-count source to give a without-lag figure that means anything, which nothing in
     *  this mod currently tracks; only the wall-clock figure is implemented here. */
    public static String elapsedTimeText() {
        if (runStartAtMs == 0) {
            return "No run yet";
        }
        long end = runEndedAtMs > 0 ? runEndedAtMs : System.currentTimeMillis();
        long elapsedSec = (end - runStartAtMs) / 1000;
        return String.format(Locale.US, "%02d:%02d", elapsedSec / 60, elapsedSec % 60);
    }

    private static String readSidebarText() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return "";
        }
        Scoreboard scoreboard = client.level.getScoreboard();
        Objective sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (sidebar == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(sidebar.getDisplayName().getString()).append('\n');
        for (PlayerScoreEntry entry : scoreboard.listPlayerScores(sidebar)) {
            sb.append(realLineText(scoreboard, entry)).append('\n');
        }
        return sb.toString();
    }

    private static String realLineText(Scoreboard scoreboard, PlayerScoreEntry entry) {
        PlayerTeam team = scoreboard.getPlayersTeam(entry.owner());
        if (team == null) {
            return entry.display() != null ? entry.display().getString() : entry.owner();
        }
        StringBuilder line = new StringBuilder();
        Component prefix = team.getPlayerPrefix();
        if (prefix != null) {
            line.append(prefix.getString());
        }
        Component suffix = team.getPlayerSuffix();
        if (suffix != null) {
            line.append(suffix.getString());
        }
        return line.toString();
    }

    public static final class InfoHudElement implements HudElement {
        @Override
        public String id() {
            return "dungeon_info";
        }

        @Override
        public String displayName() {
            return "Dungeon Info (Secrets/Time)";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            return 260;
        }

        @Override
        public int width() {
            return 140;
        }

        @Override
        public int height() {
            return 24;
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            DungeonInfoConfig cfg = DungeonInfoConfig.getInstance();
            if (Minecraft.getInstance().screen != null) {
                return;
            }
            int lineY = y;
            if (cfg.isSecretsHudEnabled() && DungeonState.isInDungeon()) {
                String text = lastSecretsCount >= 0 ? ("Secrets: " + lastSecretsCount) : "Secrets: ?";
                graphics.text(Minecraft.getInstance().font, text, x, lineY, 0xFFFFFFFF, false);
                lineY += 12;
            }
            if (cfg.isTimeTrackerEnabled() && runStartAtMs > 0) {
                graphics.text(Minecraft.getInstance().font, "Time: " + elapsedTimeText(), x, lineY, 0xFFFFFFFF, false);
            }
        }
    }
}
