package com.killer560.hub.dungeonqueue;

import com.killer560.hub.autokick.QueuedFloorDetector;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModLog;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PV Link on Join (killer560, 2026-11-03: "whenever someone joins a party in dungeons have a chat line below their name
 * for me to pv them"). On Hypixel's "Party Finder > Name joined the dungeon group! (Class Level N)" and on a normal
 * "[Rank] Name joined the party." while in a dungeon (or with a Party Finder queue known), prints one local line under
 * it: a clickable "[PV] Name" that runs the mod's own client command {@code /pv Name}. Both patterns are whole anchored
 * Hypixel lines with a {@code [A-Za-z0-9_]{1,16}} name group, so no player's chat line can make one; a throw is caught
 * because a raw chat listener's throw would disconnect him.
 */
public final class PvJoinLink {

    private static final Logger LOGGER = ModLog.get("killer560smod-pvjoin");

    static final Pattern PF_JOINED = Pattern.compile(
            "^Party Finder > ([A-Za-z0-9_]{1,16}) joined the dungeon group! \\([A-Za-z]{1,12} Level \\d{1,3}\\)$");
    static final Pattern PARTY_JOINED = Pattern.compile(
            "^(?:\\[[A-Za-z+]{1,12}] )?([A-Za-z0-9_]{1,16}) joined the party\\.$");

    /** Every name a link was printed for, newest last (the testkit reads it). */
    private static final List<String> shown = new ArrayList<>();

    private PvJoinLink() {
    }

    public static void register() {
        ChatObserver.subscribe(PvJoinLink::onChat);
    }

    public static List<String> shown() {
        synchronized (shown) {
            return List.copyOf(shown);
        }
    }

    private static void onChat(Component message) {
        try {
            String plain = ChatObserver.strip(message);
            if (plain != null) {
                onLine(plain.trim());
            }
        } catch (RuntimeException e) {
            LOGGER.warn("[PvJoin] line failed", e);
        }
    }

    /** One chat line (color codes already stripped). */
    public static void onLine(String plain) {
        DungeonQueueConfig cfg = DungeonQueueConfig.getInstance();
        if (!cfg.isPvLinkOnJoin() || !com.killer560.hub.util.SkyblockGate.allows()) {
            return;
        }
        Matcher m = PF_JOINED.matcher(plain);
        if (!m.matches()) {
            m = PARTY_JOINED.matcher(plain);
            if (!m.matches() || !(DungeonState.isInDungeon() || QueuedFloorDetector.hasDetection())) {
                return;
            }
        }
        String name = m.group(1);
        Minecraft client = Minecraft.getInstance();
        String self = client.player == null ? null : client.player.getGameProfile().name();
        if (self == null || self.equalsIgnoreCase(name)) {
            return;
        }
        // One component carries the click and hover, so the whole line opens the viewer; the children inherit it.
        int accent = com.killer560.hub.gui.ThemeAccent.onDark(com.killer560.hub.gui.ThemeAccent.light(0xFFAA00)) & 0xFFFFFF;
        MutableComponent line = Component.literal(">> [PV] ").withStyle(s -> s.withColor(accent).withBold(true)
                .withClickEvent(new ClickEvent.RunCommand("/pv " + name))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to open " + name + "'s profile"))))
                .append(Component.literal(name + " - click to open their profile").withStyle(st -> st.withBold(false)));
        synchronized (shown) {
            shown.add(name);
        }
        client.execute(() -> {
            if (client.player != null) {
                client.player.sendSystemMessage(line);
            }
        });
    }
}
