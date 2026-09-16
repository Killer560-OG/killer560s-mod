package com.killer560.hub.ticktimers;

import com.killer560.hub.hud.HudElement;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.witherdragons.ServerTickClock;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * F7/M7 boss-fight countdown timers - killer560's "tick timers from Odin/noamm" request. Every chat
 * trigger and tick count below is ported directly from Odin's own real, confirmed {@code TickTimers.kt}
 * - real boss dialogue lines and real countdown lengths (Necron/Goldor: 60 ticks; Storm's pad: 20
 * ticks; lightning: 560 ticks; the purple-pillar "PY" window: 95 ticks; Storm's second-phase crush
 * window: 620 ticks), not guessed. Goldor's "Tick:" and Storm's pad timers REPEAT (re-arm on reaching 0)
 * exactly like Odin's, until their real stop lines. Counts SERVER ticks like Odin's {@code TickEvent.Server}
 * (2026-09-15: via {@link ServerTickClock}, one tick per non-zero ClientboundPingPacket, falling back to client
 * ticks on a server that doesn't send per-tick pings), so client lag no longer drifts Goldor's 3s tick.
 * <p>
 * Goldor "one-shot" report, re-verified 2026-09-15 against Odin main @38ddc1b TickTimers.kt: Odin's server tick does
 * {@code if (goldorTickTime == 0 && goldorStartTime <= 0 && goldorHud.enabled) goldorTickTime = 60} BEFORE the
 * decrements - that re-arm (restored in c571b1d) is exactly what's below. It can only stop repeating if Goldor's
 * line arrives &lt;44 ticks after Storm's death line (Start still &gt; 0 when Tick hits 0); real runs in the Dungeons
 * instance's logs show ~5s (100 ticks) between them, and p3sim ~15s, so it repeats there too (the gate is dropped
 * anyway, see the comment on the re-arm).
 * <p>
 * Deliberately omits Odin's "Secrets" pulse timer - that one isn't a
 * real per-secret prediction (Hypixel doesn't expose secret-spawn timing), just a repeating 20-tick
 * cosmetic pulse, and killer560's list already has a real secret-count feature elsewhere
 * ({@code DungeonInfoFeature}).
 */
public final class TickTimersFeature {

    private static final Pattern NECRON_REGEX = Pattern.compile("^\\[BOSS] Necron: I'm afraid, your journey ends now\\.$");
    private static final Pattern GOLDOR_REGEX = Pattern.compile("^\\[BOSS] Goldor: Who dares trespass into my domain\\?$");
    private static final Pattern CORE_OPENING_REGEX = Pattern.compile("^The Core entrance is opening!$");
    private static final Pattern STORM_END_REGEX = Pattern.compile("^\\[BOSS] Storm: I should have known that I stood no chance\\.$");
    private static final Pattern STORM_START_REGEX = Pattern.compile("^\\[BOSS] Storm: Pathetic Maxor, just like expected\\.$");
    private static final Pattern STORM_PY_REGEX = Pattern.compile("^\\[BOSS] Storm: (ENERGY HEED MY CALL|THUNDER LET ME BE YOUR CATALYST)!$");

    private static int necronTicks = -1;
    private static int goldorTickTime = -1;
    private static int goldorStartTime = -1;
    private static int padTickTime = -1;
    private static int lightningTickTime = -1;
    private static boolean pyTriggered = false;
    private static int pyTickTime = -1;
    private static int stormTick = -1;
    private static boolean wasInDungeon = false;
    private static Object lastLevel = null;
    private static int diagGoldorRestarts = 0;

    // Diagnostic only (2026-09-14) - never read by any timer logic.
    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-ticktimers");
    private static Boolean diagWasCounting = null;

    private static void diagArmed(String timer, String line) {
        LOGGER.info("[TickTimers] {} ARMED by line \"{}\" (bossPhaseActive={}, inDungeon={}, floor={}){}",
                timer, line, DungeonState.isBossPhaseActive(), DungeonState.isInDungeon(), DungeonState.getFloor(),
                DungeonState.isBossPhaseActive() ? "" : " - WARNING: timers only count down while bossPhaseActive, this one will freeze");
    }

    private static void diagExpired(String timer, int before, int after) {
        if (before >= 0 && after < 0) {
            LOGGER.info("[TickTimers] {} countdown EXPIRED", timer);
        }
    }

    private TickTimersFeature() {
    }

    public static void register() {
        // ChatObserver, not Fabric CHAT/GAME: Odin/NoammAddons/Skyblocker can cancel a server line via
        // ALLOW_GAME and re-add their own copy straight to ChatComponent, which Fabric listeners never see.
        // Triggers here are exact/anchored server-format lines, so this mod's own client-side messages (which
        // ChatObserver also delivers) can't match. Overlay (action bar) lines are not delivered - none needed.
        ChatObserver.subscribe(TickTimersFeature::onChatMessage);
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        ServerTickClock.register();
        ServerTickClock.subscribe(TickTimersFeature::serverTick);
    }

    private static void onChatMessage(Component message) {
        if (!TickTimersConfig.getInstance().isEnabled()) {
            return;
        }
        // Real bug found and fixed (2026-09-14, pre-testing bug-review pass): this used to match the raw
        // un-stripped string - DungeonState's own BOSS_START_PATTERN fix already confirmed real Hypixel
        // boss/sidebar lines embed §-codes mid-word, which silently breaks an exact/regex match unless
        // formatting is stripped first. Every trigger here is real boss dialogue of that exact kind.
        String plain = ChatFormatting.stripFormatting(message.getString());
        String raw = plain != null ? plain : message.getString();
        if (NECRON_REGEX.matcher(raw).matches()) {
            necronTicks = 60;
            diagArmed("Necron (60t)", raw);
        } else if (GOLDOR_REGEX.matcher(raw).matches()) {
            goldorTickTime = 60;
            diagGoldorRestarts = 0;
            diagArmed("Goldor Tick (60t, repeating until Core entrance opens)", raw);
        } else if (CORE_OPENING_REGEX.matcher(raw).matches()) {
            goldorStartTime = -1;
            goldorTickTime = -1;
            LOGGER.info("[TickTimers] Goldor Start/Tick CLEARED by line \"{}\" (Tick restarted {} times this P3)", raw, diagGoldorRestarts);
        } else if (STORM_END_REGEX.matcher(raw).matches()) {
            goldorStartTime = 104;
            padTickTime = -1;
            stormTick = -1;
            diagArmed("Goldor Start (104t), Storm pad/storm cleared", raw);
        } else if (STORM_START_REGEX.matcher(raw).matches()) {
            padTickTime = 20;
            lightningTickTime = 560;
            stormTick = 0;
            diagArmed("Storm Pad (20t) + Lightning (560t) + Storm counter", raw);
        } else if (!pyTriggered && STORM_PY_REGEX.matcher(raw).matches()) {
            pyTriggered = true;
            pyTickTime = 95;
            diagArmed("PY (95t)", raw);
        }
    }

    private static void tick() {
        // Odin resets every timer on LevelEvent.Load - same here, on any real level change.
        Object level = Minecraft.getInstance().level;
        if (level != lastLevel) {
            if (lastLevel != null) {
                LOGGER.info("[TickTimers] Level changed - all timers reset");
                resetAll();
            }
            lastLevel = level;
        }
        boolean inDungeon = DungeonState.isInDungeon();
        if (!inDungeon && wasInDungeon) {
            LOGGER.info("[TickTimers] Left dungeon - all timers reset");
            resetAll();
        }
        wasInDungeon = inDungeon;

        boolean diagCounting = TickTimersConfig.getInstance().isEnabled() && DungeonState.isBossPhaseActive();
        if (diagWasCounting == null || diagWasCounting != diagCounting) {
            LOGGER.info("[TickTimers] countdown ticking {} (enabled={}, bossPhaseActive={}, inDungeon={}, floor={})",
                    diagCounting ? "ACTIVE" : "PAUSED", TickTimersConfig.getInstance().isEnabled(),
                    DungeonState.isBossPhaseActive(), inDungeon, DungeonState.getFloor());
            diagWasCounting = diagCounting;
        }
    }

    /** Odin {@code on<TickEvent.Server>} (boss-only part). */
    private static void serverTick() {
        if (!TickTimersConfig.getInstance().isEnabled() || !DungeonState.isBossPhaseActive()) {
            return;
        }
        int diagGoldorStart = goldorStartTime;
        int diagGoldorTick = goldorTickTime;
        int diagLightning = lightningTickTime;
        int diagPy = pyTickTime;
        int diagNecron = necronTicks;
        TickTimersConfig cfg = TickTimersConfig.getInstance();
        // Real bug found and fixed (2026-09-14, real-run log analysis): an earlier review pass removed this
        // re-arm as a "copy-paste bug", claiming the Core entrance line fires BEFORE Goldor's taunt. It
        // doesn't - the real order is Storm dies -> "Who dares trespass" -> terminals -> "The Core entrance
        // is opening!", so the Tick timer showed one 3s countdown at the start of P3 and then vanished.
        // Odin's TickTimers.kt restarts it at 60 every time it hits 0 (Goldor's real 3s damage tick) for all
        // of P3, until CORE_OPENING_REGEX sets it to -1. Restored exactly, including Odin's HUD-enabled gate.
        // 2026-09-15 hardening (one deliberate deviation from Odin): Odin also requires goldorStartTime <= 0 here,
        // which means the Tick timer dies for the rest of P3 if Goldor's taunt lands less than 44 ticks after
        // Storm's death line (Tick reaches 0 while the 104-tick Start countdown is still running, so it is never
        // re-armed). Real logs show ~100 ticks between those lines, but the gate buys nothing: while Start runs the
        // HUD shows "Start:" instead of "Tick:" anyway, and the Core-entrance line still sets both to -1. Dropping
        // it only removes that failure mode; the countdown's alignment is unchanged.
        if (goldorTickTime == 0 && cfg.isGoldorTimer()) {
            goldorTickTime = 60;
            diagGoldorRestarts++;
        }
        if (goldorStartTime >= 0) {
            goldorStartTime--;
        }
        if (goldorTickTime >= 0) {
            goldorTickTime--;
        }
        if (padTickTime == 0 && cfg.isStormTimer()) {
            padTickTime = 20;
        }
        if (padTickTime >= 0) {
            padTickTime--;
        }
        if (lightningTickTime >= 0) {
            lightningTickTime--;
        }
        if (pyTickTime >= 0) {
            pyTickTime--;
        }
        if (necronTicks >= 0) {
            necronTicks--;
        }
        if (stormTick >= 0) {
            stormTick++;
        }
        diagExpired("Goldor Start", diagGoldorStart, goldorStartTime);
        diagExpired("Goldor Tick", diagGoldorTick, goldorTickTime);
        diagExpired("Lightning", diagLightning, lightningTickTime);
        diagExpired("PY", diagPy, pyTickTime);
        diagExpired("Necron", diagNecron, necronTicks);
    }

    private static void resetAll() {
        necronTicks = -1;
        goldorTickTime = -1;
        goldorStartTime = -1;
        padTickTime = -1;
        lightningTickTime = -1;
        pyTickTime = -1;
        pyTriggered = false;
        stormTick = -1;
        diagGoldorRestarts = 0;
    }

    private static String format(int time, int max, String prefix) {
        TickTimersConfig cfg = TickTimersConfig.getInstance();
        String color = time >= max * 0.66 ? "§a" : time >= max * 0.33 ? "§6" : "§c";
        String value = cfg.isDisplayInTicks()
                ? time + (cfg.isShowSymbol() ? "t" : "")
                : String.format(Locale.US, "%.1f%s", time / 20f, cfg.isShowSymbol() ? "s" : "");
        return (cfg.isShowPrefix() ? prefix + " " : "") + color + value;
    }

    public static final class TickTimersHudElement implements HudElement {
        @Override
        public String id() {
            return "tick_timers";
        }

        @Override
        public String displayName() {
            return "Tick Timers";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            return 380;
        }

        @Override
        public int width() {
            return 160;
        }

        @Override
        public int height() {
            return 12 * Math.max(1, activeLines().size());
        }

        private List<String> activeLines() {
            TickTimersConfig cfg = TickTimersConfig.getInstance();
            List<String> lines = new ArrayList<>();
            if (cfg.isNecronTimer() && necronTicks >= 0) {
                lines.add(format(necronTicks, 60, "§4Necron dropping in"));
            }
            if (cfg.isGoldorTimer()) {
                // Odin: "Start:" only when its own "Start timer" setting is on, otherwise "Tick:".
                if (goldorStartTime >= 0 && cfg.isGoldorStartTimer()) {
                    lines.add(format(goldorStartTime, 100, "§aStart:"));
                } else if (goldorTickTime >= 0) {
                    lines.add(format(goldorTickTime, 60, "§7Tick:"));
                }
            }
            if (cfg.isStormTimer()) {
                if (padTickTime >= 0) {
                    lines.add(format(padTickTime, 20, "§bPad:"));
                }
                if (lightningTickTime >= 0) {
                    lines.add(format(lightningTickTime, 560, "§bLightning:"));
                }
                if (pyTickTime >= 0) {
                    lines.add(format(pyTickTime, 95, "§bPY:"));
                }
                if (stormTick >= 0) {
                    lines.add(format(stormTick, 620, "§bStorm:"));
                }
            }
            return lines;
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            if (!TickTimersConfig.getInstance().isEnabled() || Minecraft.getInstance().screen != null) {
                return;
            }
            int lineY = y;
            for (String line : activeLines()) {
                graphics.text(Minecraft.getInstance().font, line, x, lineY, 0xFFFFFFFF, false);
                lineY += 12;
            }
        }
    }
}
