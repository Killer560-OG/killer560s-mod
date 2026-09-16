package com.killer560.hub.partycommands;

import com.killer560.hub.leapmenu.PartyTracker;
import com.killer560.hub.partycommands.PartyCommandsConfig.Command;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * Odin's party commands: a real teammate types "!warp" (etc.) in party chat and your client runs the matching
 * real party command. Ported from OdinLegacy's
 * {@code src/main/kotlin/me/odinmain/features/impl/skyblock/ChatCommands.kt} (github.com/odtheking/OdinLegacy) -
 * behaviour, trigger words and reply shapes, not its code. The informational half of that same Odin module
 * ({@code !coords}/{@code !ping}/{@code !fps}/{@code !cf}/{@code !8ball}/{@code !dice}/...) already exists here
 * as {@code chatcommands.ChatCommandsFeature}, which is also the single chat listener that feeds this class -
 * there is deliberately no second listener.
 * <p>
 * <b>Why this exists even though Chat Commands refuses party management.</b> That refusal was this mod's own
 * call, not Odin's; killer560 overrode it (2026-09-16) on the condition that it "works off of teammates" - only
 * people actually in his party/dungeon team may trigger anything. Odin itself has no such check at all: its only
 * gate is a blacklist (or an opt-in whitelist), so on stock Odin any stranger who can reach your party, guild or
 * DMs can make your client kick/warp/transfer. The differences from Odin, all deliberate:
 * <ul>
 * <li><b>Teammate-only.</b> {@link #isTeammate} - the sender must be in {@link PartyTracker}'s party list or in
 *     the current run's dungeon tab list (or be you). Everyone else is ignored in silence.
 * <li><b>Party channel only.</b> Odin also accepts guild and (for {@code !invite}) private messages. A guild
 *     member or a random DM is not a teammate, so those channels never reach this class.
 * <li><b>Real server lines only.</b> Intake is {@code ChatCommandsFeature}'s Fabric
 *     {@code ClientReceiveMessageEvents.CHAT/GAME} listener, which only fires for lines that arrived in a chat
 *     packet. {@code util.ChatObserver} is NOT used here on purpose: its second source (the {@code ChatComponent}
 *     mixin) also sees lines another mod - or this mod - renders locally, so a fake "Party > Someone: !kick x"
 *     line drawn into chat would otherwise be indistinguishable from a real one.
 * <li><b>Everything off by default</b>, per-command toggles, plus a separate switch for the destructive ones.
 * <li><b>Rate limited</b> per sender and globally ({@link #GLOBAL_MIN_GAP_MS} and friends), so a spamming
 *     teammate cannot make your client flood Hypixel and get you chat-limited or kicked.
 * <li><b>Every executed command is printed locally</b> through {@link ModChat}, so you always see what someone
 *     made your client do - Odin runs most of them silently.
 * </ul>
 * Argument names are re-validated against {@link #NAME_PATTERN} before they are ever concatenated into a
 * command string, so no chat text can smuggle extra arguments into {@code sendCommand}.
 * <p>
 * One command here is NOT Odin's: {@code !reinv} / {@code !reinvite} (2026-09-16, killer560's own request) -
 * kick the teammate who asked and invite them back 5s later, the usual fix for a party/instance bug. It goes
 * through the same teammate gate, its own toggle and the destructive switch; see {@link #reinvite}.
 */
public final class PartyCommandsFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-partycommands");

    /** The one shape a Minecraft name can have - every argument must match before it is put in a command. */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    /** Dungeon tab list entry, same regex as {@code leapmenu.PartyTracker} / {@code fastleap.Teammates}. */
    private static final Pattern TAB_REGEX = Pattern.compile("^\\[\\d+] (?:\\[[^]]+] )*([A-Za-z0-9_]{1,16}) .*\\((\\w+)(?: (\\w+))?\\)$");
    /** "!f1".."!f7", "!m1".."!m7", "!t1".."!t5" - Odin's instance-queue triggers. */
    private static final Pattern FLOOR_PATTERN = Pattern.compile("^([fmt])([1-7])$");
    private static final Pattern END_OF_RUN = Pattern.compile("^ *> EXTRA STATS < *$");

    private static final String[] FLOOR_WORDS = {"one", "two", "three", "four", "five", "six", "seven"};
    private static final String[] KUUDRA_TIERS = {"normal", "hot", "burning", "fiery", "infernal"};

    private static final String RACISM_SUFFIX = "% racist. Racism is not allowed!";

    // Rate limits (Odin has none at all).
    private static final long GLOBAL_MIN_GAP_MS = 2_000L;
    private static final long PER_SENDER_MIN_GAP_MS = 8_000L;
    private static final long WINDOW_MS = 60_000L;
    private static final int GLOBAL_MAX_PER_WINDOW = 6;
    private static final int PER_SENDER_MAX_PER_WINDOW = 3;
    /** A dropped command only ever reports itself this often, so the rejection can't become the spam. */
    private static final long REJECT_LOG_GAP_MS = 15_000L;

    /** {@code !reinv}: how long to leave the player out before inviting them back (the usual party/instance
     *  bug fix is a kick and a re-invite a few seconds later, not an instant one). */
    private static final long REINVITE_DELAY_MS = 5_000L;
    private static final String REINVITE_KEY = "reinvite:";

    private static final Deque<Long> GLOBAL_TIMES = new ArrayDeque<>();
    private static final Map<String, Deque<Long>> SENDER_TIMES = new HashMap<>();
    private static long lastGlobalAtMs = 0L;
    private static long lastRejectLogAtMs = 0L;

    /**
     * Pending delayed sends (Odin's {@code runIn(n)} ticks): warp-then-transfer, end-of-run downtime, and
     * {@code !reinv}'s 5s gap. Run from the client tick, never a sleep.
     * <p>
     * {@code key} names a cancellable entry ("reinvite:&lt;name&gt;", used to cancel one and to refuse a second
     * one for the same player); {@code guard} is re-checked the moment the entry comes due and a false answer
     * drops it instead of running it - so a queued {@code /p invite} can never fire into a party, world or run
     * that isn't the one it was queued for.
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
        ClientTickEvents.END_CLIENT_TICK.register(client -> runPending());
    }

    /**
     * Every real server chat line, before channel matching - only called while the feature is enabled.
     * Keeps party-leader tracking and the end-of-run downtime payoff on the same real-server-only intake as
     * the commands themselves.
     */
    public static void onServerLine(String plain) {
        if (plain == null || plain.isEmpty()) {
            return;
        }
        PartyLeaderTracker.onServerLine(plain);
        // Left / kicked / disbanded: any queued !reinv invite would land in a party this client is no longer
        // part of (or re-invite someone into a brand new one), so drop them as soon as Hypixel says so.
        if (PartyLeaderTracker.clearsParty(plain)) {
            cancelReinvites("you're no longer in that party");
        }
        if (!DOWNTIME.isEmpty() && END_OF_RUN.matcher(plain).matches()) {
            // Odin: runIn(30) after EXTRA STATS, and only YOUR OWN reason is announced in party chat - a
            // teammate's "!dt" never makes your client speak.
            schedule(1_500L, PartyCommandsFeature::announceDowntime);
        }
    }

    /**
     * A "!" message from {@code sender} in real party chat. {@code body} is the text after the "!".
     *
     * @return true when this was a party command (handled or deliberately dropped), so the informational
     *         Chat Commands half doesn't also answer it.
     */
    public static boolean handle(String sender, String body) {
        PartyCommandsConfig cfg = PartyCommandsConfig.getInstance();
        if (!cfg.isEnabled() || body == null || body.isBlank()) {
            return false;
        }
        String[] words = body.trim().split("\\s+");
        String word = words[0].toLowerCase(Locale.US);
        String arg = words.length > 1 ? words[1] : null;

        Command command = commandFor(word);
        if (command == null) {
            return false;
        }
        // THE gate: only someone actually on your team. Silent, exactly like an unknown command.
        if (!isTeammate(sender)) {
            LOGGER.info("[PartyCommands] Ignored \"!{}\" from {} - not a party/dungeon teammate", word, sender);
            return false;
        }
        if (!cfg.isOn(command)) {
            return false;
        }
        if (command.isDestructive() && !cfg.isAllowDestructive()) {
            LOGGER.info("[PartyCommands] Ignored \"!{}\" from {} - destructive commands are off", word, sender);
            return true;
        }
        if (needsLeader(command) && PartyLeaderTracker.knownNotLeader()) {
            LOGGER.info("[PartyCommands] Ignored \"!{}\" from {} - you are not the party leader", word, sender);
            return true;
        }
        if (!allowRate(sender)) {
            return true;
        }
        try {
            run(command, word, sender, arg, body);
        } catch (RuntimeException e) {
            LOGGER.error("[PartyCommands] \"!{}\" from {} failed", word, sender, e);
        }
        return true;
    }

    private static Command commandFor(String word) {
        if (FLOOR_PATTERN.matcher(word).matches()) {
            return instanceFor(word) == null ? null : Command.QUEUE_INSTANCE;
        }
        for (Command c : Command.values()) {
            for (String trigger : c.triggers()) {
                if (trigger.equals(word)) {
                    return c;
                }
            }
        }
        return null;
    }

    /** Commands Hypixel only lets the party leader run - Odin gates these on {@code PartyUtils.isLeader()}. */
    private static boolean needsLeader(Command command) {
        return switch (command) {
            case WARP, WARP_TRANSFER, ALL_INVITE, TRANSFER, KICK, REINVITE, DEMOTE, PROMOTE, QUEUE_INSTANCE -> true;
            default -> false;
        };
    }

    private static void run(Command command, String word, String sender, String arg, String body) {
        switch (command) {
            case HELP -> partyChat("Commands: " + enabledList());
            case WARP -> execute(command, sender, "p warp", "warped the party");
            case WARP_TRANSFER -> {
                execute(command, sender, "p warp", "warped the party");
                // Odin: runIn(12) ticks, then transfer to the person who asked. Guarded so that if this client
                // left / was kicked / the party disbanded inside that window, the delayed "/p transfer" doesn't
                // fire into whatever party it is in by then (2026-09-16 audit; same idea as the !reinv guard).
                schedule(700L, "warp-transfer", () -> isTeammate(sender), () -> {
                    sendCommand("p transfer " + sender);
                    log(sender, "took the party (warp + transfer)");
                });
            }
            case ALL_INVITE -> execute(command, sender, "p settings allinvite", "toggled all-invite");
            case TRANSFER -> {
                String target = arg == null ? sender : firstNonNull(matchMember(arg), sender);
                execute(command, sender, "p transfer " + target, "transferred the party to " + target);
            }
            case KICK -> {
                String target = validName(arg) ? firstNonNull(matchMember(arg), arg) : null;
                if (target == null) {
                    return;
                }
                execute(command, sender, "p kick " + target, "kicked " + target);
            }
            case REINVITE -> reinvite(sender);
            case DEMOTE -> execute(command, sender, "p demote " + sender, "demoted themself");
            case PROMOTE -> execute(command, sender, "p promote " + sender, "promoted themself");
            case INVITE -> invite(sender, arg);
            case BOOP -> {
                if (!validName(arg)) {
                    return;
                }
                execute(command, sender, "boop " + arg, "booped " + arg);
            }
            case DOWNTIME -> downtime(sender, body);
            case UN_DOWNTIME -> unDowntime(sender);
            case QUEUE_INSTANCE -> {
                String instance = instanceFor(word);
                if (instance == null) {
                    return;
                }
                execute(command, sender, "joininstance " + instance, "queued " + word.toUpperCase(Locale.US));
            }
            case RACISM -> partyChat(sender + " is " + (1 + (int) (Math.random() * 100)) + RACISM_SUFFIX);
        }
    }

    // ------------------------------------------------------------------ teammate gate

    /**
     * The whole point of this feature: is {@code name} really on your team right now?
     * <ul>
     * <li>yourself - your own "!warp" in your own party chat still works;
     * <li>{@link PartyTracker#teammates()} - Hypixel's party list / join / leave / party-chat tracking;
     * <li>the live dungeon tab list, checked directly here so a Party-Finder run whose party list was never
     *     printed still counts, and so a stale tracker can't be the only source.
     * </ul>
     * Anything else - guild members, DMs, randoms in a lobby, and any name in a chat line this client never
     * received from the server - is not a teammate.
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
            Component display = info.getTabListDisplayName();
            if (display == null) {
                continue;
            }
            String plain = ChatFormatting.stripFormatting(display.getString());
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
            reason = "too many party commands this minute";
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
        String reason = body.trim().contains(" ") ? body.trim().substring(body.trim().indexOf(' ') + 1).trim() : "";
        if (reason.isEmpty()) {
            reason = "No reason given";
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
            partyChat("Downtime needed: " + DOWNTIME.get(self));
        }
        ModChat.send("Party Commands", ModChat.text("DT Reasons: "), ModChat.value(all.toString()));
        DOWNTIME.clear();
    }

    // ------------------------------------------------------------------ reinvite (!reinv)

    /**
     * {@code !reinv} / {@code !reinvite}: kick the teammate who asked, then invite them back
     * {@link #REINVITE_DELAY_MS} later - the usual fix for a player stuck in a broken party/instance state.
     * <p>
     * The gap is a scheduled client-tick entry, never a sleep, and it is re-validated the moment it comes due
     * ({@link #reinviteStillValid}) plus cancelled outright from {@link #onServerLine} the moment this client
     * stops being in that party. A queued invite therefore never fires into a different party, world or run -
     * the worst case is that it is dropped and the player is simply left to re-join normally.
     */
    private static void reinvite(String sender) {
        String key = REINVITE_KEY + sender.toLowerCase(Locale.US);
        if (isPending(key)) {
            // Already kicked and waiting - a second "!reinv" must not queue a second invite (the per-sender
            // rate limit is 8s, longer than the delay, so this is the belt to that braces).
            LOGGER.info("[PartyCommands] \"!reinv\" from {} ignored - a re-invite is already pending", sender);
            return;
        }
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        boolean wasInDungeon = DungeonState.isInDungeon();
        execute(Command.REINVITE, sender, "p kick " + sender,
                "asked to be re-invited - kicked, inviting back in " + (REINVITE_DELAY_MS / 1000) + "s");
        schedule(REINVITE_DELAY_MS, key, () -> reinviteStillValid(level, wasInDungeon), () -> {
            sendCommand("p invite " + sender);
            log(sender, "re-invited");
        });
    }

    /** Re-checked when the queued invite is due: same world, still connected, still in the run it was asked in,
     *  still the leader, and the command still turned on. Any of those changing drops the invite. */
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

    // ------------------------------------------------------------------ invite

    private static void invite(String sender, String arg) {
        if (!validName(arg)) {
            return;
        }
        PartyCommandsConfig cfg = PartyCommandsConfig.getInstance();
        if (!cfg.isConfirmInvites()) {
            execute(Command.INVITE, sender, "p invite " + arg, "invited " + arg);
            return;
        }
        // Odin's default: no invite is sent, you get a clickable prompt instead.
        MutableComponent line = ModChat.line("Party Commands",
                ModChat.value(sender), ModChat.text(" asked you to invite "), ModChat.value(arg),
                ModChat.dim(" - click to invite."));
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.sendSystemMessage(line.withStyle(style ->
                    style.withClickEvent(new ClickEvent.RunCommand("/party invite " + arg))));
        }
    }

    // ------------------------------------------------------------------ plumbing

    /** Odin's {@code /od <floor>}: "f7" -> catacombs_floor_seven, "m7" -> master_catacombs_floor_seven,
     *  "t5" -> kuudra_infernal. Returns null for a tier that doesn't exist (there is no T6/T7). */
    private static String instanceFor(String word) {
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

    private static void execute(Command command, String sender, String serverCommand, String description) {
        sendCommand(serverCommand);
        log(sender, description);
    }

    /** Local-only record of what a teammate just made this client do - never silent, by design. */
    private static void log(String sender, String description) {
        ModChat.send("Party Commands", ModChat.value(sender), ModChat.text(" " + description), ModChat.dim("."));
        LOGGER.info("[PartyCommands] {} -> {}", sender, description);
    }

    private static void sendCommand(String command) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.connection.sendCommand(command);
        }
    }

    private static void partyChat(String message) {
        sendCommand("pc " + message);
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

    /** True while a {@code !reinv} for this player is still waiting - a second one is ignored, never queued. */
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

    /** Drops every queued re-invite (the party is gone / this client left it), so none can fire later. */
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
