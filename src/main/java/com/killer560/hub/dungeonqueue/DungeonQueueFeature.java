package com.killer560.hub.dungeonqueue;

import com.killer560.hub.leapmenu.PartyTracker;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModChat;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dungeon Queue - re-queues the floor you just played when a run ends, the way NoammAddons' {@code AutoRequeue.kt}
 * does: on the end-of-run summary, check the party, wait the configured delay, re-check leadership, then send
 * {@code /joininstance [MASTER_]CATACOMBS_FLOOR_<N>}. It only sends the command you would type yourself.
 * <ul>
 * <li>Run end: NoammAddons' {@code DungeonListener.runEndRegex} ("[Master Mode] The Catacombs - Floor VII" line of
 * the summary), with "> EXTRA STATS <" as a fallback. Handled once per run.
 * <li>Floor: {@link DungeonState#getFloor()}, falling back to the floor named in the summary line.
 * <li>Instance ids: QUOI's {@code Floors.instance()} ({@code CATACOMBS_ENTRANCE}, {@code CATACOMBS_FLOOR_SEVEN},
 * {@code MASTER_CATACOMBS_FLOOR_SEVEN}).
 * <li>Party: leader from {@link PartyLeaderTracker} (Odin/NoammAddons {@code PartyUtils}); solo = no teammates in
 * {@link PartyTracker}. Unlike NoammAddons there is no "must be 5 players" rule, so solo runs re-queue too.
 * <li>Downtime: NoammAddons' {@code PartyHelper} "!dt" list - any party member saying the keyword during the run
 * (or during the countdown) skips that re-queue.
 * <li>Rate limits: at most one {@code /joininstance} per {@link #MIN_JOIN_INTERVAL_MS}, counting ones typed by hand
 * or sent by other mods; and never within Hypixel's 30s instance-warp cooldown after entering a dungeon (QUOI's
 * {@code WarpCooldown}).
 * </ul>
 */
