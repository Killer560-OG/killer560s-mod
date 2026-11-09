package com.killer560.hub.gui;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.fabric.impl.command.client.ClientCommandInternals;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The chat commands the menu search finds a card by (killer560, 2026-11-02: "i search invsort and the inventory sorter
 * doesnt show up even tho it is the command"). Every root command registered on the client dispatcher whose handler is
 * this mod's code gets an owner card: by the command's name first ({@link #BY_COMMAND}), then by the class that declared
 * its handler ({@link #BY_CLASS}: a whole command family such as the /f7-style shortcuts or /ar and /autoroutes), then by
 * that class's package ({@link #BY_PACKAGE}). {@link #NO_CARD} lists the commands with no card on purpose. Typing a command,
 * with or without its "/", ranks its card like an exact card name. Before a world is joined there is no dispatcher; then
 * {@link #BY_COMMAND} alone answers.
 */
public final class SearchCommands {

    private SearchCommands() {
    }

    /** Command name -> owner tab (simple class name). Commands declared in Killer560ModClient, and the main ones, so the
     *  search finds them from the title screen too. */
    static final Map<String, String> BY_COMMAND = Map.ofEntries(
            Map.entry("cringe", "CringeTab"),
            Map.entry("chmap", "CrystalHollowsMapTab"),
            Map.entry("ew", "EtherwarpTab"),
            Map.entry("language", "TranslateTab"),
            Map.entry("translate", "TranslateTab"),
            Map.entry("leaporder", "LeapMenuTab"),
            Map.entry("posmsg", "PosmsgTab"),
            Map.entry("termism", "TermismTab"),
            Map.entry("invsort", "InventorySorterTab"),
            Map.entry("autosell", "AutoSellTab"),
            Map.entry("autokick", "AutoKickTab"),
            Map.entry("rp", "RepartyTab"),
            Map.entry("reparty", "RepartyTab"),
            Map.entry("ap3", "Ap3Tab"),
            Map.entry("ar", "AutoRoutesTab"),
            Map.entry("autoroutes", "AutoRoutesTab"),
            Map.entry("customitems", "CustomItemsTab"),
            Map.entry("ah", "AuctionHouseTab"),
            Map.entry("bz", "BazaarTab"),
            Map.entry("bzflip", "BazaarAdvisorTab"),
            Map.entry("bazaarflip", "BazaarFlipTab"),
            Map.entry("bzscan", "BazaarFlipTab"),
            Map.entry("croesus", "CroesusTab"),
            Map.entry("profit", "ExperimentsTab"),
            Map.entry("breakeraura", "BreakerAuraTab"),
            Map.entry("k", "LegitGfsTab"),
            Map.entry("luck", "LuckTab"),
            Map.entry("kc", "ModChatTab"),
            Map.entry("runlog", "RunSummaryTab"),
            Map.entry("bestfriends", "BestFriendsTab"),
            Map.entry("bf", "BestFriendsTab"),
            Map.entry("fl", "FriendsListTab"),
            Map.entry("flcustom", "FriendsListTab"),
            Map.entry("streamer", "StreamerModeTab"),
            Map.entry("streamermode", "StreamerModeTab"),
            Map.entry("search", "StorageSearchTab"),
            Map.entry("pv", "ProfileViewerTab"),
            Map.entry("pf", "CustomPartyFinderTab"),
            Map.entry("partyfinder", "CustomPartyFinderTab"));

    /** Class that declared a command's handler -> owner tab: command families named at run time. */
    static final Map<String, String> BY_CLASS = Map.ofEntries(
            Map.entry("CommandShortcutsFeature", "CommandKeybindsTab"),
            Map.entry("AutoRoutesCommands", "AutoRoutesTab"),
            Map.entry("LfgCommands", "CustomPartyFinderTab"),
            Map.entry("BreakerAuraCommands", "BreakerAuraTab"),
            Map.entry("BestFriendsCommands", "BestFriendsTab"),
            Map.entry("FriendsListCommands", "FriendsListTab"));

    /** Package under com.killer560.hub of a command's handler -> owner tab. */
    static final Map<String, String> BY_PACKAGE = Map.ofEntries(
            Map.entry("invsort", "InventorySorterTab"),
            Map.entry("autosell", "AutoSellTab"),
            Map.entry("autokick", "AutoKickTab"),
            Map.entry("ap3", "Ap3Tab"),
            Map.entry("autoroutes", "AutoRoutesTab"),
            Map.entry("armourdye", "CustomItemsTab"),
            Map.entry("auction", "AuctionHouseTab"),
            Map.entry("bazaar", "BazaarTab"),
            Map.entry("bazaaradvisor", "BazaarAdvisorTab"),
            Map.entry("bazaarflip", "BazaarFlipTab"),
            Map.entry("roomsim", "SimSettingsTab"),
            Map.entry("bosssim", "SimSettingsTab"),
            Map.entry("commandshortcuts", "CommandKeybindsTab"),
            Map.entry("croesus", "CroesusTab"),
            Map.entry("gfs", "LegitGfsTab"),
            Map.entry("luck", "LuckTab"),
            Map.entry("modchat", "ModChatTab"),
            Map.entry("pathfinding", "PathfindingTab"),
            Map.entry("runsummary", "RunSummaryTab"),
            Map.entry("streamer", "StreamerModeTab"),
            Map.entry("lfg", "CustomPartyFinderTab"),
            Map.entry("scorecalc", "ScoreCalculatorTab"),
            Map.entry("storagesearch", "StorageSearchTab"),
            Map.entry("profileviewer", "ProfileViewerTab"));

    /** Commands with no card, and why (the testkit requires every other mod command to have one). */
    public static final Map<String, String> NO_CARD = Map.ofEntries(
            Map.entry("killer560", "opens this menu"),
            Map.entry("k560tested", "testing build: marks a tab tested"),
            Map.entry("reboot", "restarts the game; no setting"));

    private static final String ROOT = "com.killer560.hub.";

    /** Every root command this mod registered on the client dispatcher right now -> its owner tab's simple name (null for
     *  none), or {@link #BY_COMMAND} when there is no dispatcher (title screen). For the search and the testkit. */
    public static Map<String, String> owners() {
        Map<String, String> out = new LinkedHashMap<>();
        CommandDispatcher<?> d = null;
        try {
            d = ClientCommandInternals.getActiveDispatcher();
        } catch (Throwable ignored) {
        }
        if (d == null) {
            out.putAll(BY_COMMAND);
            return out;
        }
        for (CommandNode<?> node : d.getRoot().getChildren()) {
            String cls = handlerClass(node);
            if (cls == null || !cls.startsWith(ROOT)) {
                continue;
            }
            out.put(node.getName(), owner(node.getName(), cls));
        }
        return out;
    }

    /** The class (fully qualified, lambda suffix cut) holding the first handler under {@code node}, or null. */
    public static String handlerClass(CommandNode<?> node) {
        Deque<CommandNode<?>> todo = new ArrayDeque<>();
        todo.add(node);
        int seen = 0;
        while (!todo.isEmpty() && seen++ < 200) {
            CommandNode<?> n = todo.poll();
            Command<?> c = n.getCommand();
            if (c != null) {
                String name = c.getClass().getName();
                int cut = name.indexOf("$$");
                return cut > 0 ? name.substring(0, cut) : name;
            }
            if (n.getRedirect() != null) {
                todo.add(n.getRedirect());
            }
            todo.addAll(n.getChildren());
        }
        return null;
    }

    static String owner(String command, String cls) {
        String byName = BY_COMMAND.get(command);
        if (byName != null) {
            return byName;
        }
        String simple = cls.substring(cls.lastIndexOf('.') + 1);
        int inner = simple.indexOf('$');
        if (inner > 0) {
            simple = simple.substring(0, inner);
        }
        String byClass = BY_CLASS.get(simple);
        if (byClass != null) {
            return byClass;
        }
        if (cls.startsWith(ROOT)) {
            String rest = cls.substring(ROOT.length());
            int dot = rest.indexOf('.');
            if (dot > 0) {
                return BY_PACKAGE.get(rest.substring(0, dot));
            }
        }
        return null;
    }
}
