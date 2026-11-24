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

import java.util.function.IntFunction;

/**
 * Moonglade Beacon Tuner (both builds, off by default): in the beacon's "Tune Frequency" and "Upgrade Signal Strength"
 * menus it reads the beacon's own beats (color from the pane, speed from the ticks between its steps, pitch from the note
 * block sound that plays as it steps) and outlines the Color, Speed and Pitch buttons that still differ from your beat,
 * with the clicks needed (L = left click, R = right click), and Pause when your pane is not in the beacon's slot (P) or is
 * paused on it (U). No text readout over the menu (killer560 11-24: "there should be no hud for the tuning"). It clicks
 * nothing itself; {@link BeaconAutoTuner} does in the cheat build. The layout is {@link BeaconTuneSpec}.
 * <p>
 * Beats are followed by their panes, never by color: the beacon's two upgrade beats can share a color (both brown on
 * 11-24), and so can yours.
 */
public final class BeaconTuner {

    public static final IslandConfig CFG = IslandConfig.of("foraging", "beacontune");

    static {
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            BeaconAutoTuner.define(CFG);
        }
    }

    /** One beat's pane: its slot index in the row (-1 lost), when it last stepped, real steps seen, ticks between steps, pitch level and color. */
    static final class Beat {
        int slot = -1;
        long moved;
        int steps;
        int period = -1;
        int pitch = -1;
        int color = -1;
    }

    private static final long[] SOUND_TICK = new long[8];
    private static final int[] SOUND_LEVEL = new int[8];
    private static int soundN;
    private static final int[] HINT_SLOT = new int[12];
    private static final String[] HINT_TEXT = new String[12];
    private static int hintN;
    private static AbstractContainerScreen<?> screen;
    private static boolean upgrade;
    private static int shown;
    /** What the last {@link #step} read (read by {@link BeaconAutoTuner}): per player beat its dials, button slots and pane; the beacon's beats as {color, speed, pitch} and panes. */
    static int[][] curS = {};
    static int[][] ctlS = {};
    static Beat[] mine = {};
    static int[][] tgtRaw = {};
    static Beat[] beatRaw = {};

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
        soundN = 0;
        hintN = 0;
        mine = new Beat[0];
        beatRaw = new Beat[0];
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

    /**
     * The pitch of beacon beat {@code self}, which stepped at {@code now}: the beat sounds of this tick and the one before, less
     * one sound for each of your beats and each other known beacon beat that stepped in them; -1 when what is left disagrees.
     */
    private static int pitchAt(long now, Beat self, int[][] own) {
        int[] left = new int[3];
        for (int i = 0; i < Math.min(soundN, 8); i++) {
            if (SOUND_TICK[i] >= now - 1 && SOUND_TICK[i] <= now) {
                left[SOUND_LEVEL[i]]++;
            }
        }
        for (int g = 0; g < mine.length; g++) {
            if (mine[g].steps > 0 && mine[g].moved >= now - 1 && own[g][2] >= 0 && left[own[g][2]] > 0) {
                left[own[g][2]]--;
            }
        }
        for (Beat b : beatRaw) {
            if (b != self && b.moved >= now - 1 && b.pitch >= 0 && left[b.pitch] > 0) {
                left[b.pitch]--;
            }
        }
        int found = -1;
        for (int k = 0; k < 3; k++) {
            if (left[k] > 0) {
                found = found < 0 ? k : -2;
            }
        }
        return found >= 0 ? found : -1;
    }

    /**
     * Follows each beat's pane along a 7-slot row ({@code row[i]} its color index, -1 gray): a pane stays while its slot is
     * colored and stepped when its slot turned gray and the next one is colored. Two panes on one slot show as one; when one
     * of them leaves, the one whose step is due (longest since its last step for its period, never a paused one) stepped. A
     * slot that turns gray before the next one turns colored (the two updates can come a tick apart) is waited out. A pane not
     * placed yet takes a colored slot no other pane holds, one of its own color ({@code want}) first. Returns who stepped.
     */
    static boolean[] track(Beat[] beats, int[] row, int[] want, boolean[] frozen, long now) {
        int n = beats.length;
        boolean[] stepped = new boolean[n];
        for (int k = 0; k < n; k++) {
            Beat b = beats[k];
            if (b.slot >= 0 && row[b.slot] < 0 && row[(b.slot + 1) % BeaconTuneSpec.LEN] >= 0) {
                stepped[k] = true;
                b.slot = (b.slot + 1) % BeaconTuneSpec.LEN;
            }
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                int s = beats[i].slot;
                int next = (s + 1) % BeaconTuneSpec.LEN;
                if (s >= 0 && s == beats[j].slot && row[next] >= 0 && holder(beats, next) < 0) {
                    int k = stepped[i] || frozen[i] ? j : stepped[j] || frozen[j] ? i : due(beats[i], now) >= due(beats[j], now) ? i : j;
                    beats[k].slot = next;
                    stepped[k] = true;
                }
            }
        }
        for (int k = 0; k < n; k++) {
            if (stepped[k]) {
                Beat b = beats[k];
                long d = now - b.moved;
                b.period = b.steps > 0 && d >= 8 && d <= 70 ? (int) d : b.period;
                b.steps++;
                b.moved = now;
            }
        }
        for (int k = 0; k < n; k++) {
            if (beats[k].slot < 0) {
                int pick = -1;
                for (int i = 0; i < BeaconTuneSpec.LEN; i++) {
                    if (row[i] >= 0 && holder(beats, i) < 0 && (pick < 0 || want != null && row[i] == want[k] && row[pick] != want[k])) {
                        pick = i;
                    }
                }
                for (int i = 0; i < BeaconTuneSpec.LEN && pick < 0; i++) {
                    pick = row[i] >= 0 ? i : -1;
                }
                if (pick >= 0) {
                    beats[k].slot = pick;
                    beats[k].moved = now;
                    beats[k].steps = want == null ? 1 : 0; // a beacon beat starts its step timer as the menu opens
                }
            }
        }
        return stepped;
    }

    private static int holder(Beat[] beats, int slot) {
        for (int k = 0; k < beats.length; k++) {
            if (beats[k].slot == slot) {
                return k;
            }
        }
        return -1;
    }

    private static float due(Beat b, long now) {
        return (now - b.moved) / (float) (b.period > 0 ? b.period : 1);
    }

    private static int[] row(IntFunction<ItemStack> item, int start) {
        int[] r = new int[BeaconTuneSpec.LEN];
        for (int i = 0; i < r.length; i++) {
            String c = BeaconTuneSolver.colorOf(item.apply(start + i));
            r[i] = c == null ? -1 : BeaconTuneSpec.COLORS.indexOf(c);
        }
        return r;
    }

    /** Read the menu once for tick {@code now} (testkit entry point: {@code item} maps a menu slot to its stack). */
    public static void step(IntFunction<ItemStack> item, boolean isUpgrade, long now) {
        int[][] ctl = isUpgrade ? BeaconTuneSpec.UPGRADE_BEATS : BeaconTuneSpec.TUNE_BEATS;
        int n = ctl.length;
        if (mine.length != n) {
            mine = new Beat[n];
            beatRaw = new Beat[n];
            for (int g = 0; g < n; g++) {
                mine[g] = new Beat();
                beatRaw[g] = new Beat();
            }
        }
        int[][] cur = new int[n][3];
        int[] want = new int[n];
        boolean[] paused = new boolean[n];
        for (int g = 0; g < n; g++) {
            String col = BeaconTuneSolver.colorOf(item.apply(ctl[g][0]));
            cur[g][0] = col == null ? -1 : BeaconTuneSpec.COLORS.indexOf(col);
            cur[g][1] = BeaconTuneSolver.current(item.apply(ctl[g][1]), "speed");
            cur[g][2] = BeaconTuneSolver.current(item.apply(ctl[g][2]), "pitch");
            want[g] = cur[g][0];
            paused[g] = BeaconTuneSolver.paused(item.apply(ctl[g][3]));
            mine[g].period = cur[g][1] > 0 ? 15 + 10 * (5 - cur[g][1]) : -1;
        }
        track(mine, row(item, BeaconTuneSpec.BOTTOM), want, paused, now);
        int[] top = row(item, BeaconTuneSpec.TOP);
        boolean[] beaconStepped = track(beatRaw, top, null, new boolean[n], now);
        int[][] tgt = new int[n][];
        for (int j = 0; j < n; j++) {
            Beat b = beatRaw[j];
            if (b.slot >= 0 && top[b.slot] >= 0 && (n < 2 || beatRaw[1 - j].slot != b.slot)) {
                b.color = top[b.slot];
            }
            if (beaconStepped[j]) {
                int p = pitchAt(now, b, cur);
                b.pitch = p >= 0 ? p : b.pitch;
            }
            tgt[j] = new int[]{b.color, b.period > 0 ? BeaconTuneSolver.speedFromPeriod(b.period) : -1, b.pitch};
        }
        int[] pick = BeaconTuneSolver.assign(cur, tgt);
        curS = cur;
        ctlS = ctl;
        tgtRaw = tgt;
        hintN = 0;
        for (int g = 0; g < n; g++) {
            int[] t = tgt[pick[g]];
            for (int k = 0; k < 3; k++) {
                int p = cur[g][k] < 0 || t[k] < 0 ? 0 : BeaconTuneSolver.presses(cur[g][k], t[k], BeaconTuneSpec.WRAP[k]);
                if (p != 0) {
                    hint(ctl[g][k], (p > 0 ? "L" : "R") + Math.abs(p));
                }
            }
            Beat b = beatRaw[pick[g]];
            if (b.slot >= 0 && mine[g].slot >= 0 && t[1] > 0 && cur[g][1] == t[1] && (paused[g] == (b.slot == mine[g].slot))) {
                hint(ctl[g][3], paused[g] ? "U" : "P");
            }
        }
    }

    private static void hint(int slot, String text) {
        HINT_SLOT[hintN] = slot;
        HINT_TEXT[hintN++] = text;
    }

    /** The hint buttons of the last {@link #step}, "slot:text" each (testkit). */
    public static String[] hints() {
        String[] h = new String[hintN];
        for (int i = 0; i < hintN; i++) {
            h[i] = HINT_SLOT[i] + ":" + HINT_TEXT[i];
        }
        return h;
    }

    /** The beacon's beats and yours as the last {@link #step} read them, "color speed pitch @slot" each, beacon first (testkit). */
    public static String beats() {
        StringBuilder sb = new StringBuilder();
        for (int j = 0; j < tgtRaw.length; j++) {
            sb.append(j == 0 ? "beacon " : "; ").append(BeaconTuneSolver.target(tgtRaw[j])).append(" @").append(beatRaw[j].slot);
        }
        for (int g = 0; g < curS.length; g++) {
            sb.append(g == 0 ? " | yours " : "; ").append(BeaconTuneSolver.target(curS[g])).append(" @").append(mine[g].slot);
        }
        return sb.toString();
    }

    private static void draw(Minecraft mc, GuiGraphicsExtractor g) {
        if (!CFG.isEnabled() || screen == null || hintN == 0) {
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
    }
}
