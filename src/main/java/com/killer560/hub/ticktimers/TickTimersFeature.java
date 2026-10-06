package com.killer560.hub.ticktimers;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.SkyblockGate;
import com.killer560.hub.witherdragons.ServerTickClock;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

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
 * ticks on a server that doesn't send per-tick pings), so client lag no longer drifts Goldor's 3s tick. Every
 * trigger is Hypixel's own boss dialogue, byte-for-byte identical on p3sim.net, so nothing here needs a
 * p3sim-specific branch - {@link com.killer560.hub.secrets.DungeonState}'s own p3sim detection (see its class
 * doc) already covers {@code isBossPhaseActive()}/{@code isF7OrM7()} for both servers.
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
 * <p>
 * <b>2026-09-20 merge with Goldor Frenzy Timer.</b> killer560 answered the long-pending duplication between
 * this file's own repeating Goldor countdown and the separate {@code goldorfrenzy} package's "Goldor Frenzy
 * Timer" (same 60-server-tick countdown from the same Goldor line, ported from Devonian) by asking for ONE
 * timer here. {@link TickTimersConfig#isGoldorShowTotal()} is Devonian's one genuinely new option (replace the
 * countdown with how long P3 has run); its other option, counting the Storm-death-to-Goldor gap, was already
 * this file's own {@link TickTimersConfig#isGoldorStartTimer()} under a different name. The {@code goldorfrenzy}
 * package, its tab and its own HUD element are deleted - see the implementation notes for the exact settings
 * migration.
 * <p>
 * <b>2026-09-20 "1s death tick during clear"</b> (removed 2026-10-06, see the last paragraph). killer560's own words: "have the 1s death tick during clear,
 * with an option to turn it off after the run starts". Ported from NoammAddons' {@code floor7/TickTimers.kt}
 * "0s Death Tick" (its {@code clear} section) - there it derives from the raw world-time packet
 * ({@code ClientboundSetTimePacket.gameTime}), which would need a brand new packet mixin here. Since the value
 * is purely a cosmetic 20-tick pulse with no real trigger line (same category as the "Secrets" pulse this class
 * already deliberately omits above), it is built the same lag-proof way as every other line in this file - a
 * repeating {@code 20 - (ServerTickClock.now() % 20)} countdown - so no new mixin or fabric.mod.json entry is
 * needed. {@link TickTimersConfig#isDeathTickStopsAtBoss()} is the "turn it off after the run starts" option;
 * OFF (its default) matches killer560's literal wording that the tick runs during clear AND keeps running once
 * the boss starts unless told not to.
 * <p>
 * <b>2026-09-21 "one home per timer": Pad timer / Storm crush timer moved in from F7 Spots.</b> killer560, asked
 * directly: "Move them to Tick Timers. One home per timer. F7 Spots keeps its waypoints; every countdown lives
 * on Tick Timers." {@code f7spots.CrushTimer} (its purple-pad crush countdown/title and pad-outline render) is
 * now {@link CrushTimer} in this package. Its own separate 20-server-tick pad-cycle counter was the EXACT same
 * cycle this file already ran as {@link #padTickTime} (same two Storm P2 start/death trigger lines, same 20
 * ticks) - a pre-existing overlap this class doc used to flag before the move. That duplicate is gone: the
 * moved {@link CrushTimer} no longer tracks a pad cycle of its own, and {@link #padTickTime} below is the ONE
 * counter left, now gated by its own {@link TickTimersConfig#isPadCycleTimer()} toggle (split out of
 * {@link TickTimersConfig#isStormTimer()} so his old F7 Spots "Pad Cycle Timer" preference still means the pad
 * line specifically - see {@link TickTimersConfig#migrateFromF7SpotsCrush()}).
 * <p>
 * <b>2026-10-06 SkyBlock 0.27.2 pacing</b> (NoammAddons 1.2.9 {@code floor7/TickTimers.kt} and its {@code dev/Timer.kt},
 * which measured each gap in server ticks on the new pacing): "Maxor Start" 83 ticks from Maxor's opening line (new
 * here); Goldor "Start:" 104 -&gt; 17 ticks from Storm's death line to Goldor's line, which still ends it; "Necron
 * dropping in" 60 ticks now from "You went further than any human before, congratulations." (was "I'm afraid, your
 * journey ends now."); PY 95 -&gt; 62. The clear "Death Tick" line is gone: it was {@code 20 - now % 20} on a clock
 * counted from client launch, so its phase never matched any server cycle, and NoammAddons dropped both its death and
 * secret tick timers in the same update (secret pickup became 5 ticks).
 */
public final class TickTimersFeature {

    // NoammAddons 1.2.9 dev/Timer.kt: 60 ticks from this line to P4 on 0.27.2 (Noamm's old trigger and ours was
    // "I'm afraid, your journey ends now.").
    private static final Pattern NECRON_REGEX =
            Pattern.compile("^\\[BOSS] Necron: You went further than any human before, congratulations\\.$");
    private static final Pattern MAXOR_START_REGEX = Pattern.compile("^\\[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!$");
    /** NoammAddons 1.2.9 floor7/TickTimers.kt "Maxor Start" (was 167 before 0.27.2). */
    static final int MAXOR_START_TICKS = 83;
    /** NoammAddons 1.2.9 dev/Timer.kt: Storm's death line to Goldor's line on 0.27.2 (Odin's/our old 104). The one
     *  copy of this gap: Auto i4's prefire window ({@code I4SensorsFeature.inPrefireWindow}) reads it too. */
    public static final int GOLDOR_START_TICKS = 17;
    /** NoammAddons 1.2.9 floor7/TickTimers.kt "Storm PY Timer" 75 -&gt; 62. Ours was Odin's 95. */
    static final int STORM_PY_TICKS = 62;
    private static final Pattern GOLDOR_REGEX = Pattern.compile("^\\[BOSS] Goldor: Who dares trespass into my domain\\?$");
    private static final Pattern CORE_OPENING_REGEX = Pattern.compile("^The Core entrance is opening!$");
    private static final Pattern STORM_END_REGEX = Pattern.compile("^\\[BOSS] Storm: I should have known that I stood no chance\\.$");
    private static final Pattern STORM_START_REGEX = Pattern.compile("^\\[BOSS] Storm: Pathetic Maxor, just like expected\\.$");
    private static final Pattern STORM_PY_REGEX = Pattern.compile("^\\[BOSS] Storm: (ENERGY HEED MY CALL|THUNDER LET ME BE YOUR CATALYST)!$");

    private static int necronTicks = -1;
    private static int maxorStartTime = -1;
    private static int goldorTickTime = -1;
    private static int goldorStartTime = -1;
    private static int padTickTime = -1;
    private static int lightningTickTime = -1;
    private static boolean pyTriggered = false;
    private static int pyTickTime = -1;
    private static int stormTick = -1;
    // ServerTickClock.now() baseline set when Goldor's line lands - Devonian's own baseline for "Show Total"
    // (its Stages.Terminals.startTime.tick), ported in the 2026-09-20 Goldor Frenzy merge.
    private static long goldorPhaseStartTick = 0L;
    private static boolean wasInDungeon = false;
    private static Object lastLevel = null;

    private TickTimersFeature() {
    }

    public static void register() {
        // ChatObserver, not Fabric CHAT/GAME: Odin/NoammAddons/Skyblocker can cancel a server line via
        // ALLOW_GAME and re-add their own copy straight to ChatComponent, which Fabric listeners never see.
        // Triggers here are exact/anchored server-format lines, so this mod's own client-side messages (which
        // ChatObserver also delivers) can't match. Overlay (action bar) lines are not delivered - none needed.
        ChatObserver.subscribe(TickTimersFeature::onChatMessage);
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("TickTimersFeature", client -> tick()));
        ServerTickClock.register();
        ServerTickClock.subscribe(TickTimersFeature::serverTick);
        // CrushTimer's pad outline render (moved in from f7spots 2026-09-21) - same AFTER_TRANSLUCENT_FEATURES
        // hook + SkyblockGate check F7SpotsRenderer used to gate it with.
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context -> {
            if (!SkyblockGate.allows() || !TickTimersConfig.getInstance().isEnabled()
                    || !TickTimersConfig.getInstance().isCrushPadHighlightEnabled()) {
                return;
            }
            CrushTimer.renderPads(context);
        });
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
        } else if (MAXOR_START_REGEX.matcher(raw).matches()) {
            maxorStartTime = MAXOR_START_TICKS;
        } else if (GOLDOR_REGEX.matcher(raw).matches()) {
            goldorTickTime = 60;
            goldorPhaseStartTick = ServerTickClock.now();
            // "Start:" counts from Storm's death to this line, so the line itself ends it whatever the pacing.
            goldorStartTime = -1;
        } else if (CORE_OPENING_REGEX.matcher(raw).matches()) {
            goldorStartTime = -1;
            goldorTickTime = -1;
        } else if (STORM_END_REGEX.matcher(raw).matches()) {
            goldorStartTime = GOLDOR_START_TICKS;
            padTickTime = -1;
            stormTick = -1;
        } else if (STORM_START_REGEX.matcher(raw).matches()) {
            padTickTime = 20;
            lightningTickTime = 560;
            stormTick = 0;
        } else if (!pyTriggered && STORM_PY_REGEX.matcher(raw).matches()) {
            pyTriggered = true;
            pyTickTime = STORM_PY_TICKS;
        }
        // CrushTimer (moved in from f7spots 2026-09-21): its own trigger lines ("Oof" / "Ouch, that hurt!" /
        // the optional extra trigger text) don't overlap any pattern above, so it always gets a look.
        CrushTimer.onChat(raw);
    }

    private static void tick() {
        // Odin resets every timer on LevelEvent.Load - same here, on any real level change.
        Object level = Minecraft.getInstance().level;
        if (level != lastLevel) {
            if (lastLevel != null) {
                resetAll();
            }
            lastLevel = level;
        }
        boolean inDungeon = DungeonState.isInDungeon();
        if (!inDungeon && wasInDungeon) {
            resetAll();
        }
        wasInDungeon = inDungeon;

        // CrushTimer's interval countdown/title (moved in from f7spots 2026-09-21) - client tick, same as
        // F7SpotsFeature used to drive it, gated by the master switch since it now shares this tab.
        TickTimersConfig cfg = TickTimersConfig.getInstance();
        if (cfg.isEnabled() && (cfg.isCrushTimerEnabled() || cfg.isCrushTitleEnabled())) {
            CrushTimer.tick(Minecraft.getInstance());
        }
    }

    /** Odin {@code on<TickEvent.Server>} (boss-only part). */
    private static void serverTick() {
        if (!TickTimersConfig.getInstance().isEnabled() || !DungeonState.isBossPhaseActive()) {
            return;
        }
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
        // re-armed). Since 0.27.2 the gap is ~17 ticks (NoammAddons 1.2.9), so Odin's gate would now never trip, but it
        // still buys nothing: while Start runs the
        // HUD shows "Start:" instead of "Tick:" anyway, and the Core-entrance line still sets both to -1. Dropping
        // it only removes that failure mode; the countdown's alignment is unchanged.
        if (goldorTickTime == 0 && cfg.isGoldorTimer()) {
            goldorTickTime = 60;
        }
        if (maxorStartTime >= 0) {
            maxorStartTime--;
        }
        if (goldorStartTime >= 0) {
            goldorStartTime--;
        }
        if (goldorTickTime >= 0) {
            goldorTickTime--;
        }
        // 2026-09-21: gate split from isStormTimer() to its own isPadCycleTimer() toggle when the pad timer's
        // f7spots duplicate was merged in here - see the class doc and TickTimersConfig's migration doc.
        if (padTickTime == 0 && cfg.isPadCycleTimer()) {
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
    }

    private static void resetAll() {
        necronTicks = -1;
        maxorStartTime = -1;
        goldorTickTime = -1;
        goldorStartTime = -1;
        padTickTime = -1;
        lightningTickTime = -1;
        pyTickTime = -1;
        pyTriggered = false;
        stormTick = -1;
        CrushTimer.reset();
        goldorPhaseStartTick = 0L;
    }

    private static String format(int time, int max, String prefix) {
        TickTimersConfig cfg = TickTimersConfig.getInstance();
        String color = time >= max * 0.66 ? "§a" : time >= max * 0.33 ? "§6" : "§c";
        return (cfg.isShowPrefix() ? prefix + " " : "") + color + value(time, cfg);
    }

    /** Devonian GoldorFrenzyTimer's "Show Total" - an elapsed count that only ever grows, so (like Tick
     *  Timers' own "Storm:" counter) it stays a neutral colour instead of the countdown ramp. */
    private static String formatElapsed(long elapsedTicks, String prefix) {
        TickTimersConfig cfg = TickTimersConfig.getInstance();
        return (cfg.isShowPrefix() ? prefix + " " : "") + "§f" + value((int) Math.min(Integer.MAX_VALUE, elapsedTicks), cfg);
    }

    private static String value(int time, TickTimersConfig cfg) {
        return cfg.isDisplayInTicks()
                ? time + (cfg.isShowSymbol() ? "t" : "")
                : String.format(Locale.US, "%.1f%s", time / 20f, cfg.isShowSymbol() ? "s" : "");
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
            if (cfg.isMaxorStartTimer() && maxorStartTime >= 0) {
                lines.add(format(maxorStartTime, MAXOR_START_TICKS, "§aMaxor Start:"));
            }
            if (cfg.isNecronTimer() && necronTicks >= 0) {
                lines.add(format(necronTicks, 60, "§4Necron dropping in"));
            }
            if (cfg.isGoldorTimer()) {
                // Odin: "Start:" only when its own "Start timer" setting is on, otherwise "Tick:"/"Show Total".
                if (goldorStartTime >= 0 && cfg.isGoldorStartTimer()) {
                    lines.add(format(goldorStartTime, GOLDOR_START_TICKS, "§aStart:"));
                } else if (goldorTickTime >= 0 && cfg.isGoldorShowTotal()) {
                    long elapsed = Math.max(0, ServerTickClock.now() - goldorPhaseStartTick);
                    lines.add(formatElapsed(elapsed, "§7Frenzy total"));
                } else if (goldorTickTime >= 0) {
                    lines.add(format(goldorTickTime, 60, "§7Tick:"));
                }
            }
            // Split from isStormTimer() 2026-09-21 when the f7spots pad-cycle duplicate was merged in - see
            // the class doc. Its own toggle now, so it isn't dragged along with Lightning/PY/the Storm counter.
            if (cfg.isPadCycleTimer() && padTickTime >= 0) {
                lines.add(format(padTickTime, 20, "§bPad:"));
            }
            if (cfg.isStormTimer()) {
                if (lightningTickTime >= 0) {
                    lines.add(format(lightningTickTime, 560, "§bLightning:"));
                }
                if (pyTickTime >= 0) {
                    lines.add(format(pyTickTime, STORM_PY_TICKS, "§bPY:"));
                }
                if (stormTick >= 0) {
                    lines.add(format(stormTick, 620, "§bStorm:"));
                }
            }
            // CrushTimer's interval/count-up line (moved in from f7spots 2026-09-21) - its own toggle, same as
            // it had on the F7 Spots tab.
            if (cfg.isCrushTimerEnabled()) {
                String crushLine = CrushTimer.hudText();
                if (crushLine != null) {
                    lines.add(crushLine);
                }
            }
            return lines;
        }

        @Override
        public boolean isEnabledInSettings() {
            // Setting only - F7/M7 is the draw stamp's half of the answer.
            return TickTimersConfig.getInstance().isEnabled();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            if (!TickTimersConfig.getInstance().isEnabled() || HudVisibility.hidesHud()) {
                return;
            }
            int lineY = y;
            for (String line : activeLines()) {
                graphics.text(Minecraft.getInstance().font, line, x, lineY, 0xFFFFFFFF, false);
                lineY += 12;
            }
            if (lineY != y) {
                HudSeen.markDrawn(id());
            }
        }
    }
}
