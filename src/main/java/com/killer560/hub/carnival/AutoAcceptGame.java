package com.killer560.hub.carnival;

import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.util.regex.Pattern;

/**
 * Auto Accept Game (cheat build only, killer560 2026-11-11: "i want it to always auto click aye sure do or whatever the
 * green one is"): when you talk to a Carnival game NPC (Carnival Pirateman, Cowboy or Fisherman) and it posts its
 * "Select an option:" list, runs the command of the FIRST option (the green one that starts the game, "[Aye sure do!]")
 * once, as a click on it would: {@code sendCommand} of the {@link ClickEvent.RunCommand}, the path
 * {@code AutoDialogueFeature} and a real chat click use. No other option is ever picked, and the Carnival Leader's question
 * list is never touched (only the three game NPCs open the window). The options may sit on the "Select an option:" line
 * itself or on the lines right after it. A chat command, not a look or a click, so the Grim same-tick rules do not apply.
 * Automation: can get you banned; keep it off on the main server. Off by default.
 */
public final class AutoAcceptGame {

    private static final Logger LOGGER = ModLog.get("killer560smod-carnival");
    static final String NAME = "Auto Accept Game";
    private static final Pattern GAME_NPC = Pattern.compile("^\\[NPC] Carnival (?:Pirateman|Cowboy|Fisherman): .*");
    private static final String PROMPT = "Select an option:";
    /** An NPC line this recent opens the window for its option list. */
    static final long NPC_WINDOW_MS = 10_000;
    /** The option lines must follow the prompt this closely. */
    static final long OPTIONS_WINDOW_MS = 3_000;
    private static long npcAtMs;
    private static long promptAtMs;
    private static ClickEvent pending;
    private static int sent;

    private AutoAcceptGame() {
    }

    public static void register() {
        ChatObserver.subscribe(AutoAcceptGame::onChat);
        ClientTickEvents.END_CLIENT_TICK.register(AutoAcceptGame::tick);
    }

    /** One chat line, at {@code nowMs}: notes a game NPC, the prompt, and picks the first option's command. */
    static void onChat(Component message) {
        if (!AutoAcceptGameConfig.getInstance().isEnabledRaw()) {
            return;
        }
        feed(message, System.currentTimeMillis());
    }

    static void feed(Component message, long nowMs) {
        String plain = ChatObserver.strip(message);
        if (plain == null) {
            return;
        }
        plain = plain.trim();
        if (plain.startsWith("[NPC] ")) {
            promptAtMs = 0;
            npcAtMs = GAME_NPC.matcher(plain).matches() ? nowMs : 0;
            return;
        }
        if (npcAtMs == 0 || nowMs - npcAtMs > NPC_WINDOW_MS) {
            return;
        }
        if (plain.startsWith(PROMPT)) {
            // Inline options: the first clickable option anywhere in the line. Otherwise the options come on the next lines.
            ClickEvent inline = firstOption(message);
            if (inline != null) {
                pending = inline;
                npcAtMs = 0;
            } else {
                promptAtMs = nowMs;
            }
            return;
        }
        if (promptAtMs != 0 && nowMs - promptAtMs <= OPTIONS_WINDOW_MS) {
            // The first option line after the prompt decides it, picked or not: nothing later is ever taken.
            promptAtMs = 0;
            npcAtMs = 0;
            pending = firstOption(message);
        }
    }

    private static String command(Component c) {
        return c.getStyle().getClickEvent() instanceof ClickEvent.RunCommand run && run.command() != null
                && !run.command().isBlank() ? run.command() : null;
    }

    /** The first option a click would pick in {@code c}, depth first: a run-command, or a Custom click action (how
     *  Hypixel's game NPCs offer them: skyblock:dialogue_response with the npc and response key, his 11-14 log). */
    static ClickEvent firstOption(Component c) {
        ClickEvent e = c.getStyle().getClickEvent();
        if (e instanceof ClickEvent.Custom || command(c) != null) {
            return e;
        }
        for (Component sibling : c.getSiblings()) {
            ClickEvent found = firstOption(sibling);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** The first run-command click event in {@code c}, depth first (Hypixel's prompt is a plain prefix, an arrow, then the option). */
    public static String firstRunCommand(Component c) {
        String own = command(c);
        if (own != null) {
            return own;
        }
        for (Component sibling : c.getSiblings()) {
            String found = firstRunCommand(sibling);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static void tick(Minecraft mc) {
        if (pending == null) {
            return;
        }
        ClickEvent option = pending;
        pending = null;
        if (mc.player == null || mc.getConnection() == null || !AutoAcceptGameConfig.getInstance().isEnabled()) {
            return;
        }
        if (option instanceof ClickEvent.Custom custom) {
            // What vanilla's chat click sends for a Custom click event.
            mc.getConnection().send(new net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket(
                    custom.id(), custom.payload()));
            sent++;
            lastCommand = custom.id() + " " + custom.payload().map(Object::toString).orElse("");
            ModChat.log(NAME, ModChat.text("Accepted the game."));
            LOGGER.info("[{}] sent custom click {}", NAME, lastCommand);
            return;
        }
        String command = ((ClickEvent.RunCommand) option).command();
        String stripped = command.startsWith("/") ? command.substring(1) : command;
        mc.getConnection().sendCommand(stripped);
        sent++;
        lastCommand = stripped;
        ModChat.log(NAME, ModChat.text("Accepted the game (/" + stripped + ")."));
        LOGGER.info("[{}] ran '/{}'", NAME, stripped);
    }

    private static String lastCommand;

    /** Harness: commands sent. */
    public static int sent() {
        return sent;
    }

    /** Harness: the last command sent (without the slash), or null. */
    public static String lastCommand() {
        return lastCommand;
    }
}
