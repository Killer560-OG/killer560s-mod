package com.killer560.hub.dungeoninfo;

import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.splittimers.SplitTimersConfig;
import com.killer560.hub.splittimers.SplitTimersFeature;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Secrets-found display and run-time tracking - backs the Secrets HUD and Time HUD.
 * <ul>
 * <li>Secrets: read from the TAB LIST (player-info display names), not the sidebar - see
 * {@link #updateSecretsCount()}.
 * <li>Per-room secrets (2026-09-21, killer560: "the hud for how many secrets I have gotten in a room"): the
 * run-total secrets count above, minus whatever it was when {@link LiveMapFeature#currentRoomEntry()} last
 * changed - see {@link #updateRoomSecrets()}. Reuses LiveMap's already-solved room identity instead of
 * re-deriving room boundaries here.
 * </ul>
 * Run-time tracking reuses {@link DungeonState#isInDungeon()}'s already-integrated transition (the
 * same signal {@code PosmsgFeature} already trusts for its own "new run" reset) rather than guessing a
 * new "dungeon completed" chat regex.
 * <p>
 * Reorg 2026-09-21: this class used to also own the mimic/prince/bat KILL party alerts and the manual
 * 270/300 "Send Now" messages. Both moved to {@code ScoreCalculatorFeature}/{@code ScoreCalculatorConfig} -
 * killer560's own "score hud that has all the send messages" puts every bonus-score chat message under
 * Score, and {@code ScoreCalculatorFeature} already ran its own, independent mimic/prince/bat detection for
 * the score formula, so keeping a second copy here just to fire a chat message was the exact kind of
 * overlap this reorg was asked to collapse. See that class for the merged detection+alert code.
 */
public final class DungeonInfoFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-dungeoninfo");
    // Real bug found and fixed (2026-09-14, first real F7 run log): the old SECRETS_PATTERN scanned the
    // SIDEBAR, but Hypixel's dungeon sidebar only has Keys / Time Elapsed / Cleared - "Secrets Found" is a
    // TAB LIST entry, so lastSecretsCount stayed -1 all run. Formats per Odin's DungeonListener
    // (" Secrets Found: 12" team count, " Secrets Found: 45.5%" percentage) and NoammAddons'
    // ScoreCalculation (same lines, colour-coded) - matched on the formatting-stripped string.
    private static final Pattern TAB_SECRETS_COUNT_PATTERN = Pattern.compile("^\\s*Secrets Found: (\\d+)\\s*$");
    private static final Pattern TAB_SECRETS_PERCENT_PATTERN = Pattern.compile("^\\s*Secrets Found: ([\\d.]+)%\\s*$");

    private static boolean wasInDungeon = false;
    private static long runStartAtMs = 0;
    private static long runEndedAtMs = -1;
    private static long runStartGameTime = -1;
    private static long runEndedGameTime = -1;
    // Real bug found and fixed (2026-09-14, first real F7 run log - "Run timer stopped: elapsed=10:32
    // noLag=00:00" on all three runs): the end game time used to be read on the tick inDungeon went
    // false, but by then the dungeon world is already gone (the next instance's getGameTime() is ~250,
    // below runStartGameTime, so it clamped to 0 - or there was no world at all). Now the latest game
    // time is recorded every tick while still in the run's own world, and used as the end time.
    private static long lastInDungeonGameTime = -1;
    private static WeakReference<ClientLevel> runLevel = new WeakReference<>(null);
    private static boolean loggedSecretsLineThisRun = false;
    private static int lastSecretsCount = -1;
    private static String lastSecretsPercent = null;

    // ---- per-room secrets (2026-09-21) ----
    private static RoomEntry lastRoomEntry = null;
    private static int roomBaselineSecrets = -1;
    private static int roomSecretsFound = -1;

    private DungeonInfoFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    private static void tick() {
        boolean inDungeonNow = DungeonState.isInDungeon();
        Minecraft client = Minecraft.getInstance();
        if (inDungeonNow && !wasInDungeon) {
            runStartAtMs = System.currentTimeMillis();
            runStartGameTime = client.level != null ? client.level.getGameTime() : -1;
            lastInDungeonGameTime = runStartGameTime;
            runLevel = new WeakReference<>(client.level);
            runEndedAtMs = -1;
            runEndedGameTime = -1;
            loggedSecretsLineThisRun = false;
            lastSecretsCount = -1;
            lastSecretsPercent = null;
            secretsReadsThisRun = 0;
            loggedSecretsMissThisRun = false;
            lastRoomEntry = null;
            roomBaselineSecrets = -1;
            roomSecretsFound = -1;
            LOGGER.info("[DungeonInfo] Run timer started (floor={}, gameTime={})", DungeonState.getFloor(), runStartGameTime);
        } else if (inDungeonNow && client.level != null) {
            if (runLevel.get() == null && runStartGameTime < 0) {
                // Run started with no world loaded (e.g. sim override) - adopt the first world seen.
                runLevel = new WeakReference<>(client.level);
                runStartGameTime = client.level.getGameTime();
                lastInDungeonGameTime = runStartGameTime;
            } else if (client.level == runLevel.get()) {
                lastInDungeonGameTime = client.level.getGameTime();
            } else if (!loggedLevelMismatchThisRun) {
                loggedLevelMismatchThisRun = true;
                LOGGER.warn("[DungeonInfo] World changed mid-run without a dungeon exit - keeping last game time {} from the run's world",
                        lastInDungeonGameTime);
            }
        } else if (!inDungeonNow && wasInDungeon) {
            runEndedAtMs = System.currentTimeMillis();
            runEndedGameTime = lastInDungeonGameTime;
            loggedLevelMismatchThisRun = false;
            LOGGER.info("[DungeonInfo] Run timer stopped: elapsed={} noLag={} (gameTime {} -> {}) lastSecretsCount={} lastSecretsPercent={}",
                    elapsedTimeText(), elapsedTimeWithoutLagText(), runStartGameTime, runEndedGameTime,
                    lastSecretsCount, lastSecretsPercent);
        }
        wasInDungeon = inDungeonNow;

        DungeonInfoConfig cfg = DungeonInfoConfig.getInstance();
        String gates = "secretsHud=" + cfg.isSecretsHudEnabled() + " timeTracker=" + cfg.isTimeTrackerEnabled()
                + " inDungeon=" + inDungeonNow;
        if (!gates.equals(lastLoggedGates)) {
            LOGGER.info("[DungeonInfo] Gates changed: {}", gates);
            lastLoggedGates = gates;
        }

        if (inDungeonNow && cfg.isSecretsHudEnabled()) {
            updateSecretsCount();
            if (cfg.isShowPerRoomSecrets()) {
                updateRoomSecrets();
            }
        }
    }

    // [DungeonInfo] diagnostics - logging only.
    private static String lastLoggedGates = null;
    private static int secretsReadsThisRun = 0;
    private static boolean loggedSecretsMissThisRun = false;
    private static boolean loggedLevelMismatchThisRun = false;

    /** Reads "Secrets Found" from the tab list - the same player-info display names
     *  {@code DungeonState#logTabClassesIfChanged} already reads for class entries. */
    private static void updateSecretsCount() {
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() == null) {
            return;
        }
        boolean matchedAny = false;
        for (PlayerInfo info : client.getConnection().getListedOnlinePlayers()) {
            Component display = info.getTabListDisplayName();
            if (display == null) {
                continue;
            }
            String raw = display.getString();
            String plain = ChatFormatting.stripFormatting(raw);
            if (plain == null || !plain.contains("Secrets Found")) {
                continue;
            }
            if (!loggedSecretsLineThisRun) {
                loggedSecretsLineThisRun = true;
                LOGGER.info("[DungeonInfo] First tab-list secrets line this run: raw=\"{}\" plain=\"{}\"", raw, plain);
            }
            Matcher count = TAB_SECRETS_COUNT_PATTERN.matcher(plain);
            if (count.matches()) {
                matchedAny = true;
                int value = Integer.parseInt(count.group(1));
                if (value != lastSecretsCount) {
                    LOGGER.info("[DungeonInfo] Secrets count changed: {} -> {}", lastSecretsCount, value);
                    lastSecretsCount = value;
                }
                continue;
            }
            Matcher percent = TAB_SECRETS_PERCENT_PATTERN.matcher(plain);
            if (percent.matches()) {
                matchedAny = true;
                if (!percent.group(1).equals(lastSecretsPercent)) {
                    LOGGER.info("[DungeonInfo] Secrets percent changed: {} -> {}%", lastSecretsPercent, percent.group(1));
                    lastSecretsPercent = percent.group(1);
                }
            }
        }
        if (!matchedAny && lastSecretsCount < 0 && !loggedSecretsMissThisRun && ++secretsReadsThisRun >= 200) {
            // ~10s in a run without ever matching - dump every tab entry once so the real format is visible.
            loggedSecretsMissThisRun = true;
            StringBuilder all = new StringBuilder();
            for (PlayerInfo info : client.getConnection().getListedOnlinePlayers()) {
                Component display = info.getTabListDisplayName();
                if (display != null && !display.getString().isBlank()) {
                    all.append('"').append(display.getString()).append("\" ");
                }
            }
            LOGGER.warn("[DungeonInfo] No tab-list 'Secrets Found' line matched after {} reads this run; tab entries: [{}]",
                    secretsReadsThisRun, all.toString().trim());
        }
    }

    /** Secrets found since the player walked into whichever room {@link LiveMapFeature#currentRoomEntry()}
     *  currently resolves to - a room change is detected by reference (LiveMap keeps one {@link RoomEntry}
     *  instance per grid slot), and the baseline is the run-total secrets count at that moment. Leaves
     *  {@link #roomSecretsFound} at its last value while the room is unresolved (between a world-change
     *  placeholder and LiveMap settling) rather than flashing back to unknown every such tick. */
    private static void updateRoomSecrets() {
        RoomEntry entry = LiveMapFeature.currentRoomEntry();
        if (entry == null) {
            return;
        }
        if (entry != lastRoomEntry) {
            lastRoomEntry = entry;
            roomBaselineSecrets = Math.max(0, lastSecretsCount);
            roomSecretsFound = 0;
            LOGGER.info("[DungeonInfo] Room changed to \"{}\" - per-room secrets baseline set to {}", entry.name, roomBaselineSecrets);
        } else if (lastSecretsCount >= 0) {
            roomSecretsFound = Math.max(0, lastSecretsCount - roomBaselineSecrets);
        }
    }

    /** Client-side elapsed time for the current (or most recently finished) run - real wall-clock
     *  elapsed time, so it includes any lag/freeze along the way. */
    public static String elapsedTimeText() {
        if (runStartAtMs == 0) {
            return "No run yet";
        }
        long end = runEndedAtMs > 0 ? runEndedAtMs : System.currentTimeMillis();
        return formatSeconds((end - runStartAtMs) / 1000);
    }

    /** "Without lag" elapsed time - uses {@code ClientLevel#getGameTime()} (the world's own tick
     *  counter, real vanilla API) instead of wall-clock time. Game time only advances when the server
     *  actually sends a tick update, so a real lag spike/freeze widens the gap between this and
     *  {@link #elapsedTimeText()} while wall-clock time keeps counting regardless - the difference
     *  between the two IS the time lost to lag, which is what "without lag" means here. Only ever reads
     *  game time sampled from the run's own world (see {@link #lastInDungeonGameTime}). */
    public static String elapsedTimeWithoutLagText() {
        if (runStartGameTime < 0) {
            return "No run yet";
        }
        long end = runEndedGameTime >= 0 ? runEndedGameTime : lastInDungeonGameTime;
        long elapsedTicks = Math.max(0, end - runStartGameTime);
        return formatSeconds(elapsedTicks / 20);
    }

    private static String formatSeconds(long totalSeconds) {
        return String.format(Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    public static void sendTime() {
        DungeonInfoConfig cfg = DungeonInfoConfig.getInstance();
        String message = cfg.isSendTimeWithoutLag()
                ? String.format(Locale.US, "Time: %s (%s without lag)", elapsedTimeText(), elapsedTimeWithoutLagText())
                : "Time: " + elapsedTimeText();
        com.killer560.hub.translate.TranslateFeature.sendGenerated(message, "pc");
    }

    /** Secrets HUD - per-run and (optionally) per-room secrets found. Keeps the old "dungeon_info" HUD id
     *  so a saved drag position from before the 2026-09-21 Secrets/Time split carries over. */
    public static final class SecretsHudElement implements HudElement {
        @Override
        public String id() {
            return "dungeon_info";
        }

        @Override
        public String displayName() {
            return "Secrets HUD";
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
            return DungeonInfoConfig.getInstance().isShowPerRoomSecrets() ? 24 : 12;
        }

        @Override
        public boolean isRelevantNow() {
            return DungeonInfoConfig.getInstance().isSecretsHudEnabled() && DungeonState.isInDungeon();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            DungeonInfoConfig cfg = DungeonInfoConfig.getInstance();
            if (!cfg.isSecretsHudEnabled() || !DungeonState.isInDungeon() || HudVisibility.hidesHud()) {
                return;
            }
            int lineY = y;
            String text = lastSecretsCount >= 0 ? ("Secrets: " + lastSecretsCount) : "Secrets: ?";
            if (lastSecretsPercent != null) {
                text += " (" + lastSecretsPercent + "%)";
            }
            graphics.text(Minecraft.getInstance().font, text, x, lineY, 0xFFFFFFFF, false);
            lineY += 12;
            if (cfg.isShowPerRoomSecrets()) {
                String roomText = roomSecretsFound >= 0 ? ("Room: " + roomSecretsFound) : "Room: ?";
                graphics.text(Minecraft.getInstance().font, roomText, x, lineY, 0xFFAAAAAA, false);
            }
        }
    }

    /** Time HUD - run elapsed/no-lag timer, plus (optionally) the Split Timers feature's own "current
     *  segment" readout via its public getters - this HUD does not parse split lines itself. */
    public static final class TimeHudElement implements HudElement {
        @Override
        public String id() {
            return "dungeon_time_hud";
        }

        @Override
        public String displayName() {
            return "Time HUD";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            return 300;
        }

        @Override
        public int width() {
            return 150;
        }

        @Override
        public int height() {
            return currentSplitLine() != null ? 36 : 24;
        }

        @Override
        public boolean isRelevantNow() {
            return DungeonInfoConfig.getInstance().isTimeTrackerEnabled() && DungeonState.isInDungeon();
        }

        /** The Split Timers feature's own current-segment name/elapsed - read via its two public getters
         *  ({@code getCurrentSegmentLabel}/{@code getCurrentSegmentStartedAtMs}), never re-derived from chat
         *  here. Null when the toggle is off, Split Timers itself is disabled, or no segment is running. */
        private static String currentSplitLine() {
            if (!DungeonInfoConfig.getInstance().isShowCurrentSplit() || !SplitTimersConfig.getInstance().isEnabled()) {
                return null;
            }
            String label = SplitTimersFeature.getCurrentSegmentLabel();
            long startedAt = SplitTimersFeature.getCurrentSegmentStartedAtMs();
            if (label == null || startedAt <= 0) {
                return null;
            }
            long elapsedSec = Math.max(0, (System.currentTimeMillis() - startedAt) / 1000);
            return "Split: " + label + " " + formatSeconds(elapsedSec);
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            DungeonInfoConfig cfg = DungeonInfoConfig.getInstance();
            if (!cfg.isTimeTrackerEnabled() || !DungeonState.isInDungeon() || HudVisibility.hidesHud() || runStartAtMs <= 0) {
                return;
            }
            int lineY = y;
            graphics.text(Minecraft.getInstance().font, "Time: " + elapsedTimeText(), x, lineY, 0xFFFFFFFF, false);
            lineY += 12;
            graphics.text(Minecraft.getInstance().font, "No Lag: " + elapsedTimeWithoutLagText(), x, lineY, 0xFFAAAAAA, false);
            lineY += 12;
            String splitLine = currentSplitLine();
            if (splitLine != null) {
                graphics.text(Minecraft.getInstance().font, splitLine, x, lineY, 0xFFAAAAAA, false);
            }
        }
    }
}
