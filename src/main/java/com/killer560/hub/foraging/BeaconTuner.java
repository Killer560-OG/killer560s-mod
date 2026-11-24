package com.killer560.hub.foraging;

import com.killer560.hub.experiments.mixin.AbstractContainerScreenAccessor;
import com.killer560.hub.gui.ThemeAccent;
import com.killer560.hub.islands.IslandConfig;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * Moonglade Beacon Tuner (both builds, off by default): in the beacon's "Tune Frequency" and "Upgrade Signal Strength"
 * menus it reads the beacon's own beats (color from the pane, speed from the ticks between its steps, pitch from the note
 * block sound that plays as it steps) and outlines the Color, Speed and Pitch buttons that still differ from your beat,
 * with the clicks needed (L = left click, R = right click), plus whether your beat steps in line with the beacon's.
 * Display only: it clicks nothing and sends nothing. The layout is {@link BeaconTuneSpec}.
 */
public final class BeaconTuner {

    public static final IslandConfig CFG = IslandConfig.of("foraging", "beacontune");

    static {
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            BeaconAutoTuner.define(CFG);
        }
    }

    /** One of the beacon's beats: where its pane is, when it last stepped, and what that showed. */
    static final class Beat {
        int slot = -1;
        long moved;
        int period = -1;
        int pitch = -1;
    }

    private static final Map<String, Beat> BEATS = new HashMap<>();
    private static final long[] SOUND_TICK = new long[8];
    private static final int[] SOUND_LEVEL = new int[8];
    private static int soundN;
    private static final int[] HINT_SLOT = new int[8];
    private static final String[] HINT_TEXT = new String[8];
    private static int hintN;
    private static String[] lines = {};
    private static AbstractContainerScreen<?> screen;
    private static boolean upgrade;
    private static int shown;
    /** What the last {@link #step} read, per player beat: its dials and buttons, and the beacon's beats in color order (dials and Beat) (read by {@link BeaconAutoTuner}). */
    static int[][] curS = {};
    static int[][] tgtRaw = {};
    static Beat[] beatRaw = {};
    static int[][] ctlS = {};

    private BeaconTuner() {
    }

    public static void register() {
        int bit = FeatureBits.register("Beacon Tuner", CFG::isEnabledRaw);
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("BeaconTuner.tick", bit, BeaconTuner::tick));
        ScreenEvents.AFTER_INIT.register((client, s, w, h) -> {
            if (s instanceof AbstractContainerScreen<?> c && BeaconTuneSpec.TITLE.matcher(s.getTitle().getString()).matches()) {
                reset();
                screen = c;
                upgrade = BeaconTuneSpec.UPGRADE.equals(s.getTitle().getString());
                ScreenEvents.afterExtract(s).register((sc, g, mx, my, dt) -> draw(client, g));
                ScreenEvents.remove(s).register(sc -> {
                    screen = null;
                    if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
                        BeaconAutoTuner.stop();
                    }
                });
            }
        });
    }

    /** Frames drawn with at least one hint shown (read by the testkit). */
    public static int shown() {
        return shown;
    }

    /** The note block bass sounds near the open menu: the beacon's beat pitch is read from them. */
    public static void onSoundPacket(ClientboundSoundPacket p) {
        Minecraft mc = Minecraft.getInstance();
        if (screen != null && mc.level != null && p.getSound().value().location().getPath().equals(BeaconTuneSpec.BEAT_SOUND)) {
            sound(p.getPitch(), mc.level.getGameTime());
        }
    }

    private static void tick(Minecraft mc) {
        if (screen != null && mc.level != null && CFG.isEnabled()) {
            var slots = screen.getMenu().slots;
            long now = mc.level.getGameTime();
            step(i -> i < slots.size() ? slots.get(i).getItem() : ItemStack.EMPTY, upgrade, now);
            if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && CFG.on("autoTune")) {
                BeaconAutoTuner.tick(mc, screen, now);
            }
        }
    }

    public static void reset() {
        BEATS.clear();
        soundN = 0;
        hintN = 0;
        lines = new String[0];
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            BeaconAutoTuner.reset();
        }
    }

    /** One beat sound (testkit entry point). */
    public static void sound(float pitch, long now) {
        int i = soundN++ & 7;
        SOUND_TICK[i] = now;
        SOUND_LEVEL[i] = BeaconTuneSolver.pitchLevel(pitch);
    }

    /** The beacon's pitch for a step at {@code now}: the sounds of that tick and the one before, minus the player's own beat sounds. */
    private static int pitchAt(long now, int[][] own) {
        int found = -1;
        boolean foundMine = false;
        for (int i = 0; i < Math.min(soundN, 8); i++) {
            if (SOUND_TICK[i] >= now - 1 && SOUND_TICK[i] <= now) {
                boolean mine = false;
                for (int[] o : own) {
                    mine |= o[2] == SOUND_LEVEL[i];
                }
                if (found < 0 || foundMine && !mine) {
                    found = SOUND_LEVEL[i];
                    foundMine = mine;
                }
            }
        }
        return found;
    }

    /** Read the menu once for tick {@code now} (testkit entry point: {@code item} maps a menu slot to its stack). */
    public static void step(IntFunction<ItemStack> item, boolean isUpgrade, long now) {
        int[][] ctl = isUpgrade ? BeaconTuneSpec.UPGRADE_BEATS : BeaconTuneSpec.TUNE_BEATS;
        int[][] cur = new int[ctl.length][3];
        for (int g = 0; g < ctl.length; g++) {
            String col = BeaconTuneSolver.colorOf(item.apply(ctl[g][0]));
            cur[g][0] = col == null ? -1 : BeaconTuneSpec.COLORS.indexOf(col);
            cur[g][1] = BeaconTuneSolver.current(item.apply(ctl[g][1]), "speed");
            cur[g][2] = BeaconTuneSolver.current(item.apply(ctl[g][2]), "pitch");
        }
        String[] tc = new String[2];
        int tn = 0;
        for (int i = 0; i < BeaconTuneSpec.LEN && tn < 2; i++) {
            String c = BeaconTuneSolver.colorOf(item.apply(BeaconTuneSpec.TOP + i));
            if (c == null || (tn == 1 && c.equals(tc[0]))) {
                continue;
            }
            tc[tn++] = c;
            Beat b = BEATS.computeIfAbsent(c, k -> new Beat());
            if (b.slot != BeaconTuneSpec.TOP + i) {
                long d = now - b.moved;
                b.period = b.slot >= 0 && d >= 8 && d <= 70 ? (int) d : b.period;
                int p = pitchAt(now, cur);
                b.pitch = p >= 0 ? p : b.pitch;
                b.slot = BeaconTuneSpec.TOP + i;
                b.moved = now;
            }
        }
        int[][] tgt = new int[Math.max(tn, 1)][];
        for (int j = 0; j < tgt.length; j++) {
            Beat b = tn > 0 ? BEATS.get(tc[j]) : null;
            tgt[j] = b == null ? new int[]{-1, -1, -1}
                    : new int[]{BeaconTuneSpec.COLORS.indexOf(tc[j]), b.period > 0 ? BeaconTuneSolver.speedFromPeriod(b.period) : -1, b.pitch};
        }
        int[] pick = BeaconTuneSolver.assign(cur, tgt);
        curS = cur;
        ctlS = ctl;
        // The beacon's beats in color order, for Auto Tune (the pane order changes as they pass each other).
        Integer[] order = new Integer[tgt.length];
        for (int j = 0; j < order.length; j++) {
            order[j] = j;
        }
        java.util.Arrays.sort(order, java.util.Comparator.comparingInt(j -> tgt[j][0]));
        tgtRaw = new int[tgt.length][];
        beatRaw = new Beat[tgt.length];
        for (int j = 0; j < order.length; j++) {
            tgtRaw[j] = tgt[order[j]];
            beatRaw[j] = tn > 0 ? BEATS.get(tc[order[j]]) : null;
        }
        hintN = 0;
        String[] out = new String[ctl.length];
        for (int g = 0; g < ctl.length; g++) {
            int[] t = tgt[pick[g]];
            String[] d = BeaconTuneSolver.describe(cur[g], t);
            for (int k = 0; k < 3; k++) {
                int p = cur[g][k] < 0 || t[k] < 0 ? 0 : BeaconTuneSolver.presses(cur[g][k], t[k], BeaconTuneSpec.WRAP[k]);
                if (p != 0) {
                    HINT_SLOT[hintN] = ctl[g][k];
                    HINT_TEXT[hintN++] = (p > 0 ? "L" : "R") + Math.abs(p);
                }
            }
            out[g] = (isUpgrade ? "Beat " + (g + 1) + ": " : "") + String.join(" | ", d) + phase(item, cur[g], tn > 0 ? BEATS.get(tc[pick[g]]) : null, ctl[g])
                    + " (beacon: " + BeaconTuneSolver.target(t) + ")";
        }
        lines = out;
    }

    /**
     * Once the speeds match, where your pane sits against the beacon's, and what Pause should do (the tune only completes with
     * both panes in the same slot, killer560 11-23; Pause freezes yours while the beacon's keeps stepping, and its next step
     * comes about one period after unpausing, so unpausing as the beacon steps onto your slot lines you up one period later).
     */
    private static String phase(IntFunction<ItemStack> item, int[] cur, Beat b, int[] ctl) {
        if (b == null || b.slot < 0 || b.period < 0 || cur[1] != BeaconTuneSolver.speedFromPeriod(b.period)) {
            return "";
        }
        boolean paused = BeaconTuneSolver.paused(item.apply(ctl[3]));
        for (int i = 0; i < BeaconTuneSpec.LEN; i++) {
            if (cur[0] >= 0 && BeaconTuneSpec.COLORS.get(cur[0]).equals(BeaconTuneSolver.colorOf(item.apply(BeaconTuneSpec.BOTTOM + i)))) {
                int d = Math.floorMod(i - (b.slot - BeaconTuneSpec.TOP), BeaconTuneSpec.LEN);
                if (d == 0 && !paused) {
                    return " | in step";
                }
                HINT_SLOT[hintN] = ctl[3];
                HINT_TEXT[hintN++] = paused ? "U" : "P";
                return d == 0 ? " | in line: unpause now" : paused ? " | paused: unpause when the beacon steps onto your slot (" + d + " steps)"
                        : " | out of line: Pause now, unpause when the beacon steps onto your slot (" + d + " steps, about " + Math.round(d * b.period / 2f) / 10f + " s)";
            }
        }
        return "";
    }

    /** The lines the last {@link #step} wrote (testkit). */
    public static String[] lines() {
        return lines;
    }

    /** The hint buttons of the last {@link #step}, "slot:text" each (testkit). */
    public static String[] hints() {
        String[] h = new String[hintN];
        for (int i = 0; i < hintN; i++) {
            h[i] = HINT_SLOT[i] + ":" + HINT_TEXT[i];
        }
        return h;
    }

    private static void draw(Minecraft mc, GuiGraphicsExtractor g) {
        if (!CFG.isEnabled() || screen == null || lines.length == 0) {
            return;
        }
        AbstractContainerScreenAccessor a = (AbstractContainerScreenAccessor) screen;
        int color = ThemeAccent.accent(0xFFCC6600);
        var slots = screen.getMenu().slots;
        for (int i = 0; i < hintN; i++) {
            if (HINT_SLOT[i] < slots.size()) {
                Slot s = slots.get(HINT_SLOT[i]);
                int x = a.killer560smod$getLeftPos() + s.x;
                int y = a.killer560smod$getTopPos() + s.y;
                g.outline(x - 1, y - 1, 18, 18, color);
                g.text(mc.font, HINT_TEXT[i], x + 2, y + 5, 0xFFFFFFFF, true);
            }
        }
        shown++;
        for (int i = 0; i < lines.length; i++) {
            g.text(mc.font, lines[i], a.killer560smod$getLeftPos(), a.killer560smod$getTopPos() - 11 - 10 * (lines.length - 1 - i), 0xFFFFFFFF, true);
        }
    }
}
