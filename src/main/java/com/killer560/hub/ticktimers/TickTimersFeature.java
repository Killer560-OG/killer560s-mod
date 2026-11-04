package com.killer560.hub.ticktimers;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.PerTickServerClock;
import com.killer560.hub.util.SkyblockGate;
import com.killer560.hub.witherdragons.ServerTickClock;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
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
 * repeating {@code 20 - (PerTickServerClock.now() % 20)} countdown - so no new mixin or fabric.mod.json entry is
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
 * here); Goldor "Start:" 104 -&gt; 64 ticks (NoammAddons said 17; his logs say 64, tickfix2-1104) from Storm's death line to Goldor's line, which still ends it; "Necron
 * dropping in" 60 ticks now from "You went further than any human before, congratulations." (was "I'm afraid, your
 * journey ends now."); PY 95 -&gt; 62. The clear "Death Tick" line is gone: it was {@code 20 - now % 20} on a clock
 * counted from client launch, so its phase never matched any server cycle, and NoammAddons dropped both its death and
 * secret tick timers in the same update (secret pickup became 5 ticks).
 * <p>
 * <b>2026-11-02 (f7hud-1102, killer560's F7 screenshots).</b> Storm has ONE line ({@link StormLine}: lightning, then the
 * purple pad, then the yellow pad); the Pad, Lightning, PY, Storm and Crush lines and their settings are gone. Goldor's
 * "Tick:" is {@link GoldorTick}: a remembered server tick of a hit on {@code util.PerTickServerClock}, resynced on every
 * hit it sees, instead of a counter on {@link ServerTickClock} (which counts client ticks in a second with under 15 pings,
 * and was skipped whenever the sidebar did not read F7) - that counter drifted, and his mask popped at "1.5s".
 */
public final class TickTimersFeature {

    // NoammAddons 1.2.9 dev/Timer.kt: 60 ticks from this line to P4 on 0.27.2 (Noamm's old trigger and ours was
    // "I'm afraid, your journey ends now.").
    private static final Pattern NECRON_REGEX =
            Pattern.compile("^\\[BOSS] Necron: You went further than any human before, congratulations\\.$");
    private static final Pattern MAXOR_START_REGEX = Pattern.compile("^\\[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!$");
    /** NoammAddons 1.2.9 floor7/TickTimers.kt "Maxor Start" (was 167 before 0.27.2). */
    static final int MAXOR_START_TICKS = 83;
    /** Storm's death line to Goldor's line since SkyBlock 0.27.2: 64 ticks (Odin's number). His logs since 2026-10-06
     *  (tickfix2-1104, 482 runs at 1 s resolution) read 3 s in 357 and 4 s in 125, a steady gap of about 3.26 s, i.e. 65
     *  ticks of wall time with lag in it; before 0.27.2 they read 5 s / 6 s (Odin's old 104). NoammAddons 1.2.9's 17
     *  does not fit. The one copy of this gap: Auto i4's prefire window ({@code I4SensorsFeature.inPrefireWindow}) reads
     *  it too. */
    public static final int GOLDOR_START_TICKS = 64;
    private static final Pattern GOLDOR_REGEX = Pattern.compile("^\\[BOSS] Goldor: Who dares trespass into my domain\\?$");
    private static final Pattern CORE_OPENING_REGEX = Pattern.compile("^The Core entrance is opening!$");
    private static final Pattern STORM_END_REGEX = Pattern.compile("^\\[BOSS] Storm: I should have known that I stood no chance\\.$");
    private static final Pattern STORM_START_REGEX = Pattern.compile("^\\[BOSS] Storm: Pathetic Maxor, just like expected\\.$");
    /** Storm's Giga Lightning line (NoammAddons/Odin's "PY" trigger): the lightning has gone off. */
    private static final Pattern STORM_PY_REGEX = Pattern.compile("^\\[BOSS] Storm: (ENERGY HEED MY CALL|THUNDER LET ME BE YOUR CATALYST)!$");

