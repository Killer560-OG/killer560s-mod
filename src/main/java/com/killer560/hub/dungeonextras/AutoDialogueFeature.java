package com.killer560.hub.dungeonextras;

import com.killer560.hub.util.ChatObserver;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto Dialogue (cheat build) - port of QUOI's {@code AutoDialogue.kt} (also used verbatim by NoammAddons'
 * {@code ChatFeatures.kt}, origin/26.1.2): when a chat line's plain text starts with {@code "Select an option: "}
 * and does not contain {@code "[BARBARIANS] [MAGES]"}, run the {@link ClickEvent.RunCommand} of its first sibling
 * (the first option). Extra safety on top of the reference: a configurable delay, an optional NPC-name allow-list
 * (matched against the most recent {@code [NPC] Name: ...} line within 10s), a purchase/trade keyword block-list
 * checked against the option line, the command and the preceding NPC line, and a 3s same-command guard.
 */
public final class AutoDialogueFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-dungeonextras");
    private static final String OPTION_PREFIX = "Select an option: ";
    private static final String FACTION_CHOICE = "[BARBARIANS] [MAGES]";
    private static final Pattern NPC_LINE = Pattern.compile("^\\[NPC] ([^:]+): (.*)$");
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
        ChatObserver.subscribe(AutoDialogueFeature::onChat);
    }

    private static void onChat(Component message) {
        String plain = ChatObserver.strip(message).trim();
        Matcher npc = NPC_LINE.matcher(plain);
        long nowMs = System.currentTimeMillis();
        while (!recentNpcTimes.isEmpty() && nowMs - recentNpcTimes.peekFirst() > NPC_CONTEXT_MS) {
            recentNpcTimes.pollFirst();
            recentNpcTexts.pollFirst();
        }
        if (npc.matches()) {
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
        if (!cfg.isAutoDialogueEnabled() || !plain.startsWith(OPTION_PREFIX) || plain.contains(FACTION_CHOICE)) {
            return;
        }
        // Bug fix (2026-09-15): the QUOI reference runs anywhere; limited to dungeons unless the user opts out.
        if (!cfg.isAutoDialogueOutsideDungeons() && !com.killer560.hub.secrets.DungeonState.isInDungeon()) {
            return;
        }
        List<Component> siblings = message.getSiblings();
        if (siblings.isEmpty()) {
            return;
        }
        Component first = siblings.get(0);
        if (!(first.getStyle().getClickEvent() instanceof ClickEvent.RunCommand run)) {
            return;
        }
        String command = run.command();
        if (command == null || command.isBlank()) {
            return;
        }
        boolean recentNpc = nowMs - lastNpcAtMs <= NPC_CONTEXT_MS;
        String npcName = recentNpc ? lastNpcName : null;
        String npcText = String.join(" ", recentNpcTexts);
        // Review fix (2026-09-15): the cost of an option is frequently only in its hover tooltip.
        StringBuilder hover = new StringBuilder();
        for (Component sibling : siblings) {
            if (sibling.getStyle().getHoverEvent() instanceof HoverEvent.ShowText showText && showText.value() != null) {
                hover.append(' ').append(showText.value().getString());
            }
        }

        String filter = cfg.getAutoDialogueNpcFilter().trim();
        if (!filter.isEmpty()) {
            boolean allowed = false;
            if (npcName != null) {
                for (String name : filter.split(",")) {
                    if (!name.isBlank() && npcName.equalsIgnoreCase(name.trim())) {
                        allowed = true;
                        break;
                    }
                }
            }
            if (!allowed) {
                LOGGER.info("[DungeonExtras] Auto Dialogue skipped: NPC '{}' not in filter.", npcName);
                return;
            }
        }
        String unsafe = findUnsafeKeyword(plain + " " + first.getString() + " " + command + " " + npcText + hover);
        if (unsafe != null) {
            LOGGER.info("[DungeonExtras] Auto Dialogue skipped: blocked keyword '{}' (npc='{}', command='{}').", unsafe, npcName, command);
            return;
        }
        pendingCommand = command;
        pendingTicks = cfg.getAutoDialogueDelayTicks();
    }

    private static String findUnsafeKeyword(String text) {
        String lower = text.toLowerCase(Locale.US);
        Matcher keyword = UNSAFE_PATTERN.matcher(lower);
        if (keyword.find()) {
            return keyword.group(1);
        }
        Matcher cost = COST_NUMBER.matcher(lower);
        if (cost.find()) {
            return cost.group();
        }
        return null;
    }

    static void onClientTick(Minecraft client) {
        if (pendingCommand == null) {
            return;
        }
        DungeonExtrasConfig cfg = DungeonExtrasConfig.getInstance();
        if (client.player == null || client.getConnection() == null || !cfg.isAutoDialogueEnabled()
                || (!cfg.isAutoDialogueOutsideDungeons() && !com.killer560.hub.secrets.DungeonState.isInDungeon())) {
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
