package com.killer560.hub.dungeoninfo;

import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.translate.TranslateFeature;
import com.killer560.hub.util.ChatObserver;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Secrets-found display, run-time tracking, and mimic/prince/bat KILL party alerts.
 * <ul>
 * <li>Secrets: read from the TAB LIST (player-info display names), not the sidebar - see
 * {@link #updateSecretsCount()}.
 * <li>Prince/Bat: exact Hypixel system lines {@code A Prince falls. +1 Bonus Score} /
 * {@code A Bat has been slain. +1 Bonus Score} (confirmed in both Odin's {@code Mimic.kt} and
 * NoammAddons' {@code ScoreCalculation.kt}). These are KILL lines, so the alerts are worded as kills.
 * <li>Mimic: Hypixel sends no chat line for a normal mimic kill - Odin and NoammAddons both detect it as a
 * baby {@link Zombie} dying (entity event 3) on floor 6/7 outside the boss. This polls the same signal
 * ({@code isBaby() && isDeadOrDying()} - vanilla's event-3 handler sets health to 0) from the tick loop.
 * </ul>
 * Run-time tracking reuses {@link DungeonState#isInDungeon()}'s already-integrated transition (the
 * same signal {@code PosmsgFeature} already trusts for its own "new run" reset) rather than guessing a
 * new "dungeon completed" chat regex.
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
    // Real bug found and fixed (2026-09-14, first real F7 run log): alerts used to fire on any chat line
    // CONTAINING a user keyword with no dungeon gate - the default "bat" matched every
    // "+20 Kill Combo +15☯ Combat Wisdom" line, and all three could fire in the hub. Now exact full-line
    // matches (Odin's regexes verbatim) and only while DungeonState.isInDungeon().
    private static final Pattern PRINCE_KILLED_PATTERN = Pattern.compile("^A Prince falls\\. \\+1 Bonus Score$");
    private static final Pattern BAT_KILLED_PATTERN = Pattern.compile("^A Bat has been slain\\. \\+1 Bonus Score$");

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
    private static boolean mimicKilledThisRun = false;
    private static boolean princeKilledThisRun = false;
    private static boolean batKilledThisRun = false;

    private DungeonInfoFeature() {
    }

    public static void register() {
        // ChatObserver, not Fabric CHAT/GAME: Odin/NoammAddons/Skyblocker can cancel a server line via
        // ALLOW_GAME and re-add their own copy straight to ChatComponent, which Fabric listeners never see.
        // Triggers here are exact/anchored server-format lines, so this mod's own client-side messages (which
        // ChatObserver also delivers) can't match. Overlay (action bar) lines are not delivered - none needed.
        ChatObserver.subscribe(message -> onChatMessage(message.getString()));
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
            mimicKilledThisRun = false;
            princeKilledThisRun = false;
            batKilledThisRun = false;
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
            LOGGER.info("[DungeonInfo] Run timer stopped: elapsed={} noLag={} (gameTime {} -> {}) lastSecretsCount={} lastSecretsPercent={} mimic={} prince={} bat={}",
                    elapsedTimeText(), elapsedTimeWithoutLagText(), runStartGameTime, runEndedGameTime,
                    lastSecretsCount, lastSecretsPercent, mimicKilledThisRun, princeKilledThisRun, batKilledThisRun);
        }
        wasInDungeon = inDungeonNow;

        DungeonInfoConfig cfg = DungeonInfoConfig.getInstance();
        String gates = "secretsHud=" + cfg.isSecretsHudEnabled() + " timeTracker=" + cfg.isTimeTrackerEnabled()
                + " mimicMsg=" + cfg.isMimicMessageEnabled() + " princeMsg=" + cfg.isPrinceMessageEnabled()
                + " batMsg=" + cfg.isBatMessageEnabled() + " inDungeon=" + inDungeonNow;
        if (!gates.equals(lastLoggedGates)) {
            LOGGER.info("[DungeonInfo] Gates changed: {}", gates);
            lastLoggedGates = gates;
        }

        if (inDungeonNow && cfg.isSecretsHudEnabled()) {
            updateSecretsCount();
        }
        if (inDungeonNow && cfg.isMimicMessageEnabled() && !mimicKilledThisRun) {
            checkMimicKilled(client);
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

    /** Mimic kill - see the class doc. Floor 6/7 only (the only floors with a mimic), and not during the
     *  tracked boss phase (DungeonState only tracks F7/M7's boss; F6's boss has no baby zombies to confuse). */
    private static void checkMimicKilled(Minecraft client) {
        if (client.level == null || DungeonState.isBossPhaseActive() || !isFloor6or7(DungeonState.getFloor())) {
            return;
        }
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity instanceof Zombie zombie && zombie.isBaby() && zombie.isDeadOrDying()) {
                mimicKilledThisRun = true;
                LOGGER.info("[DungeonInfo] Mimic killed (baby zombie id={} dead at {}, floor={}) - sending party message",
                        zombie.getId(), zombie.position(), DungeonState.getFloor());
                TranslateFeature.sendGenerated(DungeonInfoConfig.getInstance().getMimicMessage(), "pc");
                return;
            }
        }
    }

    private static boolean isFloor6or7(String floor) {
        return floor != null && floor.length() >= 2 && (floor.charAt(1) == '6' || floor.charAt(1) == '7');
    }

    private static void onChatMessage(String text) {
        String plain = ChatFormatting.stripFormatting(text);
        if (plain == null) {
            return;
        }
        plain = plain.trim();
        boolean princeLine = PRINCE_KILLED_PATTERN.matcher(plain).matches();
        boolean batLine = !princeLine && BAT_KILLED_PATTERN.matcher(plain).matches();
        if (!princeLine && !batLine) {
            return;
        }
        DungeonInfoConfig cfg = DungeonInfoConfig.getInstance();
        boolean inDungeon = DungeonState.isInDungeon();
        if (princeLine) {
            boolean send = inDungeon && cfg.isPrinceMessageEnabled() && !princeKilledThisRun;
            LOGGER.info("[DungeonInfo] Prince kill line seen (inDungeon={} enabled={} alreadySentThisRun={} -> send={}): \"{}\"",
                    inDungeon, cfg.isPrinceMessageEnabled(), princeKilledThisRun, send, plain);
            if (inDungeon) {
                princeKilledThisRun = true;
            }
            if (send) {
                TranslateFeature.sendGenerated(cfg.getPrinceMessage(), "pc");
            }
        } else {
            boolean send = inDungeon && cfg.isBatMessageEnabled() && !batKilledThisRun;
            LOGGER.info("[DungeonInfo] Bat kill line seen (inDungeon={} enabled={} alreadySentThisRun={} -> send={}): \"{}\"",
                    inDungeon, cfg.isBatMessageEnabled(), batKilledThisRun, send, plain);
            if (inDungeon) {
                batKilledThisRun = true;
            }
            if (send) {
                TranslateFeature.sendGenerated(cfg.getBatMessage(), "pc");
            }
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
        TranslateFeature.sendGenerated(message, "pc");
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
            return 36;
        }

        @Override
        public boolean isRelevantNow() {
            DungeonInfoConfig cfg = DungeonInfoConfig.getInstance();
            return (cfg.isSecretsHudEnabled() || cfg.isTimeTrackerEnabled()) && DungeonState.isInDungeon();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            DungeonInfoConfig cfg = DungeonInfoConfig.getInstance();
            if (HudVisibility.hidesHud()) {
                return;
            }
            int lineY = y;
            if (cfg.isSecretsHudEnabled() && DungeonState.isInDungeon()) {
                String text = lastSecretsCount >= 0 ? ("Secrets: " + lastSecretsCount) : "Secrets: ?";
                if (lastSecretsPercent != null) {
                    text += " (" + lastSecretsPercent + "%)";
                }
                graphics.text(Minecraft.getInstance().font, text, x, lineY, 0xFFFFFFFF, false);
                lineY += 12;
            }
            if (cfg.isTimeTrackerEnabled() && runStartAtMs > 0) {
                graphics.text(Minecraft.getInstance().font, "Time: " + elapsedTimeText(), x, lineY, 0xFFFFFFFF, false);
                lineY += 12;
                graphics.text(Minecraft.getInstance().font, "No Lag: " + elapsedTimeWithoutLagText(), x, lineY, 0xFFAAAAAA, false);
            }
        }
    }
}
