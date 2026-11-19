package com.killer560.hub.chatchannel;

import com.killer560.hub.chattabs.ChatTabsConfig;
import com.killer560.hub.gui.ThemeAccent;
import com.killer560.hub.leapmenu.PartyTracker;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chat Channel Indicator: a line just above the chat input ("Chat: Party (5 members)") naming the Hypixel chat channel
 * the next typed line goes to. Hypixel's own lines are the truth; an outgoing /chat only predicts until they answer.
 * Event driven: the drawn parts are rebuilt only when the channel, the party size or a setting changes.
 */
public final class ChatChannelFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-chatchannel");

    public enum Channel {
        ALL("All", 0xFFFFFF), PARTY("Party", 0x5555FF), GUILD("Guild", 0x00AA00), OFFICER("Officer", 0x00AAAA),
        COOP("Skyblock Co-op", 0x55FFFF), PRIVATE("To", 0xFF55FF);

        final String label;
        final int color;

        Channel(String label, int color) {
            this.label = label;
            this.color = color;
        }
    }

    private static final Pattern NOW_IN = Pattern.compile("^You are now in the ([A-Za-z -]{3,20}) channel\\.?$");
    private static final Pattern MOVED_ALL = Pattern.compile("^(?:You are not in a party and were moved to the ALL channel\\.|"
            + "The conversation you were in expired and you have been moved back to the ALL channel\\.)$");
    private static final Pattern OPENED = Pattern.compile("^Opened a chat conversation with (?:\\[[^\\]]{1,20}] )?([A-Za-z0-9_]{1,16}) for the next 5 minutes\\.");
    private static final Pattern CHAT_COMMAND = Pattern.compile("^(?:chat|channel)\\s+(\\S{1,32})\\s*$", Pattern.CASE_INSENSITIVE);

    /** An unanswered /chat prediction is given up after this long. */
    private static final long PREDICT_MS = 3000L;
    private static final long COUNT_REFRESH_MS = 500L;
    private static final int LINE_H = 10;

    // Written by the render thread (chat, commands) and cleared by DISCONNECT (Netty), so volatile.
    private static volatile Channel confirmed = null;
    private static volatile String partner = null;
    private static volatile Channel predicted = null;
    private static volatile long predictedAt = 0L;

    private static volatile boolean dirty = true;
    private static long countAt = 0L;
    private static int members = 0;
    private static String label, name, count;
    private static int labelW, nameW, nameColor;
    private static Channel built;

    private static long framesDrawn = 0;
    private static int lastY = -1;

    private ChatChannelFeature() {
    }

    public static void register() {
        ChatChannelConfig.getInstance();
        ChatObserver.subscribe(m -> onLine(ChatObserver.strip(m)));
        ClientSendMessageEvents.ALLOW_COMMAND.register(ChatChannelFeature::onCommand);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
    }

    /** Settings changed or loaded: rebuild the drawn parts on the next frame. */
    public static void refresh() {
        dirty = true;
    }

    private static void reset() {
        confirmed = null;
        predicted = null;
        partner = null;
        dirty = true;
    }

    /** One Hypixel line (plain text). Prefix tests first so ordinary chat costs a few comparisons. */
    static void onLine(String plain) {
        if (plain == null) {
            return;
        }
        String s = plain.trim();
        if (s.startsWith("You are now in the ")) {
            Matcher m = NOW_IN.matcher(s);
            if (m.matches()) {
                set(channelOf(m.group(1)), null);
            }
        } else if (s.startsWith("You are not in a party and") || s.startsWith("The conversation you were in expired")) {
            if (MOVED_ALL.matcher(s).matches()) {
                set(Channel.ALL, null);
            }
        } else if (s.startsWith("Opened a chat conversation with ")) {
            Matcher m = OPENED.matcher(s);
            if (m.find()) {
                set(Channel.PRIVATE, m.group(1));
            }
        } else if (s.equals("You're already in this channel!") && predicted != null) {
            set(predicted, partner);
        }
    }

    private static Channel channelOf(String hypixelName) {
        String n = hypixelName.trim().toUpperCase(Locale.ROOT);
        if (n.contains("CO-OP") || n.contains("COOP")) {
            return Channel.COOP;
        }
        return switch (n) {
            case "PARTY" -> Channel.PARTY;
            case "GUILD" -> Channel.GUILD;
            case "OFFICER" -> Channel.OFFICER;
            default -> Channel.ALL;
        };
    }

    private static void set(Channel c, String with) {
        confirmed = c;
        partner = with;
        predicted = null;
        dirty = true;
        LOGGER.info("[ChatChannel] channel is {}", c);
    }

    /** Fabric's ALLOW_COMMAND: "/chat p" predicts the channel until Hypixel's line answers. /pc, /gc and the rest never change it. */
    static boolean onCommand(String command) {
        if (command == null || command.length() < 6 || !ChatChannelConfig.getInstance().isEnabledRaw()) {
            return true;
        }
        Matcher m = CHAT_COMMAND.matcher(command.trim());
        if (m.matches()) {
            Channel c = switch (m.group(1).toLowerCase(Locale.ROOT)) {
                case "a", "all" -> Channel.ALL;
                case "p", "party" -> Channel.PARTY;
                case "g", "guild" -> Channel.GUILD;
                case "o", "officer" -> Channel.OFFICER;
                case "coop", "skyblock-coop" -> Channel.COOP;
                default -> null;
            };
            if (c != null) {
                predicted = c;
                predictedAt = System.currentTimeMillis();
                dirty = true;
            }
        }
        return true;
    }

    /** What the line shows now: a fresh prediction over the confirmed channel. */
    private static Channel current() {
        Channel p = predicted;
        if (p != null) {
            if (System.currentTimeMillis() - predictedAt < PREDICT_MS) {
                return p;
            }
            predicted = null;
            dirty = true;
        }
        return confirmed;
    }

    /** The y of the line's top: above the input bar, and above the Chat Tabs row when that is on. */
    static int lineY(Screen s) {
        int y = s.height - 14 - LINE_H - 1;
        return ChatTabsConfig.getInstance().isEnabled() ? y - 12 - 1 : y;
    }

    /** Called from ChatChannelScreenMixin at the start of the chat screen's render (suggestions draw over it). */
    public static void draw(Screen s, GuiGraphicsExtractor graphics) {
        ChatChannelConfig cfg = ChatChannelConfig.getInstance();
        if (!cfg.isEnabled() || cfg.isAlwaysShow()) { // Always: the HUD element draws it, once
            return;
        }
        drawAt(graphics, 2, lineY(s), null);
    }

    /** Draws the line at x,y; {@code sample} stands in for an unknown channel (the HUD editor). */
    static void drawAt(GuiGraphicsExtractor graphics, int x, int y, Channel sample) {
        ChatChannelConfig cfg = ChatChannelConfig.getInstance();
        try {
            Channel c = prepare(cfg, sample);
            if (c == null) {
                return;
            }
            Font font = Minecraft.getInstance().font;
            int labelColor = cfg.isHypixelColors() ? 0xFF55FF55 : ThemeAccent.accent(0xFFFFAA00);
            graphics.text(font, label, x, y, labelColor, true);
            graphics.text(font, name, x + labelW, y, 0xFF000000 | nameColor, true);
            if (count != null) {
                graphics.text(font, count, x + labelW + nameW, y, labelColor, true);
            }
            lastY = y;
            framesDrawn++;
        } catch (RuntimeException e) {
            LOGGER.error("[ChatChannel] draw failed", e);
        }
    }

    /** Refreshes the party size and the built parts when stale; the channel to draw, or null for none. */
    private static Channel prepare(ChatChannelConfig cfg, Channel sample) {
        Channel c = sample != null ? sample : current();
        long now = System.currentTimeMillis();
        if (c == Channel.PARTY && cfg.isShowMemberCount() && now - countAt >= COUNT_REFRESH_MS) {
            countAt = now;
            int n = PartyTracker.teammates().size();
            n = n == 0 ? 0 : n + 1;
            if (n != members) {
                members = n;
                dirty = true;
            }
        }
        if (c != null && (dirty || c != built)) {
            rebuild(c, cfg, Minecraft.getInstance().font);
        }
        return c;
    }

    /** The "Always" HUD element: movable and scalable in the HUD editor, drawn by HudInGameRenderer (id in UNDRAWN_ELEMENT_IDS). */
    public static final com.killer560.hub.hud.HudElement HUD = new ChannelHud();

    private static final class ChannelHud extends com.killer560.hub.hud.BasicHud {
        ChannelHud() {
            // Free spot in the x=10 column.
            super("chat_channel", "Chat Channel", 10, 380);
        }

        private static boolean editor() {
            return com.killer560.hub.compat.McCompat.screen(Minecraft.getInstance()) instanceof com.killer560.hub.hud.HudEditorScreen;
        }

        private static Channel shown() {
            return editor() && current() == null ? Channel.ALL : null;
        }

        @Override
        public int width() {
            return prepare(ChatChannelConfig.getInstance(), shown()) == null ? 60
                    : labelW + nameW + (count == null ? 0 : Minecraft.getInstance().font.width(count));
        }

        @Override
        public int height() {
            return LINE_H;
        }

        @Override
        public boolean isEnabledInSettings() {
            ChatChannelConfig cfg = ChatChannelConfig.getInstance();
            return cfg.isEnabled() && cfg.isAlwaysShow();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            drawAt(graphics, x, y, shown());
            if (current() != null || editor()) {
                com.killer560.hub.hud.HudSeen.markDrawn(id());
            }
        }
    }

    private static void rebuild(Channel c, ChatChannelConfig cfg, Font font) {
        dirty = false;
        built = c;
        label = "Chat: ";
        name = c == Channel.PRIVATE ? "To " + (partner == null ? "?" : partner) : c.label;
        nameColor = cfg.isHypixelColors() ? c.color : 0xFFFFFF;
        count = c == Channel.PARTY && cfg.isShowMemberCount() && members > 0 ? " (" + members + " members)" : null;
        labelW = font.width(label);
        nameW = font.width(name);
    }

    // testkit
    public static String shownText() {
        Channel c = current();
        if (c == null) {
            return null;
        }
        return "Chat: " + (c == Channel.PRIVATE ? "To " + partner : c.label)
                + (c == Channel.PARTY && members > 0 && ChatChannelConfig.getInstance().isShowMemberCount() ? " (" + members + " members)" : "");
    }

    public static int lastY() {
        return lastY;
    }

    public static long framesDrawn() {
        return framesDrawn;
    }

    public static void feedLineForTest(String plain) {
        onLine(plain);
    }

    public static boolean commandForTest(String command) {
        return onCommand(command);
    }

    public static void resetForTest() {
        reset();
    }
}
