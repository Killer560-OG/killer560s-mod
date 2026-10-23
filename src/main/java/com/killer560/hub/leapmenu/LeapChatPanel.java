package com.killer560.hub.leapmenu;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.ThemedEditBox;
import com.killer560.hub.hud.GuiRects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.StringUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * The Leap Order editor's chat panel (killer560, 2026-10-21: players type their class in chat, and he reads it and
 * answers himself - no auto-fill from chat, because of misspellings). It is read-only plus send: the lines are the chat
 * window's own history ({@code ChatComponent.allMessages}, through {@code ChatTidyAccessor}, so Unlimited Chat History
 * and Chat Tidy's stacking apply here too), newest at the bottom, scrolled with the mouse wheel, following new lines while
 * at the bottom. The input sends exactly as vanilla's {@code ChatScreen.handleChatInput} does (javap 26.1.2 and 26.2:
 * trim, collapse spaces, {@code StringUtil.trimChatMessage}, {@code addRecentChat}, then {@code sendCommand} for a leading
 * "/" and {@code sendChat} otherwise), so the server gets it as ordinary chat. Nothing here waits.
 */
final class LeapChatPanel {

    /** The panel's width range; the stage keeps at least {@link #CONTENT_MIN} to its right (it shrinks, never the stage). */
    private static final int MIN_W = 90;
    private static final int MAX_W = 240;
    private static final int CONTENT_MIN = 200;
    private static final int INPUT_H = 16;
    private static final int LINE_H = 9;
    /** How many of the newest chat messages are wrapped for the panel. */
    private static final int MAX_MESSAGES = 300;

    boolean open = false;
    ThemedEditBox input;
    /** {x, y, w, h} of the whole panel as last laid out. */
    int[] bounds = new int[4];
    /** Wrapped lines scrolled back from the newest (0 = following the bottom). */
    int scroll = 0;
    /** Lines sent from this panel (testkit). */
    static int sent = 0;

    private List<FormattedCharSequence> lines = List.of();
    private List<String> plain = List.of();
    private GuiMessage cachedNewest;
    private int cachedSize = -1;
    private int cachedWidth = -1;
    private int historyAt = -1;
    private String draft = "";

    /** The panel's width for a screen this wide. */
    static int width(int screenW) {
        int want = Math.min(MAX_W, screenW * 2 / 5);
        return Math.max(MIN_W, Math.min(want, screenW - 24 - CONTENT_MIN));
    }

    /** The panel's left edge: inside the editor's 12 unit margin, on the left of the screen (killer560, 2026-10-22). */
    private static final int X = 12;

    /** Lays the panel out on the left of the screen, from {@code top} down to just above the bottom button row, and
     *  makes a fresh input there carrying the old one's text and focus. @return the new input (the screen adds it). */
    ThemedEditBox layout(Font font, int screenW, int screenH, int top) {
        int w = width(screenW);
        int x = X;
        int bottom = screenH - 36;
        int h = Math.max(INPUT_H + 24, bottom - top);
        bounds = new int[]{x, top, w, h};
        String old = input == null ? "" : input.getValue();
        ThemedEditBox box = new ThemedEditBox(font, x + 4, top + h - 4 - INPUT_H, w - 8, INPUT_H,
                Component.literal("Chat message"));
        box.hint("type to chat, Enter sends");
        box.setMaxLength(256);
        box.setValue(old);
        input = box;
        return box;
    }

    /** The x where the panel ends (the stage's left edge is a little right of it), or 0 when closed. */
    int right() {
        return open ? bounds[0] + bounds[2] : 0;
    }

    boolean over(double mx, double my) {
        return open && mx >= bounds[0] && mx < bounds[0] + bounds[2] && my >= bounds[1] && my < bounds[1] + bounds[3];
    }

    private int[] linesArea() {
        int x = bounds[0] + 4;
        int y = bounds[1] + 4;
        int w = bounds[2] - 8;
        int h = bounds[3] - 8 - INPUT_H - 4;
        return new int[]{x, y, w, h};
    }

    private int visibleCount() {
        return Math.max(1, (linesArea()[3] - 2) / LINE_H);
    }

