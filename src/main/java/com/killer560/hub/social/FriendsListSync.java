package com.killer560.hub.social;

import com.killer560.hub.players.PlayerNames;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ServerCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mirrors killer560's REAL Hypixel friends list into {@link FriendsListConfig}, and is the only thing in this
 * package that sends a real {@code /f} command - never a locally-invented add/remove (killer560, 2026-09-27:
 * "For the friends list it should go based off of my ingame friendslist. If i add someone in the menu or
 * something then it should also add them on the server.").
 * <p>
 * <b>Reading the real list.</b> This mod has no Hypixel friends/presence API access at all - the only way to
 * know the real list is the same way {@code leapmenu.PartyTracker} already reads a real Hypixel
 * "{@code /party list}" block off chat (header line, then one-or-more "{@code Category: name1, name2, ...}"
 * lines, ended by a footer or an unrelated line), which is the pattern this wave's brief pointed at. That
 * exact shape is verified for the PARTY list because a real screenshot backs {@code PartyTracker}'s regex.
 * <b>Hypixel's real {@code /fl} wording has NOT been confirmed against a live session</b> - there was no way
 * to capture it from inside this task. {@link #HEADER}/{@link #CATEGORY_LINE} are deliberately generic (any
 * "Friends" header with an optional count, any labelled comma-list underneath it, whether Hypixel splits by
 * Online/Offline, Best Friends/Friends, or doesn't split at all) so a close-but-not-identical real format
 * still parses, but if the real wording doesn't match even that shape, {@link FriendsListConfig#isEverSynced()}
 * simply stays false forever and {@link FriendsListScreen} says so plainly rather than showing an empty list
 * as if it were the truth - see this wave's brief's own "say so plainly rather than shipping something that
 * half-works and silently diverges" rule. <b>Tell killer560 to check a real {@code /fl} against this file's
 * patterns if the screen ever says "never synced."</b>
 * <p>
 * <b>Pagination.</b> If Hypixel's real output truncates a long list ("and 12 more...", a trailing "..."), that
 * is detected and surfaced ({@link FriendsListConfig#isLastSyncTruncated()}) rather than silently treated as
 * the whole list - this mod cannot detect or click a next-page control it has never seen the shape of.
 * <p>
 * <b>Writing.</b> Add/remove always sends the real command via {@link ServerCommands#toServer} (never
 * {@code sendCommand} - {@code /f} is not one of this mod's own client commands so there is no recursion risk
 * the way {@code /fl}/{@code /ah}/{@code /bz} have, but {@code ServerCommands} is still the documented "Hypixel
 * handles this" path and costs nothing extra to use consistently). Neither one mutates the cached list
 * directly - the UI only ever shows what a subsequent real {@code /fl} confirmed, never an optimistic guess -
 * so a follow-up sync is scheduled a short delay later. Both are rate-limited: a real social action is only
 * ever sent for something the player explicitly clicked, at most one every {@link #MIN_COMMAND_INTERVAL_MS},
 * and a sync (whether from opening the screen, a manual refresh, or the post-command follow-up) is throttled
 * to at most one every {@link #MIN_SYNC_INTERVAL_MS} - nothing here ever polls in the background unprompted.
 */
public final class FriendsListSync {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-friendslist-sync");
    private static final String CHAT = "Friends List";

    private static final long MIN_COMMAND_INTERVAL_MS = 1_500L;
    private static final long MIN_SYNC_INTERVAL_MS = 4_000L;
    private static final long POST_COMMAND_SYNC_DELAY_MS = 1_500L;

    // See the class doc: deliberately generic, not a literal transcript of a confirmed real message.
    private static final Pattern HEADER = Pattern.compile("^-{0,}\\s*Friends(?:\\s*\\((\\d+)\\))?\\s*-{0,}$", Pattern.CASE_INSENSITIVE);
    private static final Pattern FOOTER = Pattern.compile("^-{5,}$");
    private static final Pattern CATEGORY_LINE = Pattern.compile("^([A-Za-z][A-Za-z '-]{1,24}?)\\s*(?:\\((\\d+)\\))?\\s*:\\s*(.+)$");
    // \p{So} (Unicode "Symbol, other") on top of \p{Punct} - PartyTracker's own real /party list puts a
    // status bullet ("●", U+25CF) straight after each name, which plain ASCII \p{Punct} doesn't cover, and
    // there's no confirmed real /fl transcript to know whether Hypixel does the same thing here (see the
    // class doc) - stripping either kind of trailing decoration keeps the name matching either way.
    private static final Pattern NAME_TOKEN = Pattern.compile("^([A-Za-z0-9_]{1,16})[\\p{Punct}\\p{So}\\s]*$");
    private static final Pattern TRUNCATED_TAIL = Pattern.compile(".*(\\.{3}|\\band\\s+\\d+\\s+more\\b).*", Pattern.CASE_INSENSITIVE);

    private static boolean reading = false;
    private static boolean truncatedThisBlock = false;
    private static final List<ParsedFriend> PENDING = new ArrayList<>();

    private static long lastCommandAtMs = 0L;
    private static long lastSyncAtMs = 0L;
    private static long pendingFollowUpSyncAtMs = 0L;

    private record ParsedFriend(String name, Boolean onlineHint) {
    }

    private FriendsListSync() {
    }

    /** Call once from {@code Killer560ModClient#onInitializeClient}. */
    public static void register() {
        ChatObserver.subscribe(FriendsListSync::onChat);
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    private static void tick() {
        if (pendingFollowUpSyncAtMs != 0L && System.currentTimeMillis() >= pendingFollowUpSyncAtMs) {
            pendingFollowUpSyncAtMs = 0L;
            // Bypasses requestSync's own rate limit on purpose: this fires at most once per real add/remove,
            // which is itself already limited to one per MIN_COMMAND_INTERVAL_MS - a second, independent
            // interval check here could otherwise silently swallow the one sync that actually matters (e.g. a
            // Refresh click, or the screen's own on-open sync, having just used up the window).
            doSync(true);
        }
    }

    // ---- reading -------------------------------------------------------------------------------

    private static void onChat(Component message) {
        String plain = ChatObserver.strip(message).trim();
        if (plain.isEmpty()) {
            if (reading) {
                finishBlock();
            }
            return;
        }
        if (HEADER.matcher(plain).matches()) {
            reading = true;
            truncatedThisBlock = false;
            PENDING.clear();
            return;
        }
        if (!reading) {
            return;
        }
        if (FOOTER.matcher(plain).matches()) {
            finishBlock();
            return;
        }
        Matcher category = CATEGORY_LINE.matcher(plain);
        if (category.matches()) {
            String label = category.group(1).trim().toLowerCase(Locale.US);
            Boolean onlineHint = label.contains("online") ? Boolean.TRUE : label.contains("offline") ? Boolean.FALSE : null;
            String rest = category.group(3);
            if (TRUNCATED_TAIL.matcher(rest).matches()) {
                truncatedThisBlock = true;
            }
            for (String token : rest.split(",")) {
                Matcher nameMatch = NAME_TOKEN.matcher(token.trim());
                if (nameMatch.matches()) {
                    PENDING.add(new ParsedFriend(nameMatch.group(1), onlineHint));
                }
            }
            return;
        }
        // An unrelated line reached while "reading" (never a footer, header or category line) - same bail-out
        // PartyTracker's own list parse uses so a totally different chat line can't be swallowed as part of it.
        finishBlock();
    }

    private static void finishBlock() {
        reading = false;
        List<ParsedFriend> parsed = List.copyOf(PENDING);
        boolean truncated = truncatedThisBlock;
        PENDING.clear();
        truncatedThisBlock = false;
        applySync(parsed, truncated);
    }

    private static void applySync(List<ParsedFriend> parsed, boolean truncated) {
        FriendsListConfig cfg = FriendsListConfig.getInstance();
        List<String> names = new ArrayList<>(parsed.size());
        for (ParsedFriend p : parsed) {
            names.add(p.name());
        }
        List<FriendsListConfig.Friend> friends = cfg.applyRealSync(names, truncated);
        for (int i = 0; i < friends.size(); i++) {
            FriendsListConfig.Friend f = friends.get(i);
            Boolean hint = parsed.get(i).onlineHint();
            if (hint != null) {
                f.onlineHint = hint;
            }
            if (f.uuid == null) {
                String name = f.name;
                PlayerNames.resolveAsync(name, id -> {
                    if (id != null) {
                        FriendsListConfig.Friend live = cfg.byName(name);
                        if (live != null) {
                            live.uuid = id;
                        }
                    }
                });
            }
        }
        cfg.save();
        LOGGER.info("[FriendsList] Synced {} real friend(s) from /fl{}", parsed.size(),
                truncated ? " (truncated - Hypixel showed more than this could parse)" : "");
    }

    // ---- writing --------------------------------------------------------------------------------

    /** Opening the screen or pressing its Refresh button asks for a sync - rate-limited the same as everything
     *  else here, so mashing Refresh can't spam {@code /fl}. @return true if it actually sent. */
    public static boolean requestSync(boolean quiet) {
        if (System.currentTimeMillis() - lastSyncAtMs < MIN_SYNC_INTERVAL_MS) {
            return false;
        }
        doSync(quiet);
        return true;
    }

    private static void doSync(boolean quiet) {
        lastSyncAtMs = System.currentTimeMillis();
        ServerCommands.toServer("fl");
        if (!quiet) {
            ModChat.send(CHAT, ModChat.dim("Syncing with your real Hypixel friends list..."));
        }
    }

    /** killer560's menu "Add" - sends the real {@code /f add <name>}. The list itself isn't touched until a
     *  follow-up {@code /fl} confirms it (see the class doc) - the chat line and this method's return are the
     *  only immediate feedback. @return true if it actually sent (false = rate-limited, try again shortly). */
    public static boolean requestAdd(String name) {
        return sendSocialCommand("add", name);
    }

    /** killer560's menu "Remove" - sends the real {@code /f remove <name>}, same caveats as {@link #requestAdd}. */
    public static boolean requestRemove(String name) {
        return sendSocialCommand("remove", name);
    }

    private static boolean sendSocialCommand(String verb, String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastCommandAtMs < MIN_COMMAND_INTERVAL_MS) {
            ModChat.send(CHAT, ModChat.bad("Please wait a moment before sending another friend request/removal."));
            return false;
        }
        lastCommandAtMs = now;
        ServerCommands.toServer("f " + verb + " " + trimmed);
        ModChat.send(CHAT, ModChat.text("Sent "), ModChat.value("/f " + verb + " " + trimmed),
                ModChat.dim(" - syncing your real list shortly..."));
        // Scheduled rather than immediate: give Hypixel a moment to actually apply it before /fl re-reads it.
        pendingFollowUpSyncAtMs = now + POST_COMMAND_SYNC_DELAY_MS;
        return true;
    }
}