    private static int necronTicks = -1;
    private static int maxorStartTime = -1;
    private static int goldorStartTime = -1;
    // PerTickServerClock.now() baseline set when Goldor's line lands - Devonian's own baseline for "Show Total"
    // (its Stages.Terminals.startTime.tick), ported in the 2026-09-20 Goldor Frenzy merge.
    private static long goldorPhaseStartTick = 0L;
    /** Clock tick each countdown above ends on (MIN = not running). The int fields are worked out from these on every server
     *  tick, so a tick on which the boss-phase gate read false (the sidebar flickers) can no longer leave a countdown one
     *  tick behind for the rest of the phase, as Odin's decrement-in-a-gated-listener did here until 2026-11-04. */
    private static long necronEnd = Long.MIN_VALUE;
    private static long maxorStartEnd = Long.MIN_VALUE;
    private static long goldorStartEnd = Long.MIN_VALUE;
    private static TimerMark necronMark;
    private static TimerMark maxorStartMark;
    private static TimerMark goldorStartMark;
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
        ServerTickClock.register();
        PerTickServerClock.register();
        PerTickServerClock.subscribe(TickTimersFeature::serverTick);
        // Goldor's tick and Storm's line count on this clock (2026-11-02, see GoldorTick). Registered first, so its own
        // END_CLIENT_TICK listener runs before ours and a hit seen this tick reads this tick's clock.
        PerTickServerClock.register();
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("TickTimersFeature", client -> tick()));
        // CrushTimer's pad outline render (moved in from f7spots 2026-09-21) - same AFTER_TRANSLUCENT_FEATURES
        // hook + SkyblockGate check F7SpotsRenderer used to gate it with.
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            if (!SkyblockGate.allows() || !TickTimersConfig.getInstance().isEnabled()
                    || !TickTimersConfig.getInstance().isCrushPadHighlightEnabled()) {
                return;
            }
            CrushTimer.renderPads(context);
        });
    }

    private static void onChatMessage(Component message) {
        if (!TickTimersConfig.getInstance().isEnabled()) return;
        // Real bug found and fixed (2026-09-14, pre-testing bug-review pass): this used to match the raw
        // un-stripped string - DungeonState's own BOSS_START_PATTERN fix already confirmed real Hypixel
        // boss/sidebar lines embed §-codes mid-word, which silently breaks an exact/regex match unless
        // formatting is stripped first. Every trigger here is real boss dialogue of that exact kind.
        String raw = com.killer560.hub.util.ChatObserver.strip(message);
        long now = PerTickServerClock.now();
        if (NECRON_REGEX.matcher(raw).matches()) {
            necronTicks = 60;
            necronEnd = now + 60;
            necronMark = TimerMark.now();
        } else if (MAXOR_START_REGEX.matcher(raw).matches()) {
            maxorStartTime = MAXOR_START_TICKS;
            maxorStartEnd = now + MAXOR_START_TICKS;
            maxorStartMark = TimerMark.now();
        } else if (GOLDOR_REGEX.matcher(raw).matches()) {
            if (goldorStartMark != null && goldorStartEnd != Long.MIN_VALUE) {
                // How far the "Start:" countdown was from Goldor's line when it landed (0 = the 17-tick gap was right).
                TimerMark.proc("Goldor Start (Goldor's line, " + (goldorStartEnd - now) + " ticks left on it)", goldorStartMark);
            }
            GoldorTick.start();
            goldorPhaseStartTick = now;
            // "Start:" counts from Storm's death to this line, so the line itself ends it whatever the pacing.
            goldorStartTime = -1;
            goldorStartEnd = Long.MIN_VALUE;
            goldorStartMark = null;
        } else if (CORE_OPENING_REGEX.matcher(raw).matches()) {
            goldorStartTime = -1;
            goldorStartEnd = Long.MIN_VALUE;
            GoldorTick.stop();
        } else if (STORM_END_REGEX.matcher(raw).matches()) {
            goldorStartTime = GOLDOR_START_TICKS;
            goldorStartEnd = now + GOLDOR_START_TICKS;
            goldorStartMark = TimerMark.now();
            GoldorTick.stormDeath();
            StormLine.stop();
        } else if (STORM_START_REGEX.matcher(raw).matches()) {
            StormLine.start();
        } else if (STORM_PY_REGEX.matcher(raw).matches()) {
            StormLine.onLightning();
        } else if (CrushTimer.CRUSH_LINES.contains(raw)) {
            StormLine.onCrush();
        } else if (GoldorTick.active() && MASK_PROC_REGEX.matcher(raw).matches()) {
            GoldorTick.onProc();
        }
    }

    /** A mask or Phoenix saving you (the lines {@code MaskInvincibilityFeature} reads): in P3, that is Goldor's tick. */
    private static final Pattern MASK_PROC_REGEX = Pattern.compile("^(?:Second Wind Activated! Your Spirit Mask saved your life!"
            + "|Your (?:. )?Bonzo's Mask saved your life!|Your Phoenix Pet saved you from certain death!)$");

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

        GoldorTick.endOfTick();
        StormLine.endOfTick();
        TickTimersConfig cfg = TickTimersConfig.getInstance();
        if (cfg.isEnabled() && cfg.isGoldorTimer()) {
            GoldorTick.clientTick(Minecraft.getInstance());
        }
    }

    /** Odin {@code on<TickEvent.Server>} (boss-only part). */
    private static void serverTick() {
        // Every countdown is its end tick minus the clock, whatever the boss-phase gate reads this tick (2026-11-04).
        long now = PerTickServerClock.now();
        int was = maxorStartTime;
        maxorStartTime = remainingTo(maxorStartEnd, now);
        if (was > 0 && maxorStartTime == 0) {
            TimerMark.proc("Maxor Start", maxorStartMark);
        }
        was = necronTicks;
        necronTicks = remainingTo(necronEnd, now);
        if (was > 0 && necronTicks == 0) {
            TimerMark.proc("Necron drop", necronMark);
        }
        was = goldorStartTime;
        goldorStartTime = remainingTo(goldorStartEnd, now);
        if (was > 0 && goldorStartTime == 0) {
            TimerMark.proc("Goldor Start", goldorStartMark);
        }
        GoldorTick.serverTick();
        StormLine.serverTick();
        // Real bug found and fixed (2026-09-14, real-run log analysis): an earlier review pass removed this
        // re-arm as a "copy-paste bug", claiming the Core entrance line fires BEFORE Goldor's taunt. It
        // doesn't - the real order is Storm dies -> "Who dares trespass" -> terminals -> "The Core entrance
        // is opening!", so the Tick timer showed one 3s countdown at the start of P3 and then vanished.
        // Odin's TickTimers.kt restarts it at 60 every time it hits 0 (Goldor's real 3s damage tick) for all
        // of P3, until CORE_OPENING_REGEX sets it to -1. Restored exactly, including Odin's HUD-enabled gate.
        // 2026-09-15 hardening (one deliberate deviation from Odin): Odin also requires goldorStartTime <= 0 here,
        // which means the Tick timer dies for the rest of P3 if Goldor's taunt lands less than 44 ticks after
        // Storm's death line (Tick reaches 0 while the 104-tick Start countdown is still running, so it is never
        // re-armed). Since 0.27.2 the gap is ~64 ticks, so Odin's gate would now never trip, but it
        // still buys nothing: while Start runs the
        // HUD shows "Start:" instead of "Tick:" anyway, and the Core-entrance line still sets both to -1. Dropping
        // it only removes that failure mode; the countdown's alignment is unchanged.
        // Goldor's "Tick:" and Storm's line are no longer counted down here: they are worked out from a remembered
        // server tick on PerTickServerClock (GoldorTick, StormLine), so a skipped call cannot move them (2026-11-02).
    }

    /** Ticks from {@code now} to {@code end}, or -1 once past it or when nothing runs (Odin's -1 = off the HUD). */
    private static int remainingTo(long end, long now) {
        return end == Long.MIN_VALUE || now > end ? -1 : (int) Math.min(Integer.MAX_VALUE, end - now);
    }

    private static void resetAll() {
        necronTicks = -1;
        maxorStartTime = -1;
        goldorStartTime = -1;
        necronEnd = Long.MIN_VALUE;
        maxorStartEnd = Long.MIN_VALUE;
        goldorStartEnd = Long.MIN_VALUE;
        necronMark = null;
        maxorStartMark = null;
        goldorStartMark = null;
        GoldorTick.stop();
        StormLine.stop();
        goldorPhaseStartTick = 0L;
    }

    private static String format(int time, int max, String prefix) {
        TickTimersConfig cfg = TickTimersConfig.getInstance();
        String color = time >= max * 0.66 ? "§a" : time >= max * 0.33 ? "§6" : "§c";
        return (cfg.isShowPrefix() ? prefix + " " : "") + color + value(time, cfg);
    }

    /** Devonian GoldorFrenzyTimer's "Show Total" - an elapsed count that only ever grows, so (like Tick
     *  Timers' own "Storm:" counter) it stays a neutral color instead of the countdown ramp. */
    private static String formatElapsed(long elapsedTicks, String prefix) {
        TickTimersConfig cfg = TickTimersConfig.getInstance();
        return (cfg.isShowPrefix() ? prefix + " " : "") + "§f" + value((int) Math.min(Integer.MAX_VALUE, elapsedTicks), cfg);
    }

    private static String value(int time, TickTimersConfig cfg) {
        if (cfg.isDisplayInTicks()) return time + (cfg.isShowSymbol() ? "t" : "");
        // The seconds text for a tick count, remembered: the HUD asks for every active timer each frame and again for
        // width()/height(), and the count only moves once a server tick (2026-10-15: a String.format each time).
        boolean symbol = cfg.isShowSymbol();
        int slot = time & (VALUE_SLOTS - 1);
        if (!Minecraft.getInstance().isSameThread()) return String.format(Locale.US, "%.1f%s", time / 20f, symbol ? "s" : "");
        if (VALUE_TEXT[slot] == null || VALUE_TIME[slot] != time || VALUE_SYMBOL[slot] != symbol) {
            VALUE_TEXT[slot] = String.format(Locale.US, "%.1f%s", time / 20f, symbol ? "s" : "");
            VALUE_TIME[slot] = time;
            VALUE_SYMBOL[slot] = symbol;
        }
        return VALUE_TEXT[slot];
    }

    private static final int VALUE_SLOTS = 64;
    private static final String[] VALUE_TEXT = new String[VALUE_SLOTS];
    private static final int[] VALUE_TIME = new int[VALUE_SLOTS];
    private static final boolean[] VALUE_SYMBOL = new boolean[VALUE_SLOTS];

    public static final class TickTimersHudElement implements HudElement {
        @Override
        public String id() { return "tick_timers"; }

        @Override
        public String displayName() { return "Tick Timers"; }

        @Override
        public int defaultX() { return com.killer560.hub.hud.F7HudSlots.x(com.killer560.hub.hud.F7HudSlots.TICK_TIMERS); }

        @Override
        public int defaultY() {
            return com.killer560.hub.hud.F7HudSlots.y(com.killer560.hub.hud.F7HudSlots.TICK_TIMERS); // was 10,380: over the chat (hud/F7HudSlots)
        }

        @Override
        public int width() {
            // The widest drawn line (2026-10-07 box audit: it was a fixed 160). Nothing active draws nothing, and
            // the box keeps a grabbable 160 for the HUD editor.
            List<String> lines = activeLines();
            return lines.isEmpty() ? 160 : com.killer560.hub.hud.HudText.width(lines);
        }

        /** The box is anchored on its center (killer560, 2026-11-03: "tick timers be adjusted by center align not an
         *  edge"): the saved center, else the old saved left edge (or the default) plus half the empty 160 box it had. */
        @Override
        public int[] layoutPosition() {
            float f = com.killer560.hub.hud.AutoScale.current();
            TickTimersConfig cfg = TickTimersConfig.getInstance();
            int centre;
            if (cfg.getHudCenterX() >= 0) {
                centre = Math.round(cfg.getHudCenterX() * f);
            } else {
                int[] saved = com.killer560.hub.hud.HudConfig.getInstance().savedPosition(id());
                centre = (saved != null ? Math.round(saved[0] * f) : defaultX()) + 80;
            }
            int[] own = com.killer560.hub.hud.HudElementRegistry.ownPosition(this);
            float scale = Math.max(0.01f, com.killer560.hub.hud.HudElementRegistry.resolveScale(this));
            return new int[]{Math.round(centre - width() * scale / 2f), own[1]};
        }

        @Override
        public boolean storeEditorPosition(int x, int y, int w, int h, int screenW, int screenH) {
            int[] c = com.killer560.hub.hud.HudElementRegistry.toSaved(x + w / 2, y);
            TickTimersConfig cfg = TickTimersConfig.getInstance();
            cfg.setHudCenterX(c[0]);
            cfg.save();
            com.killer560.hub.hud.HudConfig.getInstance().setPosition(id(), c[0], c[1]);
            return true;
        }

        @Override
        public int height() { return com.killer560.hub.hud.HudText.height(activeLines().size(), 12); }

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
                } else if (GoldorTick.active() && cfg.isGoldorShowTotal()) {
                    long elapsed = Math.max(0, PerTickServerClock.now() - goldorPhaseStartTick);
                    lines.add(formatElapsed(elapsed, "§7Frenzy total"));
                } else if (GoldorTick.active()) {
                    lines.add(format(GoldorTick.remaining(), GoldorTick.PERIOD, "§7Tick:"));
                }
            }
            // Storm: ONE line (2026-11-02): lightning, then the purple pad, then the yellow pad (StormLine).
            if (cfg.isStormLine() && StormLine.visible()) {
                lines.add(format(StormLine.remaining(), StormLine.max(), StormLine.prefix()));
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
            if (!TickTimersConfig.getInstance().isEnabled() || HudVisibility.hidesHud()) return;
            int lineY = y;
            List<String> shown = activeLines();
            int boxW = shown.isEmpty() ? 0 : com.killer560.hub.hud.HudText.width(shown);
            for (String line : shown) {
                int dx = (boxW - com.killer560.hub.hud.HudText.width(line)) / 2;
                graphics.text(Minecraft.getInstance().font, line, x + Math.max(0, dx), lineY, 0xFFFFFFFF, false);
                lineY += 12;
            }
            if (lineY != y) {
                HudSeen.markDrawn(id());
            }
        }
    }
}
