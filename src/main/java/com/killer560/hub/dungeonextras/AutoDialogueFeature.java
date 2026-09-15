package com.killer560.hub.dungeonextras;

import com.killer560.hub.util.ChatObserver;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
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
            "buy", "purchase", "confirm", "coins", "coin", "sell", "pay", "trade", "bits", "gems", "cost",
            "price", "spend", "upgrade", "unlock", "faction", "reforge", "auction");

    private static String lastNpcName = null;
    private static String lastNpcText = null;
    private static long lastNpcAtMs = 0;

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
        if (npc.matches()) {
            lastNpcName = npc.group(1).trim();
            lastNpcText = npc.group(2);
            lastNpcAtMs = System.currentTimeMillis();
            return;
        }
        DungeonExtrasConfig cfg = DungeonExtrasConfig.getInstance();
        if (!cfg.isAutoDialogueEnabled() || !plain.startsWith(OPTION_PREFIX) || plain.contains(FACTION_CHOICE)) {
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
        boolean recentNpc = System.currentTimeMillis() - lastNpcAtMs <= NPC_CONTEXT_MS;
        String npcName = recentNpc ? lastNpcName : null;
        String npcText = recentNpc ? lastNpcText : null;

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
        String unsafe = findUnsafeKeyword(plain + " " + first.getString() + " " + command + " " + (npcText == null ? "" : npcText));
        if (unsafe != null) {
            LOGGER.info("[DungeonExtras] Auto Dialogue skipped: blocked keyword '{}' (npc='{}', command='{}').", unsafe, npcName, command);
            return;
        }
        pendingCommand = command;
        pendingTicks = cfg.getAutoDialogueDelayTicks();
    }

    private static String findUnsafeKeyword(String text) {
        String lower = text.toLowerCase(Locale.US);
        for (String keyword : UNSAFE_KEYWORDS) {
            if (Pattern.compile("\\b" + Pattern.quote(keyword) + "\\b").matcher(lower).find()) {
                return keyword;
            }
        }
        return null;
    }

    static void onClientTick(Minecraft client) {
        if (pendingCommand == null) {
            return;
        }
        if (client.player == null || client.getConnection() == null || !DungeonExtrasConfig.getInstance().isAutoDialogueEnabled()) {
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
