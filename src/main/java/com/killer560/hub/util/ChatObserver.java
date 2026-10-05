package com.killer560.hub.util;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Sees every chat line that actually lands in chat, once.
 * <p>
 * Real bug found and fixed (2026-09-14, real Hypixel F7 log): the displayed line "Killer560 completed a
 * device! (1/7) (14.436s | 14.436s)" - the suffix being Odin's "Terminal Splits" rewrite - was never seen
 * by ANY of this mod's Fabric {@code ClientReceiveMessageEvents} CHAT/GAME listeners, while other lines
 * were. Another installed mod (Odin/NoammAddons/Skyblocker) cancels the original via {@code ALLOW_GAME}
 * (which also skips {@code MODIFY_GAME}/{@code GAME} for everyone else) and adds its own copy straight to
 * {@code ChatComponent}, bypassing Fabric's events entirely. So Device Times, Auto Leap's i4 trigger and
 * Auto i4's completion-by-chat silently never worked with Odin installed.
 * <p>
 * Fix: two sources feed one de-duplicated dispatch - Fabric's CHAT/GAME (non-overlay) events, and
 * {@code ChatComponentMixin}'s hook on {@code ChatComponent}'s private {@code addMessage} funnel (every
 * public add path - player/client-system/server-system - goes through it, confirmed with javap on the
 * real 26.1.2 jar). A normal line fires Fabric first, then reaches ChatComponent with the same text and is
 * dropped as a duplicate; a line another mod cancelled-and-re-added only arrives via ChatComponent.
 * De-dup: identical stripped text (or one being a prefix of the other, which covers suffix rewrites like
 * Odin's splits or this mod's own Device Times annotation) within {@link #DEDUPE_WINDOW_MS}.
 * <p>
 * Note: this mod's own client-side messages (e.g. "[Simon Says] Whole device solved...") also reach
 * subscribers through the ChatComponent path, unlike with Fabric events - subscribers must match on
 * anchored, server-format-specific patterns.
 */
public final class ChatObserver {

    private static final Logger LOGGER = ModLog.get("killer560smod-chatobserver");

    private static final long DEDUPE_WINDOW_MS = 250L;
    // Prefix-based de-dup only for reasonably long lines, so a short line can't swallow an unrelated one.
    private static final int MIN_PREFIX_DEDUPE_LENGTH = 12;

    /** Rewrites a line right before it is added to chat - runs exactly once per real ChatComponent add. */
    @FunctionalInterface
    public interface Rewriter {
        Component rewrite(Component message, String plain);
    }

    private static final List<Consumer<Component>> LISTENERS = new ArrayList<>();
    private static final List<Rewriter> REWRITERS = new ArrayList<>();
    private static final ArrayDeque<Recent> RECENT = new ArrayDeque<>();
    private static boolean fabricSourcesRegistered = false;

    private record Recent(String plain, long atMs) {
    }

    /** Test hook: every listener or rewriter throw caught here, so a harness can assert none happened. */
    private static final java.util.concurrent.atomic.AtomicLong FAILURES = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> FAILURES_BY_LISTENER =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile String lastFailure = null;

    private ChatObserver() {
    }

    /** {@code listener} gets every real (non-overlay) chat line once, whichever path it arrived by. */
    public static synchronized void subscribe(Consumer<Component> listener) {
        LISTENERS.add(listener);
        if (!fabricSourcesRegistered) {
            fabricSourcesRegistered = true;
            ClientReceiveMessageEvents.CHAT.register(
                    (message, signedMessage, sender, params, receptionTimestamp) -> dispatch(message, "fabric-chat"));
            ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
                if (!overlay) {
                    dispatch(message, "fabric-game");
                }
            });
        }
    }

    public static synchronized void addRewriter(Rewriter rewriter) {
        REWRITERS.add(rewriter);
    }

    /** Called from {@code ChatComponentMixin} for every line added to chat. Returns the (possibly rewritten)
     *  line to display; subscribers are notified with the ORIGINAL incoming line. */
    public static Component onChatComponentAdd(Component message) {
        if (message == null) {
            return null;
        }
        Component result = message;
        List<Rewriter> rewriters;
        synchronized (ChatObserver.class) {
            rewriters = List.copyOf(REWRITERS);
        }
        if (!rewriters.isEmpty()) {
            String plain = strip(message);
            for (Rewriter rewriter : rewriters) {
                try {
                    Component rewritten = rewriter.rewrite(result, plain);
                    if (rewritten != null) {
                        result = rewritten;
                    }
                } catch (RuntimeException e) {
                    recordFailure(rewriter, e);
                    LOGGER.error("[ChatObserver] Rewriter threw on \"{}\"", plain, e);
                }
            }
        }
        dispatch(message, "chat-component");
        return result;
    }

    private static void dispatch(Component message, String source) {
        String plain = strip(message);
        if (plain.isBlank()) {
            return;
        }
        List<Consumer<Component>> listeners;
        boolean duplicate;
        synchronized (ChatObserver.class) {
            duplicate = isRecentDuplicate(plain, System.currentTimeMillis());
            listeners = duplicate ? List.<Consumer<Component>>of() : List.copyOf(LISTENERS);
        }
        for (Consumer<Component> listener : listeners) {
            try {
                listener.accept(message);
            } catch (RuntimeException e) {
                recordFailure(listener, e);
                LOGGER.error("[ChatObserver] Listener threw on \"{}\"", plain, e);
            }
        }
    }

    /** How many listener or rewriter throws have been caught since the game started. */
    public static long failures() {
        return FAILURES.get();
    }

    /** {@code "<listener>: <exception>"} for the most recent caught throw, or {@code null} if there has been none. */
    public static String lastFailure() {
        return lastFailure;
    }

    /** Caught throws per listener name (the class that registered the lambda, or the listener's own class). */
    public static java.util.Map<String, Long> failuresByListener() {
        return java.util.Map.copyOf(FAILURES_BY_LISTENER);
    }

    private static void recordFailure(Object listener, RuntimeException e) {
        String name = listenerName(listener);
        FAILURES.incrementAndGet();
        FAILURES_BY_LISTENER.merge(name, 1L, Long::sum);
        lastFailure = name + ": " + e;
    }

    /** A lambda's class is {@code Owner$$Lambda/0x...}; the owner is the useful part. */
    static String listenerName(Object listener) {
        String n = listener == null ? "null" : listener.getClass().getName();
        int lambda = n.indexOf("$$Lambda");
        return lambda >= 0 ? n.substring(0, lambda) : n;
    }

    /** Must hold the class lock. Records {@code plain} when it is not a duplicate. */
    private static boolean isRecentDuplicate(String plain, long now) {
        Iterator<Recent> it = RECENT.iterator();
        while (it.hasNext()) {
            if (now - it.next().atMs() > DEDUPE_WINDOW_MS) {
                it.remove();
            }
        }
        for (Recent r : RECENT) {
            if (r.plain().equals(plain)) {
                return true;
            }
            boolean recentIsShorter = r.plain().length() <= plain.length();
            String shorter = recentIsShorter ? r.plain() : plain;
            String longer = recentIsShorter ? plain : r.plain();
            if (shorter.length() >= MIN_PREFIX_DEDUPE_LENGTH && longer.startsWith(shorter)) {
                return true;
            }
        }
        RECENT.addLast(new Recent(plain, now));
        while (RECENT.size() > 64) {
            RECENT.removeFirst();
        }
        return false;
    }

    public static String strip(Component message) {
        String raw = message.getString();
        String plain = ChatFormatting.stripFormatting(raw);
        return plain == null ? raw : plain;
    }

    /** The same stripping for a raw string that is not a chat line - item/entity names, container titles - trimmed
     *  (the {@code stripName} the puzzle solvers use, shared so name comparisons all normalise the same way). */
    public static String strip(String raw) {
        if (raw == null) {
            return "";
        }
        String plain = ChatFormatting.stripFormatting(raw);
        return (plain == null ? raw : plain).trim();
    }
}
