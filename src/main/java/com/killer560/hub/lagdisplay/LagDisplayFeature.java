package com.killer560.hub.lagdisplay;

import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.witherdragons.ServerTickClock;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Lag / performance HUD - gap-analysis item 1.5 ("Lag / server-tick display"). One movable HUD element
 * with up to four lines: how long ago the last server tick arrived, ping, FPS and CPS.
 *
 * <p>Ported from Devonian {@code features/misc/LagDisplay.kt} (the "zzz for N.NNs" readout and its
 * 50-1000 ms threshold slider, default 300 ms) plus {@code misc/{PingDisplay,FPSDisplay}.kt}, and
 * NoammAddons {@code features/impl/visual/InfoDisplay.kt} (local source
 * a local NoammAddons checkout) for the CPS counter - NoammAddons counts click timestamps and
 * drops anything older than 1000 ms, which is exactly what {@link #cps(Deque)} does.
 *
 * <p><b>Server-tick source.</b> No new clock: this subscribes to the mod's existing
 * {@link ServerTickClock} (Odin's "one server tick per non-zero {@code ClientboundPingPacket}"). That
 * clock deliberately falls back to counting CLIENT ticks whenever the server stops pinging ~20x/s, which
 * would hide the very lag this readout exists to show - so the timestamp is only refreshed while
 * {@link ServerTickClock#isPingDriven()} is true. Because the clock does not fire on client ticks while it
 * is ping-driven, a freeze is caught from its first millisecond; the only cost is that recovery can be
 * noticed up to ~1 s late (the clock's own sample window), i.e. a spike can read up to a second long when
 * it was slightly shorter. It never reads short.
 *
 * <p><b>Servers that never ping 20x/s</b> (singleplayer, a plain test server) would otherwise show an
 * ever-growing fake lag number, so the lag line only appears once the clock has actually been ping-driven
 * at least once on the current connection ({@link #pingClockSeen}), and that latch is cleared on
 * disconnect.
 *
 * <p><b>CPS</b> is sampled by polling GLFW's mouse-button state from a frame callback rather than adding a
 * {@code MouseHandler} mixin, so clicks are counted at frame rate. At normal FPS that is far faster than
 * anyone can click; at very low FPS a click could be missed, which is called out in the tab.
 *
 * <p>Ships OFF.
 */
public final class LagDisplayFeature {

    /** HUD element id - also the key its position/scale are stored under in {@code killer560smod-hud.json}. */
    public static final String HUD_ID = "lag_display";

    private static final long CPS_WINDOW_MS = 1000L;

    private static final Deque<Long> LEFT_CLICKS = new ArrayDeque<>();
    private static final Deque<Long> RIGHT_CLICKS = new ArrayDeque<>();

    private static volatile long lastServerTickMs = 0L;
    private static volatile boolean pingClockSeen = false;
    private static boolean leftWasDown = false;
    private static boolean rightWasDown = false;
    private static boolean registered = false;

    private LagDisplayFeature() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;

        // Idempotent - Wither Dragons and Tick Timers already call this.
        ServerTickClock.register();
        ServerTickClock.subscribe(LagDisplayFeature::onServerTick);

        // Frame-rate click sampler. Separate from the HUD element's own draw so CPS keeps counting even
        // while the element itself is scrolled off / the list is empty.
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("killer560smod", "lag_display_click_sampler"),
                (graphics, deltaTracker) -> sampleClicks());

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
    }

    private static void reset() {
        lastServerTickMs = 0L;
        pingClockSeen = false;
        leftWasDown = false;
        rightWasDown = false;
        synchronized (LEFT_CLICKS) {
            LEFT_CLICKS.clear();
        }
        synchronized (RIGHT_CLICKS) {
            RIGHT_CLICKS.clear();
        }
    }

    private static void onServerTick() {
        if (!ServerTickClock.isPingDriven()) {
            return;
        }
        pingClockSeen = true;
        lastServerTickMs = System.currentTimeMillis();
    }

    private static void sampleClicks() {
        LagDisplayConfig cfg = LagDisplayConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        if (!cfg.isEnabled() || !cfg.isShowCps() || client.player == null || client.screen != null) {
            leftWasDown = false;
            rightWasDown = false;
            return;
        }
        long handle = client.getWindow().handle();
        boolean left = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean right = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        long now = System.currentTimeMillis();
        if (left && !leftWasDown) {
            synchronized (LEFT_CLICKS) {
                LEFT_CLICKS.addLast(now);
            }
        }
        if (right && !rightWasDown) {
            synchronized (RIGHT_CLICKS) {
                RIGHT_CLICKS.addLast(now);
            }
        }
        leftWasDown = left;
        rightWasDown = right;
    }

    /** NoammAddons {@code InfoDisplay.getCps}: drop everything older than a second, count what's left. */
    private static int cps(Deque<Long> clicks) {
        long now = System.currentTimeMillis();
        synchronized (clicks) {
            while (!clicks.isEmpty() && now - clicks.peekFirst() > CPS_WINDOW_MS) {
                clicks.pollFirst();
            }
            return clicks.size();
        }
    }

    /** @return ms since the last server tick, or -1 when the clock can't be trusted here. */
    private static long msSinceServerTick() {
        if (!pingClockSeen || lastServerTickMs == 0L) {
            return -1L;
        }
        return System.currentTimeMillis() - lastServerTickMs;
    }

    /** @return the local player's own tab-list latency, or -1 when unknown. Same source
     *  {@code ChatCommandsFeature}'s {@code !ping} command already uses. */
    private static int ping() {
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() == null || client.player == null) {
            return -1;
        }
        PlayerInfo info = client.getConnection().getPlayerInfo(client.player.getUUID());
        return info == null ? -1 : info.getLatency();
    }

    public static final class LagHudElement implements HudElement {

        private static final int COLOR_GOOD = 0xFF55FF55;
        private static final int COLOR_OK = 0xFFFFFF55;
        private static final int COLOR_BAD = 0xFFFFAA00;
        private static final int COLOR_AWFUL = 0xFFFF5555;
        private static final int COLOR_PLAIN = 0xFFFFFFFF;

        @Override
        public String id() {
            return HUD_ID;
        }

        @Override
        public String displayName() {
            return "Lag Display";
        }

        @Override
        public int defaultX() {
            // Second column (x=200) on the Real Time row: the old 10/220 sat exactly on Etherwarp Waypoints.
            return 200;
        }

        @Override
        public int defaultY() {
            return 40;
        }

        @Override
        public int width() {
            return 110;
        }

        @Override
        public int height() {
            return 12 * Math.max(1, lines().size());
        }

        @Override
        public boolean isRelevantNow() {
            return LagDisplayConfig.getInstance().isEnabled();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            if (!LagDisplayConfig.getInstance().isEnabled() || HudVisibility.hidesHud()) {
                return;
            }
            Font font = Minecraft.getInstance().font;
            int lineY = y;
            for (Line line : lines()) {
                graphics.text(font, line.text(), x, lineY, line.color(), false);
                lineY += 12;
            }
        }

        private record Line(String text, int color) {
        }

        private static List<Line> lines() {
            LagDisplayConfig cfg = LagDisplayConfig.getInstance();
            List<Line> out = new ArrayList<>();
            boolean colored = cfg.isColorByValue();

            if (cfg.isShowLag()) {
                long dt = msSinceServerTick();
                // Devonian only draws the line at all once the server has been silent past the threshold.
                if (dt >= cfg.getLagThresholdMs()) {
                    out.add(new Line(String.format(Locale.US, "zzz for %.2fs", dt / 1000.0),
                            colored ? lagColor(dt) : COLOR_PLAIN));
                }
            }
            if (cfg.isShowPing()) {
                int ping = ping();
                out.add(new Line(ping < 0 ? "Ping: -" : "Ping: " + ping + "ms",
                        colored && ping >= 0 ? pingColor(ping) : COLOR_PLAIN));
            }
            if (cfg.isShowFps()) {
                int fps = Minecraft.getInstance().getFps();
                out.add(new Line("FPS: " + fps, colored ? fpsColor(fps) : COLOR_PLAIN));
            }
            if (cfg.isShowCps()) {
                out.add(new Line("CPS: " + cps(LEFT_CLICKS) + " | " + cps(RIGHT_CLICKS), COLOR_PLAIN));
            }
            return out;
        }

        private static int lagColor(long ms) {
            if (ms < 500L) {
                return COLOR_OK;
            }
            return ms < 1500L ? COLOR_BAD : COLOR_AWFUL;
        }

        /** Devonian {@code PingDisplay.formatPing} thresholds: 50 / 100 / 150 / 200 ms. */
        private static int pingColor(int ping) {
            if (ping < 50) {
                return COLOR_GOOD;
            }
            if (ping < 100) {
                return COLOR_OK;
            }
            return ping < 200 ? COLOR_BAD : COLOR_AWFUL;
        }

        private static int fpsColor(int fps) {
            if (fps >= 120) {
                return COLOR_GOOD;
            }
            return fps >= 60 ? COLOR_OK : COLOR_BAD;
        }
    }
}
