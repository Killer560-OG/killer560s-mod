package com.killer560.hub.ticktimers;

import com.killer560.hub.hud.HudElement;
import com.killer560.hub.secrets.DungeonState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
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
 * window: 620 ticks), not guessed. Deliberately omits Odin's "Secrets" pulse timer - that one isn't a
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
        ClientReceiveMessageEvents.CHAT.register(
                (message, signedMessage, sender, params, receptionTimestamp) -> onChatMessage(message));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onChatMessage(message));
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
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
            diagArmed("Goldor Tick (60t)", raw);
        } else if (CORE_OPENING_REGEX.matcher(raw).matches()) {
            goldorStartTime = -1;
            goldorTickTime = -1;
            LOGGER.info("[TickTimers] Goldor Start/Tick CLEARED by line \"{}\"", raw);
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
        if (!TickTimersConfig.getInstance().isEnabled() || !DungeonState.isBossPhaseActive()) {
            return;
        }
        int diagGoldorStart = goldorStartTime;
        int diagGoldorTick = goldorTickTime;
        int diagLightning = lightningTickTime;
        int diagPy = pyTickTime;
        int diagNecron = necronTicks;
        // Real bug found and fixed (2026-09-14, pre-testing bug-review pass): Goldor's tick is a one-shot
        // 60-tick countdown (GOLDOR_REGEX in onChatMessage already sets it exactly once, for real), not a
        // repeating timer like Storm's pad (padTickTime, which legitimately does re-arm itself below -
        // this block looks like an incompletely-adapted copy of that same pattern). CORE_OPENING_REGEX
        // resets goldorStartTime to -1 BEFORE Goldor's own taunt line ever fires, so by the time
        // goldorTickTime first reached 0 here, goldorStartTime was already <=0 forever after - meaning
        // this rearm condition was permanently true and the "Tick:" line looped every 60 ticks (3s) for
        // the rest of the Necron fight on every real F7/M7 clear. Removed entirely; onChatMessage is the
        // only real trigger point now.
        if (goldorStartTime >= 0) {
            goldorStartTime--;
        }
        if (goldorTickTime >= 0) {
            goldorTickTime--;
        }
        if (padTickTime == 0) {
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
                if (goldorStartTime >= 0) {
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
