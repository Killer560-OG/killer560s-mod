package com.killer560.hub.chattabs;

import com.killer560.hub.chattabs.mixin.ChatTabsAccessor;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.compat.McInput;
import com.killer560.hub.gui.ThemeAccent;
import com.killer560.hub.modchat.ModChatChannel;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ServerCommands;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chat Tabs (to-do Doc 2026-11-01, "ADD CHAT TABS"): a row of tabs above the chat input while chat is open - All, Party,
 * Guild, Officer, Co-op, Messages. Clicking one shows only that channel's messages and, with Switch Channel, puts you in
 * that chat: Hypixel's {@code /chat all|party|guild|officer|coop} (hypixelskyblock.minecraft.wiki, Commands). Private
 * messages have no {@code /chat} channel, so with the Messages tab selected a plain line you type goes as
 * {@code /r <line>} (Hypixel's reply to whoever last messaged you). Typing {@code /chat p} yourself selects the
 * matching tab.
 *
 * <p>The filter is vanilla's own {@code ChatComponent.setVisibleMessageFilter} (see {@code ChatTabsFilterMixin}); it
 * applies while the chat is open, or always with Filter Closed Chat. The tabs are drawn and clicked through Fabric's
 * screen events, so nothing in {@code ChatScreen} is redirected and other chat mods (Lunar's, chat history mods) keep
 * their hooks. A message is put in a channel by its plain text prefix: "Party > ", "Guild > ", "Officer > ",
 * "Co-op > ", and "From " / "To " plus a name and ':' for Messages. Display only: a filter never acts on another
 * player's text.
 */
public final class ChatTabsFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-chattabs");
    private static final String FEATURE = "Chat Tabs";

    public enum Tab {
        ALL("All", "all"),
        PARTY("Party", "party"),
        GUILD("Guild", "guild"),
        OFFICER("Officer", "officer"),
        COOP("Co-op", "coop"),
        MESSAGES("Messages", null);

        public final String label;
        /** The {@code /chat} argument, or null when the channel has none. */
        public final String channel;

        Tab(String label, String channel) {
            this.label = label;
            this.channel = channel;
        }
    }

    /** "From [MVP+] Bob: hi" / "To Bob: hi" (a private message), bounded name and rank. */
    private static final Pattern WHISPER = Pattern.compile("^(?:From|To) (?:\\[[^\\]]{1,20}] )?[A-Za-z0-9_]{1,16}: ");
    private static final Pattern CHAT_COMMAND = Pattern.compile("^chat\\s+(\\S{1,32})\\s*$", Pattern.CASE_INSENSITIVE);

    static final int TAB_H = 12;
    static final int TAB_GAP = 2;

    private static volatile Tab selected = Tab.ALL;
    /** The tab whose filter the chat holds now (ALL = no filtering). */
    private static volatile Tab applied = Tab.ALL;
    private static volatile boolean chatOpen = false;

    // testkit
    private static int switches = 0;
    private static String lastChannelCommand = null;
    private static int repliesRewritten = 0;
    private static long framesDrawn = 0;

    private ChatTabsFeature() {
    }

    public static void register() {
        ChatTabsConfig.getInstance();
        ScreenEvents.AFTER_INIT.register(ChatTabsFeature::onScreenInit);
        ClientSendMessageEvents.ALLOW_CHAT.register(ChatTabsFeature::allowChat);
        ClientSendMessageEvents.ALLOW_COMMAND.register(ChatTabsFeature::allowCommand);
    }

    /** Which tab a message belongs to (ALL when no channel prefix matches). */
    public static Tab channelOf(String plain) {
        if (plain == null) {
            return Tab.ALL;
        }
        if (plain.startsWith("Party > ")) {
            return Tab.PARTY;
        }
        if (plain.startsWith("Guild > ")) {
            return Tab.GUILD;
        }
        if (plain.startsWith("Officer > ")) {
            return Tab.OFFICER;
        }
        if (plain.startsWith("Co-op > ")) {
            return Tab.COOP;
        }
        if ((plain.startsWith("From ") || plain.startsWith("To ")) && WHISPER.matcher(plain).find()) {
            return Tab.MESSAGES;
        }
        return Tab.ALL;
    }

    /** The chat filter's question: does {@code message} show under the tab applied now. */
    static boolean visible(GuiMessage message) {
        Tab t = applied;
        if (t == Tab.ALL) {
            return true;
        }
        try {
            String plain = ChatFormatting.stripFormatting(message.content().getString());
            return channelOf(plain == null ? "" : plain.stripLeading()) == t;
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** The tab that should filter the chat right now. */
    private static Tab wanted() {
        ChatTabsConfig cfg = ChatTabsConfig.getInstance();
        if (!cfg.isEnabled() || selected == Tab.ALL) {
            return Tab.ALL;
        }
        return chatOpen || cfg.isFilterClosedChat() ? selected : Tab.ALL;
    }

    /** Re-applies the filter when the tab it should use changed (setting, tab click, chat opened or closed). */
    public static void refresh() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return;
        }
        if (!client.isSameThread()) {
            client.execute(ChatTabsFeature::refresh);
            return;
        }
        Tab want = wanted();
        if (want == applied) {
            return;
        }
        ChatComponent chat;
        try {
            chat = McCompat.chat(client);
        } catch (RuntimeException e) {
            return;
        }
        if (chat == null) {
            return;
        }
        applied = want;
        Predicate<GuiMessage> current = ((ChatTabsAccessor) chat).killer560smod$getVisibleMessageFilter();
        // Through the setter: the mixin wraps it, and vanilla re-lays the chat lines through it.
        chat.setVisibleMessageFilter(ChatTabsFilter.unwrap(current));
        LOGGER.info("[ChatTabs] chat shows {}", want.label);
    }

    private static void onScreenInit(Minecraft client, Screen screen, int width, int height) {
        if (!(screen instanceof ChatScreen)) {
            return;
        }
        chatOpen = true;
        refresh();
        ScreenEvents.remove(screen).register(s -> {
            chatOpen = false;
            refresh();
        });
        ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
            if (!ChatTabsConfig.getInstance().isEnabled() || McInput.button(event) != 0) {
                return true;
            }
            Tab hit = tabAt(s, event.x(), event.y());
            if (hit == null || suggestionsAt(s, event.x(), event.y())) {
                return true;
            }
            select(hit);
            return false;
        });
    }

    /** The row's top: just above the input bar (vanilla draws it from height - 14 to height - 2). */
    static int rowY(Screen s) {
        return s.height - 14 - TAB_H - 1;
    }

    private static int tabW(Font font, Tab t) {
        return font.width(t.label) + 8;
    }

    static Tab tabAt(Screen s, double mx, double my) {
        Font font = Minecraft.getInstance().font;
        int y = rowY(s);
        if (my < y || my >= y + TAB_H) {
            return null;
        }
        int x = 2;
        for (Tab t : Tab.values()) {
            int w = tabW(font, t);
            if (mx >= x && mx < x + w) {
                return t;
            }
            x += w + TAB_GAP;
        }
        return null;
    }

    /** Called from {@code ChatTabsScreenMixin} at the start of the chat screen's render: the vanilla suggestion box and
     *  usage hint draw after it, so they cover the tabs. */
    public static void drawTabs(Screen s, GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        try {
            draw(s, graphics, mouseX, mouseY);
        } catch (RuntimeException e) {
            LOGGER.error("[ChatTabs] draw failed", e);
        }
    }

    private static java.lang.reflect.Field fSuggestions, fList, fRect;

    /** Whether vanilla's suggestion list is open over (x, y): a click there picks the suggestion, never a tab. Reflection
     *  on the three private fields (looked up once; Minecraft 26.x is unobfuscated), only on a click in the tab row. */
    static boolean suggestionsAt(Screen s, double x, double y) {
        net.minecraft.client.renderer.Rect2i r = suggestionRect(s);
        return r != null && x >= r.getX() && x < r.getX() + r.getWidth() && y >= r.getY() && y < r.getY() + r.getHeight();
    }

    static net.minecraft.client.renderer.Rect2i suggestionRect(Screen s) {
        try {
            if (fSuggestions == null) {
                fSuggestions = ChatScreen.class.getDeclaredField("commandSuggestions");
                fList = net.minecraft.client.gui.components.CommandSuggestions.class.getDeclaredField("suggestions");
                fRect = Class.forName("net.minecraft.client.gui.components.CommandSuggestions$SuggestionsList").getDeclaredField("rect");
                fSuggestions.setAccessible(true);
                fList.setAccessible(true);
                fRect.setAccessible(true);
            }
            Object cs = fSuggestions.get(s);
            Object list = cs == null ? null : fList.get(cs);
            return list == null ? null : (net.minecraft.client.renderer.Rect2i) fRect.get(list);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static void draw(Screen s, GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (!ChatTabsConfig.getInstance().isEnabled()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        int y = rowY(s);
        int x = 2;
        for (Tab t : Tab.values()) {
            int w = tabW(font, t);
            boolean on = t == selected;
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + TAB_H;
            int bg = on ? ThemeAccent.accent(0xE0000000 | ModChat.ORANGE) : hover ? 0xC0402810 : 0xA0000000;
            graphics.fill(x, y, x + w, y + TAB_H, bg);
            graphics.text(font, t.label, x + 4, y + 2, on ? 0xFFFFFFFF : 0xFFCCCCCC, false);
            x += w + TAB_GAP;
        }
        framesDrawn++;
    }

    /** A tab was clicked: show its messages and, with Switch Channel, go to its chat. */
    public static void select(Tab t) {
        ChatTabsConfig cfg = ChatTabsConfig.getInstance();
        selected = t;
        switches++;
        if (cfg.isSwitchChannel()) {
            if (ModChatChannel.isActive()) {
                // Hypixel's /chat goes below Fabric's ALLOW_COMMAND, where Mod Chat would have seen it and switched off.
                ModChatChannel.setActive(false);
            }
            if (t.channel != null) {
                lastChannelCommand = "chat " + t.channel;
                ServerCommands.toServer(lastChannelCommand);
            } else {
                ModChat.send(FEATURE, ModChat.text("Plain lines you type now go as "), ModChat.value("/r"),
                        ModChat.text(" to whoever last messaged you."));
            }
        }
        LOGGER.info("[ChatTabs] {} tab selected", t.label);
        refresh();
    }

    /** Fabric's ALLOW_CHAT: with Messages selected (and Switch Channel on), a plain line goes as /r instead. */
    static boolean allowChat(String message) {
        ChatTabsConfig cfg = ChatTabsConfig.getInstance();
        if (selected != Tab.MESSAGES || !cfg.isEnabled() || !cfg.isSwitchChannel() || ModChatChannel.isActive()
                || message == null || message.isBlank()) {
            return true;
        }
        String line = message.length() > 250 ? message.substring(0, 250) : message;
        repliesRewritten++;
        ServerCommands.toServer("r " + line);
        return false;
    }

    /** Fabric's ALLOW_COMMAND: "/chat p" typed by hand selects the Party tab, and so on. Never swallows anything. */
    static boolean allowCommand(String command) {
        if (command == null || !ChatTabsConfig.getInstance().isEnabled()) {
            return true;
        }
        Matcher m = CHAT_COMMAND.matcher(command.trim());
        if (!m.matches()) {
            return true;
        }
        Tab t = switch (m.group(1).toLowerCase(Locale.ROOT)) {
            case "a", "all" -> Tab.ALL;
            case "p", "party" -> Tab.PARTY;
            case "g", "guild" -> Tab.GUILD;
            case "o", "officer" -> Tab.OFFICER;
            case "coop", "skyblock-coop" -> Tab.COOP;
            default -> null;
        };
        if (t != null) {
            selected = t;
            refresh();
        }
        return true;
    }

    public static String selectedName() {
        return selected.name();
    }

    public static int switches() {
        return switches;
    }

    public static String lastChannelCommand() {
        return lastChannelCommand;
    }

    public static int repliesRewritten() {
        return repliesRewritten;
    }

    public static long framesDrawn() {
        return framesDrawn;
    }

    /** The plain text of every message the chat lays out now (newest first, each once). Client thread. */
    public static java.util.List<String> testShownMessages() {
        ChatComponent chat = McCompat.chat(Minecraft.getInstance());
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (GuiMessage.Line line : ((ChatTabsAccessor) chat).killer560smod$getTrimmedMessages()) {
            out.add(ChatFormatting.stripFormatting(line.parent().content().getString()));
        }
        return new java.util.ArrayList<>(out);
    }

    /** The screen position of a tab's middle on a {@code width x height} screen, "x,y", for a real click. */
    public static String tabCenter(String name, int height) {
        Font font = Minecraft.getInstance().font;
        int x = 2;
        for (Tab t : Tab.values()) {
            int w = tabW(font, t);
            if (t.name().equals(name)) {
                return (x + w / 2) + "," + (height - 14 - TAB_H - 1 + TAB_H / 2);
            }
            x += w + TAB_GAP;
        }
        return null;
    }
}