public final class DungeonQueueFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-dungeonqueue");
    private static final String FEATURE = "Dungeon Queue";

    static final long MIN_JOIN_INTERVAL_MS = 5_000L;
    private static final long WARP_COOLDOWN_MS = 30_000L;

    private static final Pattern RUN_END = Pattern.compile(
            "(?m)^\\s*(Master Mode )?(?:The )?Catacombs - (?:Floor ([IVX]{1,4})|Entrance)\\s*$");
    private static final Pattern EXTRA_STATS = Pattern.compile("(?m)^\\s*> EXTRA STATS <\\s*$");
    private static final Pattern PARTY_CHAT =
            Pattern.compile("^Party > (?:\\[[^]]*?] )?(\\w{1,16})(?: [ቾ⚒])?: ?(.+)$");
    private static final String COMMAND_PREFIXES = "!?.-@#`/";

    private static boolean runEndHandled = false;
    private static String lastFloor;
    private static final Set<String> downtimeRequesters = new LinkedHashSet<>();

    private static long pendingSendAtMs = 0L;
    private static String pendingFloor;
    private static long lastCountdownShown = -1L;
    private static boolean waitingNoticeShown = false;

    private static long lastJoinInstanceMs = 0L;
    private static long lastEnterMs = 0L;
    private static boolean sendingOwnCommand = false;

    private static boolean cancelWasDown = false;
    private static boolean requeueWasDown = false;
    private static Object lastLevel;

    private DungeonQueueFeature() {
    }

    public static void register() {
        PartyLeaderTracker.register();
        ChatObserver.subscribe(DungeonQueueFeature::onChat);
        ClientTickEvents.END_CLIENT_TICK.register(DungeonQueueFeature::tick);
        ClientSendMessageEvents.COMMAND.register(DungeonQueueFeature::onCommandSent);
    }

    // ---------------------------------------------------------------------------------------------- chat

    private static void onChat(Component message) {
        String plain = ChatObserver.strip(message);

        if (PartyLeaderTracker.DUNGEON_ENTER.matcher(plain).find()) {
            lastEnterMs = System.currentTimeMillis();
            runEndHandled = false;
            downtimeRequesters.clear();
            if (pendingSendAtMs != 0L) {
                cancelPending();
                ModChat.send(FEATURE, ModChat.text("Entered a new run - re-queue cancelled."));
            }
            return;
        }

        DungeonQueueConfig cfg = DungeonQueueConfig.getInstance();
        if (!cfg.isEnabled()) {
            return;
        }

        Matcher party = PARTY_CHAT.matcher(plain.trim());
        if (party.matches()) {
            onPartyChat(cfg, party.group(1), party.group(2));
            return;
        }

        Matcher end = RUN_END.matcher(plain);
        boolean endLine = end.find();
        if (!endLine && !EXTRA_STATS.matcher(plain).find()) {
            return;
        }
        if (runEndHandled) {
            return;
        }
        runEndHandled = true;
        String summaryFloor = endLine ? floorFromSummary(end.group(1) != null, end.group(2)) : null;
        onRunEnded(cfg, summaryFloor);
    }

    private static void onPartyChat(DungeonQueueConfig cfg, String sender, String text) {
        if (!cfg.isDowntimeCheck() || !isDowntimeKeyword(cfg.getDtKeyword(), text)) {
            return;
        }
        boolean duringRun = DungeonState.isInDungeon() || pendingSendAtMs != 0L;
        if (!duringRun) {
            return;
        }
        boolean first = downtimeRequesters.add(sender);
        LOGGER.info("[DungeonQueue] Downtime requested by {} (\"{}\")", sender, text);
        if (pendingSendAtMs != 0L) {
            cancelPending();
            ModChat.send(FEATURE, ModChat.value(sender), ModChat.text(" asked for downtime - re-queue cancelled."));
            downtimeRequesters.clear();
        } else if (first) {
            ModChat.send(FEATURE, ModChat.value(sender), ModChat.text(" asked for downtime - won't re-queue after this run."));
        }
    }

    /** First word of {@code text}, ignoring a leading "!"/"."/etc, equals the keyword (case-insensitive). */
    static boolean isDowntimeKeyword(String keyword, String text) {
        String key = stripCommandPrefix(keyword == null ? "" : keyword.trim());
        if (key.isEmpty() || text == null) {
            return false;
        }
        String body = stripCommandPrefix(text.trim());
        int space = body.indexOf(' ');
        String firstWord = space < 0 ? body : body.substring(0, space);
        return firstWord.equalsIgnoreCase(key);
    }

    private static String stripCommandPrefix(String s) {
        return !s.isEmpty() && COMMAND_PREFIXES.indexOf(s.charAt(0)) >= 0 ? s.substring(1) : s;
    }

    private static void onRunEnded(DungeonQueueConfig cfg, String summaryFloor) {
        String floor = DungeonState.getFloor();
        if (floor == null) {
            floor = summaryFloor != null ? summaryFloor : lastFloor;
        }
        LOGGER.info("[DungeonQueue] Run ended: floor={} (state={}, summary={}) leader={} teammates={} downtime={}",
                floor, DungeonState.getFloor(), summaryFloor, PartyLeaderTracker.getLeader(),
                PartyTracker.teammates().size(), downtimeRequesters);
        if (cfg.isDowntimeCheck() && !downtimeRequesters.isEmpty()) {
            ModChat.send(FEATURE, ModChat.text("Not re-queueing - downtime requested by "),
                    ModChat.value(String.join(", ", downtimeRequesters)), ModChat.text("."));
            downtimeRequesters.clear();
            return;
        }
        downtimeRequesters.clear();
        if (instanceId(floor) == null) {
            ModChat.send(FEATURE, ModChat.bad("Couldn't tell which floor you played - not re-queueing."));
            return;
        }
        if (cfg.isLeaderCheck() && !isLeaderOrSolo()) {
            ModChat.send(FEATURE, ModChat.text("Not re-queueing - you aren't the party leader."));
            return;
        }
        lastFloor = floor;
        pendingFloor = floor;
        pendingSendAtMs = System.currentTimeMillis() + cfg.getDelaySeconds() * 1000L;
        lastCountdownShown = -1L;
        waitingNoticeShown = false;
        if (cfg.getCancelKeyCode() < 0) {
            ModChat.send(FEATURE, ModChat.text("Re-queueing "), ModChat.value(floor), ModChat.text(" in "),
                    ModChat.value(cfg.getDelaySeconds() + "s"), ModChat.text("."));
        } else {
            ModChat.send(FEATURE, ModChat.text("Re-queueing "), ModChat.value(floor), ModChat.text(" in "),
                    ModChat.value(cfg.getDelaySeconds() + "s"), ModChat.text(" - press "),
                    ModChat.value(keyName(cfg.getCancelKeyCode())), ModChat.text(" to cancel."));
        }
    }

    // ---------------------------------------------------------------------------------------------- tick

    private static void tick(Minecraft client) {
        if (client.player == null || client.level == null) {
            if (pendingSendAtMs != 0L) {
                LOGGER.info("[DungeonQueue] Disconnected - pending re-queue dropped.");
            }
            cancelPending();
            cancelWasDown = requeueWasDown = false;
            lastLevel = null;
            return;
        }
        if (client.level != lastLevel) {
            lastLevel = client.level;
            if (pendingSendAtMs == 0L) {
                runEndHandled = false;
                downtimeRequesters.clear();
            }
        }
        String floor = DungeonState.getFloor();
        if (floor != null) {
            lastFloor = floor;
        }

        DungeonQueueConfig cfg = DungeonQueueConfig.getInstance();
        if (!cfg.isEnabled()) {
            cancelPending();
            cancelWasDown = requeueWasDown = false;
            return;
        }

        pollKeys(client, cfg);
        tickPending(client, cfg);
    }

    private static void pollKeys(Minecraft client, DungeonQueueConfig cfg) {
        boolean keysAllowed = client.screen == null || client.screen instanceof AbstractContainerScreen<?>;
        boolean cancelDown = keysAllowed && isDown(client, cfg.getCancelKeyCode());
        boolean requeueDown = keysAllowed && isDown(client, cfg.getRequeueKeyCode());

        if (cancelDown && !cancelWasDown && pendingSendAtMs != 0L) {
            cancelPending();
            client.gui.setOverlayMessage(Component.empty(), false);
            ModChat.send(FEATURE, ModChat.text("Re-queue cancelled."));
        }
        if (requeueDown && !requeueWasDown) {
            requeueNow();
        }
        cancelWasDown = cancelDown;
        requeueWasDown = requeueDown;
    }

    private static boolean isDown(Minecraft client, int keyCode) {
        return keyCode >= 0 && InputConstants.isKeyDown(client.getWindow(), keyCode);
    }

    /** Requeue Key: send right away for the current / last played floor (still rate limited). */
    private static void requeueNow() {
        String floor = DungeonState.getFloor() != null ? DungeonState.getFloor() : lastFloor;
        if (instanceId(floor) == null) {
            ModChat.send(FEATURE, ModChat.text("No floor played yet this session."));
            return;
        }
        long wait = msUntilAllowed(System.currentTimeMillis());
        if (wait > 0L) {
            ModChat.send(FEATURE, ModChat.text("Hypixel cooldown - try again in "),
                    ModChat.value(((wait + 999L) / 1000L) + "s"), ModChat.text("."));
            return;
        }
        cancelPending();
        send(floor);
    }

    private static void tickPending(Minecraft client, DungeonQueueConfig cfg) {
        if (pendingSendAtMs == 0L) {
            return;
        }
        long now = System.currentTimeMillis();
        long remaining = pendingSendAtMs - now;
        if (remaining > 0L) {
            long seconds = (remaining + 999L) / 1000L;
            if (seconds != lastCountdownShown) {
                lastCountdownShown = seconds;
                client.gui.setOverlayMessage(ModChat.line(FEATURE, ModChat.text("Re-queueing "),
                        ModChat.value(pendingFloor), ModChat.text(" in "), ModChat.value(seconds + "s")), false);
            }
            return;
        }
        long wait = msUntilAllowed(now);
        if (wait > 0L) {
            if (!waitingNoticeShown) {
                waitingNoticeShown = true;
                ModChat.send(FEATURE, ModChat.text("Waiting "), ModChat.value(((wait + 999L) / 1000L) + "s"),
                        ModChat.text(" for Hypixel's instance cooldown..."));
            }
            return;
        }
        if (cfg.isLeaderCheck() && !isLeaderOrSolo()) {
            cancelPending();
            ModChat.send(FEATURE, ModChat.text("Not re-queueing - you aren't the party leader."));
            return;
        }
        String floor = pendingFloor;
        cancelPending();
        send(floor);
    }

    private static void send(String floor) {
        Minecraft client = Minecraft.getInstance();
        String id = instanceId(floor);
        if (client.player == null || id == null) {
            return;
        }
        lastJoinInstanceMs = System.currentTimeMillis();
        LOGGER.info("[DungeonQueue] Sending /joininstance {}", id);
        sendingOwnCommand = true;
        try {
            client.player.connection.sendCommand("joininstance " + id);
        } finally {
            sendingOwnCommand = false;
        }
        client.gui.setOverlayMessage(Component.empty(), false);
        ModChat.send(FEATURE, ModChat.text("Re-queued "), ModChat.value(floor), ModChat.text("."));
    }

    /** Any /joininstance - typed by hand or sent by another mod - counts toward the rate limit, and replaces a
     *  pending auto re-queue instead of doubling it up. */
    private static void onCommandSent(String command) {
        if (sendingOwnCommand || command == null) {
            return;
        }
        String trimmed = command.trim();
        int space = trimmed.indexOf(' ');
        String name = space < 0 ? trimmed : trimmed.substring(0, space);
        if (!name.equalsIgnoreCase("joininstance")) {
            return;
        }
        lastJoinInstanceMs = System.currentTimeMillis();
        if (pendingSendAtMs != 0L) {
            cancelPending();
            ModChat.send(FEATURE, ModChat.text("/joininstance already sent - auto re-queue skipped."));
        }
    }

    private static long msUntilAllowed(long now) {
        long rate = lastJoinInstanceMs + MIN_JOIN_INTERVAL_MS - now;
        long warp = lastEnterMs == 0L ? 0L : lastEnterMs + WARP_COOLDOWN_MS - now;
        return Math.max(0L, Math.max(rate, warp));
    }

    private static void cancelPending() {
        pendingSendAtMs = 0L;
        pendingFloor = null;
        lastCountdownShown = -1L;
        waitingNoticeShown = false;
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private static boolean isLeaderOrSolo() {
        return PartyLeaderTracker.isSelfLeader() || PartyTracker.teammates().isEmpty();
    }

    /** "F7" -> CATACOMBS_FLOOR_SEVEN, "M3" -> MASTER_CATACOMBS_FLOOR_THREE, "E"/"F0" -> CATACOMBS_ENTRANCE. */
    static String instanceId(String floor) {
        if (floor == null) {
            return null;
        }
        String f = floor.trim().toUpperCase(Locale.US);
        if (f.equals("E") || f.equals("F0") || f.equals("ENTRANCE")) {
            return "CATACOMBS_ENTRANCE";
        }
        if (f.length() != 2 || (f.charAt(0) != 'F' && f.charAt(0) != 'M')) {
            return null;
        }
        int n = f.charAt(1) - '0';
        if (n < 1 || n > 7) {
            return null;
        }
        String[] names = {"ONE", "TWO", "THREE", "FOUR", "FIVE", "SIX", "SEVEN"};
        return (f.charAt(0) == 'M' ? "MASTER_" : "") + "CATACOMBS_FLOOR_" + names[n - 1];
    }

    private static String floorFromSummary(boolean master, String roman) {
        if (roman == null) {
            return master ? null : "E";
        }
        int n = switch (roman) {
            case "I" -> 1;
            case "II" -> 2;
            case "III" -> 3;
            case "IV" -> 4;
            case "V" -> 5;
            case "VI" -> 6;
            case "VII" -> 7;
            default -> 0;
        };
        return n == 0 ? null : (master ? "M" : "F") + n;
    }

    public static String keyName(int keyCode) {
        return keyCode < 0 ? "Not Set" : InputConstants.Type.KEYSYM.getOrCreate(keyCode).getDisplayName().getString();
    }
}
