package com.killer560.hub.partyinvites;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.killer560.hub.shared.RecordCatalog;
import com.killer560.hub.shared.SharedFile;
import com.killer560.hub.shared.SharedRecords;
import com.killer560.hub.shared.SharedWriter;
import com.killer560.hub.util.ConfigJson;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Your guild's members, for Auto Accept Invites' Guild group (guildtag-1118). The invite names no guild, so the mod reads
 * Hypixel's own {@code /guild list} reply once each session (sent by {@link GuildListRequest} only while the switch is ON; a
 * list you type is parsed too) and keeps the names per account in the shared store. Guild chat and join/leave/kick lines
 * keep it current. The reply's form: dashes, "Guild Name: X", "-- Guild Master --" style headers each followed by lines of
 * "[RANK] Name ●  [RANK] Name ●", "Total Members: N", "Online Members: N", dashes; "You are not in a guild!" for no guild.
 */
public final class GuildMembers {

    private static final int MAX = 300;
    private static final Pattern CHAT = Pattern.compile(
            "^Guild > (?:\\[[A-Z+]{1,12}\\] )?([A-Za-z0-9_]{1,16})(?: \\[([^\\]]{1,20})\\])?(?::| joined\\.)");
    /** "[MVP+] Name left the guild", "Name was kicked from the guild by ...", "[VIP] Name joined the guild!". */
    private static final Pattern JOIN_GUILD = Pattern.compile(
            "^(?:\\[[A-Z+]{1,12}\\] )?([A-Za-z0-9_]{1,16}) joined the guild!");
    private static final Pattern LEAVE_GUILD = Pattern.compile(
            "^(?:\\[[A-Z+]{1,12}\\] )?([A-Za-z0-9_]{1,16}) (?:left the guild!|was kicked from the guild!?)");
    private static final Pattern HEADER = Pattern.compile("^-- (.{1,30}) --$");
    private static final Pattern MEMBER = Pattern.compile("(?:\\[[A-Z+]{1,12}\\] )?([A-Za-z0-9_]{1,16}) ?[●○]");
    static final Pattern DASHES = Pattern.compile("^-{10,}$");

    /** lower-case name -> guild rank ("" when unknown). Guarded by itself. */
    private static final Map<String, String> MEMBERS = new LinkedHashMap<>();
    private static boolean loaded;
    private static boolean notInGuild;
    private static volatile boolean haveList;
    // the reply being read
    private static boolean collecting;
    private static String section = "";
    private static Map<String, String> pending;

    private GuildMembers() {
    }

    /** One stripped chat line. */
    public static void onLine(String plain) {
        if (plain == null) {
            return;
        }
        String s = plain.trim();
        if (s.startsWith("Guild > ")) {
            Matcher m = CHAT.matcher(s);
            if (m.find()) {
                add(m.group(1), m.group(2) == null ? "" : m.group(2));
            }
            return;
        }
        if (s.startsWith("Guild Name:")) {
            collecting = true;
            section = "";
            pending = new LinkedHashMap<>();
        } else if (collecting && s.startsWith("Total Members:")) {
            replaceAll(pending, false);
            collecting = false;
        } else if (s.equals("You are not in a guild!")) {
            replaceAll(new LinkedHashMap<>(), true);
        } else if (collecting) {
            Matcher h = HEADER.matcher(s);
            if (h.matches()) {
                section = h.group(1).equals("Guild Master") ? "Guild Master" : h.group(1);
            } else {
                Matcher m = MEMBER.matcher(s);
                while (m.find()) {
                    pending.put(m.group(1).toLowerCase(Locale.ROOT), section);
                }
            }
        } else if (s.indexOf('!') > 0 || s.indexOf('.') > 0) {
            Matcher m = JOIN_GUILD.matcher(s);
            if (m.find()) {
                add(m.group(1), "");
                return;
            }
            m = LEAVE_GUILD.matcher(s);
            if (m.find()) {
                remove(m.group(1));
            }
        }
    }

    /** True for a line that belongs to a {@code /guild list} reply (the display hide while the mod asked for it). */
    static boolean isReplyLine(String s) {
        return DASHES.matcher(s).matches() || s.startsWith("Guild Name:") || HEADER.matcher(s).matches()
                || s.startsWith("Total Members:") || s.startsWith("Online Members:") || s.equals("You are not in a guild!")
                || MEMBER.matcher(s).find();
    }

    private static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            JsonArray a = ConfigJson.getArray(SharedRecords.load(SharedRecords.file(RecordCatalog.GUILD_LIST)), "members");
            if (a != null) {
                a.forEach(e -> {
                    JsonObject o = e.getAsJsonObject();
                    MEMBERS.put(o.get("name").getAsString().toLowerCase(Locale.ROOT),
                            o.has("rank") ? o.get("rank").getAsString() : "");
                });
            }
        } catch (Exception ignored) {
            // an unreadable store starts empty; SharedFile keeps a copy aside
        }
    }

    private static void add(String name, String rank) {
        ensureLoaded();
        String key = name.toLowerCase(Locale.ROOT);
        synchronized (MEMBERS) {
            String old = MEMBERS.put(key, rank.isEmpty() && MEMBERS.containsKey(key) ? MEMBERS.get(key) : rank);
            if (MEMBERS.size() > MAX) {
                MEMBERS.remove(MEMBERS.keySet().iterator().next());
            }
            if (old != null && old.equals(MEMBERS.get(key))) {
                return;
            }
        }
        save();
    }

    private static void remove(String name) {
        ensureLoaded();
        boolean had;
        synchronized (MEMBERS) {
            had = MEMBERS.remove(name.toLowerCase(Locale.ROOT)) != null;
        }
        if (had) {
            save();
        }
    }

    private static void replaceAll(Map<String, String> list, boolean none) {
        ensureLoaded();
        synchronized (MEMBERS) {
            MEMBERS.clear();
            list.forEach((k, v) -> {
                if (MEMBERS.size() < MAX) {
                    MEMBERS.put(k, v);
                }
            });
        }
        notInGuild = none;
        haveList = true;
        save();
    }

    private static void save() {
        JsonObject root = new JsonObject();
        JsonArray a = new JsonArray();
        synchronized (MEMBERS) {
            MEMBERS.forEach((k, v) -> {
                JsonObject o = new JsonObject();
                o.addProperty("name", k);
                o.addProperty("rank", v);
                a.add(o);
            });
        }
        root.add("members", a);
        SharedFile target = SharedRecords.file(RecordCatalog.GUILD_LIST);
        SharedWriter.submit(() -> SharedRecords.save(target, root));
    }

    public static boolean contains(String name) {
        if (name == null) {
            return false;
        }
        ensureLoaded();
        synchronized (MEMBERS) {
            return MEMBERS.containsKey(name.toLowerCase(Locale.ROOT));
        }
    }

    /** A reply was read this session (a guild list, or "not in a guild"). */
    public static boolean haveList() {
        return haveList;
    }

    public static boolean notInGuild() {
        return notInGuild;
    }

    /** The saved rank of a member, "" when none or unknown. */
    public static String rankOf(String name) {
        synchronized (MEMBERS) {
            String r = name == null ? null : MEMBERS.get(name.toLowerCase(Locale.ROOT));
            return r == null ? "" : r;
        }
    }

    /** The saved store re-read for the signed-in account (account switch), and the session state cleared. */
    public static void reload() {
        synchronized (MEMBERS) {
            MEMBERS.clear();
        }
        loaded = false;
        haveList = false;
        notInGuild = false;
        collecting = false;
    }

    public static void clearForTest() {
        reload();
        loaded = true;
    }
}
