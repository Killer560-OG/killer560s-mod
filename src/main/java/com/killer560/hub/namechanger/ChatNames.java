package com.killer560.hub.namechanger;

import com.killer560.hub.util.ChatObserver;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Nickhider names from chat (killer560, 2026-11-01: "it doesnt randomize names from things like friends joining or
 * leaving or ..."). Randomize Others only knew the players on the tab list and in the world, so a friend or guild
 * member logging in elsewhere, a private message, party or guild chat from another server and the party/friend/guild
 * notices all showed real names. Every chat line Hypixel uses to show another player's name is read here, and each
 * name it names joins the Nickhider's seen players before the line is first drawn (the table is rebuilt on the next
 * frame), so the line itself already shows the fake name.
 *
 * <p>Every pattern is anchored on the whole line and its name group is {@code [A-Za-z0-9_]{3,16}} behind an optional
 * Hypixel rank tag: a wrong word taken for a name would be replaced everywhere it is drawn, so only exact line shapes
 * are read. Nothing here is sent anywhere; the line itself is untouched (the renaming is render-side, NameReplacer).
 */
public final class ChatNames {

    /** A player name. */
    private static final String N = "([A-Za-z0-9_]{3,16})";
    /** An optional Hypixel rank tag before a name: [VIP], [MVP++], [YOUTUBE], [ADMIN], [PIG+++]. */
    private static final String R = "(?:\\[[A-Z]{2,8}\\+{0,3}\\] )?";
    /** An optional guild rank tag after a name in guild chat: [Officer], [Member]. */
    private static final String GR = "(?: \\[[A-Za-z0-9 ]{1,20}\\])?";

    /** Lines that name one or two players; every group is a name. */
    private static final List<Pattern> LINES = List.of(
            // Friend / guild member logged in or out, from anywhere on the network.
            Pattern.compile("^(?:Friend|Guild) > " + R + N + " (?:joined|left)\\.$"),
            // Party, guild, officer and co-op chat.
            Pattern.compile("^(?:Party|Guild|Officer|Co-op) > " + R + N + GR + ": .*$"),
            // Private messages both ways.
            Pattern.compile("^(?:From|To) " + R + N + ": .*$"),
            // Skyblock all chat: "[312] [MVP+] Name: hi", with up to one emblem after the level.
            Pattern.compile("^\\[\\d{1,3}\\] (?:[^\\s\\[\\]A-Za-z0-9]{1,3} )?" + R + N + ": .*$"),
            // Party notices.
            Pattern.compile("^" + R + N + " joined the party\\.$"),
            Pattern.compile("^" + R + N + " has left the party\\.$"),
            Pattern.compile("^" + R + N + " has been removed from the party\\.$"),
            Pattern.compile("^" + R + N + " was removed from your party because they disconnected\\.?$"),
            Pattern.compile("^" + R + N + " has disconnected, they have \\d{1,2} minutes? to rejoin before they are removed from the party\\.$"),
            Pattern.compile("^" + R + N + " has invited you to join their party!.*$"),
            Pattern.compile("^" + R + N + " has invited you to join " + R + N + "'s party!.*$"),
            Pattern.compile("^" + R + N + " invited " + R + N + " to the party! They have 60 seconds to accept\\.$"),
            Pattern.compile("^You have joined " + R + N + "'s party!$"),
            Pattern.compile("^The party was transferred to " + R + N + " by " + R + N + "$"),
            Pattern.compile("^The party invite (?:to|from) " + R + N + " has expired$"),
            Pattern.compile("^Party Finder > " + N + " joined the dungeon group! \\(.{1,40}\\)$"),
            // Friend notices and /f list.
            Pattern.compile("^Friend request from " + R + N + "$"),
            Pattern.compile("^You are now friends with " + R + N + "$"),
            Pattern.compile("^You removed " + R + N + " from your friends list!$"),
            Pattern.compile("^" + R + N + " removed you from their friends list!$"),
            Pattern.compile("^" + R + N + " is (?:currently offline|in .{1,48})$"),
            // Guild notices.
            Pattern.compile("^" + R + N + " joined the guild!$"),
            Pattern.compile("^" + R + N + " left the guild!$"),
            Pattern.compile("^" + R + N + " was kicked from the guild by " + R + N + "!$"),
            Pattern.compile("^" + R + N + " has invited you to join their guild, .{1,40}!.*$"),
            Pattern.compile("^" + R + N + " has sent you a trade request\\..*$"),
            Pattern.compile("^You have sent a trade request to " + R + N + "\\.$"),
            Pattern.compile("^Trade completed with " + R + N + "!$"));

    /** "Party Leader: [MVP+] Name ●", "Party Members: [VIP] A ● B ●", and guild online lists: names separated by ●. */
    private static final Pattern LIST_LINE = Pattern.compile(
            "^(?:Party (?:Leader|Moderators|Members): )?(?:" + R + "[A-Za-z0-9_]{3,16} ?● ?){1,40}$");
    private static final Pattern LIST_NAME = Pattern.compile(R + N + " ?●");

    private static volatile int linesRead;
    private static volatile int namesAdded;

    private ChatNames() {
    }

    static void register() { ChatObserver.subscribe(ChatNames::onChat); }

    private static void onChat(Component message) {
        NameChangerConfig cfg = NameChangerConfig.getInstance();
        if (!cfg.isActive() || !cfg.isRandomizeOthers()) return;
        List<String> names = namesIn(ChatObserver.strip(message));
        if (names.isEmpty()) return;
        linesRead++;
        for (String n : names) {
            if (NameChangerFeature.noteChatName(n)) {
                namesAdded++;
            }
        }
    }

    /** The player names a chat line (plain text, no color codes) shows, in order; empty for any other line. */
    public static List<String> namesIn(String plain) {
        List<String> out = new ArrayList<>();
        if (plain == null || plain.isEmpty() || plain.length() > 600) return out;
        String line = plain.strip();
        for (Pattern p : LINES) {
            Matcher m = p.matcher(line);
            if (m.matches()) {
                for (int g = 1; g <= m.groupCount(); g++) {
                    if (m.group(g) != null) {
                        out.add(m.group(g));
                    }
                }
                return out;
            }
        }
        if (line.indexOf('●') >= 0 && LIST_LINE.matcher(line).matches()) {
            Matcher m = LIST_NAME.matcher(line);
            while (m.find()) {
                out.add(m.group(1));
            }
        }
        return out;
    }

    /** Chat lines that named a player (testkit evidence). */
    public static int linesRead() { return linesRead; }

    /** Names chat added to the Nickhider's seen players (testkit evidence). */
    public static int namesAdded() { return namesAdded; }
}
