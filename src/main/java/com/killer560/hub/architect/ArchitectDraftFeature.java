package com.killer560.hub.architect;

import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModChat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Architect's First Draft on a puzzle fail - killer560 (2026-09-21): "add a setting to automatically send a chat
 * message that if on click gets an architects first draft. This should only send on puzzle fail. Also add an option to
 * auto get it from sack only on the cheat version that only gets it from sack if you failed the puzzle. The chat
 * message looks something like PUZZLE FAIL! Killer560 was fooled by Eveleth! Yikes!"
 * <p>
 * Real bug found and fixed (2026-09-27, killer560: "It never appeared ... It did work when I failed three weirdos"):
 * this only ever matched the generic {@code "PUZZLE FAIL! <name> ..."} broadcast, which several puzzles (Three
 * Weirdos included) do send - but Quiz doesn't; Oruo narrates the wrong pick itself instead ("[STATUE] Oruo the
 * Omniscient: Killer560 chose the wrong answer! I shall never forget this moment of misrememberance."), so that
 * puzzle's fail never matched and the feature silently never fired for it. Fix: a fail line per puzzle that has its
 * own wording, same list-of-{@link Pattern} idiom {@link com.killer560.hub.puzzlesolvers.WeirdosSolverFeature}'s
 * SOLUTIONS/WRONG tables already use - the first one to match wins and its captured group is always the failer's
 * name (never hardcoded to any one player), so this generalises to every puzzle with a known fail line instead of
 * just one voiceline.
 * <p>
 * Click Message (legit): a local line whose click runs {@code /gfs architect_first_draft 1} - you still click it
 * yourself. Auto Get (cheat build): runs that command itself, only when the name is yours. A few seconds' cooldown
 * so one fail never fires twice.
 */
public final class ArchitectDraftFeature {

    private static final List<Pattern> FAIL_PATTERNS = List.of(
            // Most puzzles (Three Weirdos' wrong chest, Boulder, Ice Fill/Path, Blaze, Water Board...) broadcast this
            // generic line straight from the dungeon's own puzzle-grading system.
            Pattern.compile("^PUZZLE FAIL! (?:\\[[^\\]]+\\] )?([A-Za-z0-9_]{1,16}) "),
            // Quiz has no generic PUZZLE FAIL line of its own - Oruo the Omniscient announces the wrong pick himself.
            // Name is whoever chose wrong, matched as a pattern (not hardcoded to "Killer560" - any player can fail it).
            Pattern.compile("^\\[STATUE] Oruo the Omniscient: (?:\\[[^\\]]+\\] )?([A-Za-z0-9_]{1,16}) chose the wrong answer!")
    );
    private static final String COMMAND = "gfs architect_first_draft 1";
    private static final long COOLDOWN_MS = 3000;
    private static long lastMs;

    private ArchitectDraftFeature() {
    }

    public static void register() {
        ChatObserver.subscribe(ArchitectDraftFeature::onChat);
    }

    private static void onChat(Component message) {
        ArchitectDraftConfig cfg = ArchitectDraftConfig.getInstance();
        if (!cfg.isClickMessage() && !cfg.isAutoGet()) {
            return;
        }
        String text = ChatObserver.strip(message);
        String trimmed = text == null ? "" : text.trim();
        String matchedName = null;
        for (Pattern pattern : FAIL_PATTERNS) {
            Matcher m = pattern.matcher(trimmed);
            if (m.find()) {
                matchedName = m.group(1);
                break;
            }
        }
        if (matchedName == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastMs < COOLDOWN_MS) {
            return;
        }
        lastMs = now;
        // Lambda capture needs an effectively-final variable - matchedName above is reassigned in the loop.
        String failer = matchedName;
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (client.player == null) {
                return;
            }
            String self = client.player.getGameProfile().name();
            boolean mine = self != null && self.equalsIgnoreCase(failer);
            if (mine && cfg.isAutoGet()) {
                client.player.connection.sendCommand(COMMAND);
                ModChat.send("Architect", ModChat.text("Puzzle failed - getting an "), ModChat.value("Architect's First Draft"),
                        ModChat.text(" from your sack."));
                return;
            }
            if (cfg.isClickMessage()) {
                MutableComponent link = Component.literal("[Click to get an Architect's First Draft]")
                        .withStyle(s -> s.withColor(ChatFormatting.GOLD).withUnderlined(true)
                                .withClickEvent(new ClickEvent.RunCommand("/" + COMMAND))
                                .withHoverEvent(new HoverEvent.ShowText(Component.literal("/" + COMMAND))));
                ModChat.send("Architect", link);
            }
        });
    }
}
