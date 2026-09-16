package com.killer560.hub.goldorfrenzy;

import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.witherdragons.ServerTickClock;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Goldor Frenzy Timer - a countdown to Goldor's next P3 damage tick, so a heal / mask / absorption can be
 * timed instead of eating the tick mid-terminal. Ported from Devonian
 * {@code features/dungeons/f7/GoldorFrenzyTimer.kt} (source read 2026-09-16 at
 * a local Devonian checkout), including its exact chat triggers and tick counts:
 * <ul>
 * <li>{@code "[BOSS] Storm: I should have known that I stood no chance."} - starts a one-shot 100-tick (5s)
 *     countdown to Goldor actually arriving ({@code preGoldorTicks = 100}).
 * <li>{@code "[BOSS] Goldor: Who dares trespass into my domain?"} - starts the repeating 60-tick (3s) frenzy
 *     countdown ({@code until = 60}, re-armed to 60 every time it reaches 1).
 * <li>{@code "The Core entrance is opening!"} - stops it.
 * </ul>
 * Counts SERVER ticks through {@link ServerTickClock} (one per non-zero {@code ClientboundPingPacket}, falling
 * back to client ticks), the same clock Tick Timers and the Wither Dragon countdowns already use, so client
 * lag can't drift the 3s cadence.
 * <p>
 * <b>Overlap with Tick Timers - read before enabling both.</b> {@code TickTimersFeature}'s own "Tick:" line is
 * already this same 60-server-tick repeating Goldor countdown (Odin's {@code TickTimers.kt} goldorTickTime),
 * and its "Start:" line is the same pre-Goldor gap (Odin uses 104 ticks where Devonian uses 100). What this
 * feature adds on top is Devonian's cumulative "how long has P3 been running" readout, its own independently
 * positioned/coloured HUD line, and the pre-Goldor countdown being on by default rather than behind Tick
 * Timers' off-by-default "Goldor Start timer" switch. Both HUD lines will show the same number if Tick
 * Timers' Goldor line and this are enabled at once - that is expected, not a bug.
 * <p>
 * Legit: reads chat and counts ticks, draws a number. Ships disabled by default - see
 * {@link GoldorFrenzyConfig}.
 */
public final class GoldorFrenzyFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-goldorfrenzy");

    /** Devonian matches these three lines exactly (equality, not regex). Matched here against the
     *  formatting-stripped text for the same reason {@code TickTimersFeature} does - real Hypixel boss lines
     *  carry mid-word § codes. */
    private static final String STORM_DEATH_LINE = "[BOSS] Storm: I should have known that I stood no chance.";
    private static final String GOLDOR_LINE = "[BOSS] Goldor: Who dares trespass into my domain?";
    private static final String CORE_OPENING_LINE = "The Core entrance is opening!";

    /** Devonian's own constants. */
    private static final int FRENZY_TICKS = 60;
    private static final int PRE_GOLDOR_TICKS = 100;

    private static boolean inGoldor = false;
    private static boolean preGoldor = false;
    private static int until = 0;
    private static int preGoldorTicks = 0;
    /** {@link ServerTickClock#now()} when Goldor's line landed - the baseline for the cumulative readout
     *  (Devonian uses its own {@code Stages.Terminals.startTime.tick} for the same thing). */
    private static long phaseStartTick = 0L;

    private static Object lastLevel = null;
    private static boolean registered = false;

    private GoldorFrenzyFeature() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        // ChatObserver, not Fabric CHAT/GAME: another installed mod can cancel a server line via ALLOW_GAME and
        // re-add its own copy straight to ChatComponent, which Fabric listeners never see. The three triggers
        // here are exact server-format boss lines, so this mod's own client-side messages can't match them.
        ChatObserver.subscribe(GoldorFrenzyFeature::onChatMessage);
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        ServerTickClock.register();
        ServerTickClock.subscribe(GoldorFrenzyFeature::serverTick);
    }

    private static void onChatMessage(Component message) {
        if (!GoldorFrenzyConfig.getInstance().isEnabled()) {
            return;
        }
        String line = ChatObserver.strip(message);
        switch (line) {
            case GOLDOR_LINE -> {
                inGoldor = true;
                preGoldor = false;
                until = FRENZY_TICKS;
                preGoldorTicks = 0;
                phaseStartTick = ServerTickClock.now();
                LOGGER.info("[GoldorFrenzy] Goldor arrived - frenzy countdown armed ({} ticks, repeating)", FRENZY_TICKS);
            }
            case STORM_DEATH_LINE -> {
                preGoldor = true;
                preGoldorTicks = PRE_GOLDOR_TICKS;
                LOGGER.info("[GoldorFrenzy] Storm died - pre-Goldor countdown armed ({} ticks)", PRE_GOLDOR_TICKS);
            }
            case CORE_OPENING_LINE -> {
                if (inGoldor || preGoldor) {
                    LOGGER.info("[GoldorFrenzy] Core entrance opening - countdown stopped");
                }
                inGoldor = false;
                preGoldor = false;
            }
            default -> {
            }
        }
    }

    private static void tick() {
        // Same reset rule Tick Timers uses: Odin resets every timer on a real level change.
        Object level = Minecraft.getInstance().level;
        if (level != lastLevel) {
            if (lastLevel != null) {
                reset();
            }
            lastLevel = level;
        }
        if (!DungeonState.isInDungeon() && (inGoldor || preGoldor)) {
            reset();
        }
    }

    /** Devonian's {@code ClientThreadServerTickEvent} body. */
    private static void serverTick() {
        if (!GoldorFrenzyConfig.getInstance().isEnabled() || !DungeonState.isBossPhaseActive()) {
            return;
        }
        if (preGoldor) {
            preGoldorTicks--;
            if (preGoldorTicks < 0) {
                preGoldor = false;
                preGoldorTicks = 0;
            }
            return;
        }
        if (!inGoldor) {
            return;
        }
        // Devonian: `until = if (until > 1) until - 1 else 60` - re-arms at 60 rather than passing through 0,
        // so the line never blanks for a tick between two frenzies.
        until = until > 1 ? until - 1 : FRENZY_TICKS;
    }

    private static void reset() {
        inGoldor = false;
        preGoldor = false;
        until = 0;
        preGoldorTicks = 0;
        phaseStartTick = 0L;
    }

    /** Same green/orange/red ramp {@code TickTimersFeature#format} uses, so the two timers read the same way. */
    private static String color(int time, int max) {
        return time >= max * 0.66 ? "§a" : time >= max * 0.33 ? "§6" : "§c";
    }

    private static String amount(int ticks) {
        GoldorFrenzyConfig cfg = GoldorFrenzyConfig.getInstance();
        return cfg.isDisplayInTicks() ? ticks + "t" : String.format(Locale.US, "%.2fs", ticks / 20f);
    }

    /** @return the line to draw right now, or null when nothing should show. */
    private static String currentLine() {
        GoldorFrenzyConfig cfg = GoldorFrenzyConfig.getInstance();
        if (!cfg.isEnabled()) {
            return null;
        }
        if (preGoldor && cfg.isShowPreGoldor()) {
            return (cfg.isShowPrefix() ? "§7Goldor in " : "")
                    + color(preGoldorTicks, PRE_GOLDOR_TICKS) + amount(preGoldorTicks);
        }
        if (!inGoldor) {
            return null;
        }
        if (cfg.isShowTotal()) {
            int elapsed = (int) Math.max(0, ServerTickClock.now() - phaseStartTick);
            // No countdown to colour here - it only ever grows - so it stays neutral, like Tick Timers' own
            // "Storm:" elapsed counter.
            return (cfg.isShowPrefix() ? "§7Frenzy total " : "") + "§f" + amount(elapsed);
        }
        return (cfg.isShowPrefix() ? "§7Frenzy " : "") + color(until, FRENZY_TICKS) + amount(until);
    }

    /** HUD id {@code goldor_frenzy}, default position x=10 y=340 - the free slot in the x=10 column between
     *  Split Timers (y=320) and Tick Timers (y=380), keeping the P3 timers together at the mod's usual 20px
     *  spacing and colliding with nothing already registered. */
    public static final class GoldorFrenzyHudElement implements HudElement {

        @Override
        public String id() {
            return "goldor_frenzy";
        }

        @Override
        public String displayName() {
            return "Goldor Frenzy Timer";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            return 340;
        }

        @Override
        public int width() {
            return 120;
        }

        @Override
        public int height() {
            return 12;
        }

        @Override
        public boolean isRelevantNow() {
            return GoldorFrenzyConfig.getInstance().isEnabled() && DungeonState.isF7OrM7();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            if (HudVisibility.hidesHud()) {
                return;
            }
            String line = currentLine();
            if (line == null) {
                return;
            }
            graphics.text(Minecraft.getInstance().font, line, x, y, 0xFFFFFFFF, false);
        }
    }
}
