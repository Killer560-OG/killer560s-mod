package com.killer560.hub.social;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.players.PlayerNames;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ServerCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mirrors killer560's REAL Hypixel friends list into {@link FriendsListConfig}, and is the only thing in this
 * package that sends a real {@code /f} command - never a locally-invented add/remove.
 * <p>
 * <b>Reading the real list.</b> Hypixel's {@code /fl} is a paged block: a dashed separator, a header
 * "{@code Friends (Page 1 of 23) >>}" (pages after the first also carry a leading "{@code <<}"), one line per
 * friend shaped "{@code Name is in SkyBlock - Hub}" / "{@code Name is currently offline}", and a closing
 * separator. The "{@code >>}" is a clickable component whose {@code ClickEvent.RunCommand} runs the next page's
 * command; a sync reads that command off the header and sends it (falling back to {@code friend list N+1} when
 * no click event is found), one page every {@link #PAGE_DELAY_MS}, until the header says it is on the last
 * page. Only then is the whole accumulated list committed, so a sync that dies part-way never replaces the
 * saved list with a partial one unless it is marked incomplete.
 * <p>
 * <b>Hiding the chat block.</b> killer560 (2026-10-04): "If it is on then it should hide the normal fl that
 * shows up." Every block THIS class asked for is dropped from chat through Fabric's {@code ALLOW_GAME},
 * separators included; a {@code /flhypixel} the player typed himself is never expected and so stays visible.
 * {@code ALLOW_GAME} is a raw packet-path listener (a throw there disconnects from Hypixel), so the whole
 * handler is wrapped and every quantifier that reads chat is bounded.
 * <p>
 * <b>Writing.</b> Add/remove sends the real command via {@link ServerCommands#toServer} and schedules a
 * follow-up sync; the list itself only ever changes from a real {@code /fl} read.
 */
public final class FriendsListSync {

    private static final Logger LOGGER = ModLog.get("killer560smod-friendslist-sync");
    private static final String CHAT = "Friends List";

    private static final long MIN_COMMAND_INTERVAL_MS = 1_500L;
    private static final long MIN_SYNC_INTERVAL_MS = 4_000L;
    private static final long POST_COMMAND_SYNC_DELAY_MS = 1_500L;
    /** Between one page's block arriving and the next page's command going out. */
    private static final long PAGE_DELAY_MS = 1_200L;
    /** How long after sending a page command its block may take to arrive before the sync gives up. */
    private static final long PAGE_TIMEOUT_MS = 6_000L;
    /** Only stops a malformed header looping forever; Hypixel's cap is far below this many pages. */
    private static final int MAX_PAGES = 80;

    private static final Pattern SEPARATOR = Pattern.compile("^[-▬]{5,}$");
    private static final Pattern HEADER = Pattern.compile(
            "^(?:<<\\s*)?Friends\\s*\\((?:Page\\s+(\\d{1,3})\\s+of\\s+(\\d{1,3})|\\d{1,4})\\)(?:\\s*>>)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FRIEND_LINE = Pattern.compile(
            "^(?:\\[[A-Za-z0-9+]{1,12}\\]\\s+)?([A-Za-z0-9_]{1,16})\\s+is\\s+(.{1,120})$");
    private static final Pattern PAGE_COMMAND = Pattern.compile(
            "^/?(?:f|fl|friend|friends)(?:\\s+(?:list|l))?\\s+(\\d{1,3})$", Pattern.CASE_INSENSITIVE);

    /** A multi-page walk is in progress (from the first command sent until the last page or a give-up). */
    private static boolean walking = false;
    /** When the outstanding page command was sent; 0 when no reply is awaited. */
    private static long awaitingSinceMs = 0L;
    /** Inside a block: the header has been seen and friend lines are being collected. */
    private static boolean reading = false;
    private static int currentPage = 0;
    private static int totalPages = 0;
    private static String nextPageCommand = null;
    private static long nextPageAtMs = 0L;
    private static final Map<String, ParsedFriend> ACCUMULATED = new LinkedHashMap<>();

    private static long lastCommandAtMs = 0L;
    private static long lastSyncAtMs = 0L;
    private static long pendingFollowUpSyncAtMs = 0L;

    private record ParsedFriend(String name, Boolean onlineHint) {
    }

    private FriendsListSync() {
    }

    /** Call once from {@code Killer560ModClient#onInitializeClient}. */
    public static void register() {
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (overlay || message == null) {
                return true;
            }
            try {
                return !onGameLine(message);
            } catch (RuntimeException e) {
                // Raw packet-path listener: a throw here disconnects from Hypixel. Never let one out.
                LOGGER.error("[FriendsList] Failed to read a chat line during sync", e);
                return true;
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("FriendsListSync", client -> tick()));
    }

    /** True while a multi-page sync is walking the pages. */
    public static boolean isSyncing() {
        return walking;
    }

    /** "page X of N" progress for the screen's status line, or an empty string when not syncing. */
    public static String syncProgress() {
        if (!walking) {
            return "";
        }
        return totalPages > 0 ? "page " + currentPage + " of " + totalPages : "page 1";
    }

    private static void tick() {
        long now = System.currentTimeMillis();
        if (pendingFollowUpSyncAtMs != 0L && now >= pendingFollowUpSyncAtMs && !walking) {
            pendingFollowUpSyncAtMs = 0L;
            doSync(true);
        }
        if (!walking) {
            return;
        }
        if (Minecraft.getInstance().player == null) {
            abortWalk("disconnected");
            return;
        }
        if (nextPageAtMs != 0L && now >= nextPageAtMs) {
            nextPageAtMs = 0L;
            String command = nextPageCommand != null ? nextPageCommand : "friend list " + (currentPage + 1);
            nextPageCommand = null;
            if (!ServerCommands.toServer(command)) {
                abortWalk("could not send " + command);
                return;
            }
            awaitingSinceMs = now;
            return;
        }
        if (awaitingSinceMs != 0L && now - awaitingSinceMs > PAGE_TIMEOUT_MS) {
            abortWalk("no reply to page " + (currentPage + 1));
        }
    }

    // ---- reading -------------------------------------------------------------------------------

    /** @return true to drop this message from chat. */
    private static boolean onGameLine(Component message) {
        if (!walking || (awaitingSinceMs == 0L && !reading)) {
            return false;
        }
        String full = ChatObserver.strip(message);
        String[] lines = full.split("\n", 64);
        boolean suppress = false;
        for (String line : lines) {
            if (handleLine(line.trim(), message)) {
                suppress = true;
            }
        }
        // A whole block sent as one multi-line message ends with the message.
        if (lines.length > 1 && reading) {
            finishPage();
        }
        return suppress;
    }

    private static boolean handleLine(String plain, Component message) {
        if (plain.isEmpty()) {
            return reading || awaitingSinceMs != 0L;
        }
        if (SEPARATOR.matcher(plain).matches()) {
            if (reading) {
                finishPage();
                return true;
            }
            // The opening separator arrives before the header; it is only hidden while a reply is awaited.
            return awaitingSinceMs != 0L;
        }
        Matcher header = HEADER.matcher(plain);
        if (header.matches()) {
            if (awaitingSinceMs == 0L) {
                return false;
            }
            reading = true;
            awaitingSinceMs = 0L;
            int page = 1;
            int total = 1;
            if (header.group(1) != null) {
                page = parseSmall(header.group(1), 1);
                total = parseSmall(header.group(2), 1);
            }
            currentPage = page;
            totalPages = Math.max(page, total);
            nextPageCommand = findNextPageCommand(message, page + 1);
            return true;
        }
        if (!reading) {
            return false;
        }
        Matcher friend = FRIEND_LINE.matcher(plain);
        if (friend.matches()) {
            String name = friend.group(1);
            boolean offline = friend.group(2).toLowerCase(Locale.US).contains("offline");
            ACCUMULATED.putIfAbsent(name.toLowerCase(Locale.US), new ParsedFriend(name, !offline));
            return true;
        }
        // Something unrelated reached mid-block: the block is over, and this line is not ours to hide.
        finishPage();
        return false;
    }

    private static int parseSmall(String digits, int fallback) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** The "{@code >>}" arrow's own command, read off its click event. Null when none is found. */
    private static String findNextPageCommand(Component root, int wantedPage) {
        List<Component> stack = new ArrayList<>();
        stack.add(root);
        int visited = 0;
        while (!stack.isEmpty() && visited++ < 512) {
            Component c = stack.remove(stack.size() - 1);
            if (c.getStyle().getClickEvent() instanceof ClickEvent.RunCommand run) {
                String command = run.command() == null ? "" : run.command().trim();
                Matcher m = PAGE_COMMAND.matcher(command);
                if (m.matches() && parseSmall(m.group(1), -1) == wantedPage) {
                    return command.startsWith("/") ? command.substring(1) : command;
                }
            }
            stack.addAll(c.getSiblings());
        }
        return null;
    }

    private static void finishPage() {
        reading = false;
        if (currentPage < totalPages && currentPage < MAX_PAGES) {
            nextPageAtMs = System.currentTimeMillis() + PAGE_DELAY_MS;
            return;
        }
        List<ParsedFriend> parsed = List.copyOf(ACCUMULATED.values());
        endWalk();
        applySync(parsed, false);
    }

    private static void abortWalk(String why) {
        List<ParsedFriend> parsed = List.copyOf(ACCUMULATED.values());
        int page = currentPage;
        int total = totalPages;
        endWalk();
        LOGGER.warn("[FriendsList] Sync stopped at page {} of {}: {}", page, total, why);
        if (!parsed.isEmpty()) {
            applySync(parsed, true);
            ModChat.send(CHAT, ModChat.bad("Sync stopped at page " + page + " of " + total
                    + " - kept what was read and marked the list incomplete."));
        } else {
            ModChat.send(CHAT, ModChat.bad("Sync got no reply from Hypixel's /fl."));
        }
    }

    private static void endWalk() {
        walking = false;
        reading = false;
        awaitingSinceMs = 0L;
        nextPageAtMs = 0L;
        nextPageCommand = null;
        currentPage = 0;
        totalPages = 0;
        ACCUMULATED.clear();
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
        LOGGER.info("[FriendsList] Synced {} real friend(s) from /fl{}", parsed.size(), truncated ? " (incomplete)" : "");
    }

    // ---- writing --------------------------------------------------------------------------------

    /** The screen's Refresh asks for a full sync - rate-limited, and refused while one is already walking.
     *  @return true if it actually started. */
    public static boolean requestSync(boolean quiet) {
        if (walking || System.currentTimeMillis() - lastSyncAtMs < MIN_SYNC_INTERVAL_MS) {
            return false;
        }
        return doSync(quiet);
    }

    private static boolean doSync(boolean quiet) {
        if (walking) {
            return false;
        }
        lastSyncAtMs = System.currentTimeMillis();
        endWalk();
        if (!ServerCommands.toServer("fl")) {
            return false;
        }
        walking = true;
        awaitingSinceMs = lastSyncAtMs;
        if (!quiet) {
            ModChat.send(CHAT, ModChat.dim("Syncing every page of your real Hypixel friends list..."));
        }
        return true;
    }

    /** Sends the real {@code /f add <name>}; the list updates from the follow-up sync. */
    public static boolean requestAdd(String name) {
        return sendSocialCommand("add", name);
    }

    /** Sends the real {@code /f remove <name>}, same caveats as {@link #requestAdd}. */
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
        pendingFollowUpSyncAtMs = now + POST_COMMAND_SYNC_DELAY_MS;
        return true;
    }
}
