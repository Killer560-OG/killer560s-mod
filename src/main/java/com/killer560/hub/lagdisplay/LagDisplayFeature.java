package com.killer560.hub.lagdisplay;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.witherdragons.ServerTickClock;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
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
 * <p><b>Ping</b> is a measured round trip ({@link com.killer560.hub.util.PingTracker}: vanilla's ping request /
 * pong pair, once a second while the line is on). It used to read the player's own tab-list latency, which Hypixel
 * never fills in, so it always said 1ms (killer560, 2026-10-11).
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
    /** Same window length as Party Commands' "!tps" ({@code partycommands.InfoReplies}). */
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
        com.killer560.hub.util.PingTracker.register();
        com.killer560.hub.util.PingTracker.addDemand(() -> {
            LagDisplayConfig cfg = LagDisplayConfig.getInstance();
            return cfg.isEnabled() && cfg.isShowPing();
        });
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

    /** @return the measured round trip ({@link com.killer560.hub.util.PingTracker}), or -1 when unknown. The tab-list
     *  latency this used to read is never filled in by Hypixel, which is why the HUD said 1 ms. */
    private static int ping() {
        return com.killer560.hub.util.PingTracker.bestMs();
    }

    public static final class LagHudElement implements HudElement {

        // The five colours are the user's (LagDisplayConfig, Performance HUD > Colors); lines() reads them once per
        // rebuild into these, and a change of any of them rebuilds the cached lines.
        private static int COLOR_GOOD = LagDisplayConfig.DEFAULT_GOOD;
        private static int COLOR_OK = LagDisplayConfig.DEFAULT_OK;
        private static int COLOR_BAD = LagDisplayConfig.DEFAULT_BAD;
        private static int COLOR_AWFUL = LagDisplayConfig.DEFAULT_AWFUL;
        private static int COLOR_PLAIN = LagDisplayConfig.DEFAULT_TEXT;

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
            // The widest drawn line (it was a fixed 110 around about 45 units of text).
            Font font = Minecraft.getInstance().font;
            int w = 1;
            for (Line line : lines()) {
                w = Math.max(w, font.width(line.text()));
            }
            return w;
        }

        @Override
        public int height() {
            return com.killer560.hub.hud.HudText.height(lines().size(), 12);
        }

        @Override
        public boolean isEnabledInSettings() {
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
            if (lineY != y) {
                HudSeen.markDrawn(id());
            }
        }

        private record Line(String text, int color) {
        }

        // The lines only change when a shown number does, so they are built once per change rather than every frame
        // and again for width()/height() (String.format's Formatter each time: 23 us and 2.5 KB a frame,
        // 95-perf-features 2026-10-15). Render thread only.
        private static List<Line> cachedLines = List.of();
        private static long cachedKey = Long.MIN_VALUE;
        private static int cachedFlags = -1;
        private static long cachedColours = Long.MIN_VALUE;

        private static List<Line> lines() {
            LagDisplayConfig cfg = LagDisplayConfig.getInstance();
            boolean colored = cfg.isColorByValue();
            int flags = (cfg.isShowTps() ? 1 : 0) | (cfg.isShowPing() ? 2 : 0) | (cfg.isShowFps() ? 4 : 0)
                    | (cfg.isShowCps() ? 8 : 0) | (colored ? 16 : 0);
            // TPS in tenths (what is shown), or -1; the other values are small whole numbers.
            long colours = ((long) cfg.getColorGood() * 31 + cfg.getColorOk()) * 31 + cfg.getColorBad();
            colours = ((colours * 31 + cfg.getColorAwful()) * 31) + cfg.getColorText();
            long tpsTenths = (flags & 1) != 0 ? tenths(tps()) : 0;
            long ping = (flags & 2) != 0 ? ping() : 0;
            long fps = (flags & 4) != 0 ? Minecraft.getInstance().getFps() : 0;
            long cpsL = (flags & 8) != 0 ? cps(LEFT_CLICKS) : 0;
            long cpsR = (flags & 8) != 0 ? cps(RIGHT_CLICKS) : 0;
            // Packed key: tps 10 bits, ping 16, fps 16, cps 2 x 8 (each clamped; a clamp only costs a rebuild).
            long key = (Math.min(tpsTenths + 1, 1023)) | (Math.min(ping + 1, 65535) << 10)
                    | (Math.min(fps, 65535) << 26) | (Math.min(cpsL, 255) << 42) | (Math.min(cpsR, 255) << 50);
            if (key == cachedKey && flags == cachedFlags && colours == cachedColours && tpsTenths < 1022 && ping < 65534 && fps < 65535
                    && cpsL < 255 && cpsR < 255) {
                return cachedLines;
            }
            COLOR_GOOD = cfg.getColorGood();
            COLOR_OK = cfg.getColorOk();
            COLOR_BAD = cfg.getColorBad();
            COLOR_AWFUL = cfg.getColorAwful();
            COLOR_PLAIN = cfg.getColorText();
            List<Line> out = new ArrayList<>(4);
            if ((flags & 1) != 0) {
                out.add(new Line(tpsTenths < 0 ? "TPS: -" : "TPS: " + tpsTenths / 10 + "." + tpsTenths % 10,
                        colored && tpsTenths >= 0 ? tpsColor(tpsTenths / 10.0) : COLOR_PLAIN));
            }
            if ((flags & 2) != 0) {
                out.add(new Line(ping < 0 ? "Ping: -" : "Ping: " + ping + "ms",
                        colored && ping >= 0 ? pingColor((int) ping) : COLOR_PLAIN));
            }
            if ((flags & 4) != 0) {
                out.add(new Line("FPS: " + fps, colored ? fpsColor((int) fps) : COLOR_PLAIN));
            }
            if ((flags & 8) != 0) {
                out.add(new Line("CPS: " + cpsL + " | " + cpsR, COLOR_PLAIN));
            }
            cachedLines = out;
            cachedKey = key;
            cachedFlags = flags;
            cachedColours = colours;
            return out;
        }

        /** {@code t} rounded to tenths as %.1f shows it (half up), or -1 for "unknown". */
        private static long tenths(double t) {
            return t < 0 ? -1 : Math.round(t * 10.0);
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
