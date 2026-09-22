package com.killer560.hub.architect;

import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModChat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Architect's First Draft on a puzzle fail - killer560 (2026-09-21): "add a setting to automatically send a chat
 * message that if on click gets an architects first draft. This should only send on puzzle fail. Also add an option to
 * auto get it from sack only on the cheat version that only gets it from sack if you failed the puzzle. The chat
 * message looks something like PUZZLE FAIL! Killer560 was fooled by Eveleth! Yikes!"
 * <p>
 * Every Hypixel puzzle-fail line starts "PUZZLE FAIL! &lt;name&gt; ...". Click Message (legit): a local line whose click
 * runs {@code /gfs architect_first_draft 1} - you still click it yourself. Auto Get (cheat build): runs that command
 * itself, only when the name is yours. A few seconds' cooldown so one fail never fires twice.
 */
public final class ArchitectDraftFeature {

    private static final Pattern FAIL = Pattern.compile("^PUZZLE FAIL! (?:\\[[^\\]]+\\] )?([A-Za-z0-9_]{1,16}) ");
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
        Matcher m = FAIL.matcher(text == null ? "" : text.trim());
        if (!m.find()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastMs < COOLDOWN_MS) {
            return;
        }
        lastMs = now;
        String failer = m.group(1);
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
