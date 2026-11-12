package com.killer560.hub.util;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.Set;

/**
 * The one queue every chat line this mod sends on its own goes through: party callouts (Posmsg, Leap Message, Cringe,
 * Simon Says, Livid, Blood Camp...), all in the order they were asked for and never two closer together than
 * {@link #MIN_GAP_MS}.
 * <p>
 * Why (killer560, 2026-11-02): "if i leap to someone and they are in one of the posmsg waypoints it tries to send that
 * at the same time as other chat messages". His 26.3 log, 19:26:54: "Leaping to MiningGollum_69" went out, the Posmsg
 * line left in the same tick and Hypixel answered "Woah slow down, you're doing that too fast!" - the line was lost - while
 * the Cringe line Leap Message sends 10 ticks (0.5 s) after Leaping To went through. So 0.5 s between two lines is a gap
 * Hypixel is seen to accept, and the same tick is one it is seen to refuse. SkyHanni's ChatUtils queue spaces its own
 * lines 300 ms apart; 0.5 s is a gap his own log proves, and he chose 250 ms (2026-11-11).
 * <p>
 * A line goes out at once when nothing is waiting and the last line (ours, or one he typed: Fabric's
 * {@link ClientSendMessageEvents} reports those) is at least the gap old; otherwise it waits its turn and the tick
 * loop sends it. {@link #reserve()} keeps a place in line for a line that is not written yet (a translation still on
 * the network), so the order is the order things were asked for, not the order they finished. A place nobody fills
 * is given up after {@link #RESERVE_TIMEOUT_MS}.
 * <p>
 * If Hypixel still answers its rate-limit line right after a line of ours, that line is put back at the front once
 * and sent again a gap later: a callout is never dropped silently. Lines leave through {@link ServerCommands#toServer}
 * (commands) or {@code sendChat} (plain chat), so nothing here re-enters a client command.
 */
public final class OutgoingChat {

    private static final Logger LOGGER = ModLog.get("killer560smod-outgoingchat");

    // Every wait below is 250 ms (killer560, 2026-11-11: "just remove that crazy delay. it should be like .25s").
    /** The least time between two lines this mod sends (see the class comment for where the number comes from). */
    public static final long MIN_GAP_MS = 250L;
    /** A reserved place nobody filled in this long is given up, so it never holds the queue for good. */
    static final long RESERVE_TIMEOUT_MS = 3_000L;
    /** Hypixel's rate-limit answer counts as being about our last line only this soon after it. */
    static final long REJECT_WINDOW_MS = 1_500L;
    /** Hypixel's answer when two lines come too close (his 26.3 log, 2026-11-02). Exact: a system line, not chat. */
    public static final String RATE_LIMIT_LINE = "Woah slow down, you're doing that too fast!";
    /**
     * After a party line, the next line waits for Hypixel's own "Party > ...: text" echo of it, or this long. Hypixel
     * handles a player's commands one after another, so every line already sent sits in front of the next command he
     * presses: his 26.3 log, 2026-11-11, EE3: /pc "Spirit Mask popped!" and "at ee3" left at 21:29:31, echoed 21:29:33,
     * and his /stats keybind's "Stats & Equipment" opened only at 21:29:34, right behind them (the same at 21:19:41,
     * 21:20:02, 21:26:22). Lines sent in a burst echoed 2-3 s late, lone ones at once. One line in flight keeps his own
     * command at most one line behind ours.
     */
    static final long ECHO_WAIT_MS = 250L;
    /**
     * Terminal guard (termclose-1111, killer560 2026-11-11: "something keeps closing my terminals"). His 26.3 logs of
     * 11-11: every F7 terminal Hypixel closed unsolved (5 of 75 opens) had one of our party lines unanswered (sent, not
     * yet echoed) or sent within a second of the open, while 41 opens with nothing in flight were all solved, and the 34
     * Melody progress lines sent well inside an open terminal closed none. So our lines wait while he stands at a P3
     * terminal not yet done (at most {@link #NEAR_WAIT_MS}), from his click on an entity (a terminal stand) until a menu
     * opens ({@link #OPEN_WAIT_MS} at most), and until a terminal has been open this long. His own commands never wait.
     */
    static final long TERMINAL_SETTLE_MS = 250L;
    /** After an entity click, how long our lines wait for a menu to open. */
    static final long OPEN_WAIT_MS = 250L;
    /** The longest our lines wait while he stands at a terminal without opening it (someone else may be in it). */
    static final long NEAR_WAIT_MS = 250L;

