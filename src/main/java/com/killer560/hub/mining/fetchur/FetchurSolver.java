package com.killer560.hub.mining.fetchur;

import com.killer560.hub.islands.IslandConfig;
import com.killer560.hub.mining.MiningAlerts;
import com.killer560.hub.mining.MiningIsland;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModChat;
import net.minecraft.network.chat.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetchur Solver (legit, both builds; Skyblocker's Fetchur): in the Dwarven Mines, Fetchur's daily riddle ("its yellow and
 * see through") is answered in chat with the item he wants, and optionally as a title. Reads chat only; sends nothing.
 * Off by default.
 */
public final class FetchurSolver {

    public static final IslandConfig CFG = IslandConfig.of("mining", "fetchur")
            .flag("chat", true)
            .flag("title", false);

    /** Skyblocker's line, anchored: Hypixel's NPC prefix starts the line, so another player's chat cannot match. */
    private static final Pattern RIDDLE = Pattern.compile("^\\[NPC] Fetchur: (?:its|theyre) ([a-zA-Z, \\-]{1,60})$");

    /** Riddle -> item, Skyblocker's table with the item names written out and the amount he wants (wiki Fetchur page). */
    private static final Map<String, String> ANSWERS = Map.ofEntries(
            Map.entry("yellow and see through", "20x Yellow Stained Glass"),
            Map.entry("circular and sometimes moves", "1x Compass"),
            Map.entry("expensive minerals", "20x Mithril"),
            Map.entry("useful during celebrations", "1x Firework Rocket"),
            Map.entry("hot and gives energy", "1x Cheap / Decent / Black Coffee"),
            Map.entry("tall and can be opened", "1x Any Wooden Door / Iron Door"),
            Map.entry("brown and fluffy", "3x Rabbit's Foot"),
            Map.entry("explosive but more than usual", "1x Superboom TNT"),
            Map.entry("wearable and grows", "1x Pumpkin"),
            Map.entry("shiny and makes sparks", "1x Flint and Steel"),
            Map.entry("green and some dudes trade stuff for it", "50x Emerald"),
            Map.entry("red and soft", "50x Red Wool"));

    private static volatile String lastAnswer = null;

    private FetchurSolver() {
    }

    public static void register() {
        ChatObserver.subscribe(FetchurSolver::onChat);
    }

    /** The item for a riddle, or null when the riddle is not in the table. Testkit entry point. */
    public static String answer(String riddle) {
        return riddle == null ? null : ANSWERS.get(riddle.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /** The last answer given this session, or null (read by the testkit). */
    public static String lastAnswer() {
        return lastAnswer;
    }

    private static void onChat(Component message) {
        if (!CFG.isEnabled() || !MiningIsland.dwarvenMines()) {
            return;
        }
        Matcher m = RIDDLE.matcher(ChatObserver.strip(message));
        if (!m.matches()) {
            return;
        }
        String riddle = m.group(1);
        String item = answer(riddle);
        String shown = item != null ? item : "unknown riddle \"" + riddle + "\"";
        lastAnswer = shown;
        if (CFG.on("chat")) {
            ModChat.send("Fetchur", ModChat.text("Fetchur wants: "), ModChat.value(shown));
        }
        if (CFG.on("title") && item != null) {
            MiningAlerts.title("§eFetchur", "§f" + item, 50);
        }
    }
}
