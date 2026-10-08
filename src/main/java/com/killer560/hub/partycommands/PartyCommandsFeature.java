package com.killer560.hub.partycommands;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.dungeonqueue.DungeonQueueFeature;
import com.killer560.hub.leapmenu.PartyTracker;
import com.killer560.hub.partycommands.PartyCommandsConfig.Channel;
import com.killer560.hub.partycommands.PartyCommandsConfig.Command;
import com.killer560.hub.partycommands.PartyCommandsConfig.Kind;
import com.killer560.hub.relay.RelayClient;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ServerCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Odin's party commands plus its informational "!" replies, in one feature: someone types "!warp" or "!coords" in a
 * chat you allow, and your client runs the party command or answers. Ported from OdinLegacy's
 * {@code src/main/kotlin/me/odinmain/features/impl/skyblock/ChatCommands.kt} (github.com/odtheking/OdinLegacy) -
 * behaviour, trigger words and reply shapes, not its code. {@link CommandChatListener} is the one chat intake.
 * <p>
 * <b>One list, chosen chats, 2026-10-08</b> (see {@link PartyCommandsConfig}'s class doc for his words): the old
 * "destructive" switch, "Confirm Invites", the joke command and the separate informational list are gone. What is left:
 * <ul>
 * <li><b>Which chats</b> may trigger a command ({@link Channel}: party, guild, all chat, Mod Chat, private, co-op).
 *     A reply goes back into the chat it was asked in.</li>
 * <li><b>The teammate gate</b> - the one rule killer560 asked to keep (2026-09-21): a {@link Kind#PARTY} command (it
 *     changes the party or the run) only runs for someone actually in his party or his current dungeon run, whatever
 *     chat it came from ({@link #isTeammate}). The single exception is Odin's own: a DM'd {@code !invite} invites the
 *     person who sent it, who by definition is not on the team yet.</li>
 * <li><b>A reply delay</b> ({@link PartyCommandsConfig#getReplyDelayMs}, 200 ms by default): every reply and every
 *     party command is scheduled that long after the line that asked for it and sent from the client tick - never from
 *     inside the chat packet's handler. Hypixel answered "You are sending commands too fast" to a command sent in the
 *     same instant (killer560: "Like .2 seconds is all it needs").</li>
 * <li><b>Real lines only.</b> Hypixel chat comes from Fabric's receive events (server packets only, never a line a mod
 *     drew locally); Mod Chat lines come from the relay, whose sender name is stamped from a Mojang-verified login.</li>
 * <li><b>Rate limited</b> per sender and overall ({@link #GLOBAL_MIN_GAP_MS} and friends), so nobody can make your
 *     account flood Hypixel and get muted or kicked.</li>
 * <li><b>Everything a party command does is printed locally</b> through {@link ModChat}.</li>
 * </ul>
 * Argument names are re-validated against {@link #NAME_PATTERN} before they are ever concatenated into a command, so no
 * chat text can smuggle extra arguments into one. {@code !reinv} (kick, then invite back 5 s later) is killer560's own
 * addition (2026-09-16), see {@link #reinvite}.
 */
public final class PartyCommandsFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-partycommands");

    /** The one shape a Minecraft name can have - every argument must match before it is put in a command. */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    /** Dungeon tab list entry, same regex as {@code leapmenu.PartyTracker} / {@code fastleap.Teammates}. */
    private static final Pattern TAB_REGEX = Pattern.compile("^\\[\\d+] (?:\\[[^]]+] )*([A-Za-z0-9_]{1,16}) .*\\((\\w+)(?: (\\w+))?\\)$");
    /** "!f1".."!f7", "!m1".."!m7", "!t1".."!t5" - Odin's instance-queue triggers. */
    private static final Pattern FLOOR_PATTERN = Pattern.compile("^([fmt])([1-7])$");
    private static final Pattern END_OF_RUN = Pattern.compile("^ *> EXTRA STATS < *$");
    /** A "!" line's body is short; anything longer is not a command and is never split or parsed. */
    private static final int MAX_BODY_LENGTH = 256;

    private static final String[] FLOOR_WORDS = {"one", "two", "three", "four", "five", "six", "seven"};
    private static final String[] KUUDRA_TIERS = {"normal", "hot", "burning", "fiery", "infernal"};

    // Rate limits (Odin has none at all).
    private static final long GLOBAL_MIN_GAP_MS = 2_000L;
    private static final long PER_SENDER_MIN_GAP_MS = 8_000L;
    private static final long WINDOW_MS = 60_000L;
    private static final int GLOBAL_MAX_PER_WINDOW = 6;
    private static final int PER_SENDER_MAX_PER_WINDOW = 3;
    /** A dropped command only ever reports itself this often, so the rejection can't become the spam. */
    private static final long REJECT_LOG_GAP_MS = 15_000L;

    /** {@code !reinv}: how long to leave the player out before inviting them back. */
    private static final long REINVITE_DELAY_MS = 5_000L;
    private static final String REINVITE_KEY = "reinvite:";

    private static final Deque<Long> GLOBAL_TIMES = new ArrayDeque<>();
    private static final Map<String, Deque<Long>> SENDER_TIMES = new HashMap<>();
    private static long lastGlobalAtMs = 0L;
    private static long lastRejectLogAtMs = 0L;

    /**
     * Pending delayed sends: every reply and command (the reply delay), warp-then-transfer, end-of-run downtime, and
     * {@code !reinv}'s 5 s gap. Run from the client tick, never a sleep. {@code key} names a cancellable entry;
     * {@code guard} is re-checked when the entry comes due and a false answer drops it, so a queued command can never
     * fire into a party, world or run that isn't the one it was queued for.
     */
    private record Pending(long dueAtMs, String key, BooleanSupplier guard, Runnable action) {
    }

    private static final List<Pending> PENDING = new ArrayList<>();
    /** name -> reason, from {@code !dt}; announced at the end of the run. */
    private static final Map<String, String> DOWNTIME = new LinkedHashMap<>();
    private static boolean registered = false;

    private PartyCommandsFeature() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        CommandChatListener.register();
        InfoReplies.register();
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("PartyCommandsFeature", client -> runPending()));
    }

    /**
     * Every real server chat line, before channel matching - only called while the feature is enabled. Keeps
     * party-leader tracking and the end-of-run downtime payoff on the same real-server-only intake.
     */
    static void onServerLine(String plain) {
        if (plain == null || plain.isEmpty()) {
            return;
        }
        PartyLeaderTracker.onServerLine(plain);
        if (PartyLeaderTracker.clearsParty(plain)) {
            cancelReinvites("you're no longer in that party");
        }
        if (!DOWNTIME.isEmpty() && END_OF_RUN.matcher(plain).matches()) {
            // Odin: runIn(30) after EXTRA STATS, and only YOUR OWN reason is announced in party chat.
            schedule(1_500L, PartyCommandsFeature::announceDowntime);
        }
    }

    /**
     * A Mod Chat line from the relay ({@code ModChatFeature}'s listener, already on the client thread). {@code from}
     * is the relay's verified sender name. Never throws: the relay's listener path must not break on a bad line.
     */
    public static void onModChat(String from, String message) {
        try {
            if (message == null || !message.startsWith("!") || !PartyCommandsConfig.getInstance().isEnabled()) {
                return;
            }
            handle(from, message.substring(1).trim(), Channel.MOD_CHAT);
        } catch (RuntimeException e) {
            LOGGER.error("[PartyCommands] Mod Chat line from {} failed", from, e);
        }
    }

    /**
     * A "!" message from {@code sender} in {@code channel}. {@code body} is the text after the "!".
     *
     * @return true when this was a command (handled, or deliberately dropped by a gate after matching).
     */
    public static boolean handle(String sender, String body, Channel channel) {
        PartyCommandsConfig cfg = PartyCommandsConfig.getInstance();
        if (!cfg.isEnabled() || channel == null || body == null || body.isBlank() || body.length() > MAX_BODY_LENGTH) {
            return false;
        }
        if (!validName(sender)) {
            return false;
        }
        if (!cfg.isChannelOn(channel)) {
            return false;
        }
        String[] words = body.trim().split("\\s+", 3);
        String word = words[0].toLowerCase(Locale.US);
        String arg = words.length > 1 ? words[1] : null;

        Command command = commandFor(word);
        if (command == null || !cfg.isOn(command)) {
            return false;
        }
        // THE gate (killer560, 2026-09-21: "the one thing to keep"): anything that changes the party or the run only
        // for someone actually on the team, from whichever chat. A DM'd !invite invites the sender, Odin's one
        // exception, since inviting them is how they would become a teammate.
        boolean dmInvite = command == Command.INVITE && channel == Channel.PRIVATE;
        if (command.kind() == Kind.PARTY && !dmInvite && !isTeammate(sender)) {
            LOGGER.info("[PartyCommands] Ignored \"!{}\" from {} in {} - not a party/dungeon teammate", word, sender, channel);
            return false;
        }
        if (needsLeader(command) && PartyLeaderTracker.knownNotLeader()) {
            LOGGER.info("[PartyCommands] Ignored \"!{}\" from {} - you are not the party leader", word, sender);
            return true;
        }
        if (!allowRate(sender)) {
            return true;
        }
        long received = System.currentTimeMillis();
        // Everything a command does goes out from the client tick after the reply delay (see the class doc). The
        // world it was asked in must still be the one it answers into.
        ClientLevel level = Minecraft.getInstance().level;
        schedule(cfg.getReplyDelayMs(), null, () -> Minecraft.getInstance().level == level, () -> {
            try {
                run(command, word, sender, arg, body, channel, received);
            } catch (RuntimeException e) {
                LOGGER.error("[PartyCommands] \"!{}\" from {} failed", word, sender, e);
            }
        });
        return true;
    }

    /** The command a "!" word names, or null. Which chat it came from never changes the answer. */
    static Command commandFor(String word) {
        if (word == null) {
            return null;
        }
        if (FLOOR_PATTERN.matcher(word).matches()) {
            return instanceFor(word) == null ? null : Command.QUEUE_INSTANCE;
        }
        for (Command c : Command.values()) {
            if (c == Command.QUEUE_INSTANCE) {
                continue;   // its "f1" trigger is the label only; the floor pattern above answers for it
            }
            for (String trigger : c.triggers()) {
                if (trigger.equals(word)) {
                    return c;
                }
            }
        }
        return null;
    }

    /** Commands Hypixel only lets the party leader run - Odin gates these on {@code PartyUtils.isLeader()}. */
    static boolean needsLeader(Command command) {
        return switch (command) {
            case WARP, WARP_TRANSFER, ALL_INVITE, TRANSFER, KICK, KICK_OFFLINE, REINVITE, DEMOTE, PROMOTE, QUEUE_INSTANCE -> true;
            default -> false;
        };
    }

    private static void run(Command command, String word, String sender, String arg, String body, Channel channel,
                            long receivedAtMs) {
        switch (command) {
            case HELP -> reply("Commands: " + enabledList(), sender, channel);
            case WARP -> execute(sender, "p warp", "warped the party");
            case WARP_TRANSFER -> {
                execute(sender, "p warp", "warped the party");
                // Odin: runIn(12) ticks, then transfer to the person who asked; guarded like !reinv.
                schedule(700L, "warp-transfer", () -> isTeammate(sender), () -> {
                    toServer("p transfer " + sender);
                    log(sender, "took the party (warp + transfer)");
                });
            }
            case ALL_INVITE -> execute(sender, "p settings allinvite", "toggled all-invite");
            case TRANSFER -> {
                // "!pt" alone takes the party; "!pt <name>" must name a member.
                String target = arg == null ? sender : matchMember(arg);
                if (target == null) {
                    String shown = arg.length() > 32 ? arg.substring(0, 32) + "..." : arg;
                    ModChat.send("Party Commands", ModChat.bad("Not transferring"), ModChat.text(" - "),
                            ModChat.value(shown), ModChat.text(" (from "), ModChat.value(sender),
                            ModChat.dim(") is not a party member."));
                    return;
                }
                execute(sender, "p transfer " + target, "transferred the party to " + target);
            }
            case KICK -> {
                String target = validName(arg) ? firstNonNull(matchMember(arg), arg) : null;
                if (target == null) {
                    return;
                }
                execute(sender, "p kick " + target, "kicked " + target);
            }
            case KICK_OFFLINE -> execute(sender, "p kickoffline", "kicked the offline members");
            case REINVITE -> reinvite(sender);
            case DEMOTE -> execute(sender, "p demote " + sender, "demoted themself");
            case PROMOTE -> execute(sender, "p promote " + sender, "promoted themself");
            case INVITE -> {
                // Party/guild/all/Mod Chat: invites the named player. A DM: invites whoever sent it (Odin).
                String target = channel == Channel.PRIVATE ? sender : arg;
                if (validName(target)) {
                    execute(sender, "p invite " + target, "invited " + target);
                }
            }
            case BOOP -> {
                if (validName(arg)) {
                    execute(sender, "boop " + arg, "booped " + arg);
                }
            }
            case DOWNTIME -> {
                downtime(sender, body);
                // Odin: "!dt" also skips the pending Auto Requeue for this run.
                DungeonQueueFeature.skipRequeueForThisRun();
            }
            case UN_DOWNTIME -> unDowntime(sender);
            case QUEUE_INSTANCE -> {
                String instance = instanceFor(word);
                if (instance != null) {
                    execute(sender, "joininstance " + instance, "queued " + word.toUpperCase(Locale.US));
                }
            }
            default -> {
                String text = InfoReplies.reply(command);
                if (text != null) {
                    reply(text, sender, channel);
                }
            }
        }
        LOGGER.info("[PartyCommands] \"!{}\" from {} answered {} ms after it arrived", word, sender,
                System.currentTimeMillis() - receivedAtMs);
    }

    // ------------------------------------------------------------------ teammate gate

    /**
     * Is {@code name} really on your team right now? Yourself; {@link PartyTracker#teammates()} (Hypixel's party
     * list, joins, leaves, party chat); or the live dungeon tab list. Anyone else - guild members, DMs, all chat,
     * Mod Chat users outside the party - is not.
     */
    static boolean isTeammate(String name) {
        if (name == null || !validName(name)) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        String self = client.player == null ? null : client.player.getGameProfile().name();
        if (self != null && self.equalsIgnoreCase(name)) {
            return true;
        }
        for (String member : PartyTracker.teammates()) {
            if (member.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return isInDungeonTabList(client, name);
    }

    private static boolean isInDungeonTabList(Minecraft client, String name) {
        if (client.getConnection() == null || !DungeonState.isInDungeon()) {
            return false;
        }
        for (PlayerInfo info : client.getConnection().getListedOnlinePlayers()) {
            String plain = com.killer560.hub.util.TabText.plain(info);
            if (plain == null) {
                continue;
            }
            Matcher m = TAB_REGEX.matcher(plain.trim());
            if (m.matches() && m.group(1).equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** Odin's {@code findPartyMember}: exact name first, then a unique prefix match, else null. */
    private static String matchMember(String arg) {
        if (!validName(arg)) {
            return null;
        }
        String lower = arg.toLowerCase(Locale.US);
        List<String> members = PartyTracker.teammates();
        for (String member : members) {
            if (member.toLowerCase(Locale.US).equals(lower)) {
                return member;
            }
        }
        String found = null;
        for (String member : members) {
            if (member.toLowerCase(Locale.US).startsWith(lower)) {
                if (found != null) {
                    return null;
                }
                found = member;
            }
        }
        return found;
    }

    private static boolean validName(String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    // ------------------------------------------------------------------ rate limiting

    private static boolean allowRate(String sender) {
        long now = System.currentTimeMillis();
        String key = sender.toLowerCase(Locale.US);
        Deque<Long> mine = SENDER_TIMES.computeIfAbsent(key, k -> new ArrayDeque<>());
        prune(GLOBAL_TIMES, now);
        prune(mine, now);
        String reason = null;
        if (now - lastGlobalAtMs < GLOBAL_MIN_GAP_MS) {
            reason = "too soon after the last one";
        } else if (GLOBAL_TIMES.size() >= GLOBAL_MAX_PER_WINDOW) {
            reason = "too many commands this minute";
        } else if (!mine.isEmpty() && now - mine.peekLast() < PER_SENDER_MIN_GAP_MS) {
            reason = "they just ran one";
        } else if (mine.size() >= PER_SENDER_MAX_PER_WINDOW) {
            reason = "they've hit their limit for this minute";
        }
        if (reason != null) {
            if (now - lastRejectLogAtMs >= REJECT_LOG_GAP_MS) {
                lastRejectLogAtMs = now;
                ModChat.send("Party Commands", ModChat.text("Ignoring "), ModChat.value(sender),
                        ModChat.text(" - "), ModChat.dim(reason + "."));
            }
            LOGGER.info("[PartyCommands] Rate limited {} ({})", sender, reason);
            return false;
        }
        lastGlobalAtMs = now;
        GLOBAL_TIMES.addLast(now);
        mine.addLast(now);
        if (SENDER_TIMES.size() > 32) {
            SENDER_TIMES.entrySet().removeIf(e -> e.getValue().isEmpty());
        }
        return true;
    }

    private static void prune(Deque<Long> times, long now) {
        while (!times.isEmpty() && now - times.peekFirst() > WINDOW_MS) {
            times.removeFirst();
        }
    }

    // ------------------------------------------------------------------ downtime (Odin's !dt)

    private static void downtime(String sender, String body) {
        String trimmed = body.trim();
        String reason = trimmed.contains(" ") ? trimmed.substring(trimmed.indexOf(' ') + 1).trim() : "";
        if (reason.isEmpty()) {
            reason = "No reason given";
        }
        if (reason.length() > 64) {
            reason = reason.substring(0, 64);
        }
        if (DOWNTIME.putIfAbsent(sender, reason) != null) {
            ModChat.send("Party Commands", ModChat.value(sender), ModChat.text(" already has a reminder!"));
            return;
        }
        ModChat.send("Party Commands", ModChat.good("Reminder set for the end of the run "),
                ModChat.dim("(" + sender + ": " + reason + ")"));
    }

    private static void unDowntime(String sender) {
        if (DOWNTIME.remove(sender) == null) {
            ModChat.send("Party Commands", ModChat.value(sender), ModChat.text(" has no reminder set!"));
        } else {
            ModChat.send("Party Commands", ModChat.good("Reminder removed! "), ModChat.dim("(" + sender + ")"));
        }
    }

    private static void announceDowntime() {
        if (DOWNTIME.isEmpty()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        String self = client.player == null ? null : client.player.getGameProfile().name();
        StringBuilder all = new StringBuilder();
        for (Map.Entry<String, String> e : DOWNTIME.entrySet()) {
            if (!all.isEmpty()) {
                all.append(", ");
            }
            all.append(e.getKey()).append(": ").append(e.getValue());
        }
        // Odin announces only your OWN downtime in party chat; everyone else's is a local reminder.
        if (self != null && DOWNTIME.containsKey(self)) {
            toServer("pc Downtime needed: " + DOWNTIME.get(self));
        }
        ModChat.send("Party Commands", ModChat.text("DT Reasons: "), ModChat.value(all.toString()));
        DOWNTIME.clear();
    }

    // ------------------------------------------------------------------ reinvite (!reinv)

    /**
     * {@code !reinv} / {@code !reinvite}: kick the teammate who asked, then invite them back {@link #REINVITE_DELAY_MS}
     * later. The gap is a scheduled tick entry, re-validated when due ({@link #reinviteStillValid}) and cancelled from
     * {@link #onServerLine} the moment this client stops being in that party.
     */
    private static void reinvite(String sender) {
        String key = REINVITE_KEY + sender.toLowerCase(Locale.US);
        if (isPending(key)) {
            LOGGER.info("[PartyCommands] \"!reinv\" from {} ignored - a re-invite is already pending", sender);
            return;
        }
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        boolean wasInDungeon = DungeonState.isInDungeon();
        execute(sender, "p kick " + sender,
                "asked to be re-invited - kicked, inviting back in " + (REINVITE_DELAY_MS / 1000) + "s");
        schedule(REINVITE_DELAY_MS, key, () -> reinviteStillValid(level, wasInDungeon), () -> {
            toServer("p invite " + sender);
            log(sender, "re-invited");
        });
    }

    private static boolean reinviteStillValid(ClientLevel level, boolean wasInDungeon) {
        if (!PartyCommandsConfig.getInstance().allows(Command.REINVITE)) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.getConnection() == null || client.level != level) {
            return false;
        }
        if (wasInDungeon && !DungeonState.isInDungeon()) {
            return false;
        }
        return !PartyLeaderTracker.knownNotLeader();
    }

    // ------------------------------------------------------------------ plumbing

    /** Odin's {@code /od <floor>}: "f7" -> catacombs_floor_seven, "m7" -> master_catacombs_floor_seven,
     *  "t5" -> kuudra_infernal. Returns null for a tier that doesn't exist (there is no T6/T7). */
    static String instanceFor(String word) {
        Matcher m = FLOOR_PATTERN.matcher(word);
        if (!m.matches()) {
            return null;
        }
        int n = m.group(2).charAt(0) - '0';
        return switch (m.group(1)) {
            case "f" -> "catacombs_floor_" + FLOOR_WORDS[n - 1];
            case "m" -> "master_catacombs_floor_" + FLOOR_WORDS[n - 1];
            case "t" -> n <= KUUDRA_TIERS.length ? "kuudra_" + KUUDRA_TIERS[n - 1] : null;
            default -> null;
        };
    }

    private static void execute(String sender, String serverCommand, String description) {
        toServer(serverCommand);
        log(sender, description);
    }

    /** Local-only record of what someone just made this client do - never silent, by design. */
    private static void log(String sender, String description) {
        ModChat.send("Party Commands", ModChat.value(sender), ModChat.text(" " + description), ModChat.dim("."));
    }

    /** Hypixel's commands go below the client dispatcher (util/ServerCommands), never back into this mod's own. */
    private static void toServer(String command) {
        if (!ServerCommands.toServer(command)) {
            LOGGER.info("[PartyCommands] Not sent (no connection): /{}", command);
        }
    }

    /** Sends {@code message} back into the chat the command came from. */
    private static void reply(String message, String sender, Channel channel) {
        switch (channel) {
            case PRIVATE -> toServer("msg " + sender + " " + message);
            case MOD_CHAT -> {
                if (!RelayClient.sendChat(message)) {
                    LOGGER.info("[PartyCommands] Mod Chat reply not sent - relay {}", RelayClient.statusText());
                }
            }
            default -> toServer(channel.prefix() + " " + message);
        }
    }

    private static String enabledList() {
        PartyCommandsConfig cfg = PartyCommandsConfig.getInstance();
        StringBuilder sb = new StringBuilder();
        for (Command c : Command.values()) {
            if (!cfg.allows(c)) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(c.canonical());
        }
        return sb.isEmpty() ? "none" : sb.toString();
    }

    private static void schedule(long delayMs, Runnable action) {
        schedule(delayMs, null, null, action);
    }

    private static void schedule(long delayMs, String key, BooleanSupplier guard, Runnable action) {
        synchronized (PENDING) {
            PENDING.add(new Pending(System.currentTimeMillis() + delayMs, key, guard, action));
        }
    }

    private static void runPending() {
        List<Pending> due = null;
        synchronized (PENDING) {
            if (PENDING.isEmpty()) {
                return;
            }
            long now = System.currentTimeMillis();
            for (int i = PENDING.size() - 1; i >= 0; i--) {
                if (PENDING.get(i).dueAtMs() <= now) {
                    if (due == null) {
                        due = new ArrayList<>();
                    }
                    due.add(PENDING.remove(i));
                }
            }
        }
        if (due == null) {
            return;
        }
        // The backwards remove-loop above collects newest-first; run in scheduling order.
        java.util.Collections.reverse(due);
        for (Pending pending : due) {
            try {
                if (pending.guard() != null && !pending.guard().getAsBoolean()) {
                    LOGGER.info("[PartyCommands] Dropped pending {} - conditions changed before it was due",
                            pending.key() == null ? "action" : pending.key());
                    continue;
                }
                pending.action().run();
            } catch (RuntimeException e) {
                LOGGER.error("[PartyCommands] Delayed action failed", e);
            }
        }
    }

    private static boolean isPending(String key) {
        synchronized (PENDING) {
            for (Pending pending : PENDING) {
                if (key.equals(pending.key())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void cancelReinvites(String reason) {
        List<String> cancelled = new ArrayList<>();
        synchronized (PENDING) {
            for (int i = PENDING.size() - 1; i >= 0; i--) {
                String key = PENDING.get(i).key();
                if (key != null && key.startsWith(REINVITE_KEY)) {
                    cancelled.add(key.substring(REINVITE_KEY.length()));
                    PENDING.remove(i);
                }
            }
        }
        if (cancelled.isEmpty()) {
            return;
        }
        ModChat.send("Party Commands", ModChat.bad("Re-invite cancelled"), ModChat.text(" for "),
                ModChat.value(String.join(", ", cancelled)), ModChat.dim(" - " + reason + "."));
        LOGGER.info("[PartyCommands] Cancelled queued re-invite(s) {} - {}", cancelled, reason);
    }
}
