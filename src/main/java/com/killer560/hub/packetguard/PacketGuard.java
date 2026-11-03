package com.killer560.hub.packetguard;

import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Set;

/**
 * Backstop for a throw out of THIS mod's code on the packet path.
 *
 * <p>Every clientbound packet is applied inside {@code PacketProcessor$ListenerAndPacket.handle}, which catches any
 * {@code Exception} and hands it to {@code ClientCommonPacketListenerImpl.onPacketError}: that logs "Failed to handle
 * packet", writes a disconnection report (which reads like a crash log and lists every mixin on the classes in the trace)
 * and drops the connection with "Network Protocol Error" - in a dungeon, a failed run (player report 2026-11-02:
 * 26.1.2 + NoammAddons + ViaFabric, once after about 8 hours, during a clear). Our handlers are meant to catch their own
 * throws; this catches the one that slips through, so a bug of ours costs that one packet instead of the run.
 *
 * <p>Only a throw whose THROW SITE is our code is kept: the first stack frame outside the JDK and common libraries must
 * be in {@code com.killer560.} or be a mixin handler named {@code killer560smod$...}. A vanilla or another mod's throw
 * that merely passes through one of our wrappers is left to vanilla. A flood (more than {@link #FLOOD_LIMIT} in
 * {@link #FLOOD_WINDOW_MS}) is let through too: a handler failing on every packet means the world state is already
 * wrong, and staying connected to a broken world is worse than the disconnect.
 */
public final class PacketGuard {

    private static final Logger LOGGER = ModLog.get("killer560smod-packetguard");
    static final int FLOOD_LIMIT = 40;
    static final long FLOOD_WINDOW_MS = 10_000L;

    private static final Set<String> loggedSites = new HashSet<>();
    private static long windowStart;
    private static int windowCount;
    private static int kept;
    private static boolean toldPlayer;

    private PacketGuard() {
    }

    /** Total throws kept from disconnecting (testkit). */
    public static synchronized int kept() {
        return kept;
    }

    private static volatile int testThrows;

    /** Testkit: the next {@code n} time packets throw from our own hook (dev builds only). */
    public static void armTestThrow(int n) {
        testThrows = n;
    }

    public static boolean consumeTestThrow() {
        if (testThrows <= 0) {
            return false;
        }
        testThrows--;
        return true;
    }

    /** True when the disconnect for this packet error should be skipped (the throw came from this mod). */
    public static synchronized boolean keepConnected(Packet<?> packet, Exception error) {
        StackTraceElement site = ourThrowSite(error);
        if (site == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - windowStart > FLOOD_WINDOW_MS) {
            windowStart = now;
            windowCount = 0;
        }
        if (++windowCount > FLOOD_LIMIT) {
            LOGGER.error("[PacketGuard] {} throws from this mod in {} ms; letting the disconnect through", windowCount,
                    now - windowStart);
            return false;
        }
        kept++;
        String key = site.getClassName() + "#" + site.getMethodName() + ":" + site.getLineNumber();
        if (loggedSites.size() < 256 && loggedSites.add(key)) {
            LOGGER.error("[PacketGuard] kept the connection: {} threw handling {} (first time from {})",
                    error.getClass().getSimpleName(), packetName(packet), key, error);
        }
        if (!toldPlayer) {
            toldPlayer = true;
            ModChat.send("killer560's mod", Component.literal("a packet handler failed and was skipped so you stay "
                    + "connected. Please send latest.log to killer560."));
        }
        return true;
    }

    private static String packetName(Packet<?> packet) {
        return packet == null ? "?" : packet.getClass().getSimpleName();
    }

    /** The first frame that is not the JDK or a library, if it is ours; null otherwise. Looks at the root cause. */
    static StackTraceElement ourThrowSite(Throwable error) {
        Throwable t = error;
        for (int i = 0; i < 8 && t != null && t.getCause() != null && t.getCause() != t; i++) {
            t = t.getCause();
        }
        if (t == null) {
            return null;
        }
        for (StackTraceElement e : t.getStackTrace()) {
            String c = e.getClassName();
            if (isLibrary(c)) {
                continue;
            }
            return isOurs(e) ? e : null;
        }
        return null;
    }

    private static boolean isLibrary(String c) {
        return c.startsWith("java.") || c.startsWith("jdk.") || c.startsWith("sun.") || c.startsWith("com.google.")
                || c.startsWith("it.unimi.") || c.startsWith("org.apache.") || c.startsWith("com.mojang.serialization.");
    }

    private static boolean isOurs(StackTraceElement e) {
        return e.getClassName().startsWith("com.killer560.") || e.getMethodName().contains("killer560smod$");
    }
}