    /** Command words that are chat lines to Hypixel (counted against the gap when he types them himself). */
    static final Set<String> CHAT_COMMANDS = Set.of("pc", "ac", "gc", "oc", "cc", "msg", "w", "r", "tell",
            "whisper", "pchat", "achat", "gchat", "ochat", "cchat", "shout");

    /** One line, or a reserved place for one. */
    public static final class Slot {
        private final long createdMs;
        private String line;
        private boolean command;
        private boolean cancelled;
        private boolean retried;

        private Slot(long createdMs) { this.createdMs = createdMs; }

        /** Fills a reserved place with a command (without its slash, e.g. {@code "pc hi"}). */
        public void fillCommand(String command) { fill(command, true); }

        /** Fills a reserved place with a plain chat line. */
        public void fillChat(String text) { fill(text, false); }

        /** Gives the place up (the line turned out empty, or the translation failed and nothing is sent). */
        public void cancel() {
            synchronized (OutgoingChat.class) {
                if (line == null) {
                    cancelled = true;
                }
            }
            drain();
        }

        private void fill(String text, boolean asCommand) {
            synchronized (OutgoingChat.class) {
                if (text == null || text.isBlank()) {
                    cancelled = true;
                } else {
                    line = text;
                    command = asCommand;
                    cancelled = false;
                    if (!QUEUE.contains(this)) {
                        // Given up on before it was filled: it still goes, at the back.
                        QUEUE.addLast(this);
                    }
                }
            }
            drain();
        }

        boolean ready() { return line != null; }
    }

    private static final ArrayDeque<Slot> QUEUE = new ArrayDeque<>();
    private static long lastSentMs = Long.MIN_VALUE / 4;
    private static Slot lastOurs;
    /** The text of our last party line until Hypixel echoes it (null: nothing in flight). */
    private static String awaitEcho;
    private static boolean registered;
    private static long interactSentMs = Long.MIN_VALUE / 4;
    private static Object terminalScreen;
    private static long terminalOpenedMs;
    private static String holdLogged;
    private static long nearSinceMs = Long.MIN_VALUE / 4;
    private static boolean wasNear;

