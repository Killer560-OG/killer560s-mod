package com.killer560.hub.util;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Every chat command this mod sends on its own (party callouts, keybind replies, auto messages) goes through
 * {@link #command}, and it leaves the moment it is requested: no queue, no gap between lines, no waiting for an echo
 * (killer560, 2026-11-12: "do away with the timing stuff. just have it all send when i should send it").
 * <p>
 * Two rules remain. Nothing is sent while not connected. And nothing mod-originated goes out while a terminal GUI is
 * open ("nothing goes off in a terminal", killer560, 2026-11-12): Hypixel closes his terminal when a line goes out then
 * (his 11-11 logs: every terminal closed unsolved had one of our party lines in flight). Such a line is held and goes out
 * on the first tick no terminal is open, or is dropped if it is then older than {@link #STALE_MS}. Only a line asked for
 * during a terminal is held; nothing else waits behind it, and his own commands never wait.
 */
public final class OutgoingChat {

    private static final Logger LOGGER = ModLog.get("killer560smod-outgoingchat");

    /** Command words that are chat lines to Hypixel (the sim answers them itself, ServerCommands.simChat). */
    static final Set<String> CHAT_COMMANDS = Set.of("pc", "ac", "gc", "oc", "cc", "msg", "w", "r", "tell",
            "whisper", "pchat", "achat", "gchat", "ochat", "cchat", "shout");

    /** A held line older than this when the terminal closes is dropped as out of date. */
    static final long STALE_MS = 30_000L;

    private record Held(String text, boolean command, long atMs) { }

    /** Lines held while a terminal is open (cleared on disconnect, at most 16). */
    private static final List<Held> HELD = new ArrayList<>();
    private static boolean registered;

    private OutgoingChat() {
    }

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("OutgoingChat", client -> flush()));
    }

    /**
     * Sends a command (without its slash, e.g. {@code "pc at ee2"}) now. @return false only when there is nobody to
     * send it as (not connected).
     */
    public static boolean command(String command) {
        return go(command, true);
    }

    /** Sends a plain chat line now (same rules as {@link #command}). */
    public static boolean chat(String text) {
        return go(text, false);
    }

    private static boolean go(String text, boolean command) {
        if (text == null || text.isBlank()) return false;
        Minecraft client = Minecraft.getInstance();
        if (!connected(client)) return false;
        if (!client.isSameThread()) {
            client.execute(() -> go(text, command));
            return true;
        }
        if (terminalOpen(client)) {
            synchronized (OutgoingChat.class) {
                if (HELD.size() < 16) HELD.add(new Held(text, command, System.currentTimeMillis()));
            }
            LOGGER.info("[OutgoingChat] holding {}{}: a terminal is open", command ? "/" : "", text);
            return true;
        }
        send(text, command);
        return true;
    }

    /** No movement key held, not sprinting: a command sent mid-walk draws experimental Grim's ChatC (Inventory Walk gate, 2026-11-06). */
    public static boolean standingStill() {
        net.minecraft.client.player.LocalPlayer p = net.minecraft.client.Minecraft.getInstance().player;
        if (p == null) return false;
        net.minecraft.world.entity.player.Input k = p.input.keyPresses;
        return !(k.forward() || k.backward() || k.left() || k.right() || k.sprint() || k.shift() || p.isSprinting());
    }

    /** Held lines still waiting for a terminal to close, for the testkit and diagnostics. */
    public static synchronized int pending() { return HELD.size(); }

    private static void flush() {
        List<Held> out;
        synchronized (OutgoingChat.class) {
            if (HELD.isEmpty()) return;
            Minecraft client = Minecraft.getInstance();
            if (!connected(client)) {
                HELD.clear();
                return;
            }
            if (terminalOpen(client)) return;
            out = new ArrayList<>(HELD);
            HELD.clear();
        }
        long now = System.currentTimeMillis();
        for (Held h : out) {
            if (now - h.atMs() > STALE_MS) {
                LOGGER.info("[OutgoingChat] dropped stale held line {}", h.text());
            } else {
                send(h.text(), h.command());
            }
        }
    }

    private static void send(String text, boolean command) {
        if (command) {
            ServerCommands.toServer(text);
        } else {
            Minecraft.getInstance().player.connection.sendChat(text);
        }
        LOGGER.info("[OutgoingChat] sent {}{}", command ? "/" : "", text);
    }

    private static boolean terminalOpen(Minecraft client) {
        return com.killer560.hub.hud.HudVisibility.isTerminalMenu(com.killer560.hub.compat.McCompat.screen(client));
    }

    private static boolean connected(Minecraft client) {
        return client.player != null && client.player.connection != null;
    }
}
