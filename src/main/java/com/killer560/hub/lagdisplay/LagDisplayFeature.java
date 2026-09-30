package com.killer560.hub.lagdisplay;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.witherdragons.ServerTickClock;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
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
import com.killer560.hub.compat.McCompat;

/**
 * Performance HUD (renamed from "Lag Display", killer560 2026-09-27) - gap-analysis item 1.5 ("Lag /
 * server-tick display"). One movable HUD element with up to four lines: TPS, ping, FPS and CPS.
 *
 * <p>Ported from Devonian {@code misc/{PingDisplay,FPSDisplay}.kt} plus NoammAddons
 * {@code features/impl/visual/InfoDisplay.kt} (local source, a local NoammAddons checkout) for the CPS
 * counter - NoammAddons counts click timestamps and drops anything older than 1000 ms, which is exactly
 * what {@link #cps(Deque)} does. The old "server lag" line (Devonian's "zzz for N.NNs", threshold slider
 * and all) is gone - killer560 (2026-09-27): "instead of server lag make it tps" - replaced by a real TPS
 * readout, same windowed-tick-time math as {@code ChatCommandsFeature}'s own "!tps" command.
 *
 * <p><b>Ping</b> is real network latency, not a client-side measurement: the local player's own tab-list
 * entry ({@link net.minecraft.client.multiplayer.PlayerInfo#getLatency()}), the same real round-trip value
 * Hypixel reports for everyone in the tab list - not something derived locally, so it can't just read 1ms.
 *
 * <p><b>TPS source.</b> No new clock: this subscribes to the mod's existing {@link ServerTickClock} (Odin's
 * "one server tick per non-zero {@code ClientboundPingPacket}") and keeps a rolling window of tick
 * timestamps, exactly like {@code ChatCommandsFeature#tpsReply()}. That clock deliberately falls back to
 * counting CLIENT ticks whenever the server stops pinging ~20x/s - a server that never pings 20x/s
 * (singleplayer, a plain test server) would otherwise show a fake, always-20 TPS number, so the TPS line
 * only ever prints a number while {@link ServerTickClock#isPingDriven()} is true; otherwise it reads "-".
 *
 * <p><b>CPS</b> is sampled by polling GLFW's mouse-button state from a frame callback rather than adding a
 * {@code MouseHandler} mixin, so clicks are counted at frame rate. At normal FPS that is far faster than
 * anyone can click; at very low FPS a click could be missed, which is called out in the tab.
 *
 * <p>Ships OFF.
 */
public final class LagDisplayFeature {

    /** HUD element id - also the key its position/scale are stored under in {@code killer560smod-hud.json}.
     *  Kept as "lag_display" (the old name) so a saved drag position carries over the rename. */
    public static final String HUD_ID = "lag_display";

    private static final long CPS_WINDOW_MS = 1000L;
    /** Same window length as {@code ChatCommandsFeature}'s "!tps" command. */
    private static final long TPS_WINDOW_MS = 5_000L;

    private static final Deque<Long> LEFT_CLICKS = new ArrayDeque<>();
    private static final Deque<Long> RIGHT_CLICKS = new ArrayDeque<>();
    private static final Deque<Long> TICK_TIMES = new ArrayDeque<>();

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
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("LagDisplayFeature", client -> pruneTicks()));

        // Frame-rate click sampler. Separate from the HUD element's own draw so CPS keeps counting even
        // while the element itself is scrolled off / the list is empty.
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("killer560smod", "lag_display_click_sampler"),
                (graphics, deltaTracker) -> sampleClicks());

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
    }

    private static void reset() {
        leftWasDown = false;
        rightWasDown = false;
        synchronized (LEFT_CLICKS) {
            LEFT_CLICKS.clear();
        }
        synchronized (RIGHT_CLICKS) {
            RIGHT_CLICKS.clear();
        }
        synchronized (TICK_TIMES) {
            TICK_TIMES.clear();
        }
    }

    private static void onServerTick() {
        if (!ServerTickClock.isPingDriven()) {
            return;
        }
        synchronized (TICK_TIMES) {
            TICK_TIMES.addLast(System.currentTimeMillis());
            // Bounded independently of pruneTicks() below - a sudden ping burst after a stall must never
            // grow this without limit while nothing has pruned it yet.
            while (TICK_TIMES.size() > 200) {
                TICK_TIMES.pollFirst();
            }
        }
    }

    private static void pruneTicks() {
        long now = System.currentTimeMillis();
        synchronized (TICK_TIMES) {
            while (!TICK_TIMES.isEmpty() && now - TICK_TIMES.peekFirst() > TPS_WINDOW_MS) {
                TICK_TIMES.pollFirst();
            }
        }
    }

    private static void sampleClicks() {
        LagDisplayConfig cfg = LagDisplayConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        if (!cfg.isEnabled() || !cfg.isShowCps() || client.player == null || McCompat.screen(client) != null) {
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

    /** @return current server TPS (capped at 20), or -1 when the server isn't ping-driven right now or
     *  there isn't enough data yet - same windowed-tick-time approach as {@code ChatCommandsFeature}'s
     *  own "!tps" command; see that method's doc for why this only ever trusts ping-driven ticks. */
    private static double tps() {
        if (!ServerTickClock.isPingDriven()) {
            return -1.0;
        }
        synchronized (TICK_TIMES) {
            if (TICK_TIMES.size() < 2) {
                return -1.0;
            }
            long spanMs = TICK_TIMES.peekLast() - TICK_TIMES.peekFirst();
            if (spanMs <= 0) {
                return -1.0;
            }
            return Math.min(20.0, (TICK_TIMES.size() - 1) * 1000.0 / spanMs);
        }
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
            return "Performance HUD";
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

            if (cfg.isShowTps()) {
                double t = tps();
                out.add(new Line(t < 0 ? "TPS: -" : String.format(Locale.US, "TPS: %.1f", t),
                        colored && t >= 0 ? tpsColor(t) : COLOR_PLAIN));
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

        /** 20 TPS is perfect; Hypixel dungeons feel fine down to ~18, get choppy under 15, and under 10
         *  is a real lag spike. */
        private static int tpsColor(double t) {
            if (t >= 19.5) {
                return COLOR_GOOD;
            }
            if (t >= 15.0) {
                return COLOR_OK;
            }
            return t >= 10.0 ? COLOR_BAD : COLOR_AWFUL;
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