    private OutgoingChat() {
    }

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("OutgoingChat", client -> drain()));
        ClientSendMessageEvents.CHAT.register(message -> noteOther());
        // Every command he types (not only chat lines) goes first: our waiting lines take their gap after it.
        ClientSendMessageEvents.COMMAND.register(command -> noteOther());
        ChatObserver.subscribe(message -> onServerLine(ChatObserver.strip(message)));
    }

    /**
     * Sends a command (without its slash, e.g. {@code "pc at ee2"}) now, or as soon as the gap allows after every
     * line already waiting. @return false only when there is nobody to send it as (not connected).
     */
    public static boolean command(String command) {
        if (command == null || command.isBlank()) return false;
        if (!connected()) return false;
        Slot s;
        synchronized (OutgoingChat.class) {
            s = new Slot(now());
            s.line = command;
            s.command = true;
            QUEUE.addLast(s);
        }
        drain();
        return true;
    }

    /** No movement key held, not sprinting: a command sent mid-walk draws experimental Grim's ChatC (Inventory Walk gate, 2026-11-06). */
    public static boolean standingStill() {
        net.minecraft.client.player.LocalPlayer p = net.minecraft.client.Minecraft.getInstance().player;
        if (p == null) return false;
        net.minecraft.world.entity.player.Input k = p.input.keyPresses;
        return !(k.forward() || k.backward() || k.left() || k.right() || k.sprint() || k.shift() || p.isSprinting());
    }

    /** Keeps a place in line for a line that is not written yet; fill or cancel it. */
    public static Slot reserve() {
        synchronized (OutgoingChat.class) {
            Slot s = new Slot(now());
            QUEUE.addLast(s);
            return s;
        }
    }

    /** Lines (and reserved places) still waiting. */
    public static synchronized int pending() { return QUEUE.size(); }

    /** When the last line left (ms, {@link System#nanoTime} based), for the testkit and diagnostics. */
    public static synchronized long lastSentMs() { return lastSentMs; }

    /** Sends the head of the line if its turn has come. Called on every tick and after every change. */
    static void drain() {
        Minecraft client = Minecraft.getInstance();
        if (!client.isSameThread()) {
            client.execute(OutgoingChat::drain);
            return;
        }
        Slot send = null;
        synchronized (OutgoingChat.class) {
            if (!connected()) {
                if (!QUEUE.isEmpty()) {
                    LOGGER.info("[OutgoingChat] not connected: {} waiting line(s) not sent", QUEUE.size());
                    QUEUE.clear();
                }
                return;
            }
            long t = now();
            if (terminalHold(client, t)) return;
            while (!QUEUE.isEmpty()) {
                Slot head = QUEUE.peekFirst();
                if (head.cancelled) {
                    QUEUE.pollFirst();
                    continue;
                }
                if (!head.ready()) {
                    if (t - head.createdMs >= RESERVE_TIMEOUT_MS) {
                        LOGGER.info("[OutgoingChat] a reserved place was not filled in {} ms; the lines behind it go on",
                                RESERVE_TIMEOUT_MS);
                        head.cancelled = true;
                        QUEUE.pollFirst();
                        continue;
                    }
                    return;
                }
                if (t - lastSentMs < MIN_GAP_MS) return;
                if (awaitEcho != null && t - lastSentMs < ECHO_WAIT_MS) return;
                send = QUEUE.pollFirst();
                lastSentMs = t;
                lastOurs = send;
                awaitEcho = send.command && send.line.regionMatches(true, 0, "pc ", 0, 3) ? send.line.substring(3).strip() : null;
                break;
            }
        }
        if (send == null) return;
        if (send.command) {
            ServerCommands.toServer(send.line);
        } else if (client.player != null) {
            client.player.connection.sendChat(send.line);
        }
        LOGGER.info("[OutgoingChat] sent {}{}", send.command ? "/" : "", send.line);
    }

    /** An entity click (his or Terminal Aura's) left: a terminal may be opening (RightClickSendMixin). */
    public static synchronized void onInteractSent() { interactSentMs = now(); }

    /** True while our lines must wait for a terminal that is opening or just opened (see {@link #TERMINAL_SETTLE_MS}). */
    private static boolean terminalHold(Minecraft client, long t) {
        net.minecraft.client.gui.screens.Screen screen = com.killer560.hub.compat.McCompat.screen(client);
        boolean terminal = com.killer560.hub.hud.HudVisibility.isTerminalMenu(screen);
        if (!terminal) {
            terminalScreen = null;
        } else if (screen != terminalScreen) {
            terminalScreen = screen;
            terminalOpenedMs = t;
            holdLogged = null;
            interactSentMs = Long.MIN_VALUE / 4;
        }
        boolean near = !terminal && !QUEUE.isEmpty() && client.player != null
                && com.killer560.hub.p3status.P3StatusFeature.nearUndoneTerminal(client.player.position());
        if (near && !wasNear) nearSinceMs = t;
        wasNear = near;
        String why = terminal && t - terminalOpenedMs < TERMINAL_SETTLE_MS ? "a terminal just opened"
                : !terminal && screen == null && t - interactSentMs < OPEN_WAIT_MS ? "an entity click may open a terminal"
                : near && t - nearSinceMs < NEAR_WAIT_MS ? "he stands at a terminal" : null;
        if (why != null && !QUEUE.isEmpty() && !why.equals(holdLogged)) {
            LOGGER.info("[OutgoingChat] holding {} line(s): {}", QUEUE.size(), why);
        }
        holdLogged = QUEUE.isEmpty() ? null : why;
        return why != null;
    }

    /**
     * He sent a line or command himself (typed, a Chat Keybind, another mod through the normal path): it goes now, and
     * our waiting lines take their full gap after it, never before it.
     */
    public static synchronized void noteOther() {
        lastSentMs = now();
        lastOurs = null;
    }

    private static void onServerLine(String text) {
        String echo = awaitEcho;
        if (echo != null && text.startsWith("Party > ") && text.endsWith(": " + echo)) {
            synchronized (OutgoingChat.class) {
                awaitEcho = null;
            }
            return;
        }
        if (!RATE_LIMIT_LINE.equals(text)) return;
        synchronized (OutgoingChat.class) {
            Slot ours = lastOurs;
            if (ours == null || ours.retried || now() - lastSentMs > REJECT_WINDOW_MS) return;
            ours.retried = true;
            lastOurs = null;
            // Hypixel refused it: send it again first, a full gap from now.
            lastSentMs = now();
            QUEUE.addFirst(ours);
            LOGGER.info("[OutgoingChat] Hypixel refused \"{}\" as too fast; sending it again", ours.line);
        }
    }

    private static boolean connected() {
        Minecraft client = Minecraft.getInstance();
        return client.player != null && client.player.connection != null;
    }

    private static long now() { return System.nanoTime() / 1_000_000L; }
}
