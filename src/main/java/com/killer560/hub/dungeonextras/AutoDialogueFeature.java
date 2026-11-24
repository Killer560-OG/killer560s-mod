package com.killer560.hub.dungeonextras;

import com.killer560.hub.util.ChatObserver;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto Dialogue (cheat build) - port of (also used verbatim by the
 * {@code ChatFeatures.kt}, origin/26.1.2): when a chat line's plain text starts with {@code "Select an option: "}
 * and does not contain {@code "[BARBARIANS] [MAGES]"}, run the {@link ClickEvent.RunCommand} of its first sibling
 * (the first option). Extra safety on top of the reference: a configurable delay, every NPC (the NPC-name allow-list was removed 2026-11-09), a purchase/trade keyword block-list
 * checked against the option line, the command and the preceding NPC line, and a 3s same-command guard.
 */
public final class AutoDialogueFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-dungeonextras");
    private static final String OPTION_PREFIX = "Select an option: ";
    private static final String FACTION_CHOICE = "[BARBARIANS] [MAGES]";
    private static final Pattern NPC_LINE = Pattern.compile("^\\[NPC] ([^:]+): (.*)$");
    /** The only commands ever sent, from the real captures: a numbered NPC option, or the YES/NO prompt of an NPC that just spoke. */
    private static final Pattern NPC_OPTION_COMMAND = Pattern.compile("^/selectnpcoption \\S+ \\S+$");
    private static final Pattern PROMPT_COMMAND = Pattern.compile("^/chatprompt [0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12} \\w+$");
    private static final String PROMPT_MARK = "Click an option: ";
    private static final long NPC_CONTEXT_MS = 10_000L;
    private static final long SAME_COMMAND_GUARD_MS = 3_000L;
    private static final List<String> UNSAFE_KEYWORDS = List.of(
            "buy", "buying", "bought", "purchase", "confirm", "coins", "coin", "sell", "selling", "pay", "paying",
            "trade", "trading", "bits", "gems", "cost", "costs", "price", "spend", "upgrade", "unlock", "faction",
            "reforge", "auction", "bank", "deposit", "withdraw", "give", "giving", "donate", "exchange", "swap",
            "sacrifice", "salvage", "recombobulate", "bid", "offer", "motes", "mote", "copper", "essence",
            "tokens", "token", "pelts", "gold", "fee", "rent", "reset", "delete", "remove", "destroy");
    // Review fix (2026-09-15): one precompiled pattern instead of compiling 18+ regexes per chat line.
    private static final Pattern UNSAFE_PATTERN = Pattern.compile(
            "\\b(" + String.join("|", UNSAFE_KEYWORDS.stream().map(Pattern::quote).toList()) + ")\\b");
    // Review fix (2026-09-15): costs are often only numbers ("for 5k", "1,250", "x64", "64x") with no keyword.
    private static final Pattern COST_NUMBER = Pattern.compile(
            "\\b\\d+(?:\\.\\d+)?[kmb]\\b|\\b\\d{1,3}(?:,\\d{3})+\\b|\\bx\\d+\\b|\\b\\d+x\\b");

    private static String lastNpcName = null;
    private static long lastNpcAtMs = 0;
    /** Every [NPC] line from the last {@link #NPC_CONTEXT_MS} - a price is often said 2-3 lines before the options. */
    private static final java.util.ArrayDeque<String> recentNpcTexts = new java.util.ArrayDeque<>();
    private static final java.util.ArrayDeque<Long> recentNpcTimes = new java.util.ArrayDeque<>();

    private static String pendingCommand = null;
    private static int pendingTicks = 0;
    private static String lastSentCommand = null;
    private static long lastSentAtMs = 0;

    private AutoDialogueFeature() {
    }

    static void register() {
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return; // legit jar: the feature cannot act, so it listens to nothing
        }
        ChatObserver.subscribe(AutoDialogueFeature::onChat);
    }

    private static void onChat(Component message) {
        // Switched off: no NPC bookkeeping, no regex, no string work for any chat line.
        if (!DungeonExtrasConfig.getInstance().isAutoDialogueEnabledRaw()) {
            return;
        }
        String plain = ChatObserver.strip(message).trim();
        Matcher npc = plain.startsWith("[NPC] ") ? NPC_LINE.matcher(plain) : null; // anchored on it (fps-idle-1015)
        long nowMs = System.currentTimeMillis();
        while (!recentNpcTimes.isEmpty() && nowMs - recentNpcTimes.peekFirst() > NPC_CONTEXT_MS) {
            recentNpcTimes.pollFirst();
            recentNpcTexts.pollFirst();
        }
        if (npc != null && npc.matches()) {
            lastNpcName = npc.group(1).trim();
            lastNpcAtMs = nowMs;
            recentNpcTexts.addLast(npc.group(2));
            recentNpcTimes.addLast(nowMs);
            while (recentNpcTexts.size() > 8) {
                recentNpcTexts.pollFirst();
                recentNpcTimes.pollFirst();
            }
            return;
        }
        DungeonExtrasConfig cfg = DungeonExtrasConfig.getInstance();
        boolean select = plain.startsWith(OPTION_PREFIX);
        if (!cfg.isAutoDialogueEnabled() || !(select || plain.contains(PROMPT_MARK)) || plain.contains(FACTION_CHOICE)) {
            return;
        }
        // Carnival NPCs belong to the "Carnival Games" row (AutoAcceptGame); this feature never answers them.
        if (nowMs - lastNpcAtMs <= NPC_CONTEXT_MS && lastNpcName != null && lastNpcName.startsWith("Carnival ")) {
            return;
        }
        // Hypixel nests the clickable option under plain siblings (a prefix, an arrow, then the option): search depth first.
        String command = firstRunCommand(message);
        boolean recentNpc = nowMs - lastNpcAtMs <= NPC_CONTEXT_MS;
        if (command == null || !isDialogueCommand(command, select, recentNpc, plain)) {
            return;
        }
        String npcName = recentNpc ? lastNpcName : null;
        String npcText = String.join(" ", recentNpcTexts);
        // Review fix (2026-09-15): the cost of an option is frequently only in its hover tooltip.
        StringBuilder hover = new StringBuilder();
        collectHover(message, hover);

        String unsafe = findUnsafeKeyword(plain + " " + command + " " + npcText + hover);
        if (unsafe != null) {
            LOGGER.info("[DungeonExtras] Auto Dialogue skipped: blocked keyword '{}' (npc='{}', command='{}').", unsafe, npcName, command);
            return;
        }
        pendingCommand = command;
        pendingTicks = cfg.getAutoDialogueDelayTicks();
    }

    /**
     * Allow rule from the real lines: "Select an option:" answers with /selectnpcoption; a "Click an option:" question
     * (the Trapper's "Accept the trapper's task..." YES/NO) answers with /chatprompt only right after an [NPC] line and
     * when its first line is a bare question (a player's or party line carries "Name: " or a bracket). Custom click
     * events (the [GIVE ITEM] button) and every other command are never run.
     */
    static boolean isDialogueCommand(String command, boolean select, boolean recentNpc, String plain) {
        if (select) {
            return NPC_OPTION_COMMAND.matcher(command).matches();
        }
        String first = plain.strip().lines().findFirst().orElse("");
        return recentNpc && PROMPT_COMMAND.matcher(command).matches() && !first.startsWith("[") && !first.contains(": ");
    }

    /** The first run-command click event in {@code c}, depth first. */
    static String firstRunCommand(Component c) {
        if (c.getStyle().getClickEvent() instanceof ClickEvent.RunCommand run && run.command() != null && !run.command().isBlank()) {
            return run.command();
        }
        for (Component sibling : c.getSiblings()) {
            String found = firstRunCommand(sibling);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static void collectHover(Component c, StringBuilder out) {
        if (c.getStyle().getHoverEvent() instanceof HoverEvent.ShowText showText && showText.value() != null) {
            out.append(' ').append(showText.value().getString());
        }
        for (Component sibling : c.getSiblings()) {
            collectHover(sibling, out);
        }
    }

    private static String findUnsafeKeyword(String text) {
        String lower = text.toLowerCase(Locale.US);
        Matcher keyword = UNSAFE_PATTERN.matcher(lower);
        if (keyword.find()) {
            return keyword.group(1);
        }
        Matcher cost = COST_NUMBER.matcher(lower);
        return cost.find() ? cost.group() : null;
    }

    static void onClientTick(Minecraft client) {
        if (pendingCommand == null) {
            return;
        }
        DungeonExtrasConfig cfg = DungeonExtrasConfig.getInstance();
        if (client.player == null || client.getConnection() == null || !cfg.isAutoDialogueEnabled()) {
            pendingCommand = null;
            return;
        }
        if (pendingTicks > 0) {
            pendingTicks--;
            return;
        }
        String command = pendingCommand;
        pendingCommand = null;
        long now = System.currentTimeMillis();
        if (command.equals(lastSentCommand) && now - lastSentAtMs < SAME_COMMAND_GUARD_MS) {
            return;
        }
        lastSentCommand = command;
        lastSentAtMs = now;
        String stripped = command.startsWith("/") ? command.substring(1) : command;
        client.getConnection().sendCommand(stripped);
        LOGGER.info("[DungeonExtras] Auto Dialogue ran '/{}' (npc='{}').", stripped, lastNpcName);
    }
}
