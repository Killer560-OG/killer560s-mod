package com.killer560.hub.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Short names players type in the menu search that don't appear in any tab or setting label. The search
 *  box still shows what was typed; a tab shows if it matches the typed text OR any alias it expands to. */
public final class SearchAliases {
    private SearchAliases() {}

    // killer560 (2026-10-12): "etable" should find every Experimentation Table feature, and so should any start
    // of it ("eta", "etab") - not only the full word.
    // Map.ofEntries, not Map.of: Map.of stops at ten pairs, and the list reached ten on 2026-10-19.
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("etable", "experiment"),
            // killer560 (2026-11-10): "the get sent to lobby thing" should come up for lobby / lobbied.
            Map.entry("lobby", "kick rejoin timer"),
            Map.entry("lobbied", "kick rejoin timer"),
            // cheatbugs-1109: renamed from Terminator Auto Clicker.
            Map.entry("terminator clicker", "shortbow auto clicker"),
            Map.entry("terminator auto", "shortbow auto clicker"),
            // featC-1108: other mods names for these.
            Map.entry("puzzle display", "puzzle hud"),
            Map.entry("puzzle timer", "puzzle hud"),
            Map.entry("rp", "reparty"),
            Map.entry("vroom", "watcher helper"),
            // featG-1109: other mods names for these.
            Map.entry("carry", "carry tracker"),
            Map.entry("carries", "carry tracker"),
            Map.entry("carry counter", "carry tracker"),
            Map.entry("watcher speed", "watcher helper"),
            // orderhelper-1109: renamed from Bazaar Order Helper.
            Map.entry("bazaar order helper", "order helper"),
            Map.entry("bz order helper", "order helper"),
            Map.entry("secret triggerbot", "secret aura"),
            Map.entry("leap look", "no rotate"),
            Map.entry("leap takes target", "no rotate"),
            Map.entry("jungle pickaxe", "wrong armor alert"),
            // killer560 (2026-11-04): the built-in mod list is "essentially mod menu".
            Map.entry("mod menu", "mods screen"),
            Map.entry("modmenu", "mods screen"),
            Map.entry("mod list", "mods screen"),
            Map.entry("e table", "experiment"),
            Map.entry("exp table", "experiment"),
            // killer560 (2026-10-18): "make chat keybinds also pop up if I search something like chat commands" - the tab was
            // renamed from Command Shortcuts, so the old names find it too.
            Map.entry("chat command", "chat keybind"),
            Map.entry("command shortcut", "chat keybind"),
            Map.entry("command keybind", "chat keybind"),
            // killer560 (2026-10-18): "cf" should find the Chocolate Factory tab.
            Map.entry("cf", "chocolate factory"),
            // killer560 (2026-10-19): "make chocolate factory also show up if you search something like hoppity or egg".
            Map.entry("hoppity", "chocolate factory"),
            Map.entry("egg", "chocolate factory"),
            Map.entry("egglocator", "chocolate factory"),
            // killer560 (2026-11-01, to-do Doc "ZPEW"): "make the setting show up if you search zpew". The Zero Ping
            // Etherwarp switch row of the Etherwarp tab answers it.
            Map.entry("zpew", "zero ping etherwarp"),
            // drillfuel-1104: the Drill Fuel Bar and Drill Fuel Text rows in Health and Mana Bars.
            Map.entry("fuel bar", "drill fuel"),
            // killer560 (2026-11-01, to-do Doc "VIDEO BROWSER"): "make this show up if i search things like tictok or yt or
            // youtube or web or anything like that".
            Map.entry("tiktok", "video browser"),
            Map.entry("tictok", "video browser"),
            Map.entry("tik tok", "video browser"),
            Map.entry("yt", "video browser"),
            Map.entry("youtube", "video browser"),
            Map.entry("web", "video browser"),
            Map.entry("browser", "video browser"),
            Map.entry("reels", "video browser"),
            Map.entry("instagram", "video browser"),
            Map.entry("twitch", "video browser"),
            Map.entry("shorts", "video browser"),
            // killer560 (2026-11-02, "i keep searching for things we have and I cant find them"): words typed apart that
            // the menu writes as one, and the effects panel he asked to hide.
            Map.entry("trigger bot", "triggerbot"),
            Map.entry("free look", "freelook"),
            Map.entry("full bright", "fullbright"),
            Map.entry("night vision", "fullbright"),
            Map.entry("potion effect", "hide potion effects"),
            // pvstyle-1104: the Profile Viewer's Styles are named after NEU's and SkyBlockPV's viewers.
            Map.entry("neu", "profile viewer"),
            Map.entry("skyblockpv", "profile viewer"),
            Map.entry("skyblock pv", "profile viewer"),
            Map.entry("sbpv", "profile viewer"),
            Map.entry("pv style", "profile viewer"),
            Map.entry("pv theme", "profile viewer"),
            // README names (2026-11-02) that are not a card's name: the card they live in.
            Map.entry("goldor frenzy", "tick timers"),
            Map.entry("frenzy timer", "tick timers"),
            Map.entry("sweat mode", "mod & hud"),
            Map.entry("dungeon sim room filters", "sim settings"),
            Map.entry("dungeon sim f7 boss", "sim settings"),
            Map.entry("dungeon sim teleports", "sim settings"),
            Map.entry("dungeon sim room capture", "sim settings"),
            Map.entry("dungeon sim", "sim settings"),
            Map.entry("cheat utilities", "secret aura"),
            // killer560 (2026-11-04) calls the Structure Scanner "the cheat map scanner".
            Map.entry("map scanner", "crystal hollows map"),
            // minedeploy-1104 (killer560, 2026-11-04): the Deployables card answers the item names players type.
            Map.entry("lantern", "deployables"),
            Map.entry("deployable", "deployables"),
            Map.entry("power orb", "deployables"),
            Map.entry("orb", "deployables"),
            Map.entry("plasmaflux", "deployables"),
            Map.entry("flare", "deployables"),
            Map.entry("umberella", "deployables"),
            Map.entry("umbrella", "deployables"),
            Map.entry("will-o'-wisp", "deployables"),
            // colltrack-1104: the Collection Tracker card by its short names.
            Map.entry("coll tracker", "collection tracker"),
            Map.entry("colltracker", "collection tracker"));

    /** A typed prefix shorter than this only matches the alias when typed in full ("e" alone stays plain). */
    private static final int MIN_PREFIX = 2;

    /**
     * Whether {@code name} answers the typed {@code query} (killer560, 2026-10-18: "make copy chat also pop up in the search
     * bar when i search chat copy"): the query as it is typed, or, for a query of several words, every word somewhere in
     * the name in any order. "chat copy" finds Copy Chat; one word behaves exactly as a plain contains.
     */
    public static boolean nameMatches(String name, String query) {
        String n = name.toLowerCase(Locale.ROOT);
        String q = query.toLowerCase(Locale.ROOT).trim();
        if (q.isEmpty() || n.contains(q)) {
            return true;
        }
        String[] words = q.split("\\s+");
        if (words.length < 2) {
            return false;
        }
        for (String w : words) {
            if (!n.contains(w)) {
                return false;
            }
        }
        return true;
    }

    /** The typed query first, then each alias target the query names - in full, inside a longer query, or as
     *  the start of the alias word. */
    public static List<String> variants(String query) {
        List<String> out = new ArrayList<>();
        out.add(query);
        String q = query.toLowerCase(Locale.US).trim();
        if (q.isEmpty()) {
            return out;
        }
        for (Map.Entry<String, String> e : ALIASES.entrySet()) {
            String key = e.getKey();
            String target = null;
            if (q.contains(key)) {
                target = q.replace(key, e.getValue());
            } else if (q.length() >= MIN_PREFIX && key.startsWith(q)) {
                target = e.getValue();
            }
            if (target != null && !out.contains(target)) {
                out.add(target);
            }
        }
        return out;
    }
}