    /** Rewraps the chat history when it or the width changed; keeps a scrolled-back view where it was. */
    private void refresh(Font font) {
        ChatComponent chat = McCompat.chat(Minecraft.getInstance());
        if (chat == null) {
            return;
        }
        List<GuiMessage> all = ((com.killer560.hub.chattidy.mixin.ChatTidyAccessor) chat).killer560smod$getAllMessages();
        int width = linesArea()[2] - 4;
        GuiMessage newest = all.isEmpty() ? null : all.get(0);
        if (newest == cachedNewest && all.size() == cachedSize && width == cachedWidth) {
            return;
        }
        // Messages that arrived since the last wrap: everything newer than the old newest (all is newest first). Counted
        // by message, not by total lines, because the oldest drop off the end once MAX_MESSAGES is reached.
        int fresh = -1;
        for (int i = 0; i < all.size() && cachedNewest != null; i++) {
            if (all.get(i) == cachedNewest) {
                fresh = i;
                break;
            }
        }
        int freshLines = 0;
        List<FormattedCharSequence> out = new ArrayList<>();
        List<String> text = new ArrayList<>();
        int n = Math.min(all.size(), MAX_MESSAGES);
        for (int i = n - 1; i >= 0; i--) { // all is newest first; the panel reads top to bottom
            Component content = all.get(i).content();
            List<FormattedCharSequence> split = font.split(content, Math.max(20, width));
            if (i < fresh) {
                freshLines += split.size();
            }
            for (FormattedCharSequence seq : split) {
                out.add(seq);
                StringBuilder sb = new StringBuilder();
                seq.accept((index, style, cp) -> {
                    sb.appendCodePoint(cp);
                    return true;
                });
                text.add(sb.toString());
            }
        }
        if (scroll > 0 && width == cachedWidth) {
            scroll += freshLines; // a scrolled-back view stays on the same lines
        }
        lines = out;
        plain = text;
        cachedNewest = newest;
        cachedSize = all.size();
        cachedWidth = width;
        clampScroll();
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, Math.max(0, lines.size() - visibleCount())));
    }

    void draw(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
        if (!open) {
            return;
        }
        refresh(font);
        int[] b = bounds;
        GuiRects r = GuiRects.begin(g);
        ScreenChrome.card(r, b[0], b[1], b[2], b[3], false, false);
        int[] a = linesArea();
        // Vanilla chat's dark backing whatever the theme, so every chat colour stays readable.
        r.fill(a[0], a[1], a[0] + a[2], a[1] + a[3], 0xB0000000);
        r.submit();
        int count = visibleCount();
        int end = lines.size() - scroll;
        int start = Math.max(0, end - count);
        g.enableScissor(a[0], a[1], a[0] + a[2], a[1] + a[3]);
        int y = a[1] + a[3] - 1 - (end - start) * LINE_H;
        for (int i = start; i < end; i++) {
            g.text(font, lines.get(i), a[0] + 2, y, 0xFFFFFFFF, true);
            y += LINE_H;
        }
        g.disableScissor();
        if (lines.isEmpty()) {
            ScreenChrome.centered(g, "No chat yet", a[0] + a[2] / 2, a[1] + a[3] / 2 - 4, MenuStyle.DIM);
        }
        if (scroll > 0) {
            String more = "▼ " + scroll + " newer";
            int tw = font.width(more);
            GuiRects m = GuiRects.begin(g);
            m.fill(a[0] + a[2] - tw - 6, a[1] + a[3] - 11, a[0] + a[2], a[1] + a[3], 0xE0000000);
            m.submit();
            ScreenChrome.text(g, more, a[0] + a[2] - tw - 3, a[1] + a[3] - 10, MenuStyle.OR);
        }
    }

    boolean scroll(double mx, double my, double amount) {
        if (!over(mx, my) || amount == 0) {
            return false;
        }
        scroll += amount > 0 ? 3 : -3;
        clampScroll();
        return true;
    }

    void page(int dir) {
        scroll += dir * Math.max(1, visibleCount() - 1);
        clampScroll();
    }

    /** Up/Down through what was sent before (vanilla chat's own recent list). */
    void history(int dir) {
        ChatComponent chat = McCompat.chat(Minecraft.getInstance());
        if (chat == null || input == null) {
            return;
        }
        List<String> recent = new ArrayList<>(chat.getRecentChat());
        if (recent.isEmpty()) {
            return;
        }
        if (historyAt < 0) {
            draft = input.getValue();
            historyAt = recent.size();
        }
        historyAt = Math.max(0, Math.min(recent.size(), historyAt - dir));
        input.setValue(historyAt == recent.size() ? draft : recent.get(historyAt));
        input.moveCursorToEnd(false);
    }

    /** Sends what is typed, as vanilla chat sends it, and empties the box. */
    void send() {
        if (input == null) {
            return;
        }
        String typed = input.getValue();
        input.setValue("");
        historyAt = -1;
        scroll = 0;
        sendAsChat(typed);
    }

    /** {@code ChatScreen.handleChatInput(text, true)}, without needing a ChatScreen. */
    static void sendAsChat(String text) {
        String msg = StringUtil.trimChatMessage(text == null ? "" : text.trim().replaceAll("\\s+", " "));
        if (msg.isEmpty()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        ChatComponent chat = McCompat.chat(client);
        if (chat != null) {
            chat.addRecentChat(msg);
        }
        if (client.player == null) {
            return;
        }
        sent++;
        if (msg.startsWith("/")) {
            client.player.connection.sendCommand(msg.substring(1));
        } else {
            client.player.connection.sendChat(msg);
        }
    }

    /** The lines the panel shows right now, top to bottom, as plain text (testkit). */
    List<String> shown() {
        if (!open) {
            return List.of();
        }
        int end = plain.size() - scroll;
        int start = Math.max(0, end - visibleCount());
        return List.copyOf(plain.subList(Math.max(0, start), Math.max(0, end)));
    }
}
