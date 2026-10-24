package com.killer560.hub.util;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.Iterator;
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
 * dropped as a duplicate; a line another mod canceled-and-re-added only arrives via ChatComponent.
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

    // Copy-on-write: written under the class lock (rare, at start-up), read lock-free per line. Before 2026-10-15
    // every line copied the ~55-entry listener list (List.copyOf) under the lock, twice (Fabric, then ChatComponent).
    @SuppressWarnings("unchecked")
    private static volatile Consumer<Component>[] listeners = new Consumer[0];
    private static volatile Rewriter[] rewriters = new Rewriter[0];
    private static final ArrayDeque<Recent> RECENT = new ArrayDeque<>();
    private static boolean fabricSourcesRegistered = false;

    /**
     * The line being delivered right now, and its text, computed at most once (fps-idle-1015, 2026-10-15).
     *
     * <p>Every subscriber and most raw chat listeners start with {@code message.getString()} (a full walk of the
     * component tree into a new String) and usually a strip of the color codes; with ~55 subscribers that was ~55
     * walks of the same line. While a line is being delivered - one {@link #dispatch}, or one of Fabric's CHAT /
     * GAME / MODIFY_GAME events between the two phases {@link #registerScopes} adds around them - {@link #raw} and
     * {@link #strip(Component)} hand back the text already made for that very object. Identity only, and only for
     * the duration of that one delivery on that thread, so a component built and changed elsewhere is never served a
     * stale string.
     */
    private static Component scopeMsg;
    private static Thread scopeThread;
    private static String scopeRaw;
    private static String scopePlain;
    private static final net.minecraft.resources.Identifier SCOPE_OPEN =
            net.minecraft.resources.Identifier.fromNamespaceAndPath("killer560smod", "chat_text_scope_open");
    private static final net.minecraft.resources.Identifier SCOPE_CLOSE =
            net.minecraft.resources.Identifier.fromNamespaceAndPath("killer560smod", "chat_text_scope_close");
    private static boolean scopesRegistered;

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
        Consumer<Component>[] next = java.util.Arrays.copyOf(listeners, listeners.length + 1);
        next[next.length - 1] = listener;
        listeners = next;
        registerScopes();
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
        Rewriter[] next = java.util.Arrays.copyOf(rewriters, rewriters.length + 1);
        next[next.length - 1] = rewriter;
        rewriters = next;
    }

    /**
     * Opens the text scope (see {@link #scopeMsg}) at the start of Fabric's CHAT, GAME and MODIFY_GAME events and
     * closes it at their end, so every listener of one event shares one {@code getString()}. Their invokers are plain
     * loops over every listener (no early exit), so the closing phase always runs. ALLOW_* events can stop early and
     * are left alone. Idempotent; called from {@link #subscribe}, which every client start reaches.
     */
    public static synchronized void registerScopes() {
        if (scopesRegistered) {
            return;
        }
        scopesRegistered = true;
        net.fabricmc.fabric.api.event.Event<?>[] events = {ClientReceiveMessageEvents.CHAT,
                ClientReceiveMessageEvents.GAME, ClientReceiveMessageEvents.MODIFY_GAME};
        for (net.fabricmc.fabric.api.event.Event<?> e : events) {
            e.addPhaseOrdering(SCOPE_OPEN, net.fabricmc.fabric.api.event.Event.DEFAULT_PHASE);
            e.addPhaseOrdering(net.fabricmc.fabric.api.event.Event.DEFAULT_PHASE, SCOPE_CLOSE);
        }
        ClientReceiveMessageEvents.CHAT.register(SCOPE_OPEN, (message, signed, sender, params, at) -> openScope(message));
        ClientReceiveMessageEvents.CHAT.register(SCOPE_CLOSE, (message, signed, sender, params, at) -> closeScope());
        ClientReceiveMessageEvents.GAME.register(SCOPE_OPEN, (message, overlay) -> openScope(message));
        ClientReceiveMessageEvents.GAME.register(SCOPE_CLOSE, (message, overlay) -> closeScope());
        ClientReceiveMessageEvents.MODIFY_GAME.register(SCOPE_OPEN, (message, overlay) -> {
            openScope(message);
            return message;
        });
        ClientReceiveMessageEvents.MODIFY_GAME.register(SCOPE_CLOSE, (message, overlay) -> {
            closeScope();
            return message;
        });
    }

    private static void openScope(Component message) {
        scopeMsg = message;
        scopeThread = Thread.currentThread();
        scopeRaw = null;
        scopePlain = null;
    }

    private static void closeScope() {
        scopeMsg = null;
        scopeThread = null;
        scopeRaw = null;
        scopePlain = null;
    }

    private static boolean inScope(Component message) {
        return message != null && message == scopeMsg && Thread.currentThread() == scopeThread;
    }

    /**
     * {@code message.getString()}, made once per delivered line: inside a chat delivery (a subscriber, or a raw
     * CHAT / GAME / MODIFY_GAME listener) the text the first caller made is handed to every later one. Anywhere else
     * it is exactly {@code message.getString()}.
     */
    public static String raw(Component message) {
        if (inScope(message)) {
            String r = scopeRaw;
            if (r == null) {
                r = message.getString();
                scopeRaw = r;
            }
            return r;
        }
        return message.getString();
    }

    /** Called from {@code ChatComponentMixin} for every line added to chat. Returns the (possibly rewritten)
     *  line to display; subscribers are notified with the ORIGINAL incoming line. */
    public static Component onChatComponentAdd(Component message) {
        if (message == null) {
            return null;
        }
        // One text scope for the rewriters and the dispatch (which reuses it), restored after: not inside any Fabric
        // event here.
        Component outerMsg = scopeMsg;
        Thread outerThread = scopeThread;
        String outerRaw = scopeRaw;
        String outerPlain = scopePlain;
        boolean reuse = inScope(message);
        if (!reuse) {
            openScope(message);
        }
        try {
            Component result = message;
            Rewriter[] rewriters = ChatObserver.rewriters;
            if (rewriters.length > 0) {
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
        } finally {
            if (!reuse) {
                scopeMsg = outerMsg;
                scopeThread = outerThread;
                scopeRaw = outerRaw;
                scopePlain = outerPlain;
            }
        }
    }

    private static void dispatch(Component message, String source) {
        // A scope of its own (saved and restored, as a listener can add a chat line of its own): the ChatComponent
        // path is not inside a Fabric event, and the Fabric one may be inside another mod's.
        Component outerMsg = scopeMsg;
        Thread outerThread = scopeThread;
        String outerRaw = scopeRaw;
        String outerPlain = scopePlain;
        boolean reuse = inScope(message);
        if (!reuse) {
            openScope(message);
        }
        try {
            String plain = strip(message);
            if (plain.isBlank()) {
                return;
            }
            boolean duplicate;
            synchronized (ChatObserver.class) {
                duplicate = isRecentDuplicate(plain, System.currentTimeMillis());
            }
            if (duplicate) {
                return;
            }
            for (Consumer<Component> listener : listeners) {
                try {
                    listener.accept(message);
                } catch (RuntimeException e) {
                    recordFailure(listener, e);
                    LOGGER.error("[ChatObserver] Listener threw on \"{}\"", plain, e);
                }
            }
        } finally {
            if (!reuse) {
                scopeMsg = outerMsg;
                scopeThread = outerThread;
                scopeRaw = outerRaw;
                scopePlain = outerPlain;
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

    /**
     * Exactly {@link ChatFormatting#stripFormatting} (null in, null out), without its regex when the string holds no
     * section sign - which is nearly always: tab-list and scoreboard Components carry their color in Style, so
     * {@code getString()} has no codes in it. Several tick handlers strip every tab entry every tick; the regex's
     * Matcher and replaceAll were most of that (95-fps-bench JFR, 2026-10-05).
     */
    public static String stripCodes(String raw) {
        return raw == null || raw.indexOf('§') < 0 ? raw : ChatFormatting.stripFormatting(raw);
    }

    /** {@code message.getString()} with color codes stripped; made once per delivered line (see {@link #raw}). */
    public static String strip(Component message) {
        if (inScope(message)) {
            String p = scopePlain;
            if (p == null) {
                String raw = raw(message);
                String plain = stripCodes(raw);
                p = plain == null ? raw : plain;
                scopePlain = p;
            }
            return p;
        }
        String raw = message.getString();
        String plain = stripCodes(raw);
        return plain == null ? raw : plain;
    }

    /** The same stripping for a raw string that is not a chat line - item/entity names, container titles - trimmed
     *  (the {@code stripName} the puzzle solvers use, shared so name comparisons all normalize the same way). */
    public static String strip(String raw) {
        if (raw == null) {
            return "";
        }
        String plain = stripCodes(raw);
        return (plain == null ? raw : plain).trim();
    }
}
