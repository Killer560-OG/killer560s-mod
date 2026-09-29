package com.killer560.hub.dungeoninfo;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.secrets.DungeonState;
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
 * Secrets-found display and run-time tracking - backs the Secrets HUD.
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
 * <p>
 * Reworked 2026-09-27 (killer560: "the secret hud should only be in room secrets collected / total secrets
 * in the room"): the HUD line dropped the run-total/percent text for just "found/total" in the room the
 * player is standing in - see {@link RoomEntry#secrets} for the total. Tracking itself
 * ({@link #updateSecretsCount()}/{@link #updateRoomSecrets()}) no longer waits on the Secrets HUD's own
 * enabled toggle - {@code com.killer560.hub.dungeonalerts.RoomAlerts}' Secrets Done alert reads
 * {@link #roomSecretsFound()} too, independent of whether this HUD is even on.
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
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("DungeonInfoFeature", client -> tick()));
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
        String gates = "secretsHud=" + cfg.isSecretsHudEnabled() + " inDungeon=" + inDungeonNow;
        if (!gates.equals(lastLoggedGates)) {
            LOGGER.info("[DungeonInfo] Gates changed: {}", gates);
            lastLoggedGates = gates;
        }

        // Tracked whenever in a dungeon, not just while the Secrets HUD is on - Dungeon Alerts' Secrets
        // Done alert (roomSecretsFound()) needs this too, independent of this HUD's own toggle.
        if (inDungeonNow) {
            updateSecretsCount();
            updateRoomSecrets();
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
            roomBaselineSecrets = lastSecretsCount;
            roomSecretsFound = 0;
            LOGGER.info("[DungeonInfo] Room changed to \"{}\" - per-room secrets baseline set to {}",
                    entry.name, roomBaselineSecrets);
        }
        // Hypixel's OWN per-room number first.
        //
        // The fallback below subtracts the tab list's run total from a baseline, and that total is the WHOLE
        // TEAM's - the field's own comment says so. So on a five-man floor every secret anyone found anywhere
        // was credited to the room he was standing in: stand still in a 3-secret room while three teammates
        // pop one each and the HUD reads "Secrets: 3/3" for a room he never searched. It also fired the
        // "Secrets Done!" alert, which latches per room for the rest of the run, so the alert was then
        // spent and could never fire correctly for that room.
        //
        // The action bar's "x/y Secrets" IS the per-room count, and LiveMapFeature already parses it. It is
        // only absent before the first secret of a room is found, which is exactly when zero is right anyway.
        int fromActionBar = LiveMapFeature.foundSecretsForRoom(entry.name);
        if (fromActionBar >= 0) {
            roomSecretsFound = fromActionBar;
            return;
        }
        // No action bar line yet. The team-total delta is kept only as a floor of zero rather than a guess,
        // because a wrong number here fires an alert - and "none found yet" is right far more often than the
        // delta was.
        if (roomBaselineSecrets < 0) {
            roomSecretsFound = 0;
        }
    }

    /** Secrets found since the player walked into the room they're currently standing in, or -1 when
     *  unknown (room unresolved, or the tab-list count hasn't been read yet this run). Used by both the
     *  Secrets HUD line and {@code com.killer560.hub.dungeonalerts.RoomAlerts}' Secrets Done alert - see
     *  {@link #updateRoomSecrets()}. */
    public static int roomSecretsFound() {
        return roomSecretsFound;
    }

    /** Client-side elapsed time for the current (or most recently finished) run - real wall-clock
     *  elapsed time, so it includes any lag/freeze along the way. Only used for the run-timer-stopped
     *  log line now that the Time HUD (its only display) is gone (killer560, 2026-09-27: "remove the
     *  time hud those are things that should be in the splits section"). */
    private static String elapsedTimeText() {
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
    private static String elapsedTimeWithoutLagText() {
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

    /** Secrets HUD - secrets found in the room you're standing in, over that room's total. Keeps the old
     *  "dungeon_info" HUD id so a saved drag position from before the 2026-09-21 Secrets/Time split carries
     *  over. */
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
            return 12;
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
            RoomEntry entry = LiveMapFeature.currentRoomEntry();
            String found = roomSecretsFound >= 0 ? String.valueOf(roomSecretsFound) : "?";
            String total = entry != null ? String.valueOf(entry.secrets) : "?";
            graphics.text(Minecraft.getInstance().font, "Secrets: " + found + "/" + total, x, y, 0xFFFFFFFF, false);
        }
    }
}
